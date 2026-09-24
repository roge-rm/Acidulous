// Rendering a whole song, off a phone.
//
// Every other harness here takes one piece - a machine, an effect, the
// scheduler, a stretcher - and asks whether that piece is right. This one runs
// the **Engine**: racks, scheduler, master, the lot, rendering blocks the way
// an export does, and asks the questions that only exist once the pieces are
// together.
//
// It was written because the alternative was a phone. Proving "a render
// repeats" or "an export ignores the quality setting" meant freezing a track
// on an emulator, pulling the file over adb, doing it again and comparing -
// minutes per attempt, with the app's own state drifting underneath. Three
// attempts at it produced two tests that *could not fail* (the clip I chose
// was on a machine lean does not reach, then on a patch whose mode count was
// already at the lean cap) and one result nobody could interpret. None of that
// was a hard question; it was an unusable loop.
//
// The lesson is the one the tree already knows about wall clocks and averages:
// **a measurement you cannot repeat cheaply is a measurement you will get
// wrong.**
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Settings.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Effects.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-52s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int32_t kBar = kPPQN * 4;

/**
 * A song with something in it, built by hand.
 *
 * Deliberately not the demo: the demo is Kotlin and this is C++, and a harness
 * that needed the app's own song file to run would be a harness that breaks
 * when somebody edits the demo. Two racks, two scenes, a machine that is
 * reached by `lean` and one that is not - which is the distinction most of the
 * questions below turn on.
 */
struct Fixture {
    Engine engine;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;

    Fixture() {
        // Rack 0: Reflux with a Distortion. Distortion is one of the four
        // units `lean` reaches, so this rack sounds different in the two modes
        // and is what any quality question has to be asked of.
        engine.racks[0].swapMachine(MachineRegistry::create("Reflux"));
        engine.racks[0].swapEffect(0, EffectRegistry::create("Distortion"));
        // Rack 1: Hexbeat, which lean does not reach - the control.
        engine.racks[1].swapMachine(MachineRegistry::create("Hexbeat"));
        for (int32_t r = 0; r < 2; ++r) {
            if (Machine *m = engine.racks[r].currentMachine()) {
                m->prepare(kSampleRate);
                m->reset();
                m->params().jumpAll();
            }
            if (Effect *e = engine.racks[r].currentEffect(0)) {
                e->prepare(kSampleRate);
                e->reset();
                e->params().jumpAll();
            }
        }
        scene(1, 2);
        scene(2, 2);
        clip(0, 0, 36);
        clip(0, 1, 41);
        clip(1, 0, 36);
        snap->rackCount = 2;
        engine.scheduler.swapSnapshot(snap.get());
    }

    ~Fixture() {
        for (int32_t r = 0; r < kRackCount; ++r) {
            for (int32_t s = 0; s < kEffectSlots; ++s) delete engine.racks[r].swapEffect(s, nullptr);
            delete engine.racks[r].swapMachine(nullptr);
        }
    }

    void scene(int64_t id, int32_t bars) {
        SceneInfo s;
        s.id = id;
        s.bars = bars;
        s.repeat = 1;
        s.ticksPerBar = kBar;
        snap->scenes.push_back(s);
    }

    void clip(int32_t rack, int32_t sceneIdx, uint8_t pitch) {
        auto c = std::make_shared<Clip>();
        c->rev = rack * 100 + sceneIdx + 1;
        c->bars = 2;
        c->ticksPerBar = kBar;
        for (int32_t i = 0; i < 8; ++i) {
            ClipNote n{};
            n.tick = i * (kBar / 4);
            n.length = kBar / 8;
            n.pitch = static_cast<uint8_t>(pitch + (i % 3));
            n.velocity = static_cast<uint8_t>(80 + (i % 4) * 12);
            c->notes.push_back(n);
        }
        keep.push_back(c);
        snap->setClip(rack, sceneIdx, c);
    }
};

/** Render [blocks] blocks from the top of the song into one interleaved buffer. */
std::vector<float> render(int32_t blocks) {
    Fixture f;
    // What an offline render does: panic to a known state, then play from the
    // top. `renderBlock` applies a requested transport between blocks, so the
    // first block is the one that starts it.
    // The engine's own panic, requested the way the host requests it: the
    // flag is read at the top of a block, which is why one is rendered before
    // anything is asked to play.
    f.engine.panicFlag.store(true, std::memory_order_release);
    // A real buffer even for the block nobody listens to: the master writes
    // its output unconditionally, and `nullptr` is an output nothing in this
    // engine accepts - the host always hands it a scratch block.
    float scratch[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.requestPlay(0);

    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    for (int32_t b = 0; b < blocks; ++b) {
        f.engine.renderBlock(nullptr, out.data() + static_cast<size_t>(b) * kBlockFrames * 2);
    }
    return out;
}

float peakOf(const std::vector<float> &v) {
    float p = 0.0f;
    for (float s : v) p = std::max(p, std::fabs(s));
    return p;
}

size_t firstDifference(const std::vector<float> &a, const std::vector<float> &b) {
    const size_t n = std::min(a.size(), b.size());
    for (size_t i = 0; i < n; ++i) {
        if (a[i] != b[i]) return i;
    }
    return a.size() == b.size() ? a.size() : n;
}

/**
 * The same song, rendered twice, is the same audio.
 *
 * The property the export has always claimed and that nothing off a phone
 * checked. It is also the one that catches a whole family of faults at once -
 * a parameter still gliding from whatever played before, a modifier part way
 * through its pattern, a filter holding state a `reset` missed - because every
 * one of them shows up as two renders that disagree.
 */
void aRenderRepeats() {
    printf("- the same song rendered twice\n");
    const auto first = render(160);
    const auto second = render(160);
    ok("it made a sound at all", peakOf(first) > 0.01f, std::to_string(peakOf(first)));
    const size_t at = firstDifference(first, second);
    ok("and the second render is the first", at == first.size(),
       at == first.size() ? "" : "differ at sample " + std::to_string(at) + " of " +
                                     std::to_string(first.size()));
}

/**
 * A render ignores the quality setting.
 *
 * `lean` buys headroom against a deadline, and a render has no deadline - it
 * stops the stream and pulls blocks as fast as the machine allows. So a file
 * must not come out thinner because the setting happened to say lean, or
 * because the automatic watcher chose it a minute earlier.
 *
 * **This test can fail**, which is the part that took three goes on a phone to
 * get right: rack 0 carries a Distortion, whose oversampling is one of the
 * four things `lean` actually reaches. The control below proves the fixture
 * can tell the two modes apart at all.
 */
void aRenderIsAlwaysFullQuality() {
    printf("- quality, and what a render ignores\n");
    auto &settings = EngineSettings::get();

    // First: with the offline flag *off*, lean really does change this song.
    // If it does not, everything under it is vacuous.
    settings.offlineRender.store(false, std::memory_order_relaxed);
    settings.quality.store(1, std::memory_order_relaxed);
    const auto liveFull = render(160);
    settings.quality.store(0, std::memory_order_relaxed);
    const auto liveLean = render(160);
    ok("lean changes this song when it is allowed to",
       firstDifference(liveFull, liveLean) != liveFull.size());

    // Then the claim itself: with the flag on, the setting stops mattering.
    settings.offlineRender.store(true, std::memory_order_relaxed);
    settings.quality.store(0, std::memory_order_relaxed);
    const auto renderedLean = render(160);
    settings.quality.store(1, std::memory_order_relaxed);
    const auto renderedFull = render(160);
    const size_t at = firstDifference(renderedLean, renderedFull);
    ok("a render is the same whichever the setting says", at == renderedFull.size(),
       at == renderedFull.size() ? "" : "differ at sample " + std::to_string(at));
    ok("and it is the full-quality one", firstDifference(renderedFull, liveFull) == liveFull.size());

    settings.offlineRender.store(false, std::memory_order_relaxed);
    settings.quality.store(1, std::memory_order_relaxed);
}

/**
 * A render does not depend on what played before it.
 *
 * The same engine, rendering the same passage twice with a panic between, must
 * give the same audio - because a panic is what an offline render does to get
 * to a known state, and "known" has to mean it.
 *
 * This is the shape of a real fault, found in `freezeClip` the week this was
 * written: it reset the machine and the effects but never jumped their
 * *parameters*, and every one of them is smoothed - so the first few
 * milliseconds of a render slid in from wherever the knobs had been left, and
 * freezing the same clip twice gave two different files. `Engine`'s own panic
 * path had the fix and the comment explaining it; the freeze had half of it.
 */
void aRenderDoesNotDependOnWhatPlayedBefore() {
    printf("- a render, after something else has played\n");
    Fixture f;
    float scratch[kBlockFrames * 2];

    const auto take = [&](bool moveAKnobFirst) {
        // **Both takes ask for the same sound.** The only difference between
        // them is the *history* - one of them played something else first and
        // left a smoothed parameter part way home. Setting the target in one
        // branch only would be two different cutoffs, which is a test that
        // fails for the wrong reason, and did.
        const auto aim = [&](float v) {
            if (Machine *m = f.engine.racks[0].currentMachine()) {
                const int32_t cutoff = m->params().indexOf("cutoff");
                if (cutoff >= 0) m->params().set(cutoff, v);
            }
        };
        if (moveAKnobFirst) {
            // Somewhere else entirely, and let it glide: a smoothed parameter
            // part way to a new value is exactly the state a render must not
            // inherit.
            aim(0.05f);
            f.engine.transport.requestPlay(0);
            for (int i = 0; i < 40; ++i) f.engine.renderBlock(nullptr, scratch);
        }
        aim(0.7f);
        f.engine.panicFlag.store(true, std::memory_order_release);
        f.engine.renderBlock(nullptr, scratch);
        f.engine.transport.requestPlay(0);
        std::vector<float> out(static_cast<size_t>(120) * kBlockFrames * 2);
        for (int32_t b = 0; b < 120; ++b) {
            f.engine.renderBlock(nullptr, out.data() + static_cast<size_t>(b) * kBlockFrames * 2);
        }
        return out;
    };

    const auto clean = take(false);
    const auto after = take(true);
    const size_t at = firstDifference(clean, after);
    ok("the same audio either way", at == clean.size(),
       at == clean.size() ? "" : "differ at sample " + std::to_string(at));
}

/**
 * What a track costs, as a figure a single bad block cannot set.
 *
 * `worst track` was a peak-hold over a whole song, which is the least
 * repeatable statistic available: three runs of one build on one phone put
 * the per-track figures up to 26% apart, so a twenty per cent saving - most of
 * what is left to find - could not be told from the same build measured twice.
 * The engine keeps a coarse histogram per rack instead and reads a percentile
 * out of it.
 *
 * Repeatability itself cannot be tested here, because an offline render is
 * deterministic and the peak would agree with itself too. What can be tested
 * is that the number is a percentile of the right distribution: present when
 * the rack has played, never above the worst block it saw, and gone when the
 * reset button is pressed.
 */
void aTracksCostIsAPercentile() {
    printf("- what a track costs, as a distribution\n");
    Fixture f;
    float scratch[kBlockFrames * 2];
    f.engine.panicFlag.store(true, std::memory_order_release);
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.requestPlay(0);
    for (int32_t b = 0; b < 400; ++b) f.engine.renderBlock(nullptr, scratch);

    // The percentile first: reading the peak is what clears it.
    const int32_t p99 = f.engine.rackPercentileUs(0);
    const int32_t peak = f.engine.worstRackUs(0);
    ok("a rack that has played reports a cost", p99 > 0, std::to_string(p99) + " us");
    // The bucket's top, so it may sit a little above the worst block seen -
    // eight buckets an octave is at most nine per cent over.
    ok("and never more than the worst block it saw", p99 <= peak + peak / 8 + 1,
       "p99 " + std::to_string(p99) + " against peak " + std::to_string(peak));
    ok("a rack with nothing mounted reports nothing", f.engine.rackPercentileUs(5) == 0);
    f.engine.resetRackCosts();
    ok("and the reset button empties it", f.engine.rackPercentileUs(0) == 0);
}

} // namespace

/**
 * **Sidechain: a track that listens to another.**
 *
 * A held chord on rack 2 through a compressor keyed by a kick on rack 5 -
 * the listener on the *lower* rack, deliberately, because racks used to
 * render in index order and a listener that rendered first could only ever
 * hear its source a block late. The engine now renders sources first.
 */
struct SideFixture {
    Engine engine;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    static constexpr int32_t kListener = 2, kSource = 5;

    explicit SideFixture(int32_t sidechain, int32_t secondListener = -1, bool bypass = false) {
        add(kListener, "Trinity");
        add(kSource, "Genesis");
        Effect *comp = EffectRegistry::create("Compressor");
        comp->prepare(kSampleRate);
        comp->reset();
        auto &p = comp->params();
        p.set(effect::Compressor::Threshold, 0.25f); // -45 dB: anything the kick does is over it
        p.set(effect::Compressor::Ratio, 1.0f);      // twenty to one
        p.set(effect::Compressor::Attack, 0.0f);     // a tenth of a millisecond
        p.set(effect::Compressor::Sidechain, static_cast<float>(sidechain) / 16.0f);
        p.jumpAll();
        comp->setBypass(bypass);
        delete engine.racks[kListener].swapEffect(0, comp);
        if (secondListener >= 0) {
            // And the source listening back: a loop, which cannot be ordered.
            Effect *back = EffectRegistry::create("Compressor");
            back->prepare(kSampleRate);
            back->reset();
            back->params().set(effect::Compressor::Sidechain, static_cast<float>(secondListener) / 16.0f);
            back->params().jumpAll();
            delete engine.racks[kSource].swapEffect(0, back);
        }
        SceneInfo sc;
        sc.id = 1;
        sc.bars = 2;
        sc.repeat = 1;
        sc.ticksPerBar = kBar;
        snap->scenes.push_back(sc);
        auto held = std::make_shared<Clip>();
        held->rev = 1;
        held->bars = 2;
        held->ticksPerBar = kBar;
        for (uint8_t n : {48, 55, 60}) held->notes.push_back(ClipNote{0, 2 * kBar - 1, n, 100});
        keep.push_back(held);
        snap->setClip(kListener, 0, held);
        auto kick = std::make_shared<Clip>();
        kick->rev = 2;
        kick->bars = 2;
        kick->ticksPerBar = kBar;
        for (int32_t i = 0; i < 8; ++i) kick->notes.push_back(ClipNote{i * kPPQN + kPPQN / 3, kPPQN / 4, 36, 120});
        keep.push_back(kick);
        snap->setClip(kSource, 0, kick);
        snap->rackCount = kSource + 1;
        engine.scheduler.swapSnapshot(snap.get());
    }
    void add(int32_t rack, const char *machine) {
        Machine *m = MachineRegistry::create(machine);
        m->prepare(kSampleRate);
        m->reset();
        m->params().jumpAll();
        delete engine.racks[rack].swapMachine(m);
    }
    ~SideFixture() {
        for (int32_t r = 0; r < kRackCount; ++r) {
            for (int32_t s = 0; s < kEffectSlots; ++s) delete engine.racks[r].swapEffect(s, nullptr);
            delete engine.racks[r].swapMachine(nullptr);
        }
    }
    /** Render, keeping the listener's and the source's own buffers per sample. */
    void run(int32_t blocks, std::vector<float> &listener, std::vector<float> &source, std::vector<float> *mix = nullptr) {
        engine.panicFlag.store(true, std::memory_order_release);
        float scratch[kBlockFrames * 2];
        engine.renderBlock(nullptr, scratch);
        engine.transport.requestPlay(0);
        for (int32_t b = 0; b < blocks; ++b) {
            engine.renderBlock(nullptr, scratch);
            listener.insert(listener.end(), engine.racks[kListener].bufL, engine.racks[kListener].bufL + kBlockFrames);
            source.insert(source.end(), engine.racks[kSource].keyBuf, engine.racks[kSource].keyBuf + kBlockFrames);
            if (mix != nullptr) mix->insert(mix->end(), scratch, scratch + kBlockFrames * 2);
        }
    }
};

void aTrackCanListenToAnother() {
    printf("- sidechain\n");
    constexpr int32_t kBlocks = 1500; // two seconds: a bar and a bit at 120
    std::vector<float> dry, keyA, keyed, keyB;
    // The baseline is the compressor *bypassed*: left on its own input at
    // -45 dB and twenty to one it squashes the chord by itself.
    { SideFixture f(0, -1, true); f.run(kBlocks, dry, keyA); }
    { SideFixture f(SideFixture::kSource + 1); f.run(kBlocks, keyed, keyB); }

    // Gain at each sample, as the keyed listener over the unkeyed one - the
    // same machine, the same notes, so the ratio is the compressor and
    // nothing else. Only where the dry signal is big enough to divide by.
    size_t firstKick = 0;
    while (firstKick < keyB.size() && std::fabs(keyB[firstKick]) < 0.02f) ++firstKick;
    size_t firstDuck = 0;
    for (size_t i = 0; i < dry.size(); ++i) {
        if (std::fabs(dry[i]) > 0.02f && std::fabs(keyed[i]) < std::fabs(dry[i]) * 0.7f) { firstDuck = i; break; }
    }
    float deepest = 1.0f;
    for (size_t i = firstKick; i < std::min(dry.size(), firstKick + 4800); ++i) {
        if (std::fabs(dry[i]) > 0.05f) deepest = std::min(deepest, std::fabs(keyed[i]) / std::fabs(dry[i]));
    }
    ok("a held chord ducks under another track's kick", firstKick < keyB.size() && deepest < 0.3f,
       "down to " + std::to_string(deepest));
    ok("in the same block as the kick, not the next",
       firstDuck >= firstKick && firstDuck < firstKick + kBlockFrames,
       "kick at " + std::to_string(firstKick) + ", duck at " + std::to_string(firstDuck));

    // Pre-mute: a muted kick still drives the duck.
    std::vector<float> mutedOut, mutedKey;
    {
        SideFixture f(SideFixture::kSource + 1);
        f.engine.racks[SideFixture::kSource].setParam(Unit::Channel, Rack::Mute, 1.0f);
        f.run(kBlocks, mutedOut, mutedKey);
    }
    ok("a muted source still ducks, because the key is pre-fader", firstDifference(mutedOut, keyed) == keyed.size());

    // A key from a rack with nothing on it is silence, not the listener's own
    // input and not whatever that rack last held.
    std::vector<float> emptyOut, emptyKey;
    { SideFixture f(12); f.run(kBlocks, emptyOut, emptyKey); }
    ok("a key from an empty rack is silence: nothing ducks", firstDifference(emptyOut, dry) == dry.size());

    // A loop cannot be ordered; it must still render, and repeat.
    std::vector<float> loopA, loopKeyA, loopB, loopKeyB;
    { SideFixture f(SideFixture::kSource + 1, SideFixture::kListener + 1); f.run(400, loopA, loopKeyA); }
    { SideFixture f(SideFixture::kSource + 1, SideFixture::kListener + 1); f.run(400, loopB, loopKeyB); }
    ok("two tracks keyed by each other render, and repeat", firstDifference(loopA, loopB) == loopA.size());

    // And the whole thing repeats.
    std::vector<float> again, againKey, mix1, mix2, k1, k2;
    { SideFixture f(SideFixture::kSource + 1); f.run(600, again, againKey, &mix1); }
    { SideFixture f(SideFixture::kSource + 1); f.run(600, k1, k2, &mix2); }
    ok("a sidechained render is the same twice", firstDifference(mix1, mix2) == mix1.size());
}

/**
 * **Groups: tracks routed into a strip in the mixer.**
 *
 * Two members - a pattern on rack 1 and a kick on rack 5 - routed into the
 * master's group 0, which has two inserts and a fader of its own.
 */
struct GroupFixture {
    Engine engine;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    static constexpr int32_t kA = 1, kB = 5, kGroup = 0;

    explicit GroupFixture(bool grouped) {
        add(kA, "Hexbeat");
        add(kB, "Genesis");
        if (grouped) {
            for (int32_t m : {kA, kB}) engine.racks[m].setParam(Unit::Channel, Rack::Output, (kGroup + 1) / 16.0f);
        }
        SceneInfo sc;
        sc.id = 1;
        sc.bars = 2;
        sc.repeat = 1;
        sc.ticksPerBar = kBar;
        snap->scenes.push_back(sc);
        for (int32_t r : {kA, kB}) {
            auto c = std::make_shared<Clip>();
            c->rev = r + 1;
            c->bars = 2;
            c->ticksPerBar = kBar;
            for (int32_t i = 0; i < 16; ++i) c->notes.push_back(ClipNote{i * kPPQN / 2, kPPQN / 4, static_cast<uint8_t>(36 + (r == kA ? 6 : 0) + (i % 2)), 110});
            keep.push_back(c);
            snap->setClip(r, 0, c);
        }
        snap->rackCount = kB + 1;
        engine.scheduler.swapSnapshot(snap.get());
    }
    void add(int32_t rack, const char *machine) {
        Machine *m = MachineRegistry::create(machine);
        m->prepare(kSampleRate);
        m->reset();
        m->params().jumpAll();
        delete engine.racks[rack].swapMachine(m);
    }
    ~GroupFixture() {
        for (int32_t r = 0; r < kRackCount; ++r) delete engine.racks[r].swapMachine(nullptr);
        for (int32_t s = 0; s < kGroupInsertSlots; ++s) delete engine.master.swapGroupInsert(kGroup, s, nullptr);
    }
    void groupParam(int32_t which, float v) {
        engine.master.params().set(MasterBus::groupParam(kGroup) + which, v);
        engine.master.params().jumpAll();
    }
    std::vector<float> run(int32_t blocks) {
        engine.panicFlag.store(true, std::memory_order_release);
        float scratch[kBlockFrames * 2];
        engine.renderBlock(nullptr, scratch);
        engine.transport.requestPlay(0);
        std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
        for (int32_t b = 0; b < blocks; ++b) engine.renderBlock(nullptr, out.data() + static_cast<size_t>(b) * kBlockFrames * 2);
        return out;
    }
};

float largestDifference(const std::vector<float> &a, const std::vector<float> &b) {
    float d = 0.0f;
    for (size_t i = 0; i < std::min(a.size(), b.size()); ++i) d = std::max(d, std::fabs(a[i] - b[i]));
    return d;
}

void tracksCanBeGrouped() {
    printf("- groups\n");
    constexpr int32_t kBlocks = 800;
    std::vector<float> plain, grouped;
    { GroupFixture f(false); plain = f.run(kBlocks); }
    { GroupFixture f(true); grouped = f.run(kBlocks); }
    // A group at unity with nothing on it is its members' sum: the same mix,
    // to within the rounding of one more fader.
    const float d = largestDifference(plain, grouped);
    ok("a group at unity sounds like its members did", peakOf(plain) > 0.05f && d < 1e-4f,
       "largest difference " + std::to_string(d));

    std::vector<float> muted;
    {
        GroupFixture f(true);
        f.groupParam(1, 1.0f); // mute
        muted = f.run(kBlocks);
    }
    ok("muting the group silences its members", peakOf(std::vector<float>(muted.begin() + 20 * 128, muted.end())) < 1e-6f);

    // Solo one member: it is heard through the group and the other is not.
    std::vector<float> soloGrouped, soloPlain;
    {
        GroupFixture f(true);
        f.engine.racks[GroupFixture::kB].setParam(Unit::Channel, Rack::Solo, 1.0f);
        soloGrouped = f.run(kBlocks);
    }
    {
        GroupFixture f(false);
        f.engine.racks[GroupFixture::kB].setParam(Unit::Channel, Rack::Solo, 1.0f);
        soloPlain = f.run(kBlocks);
    }
    ok("a soloed member is heard through its group, alone", largestDifference(soloGrouped, soloPlain) < 1e-4f &&
       peakOf(soloPlain) > 0.05f);

    // A member's sends go straight to the send buses, grouped or not.
    std::vector<float> sentPlain, sentGrouped;
    for (int32_t g = 0; g < 2; ++g) {
        GroupFixture f(g == 1);
        f.engine.master.swapSend(0, nullptr);
        Effect *rev = EffectRegistry::create("Delay");
        rev->prepare(kSampleRate);
        rev->reset();
        rev->params().jumpAll();
        delete f.engine.master.swapSend(0, rev);
        f.engine.racks[GroupFixture::kA].setParam(Unit::Channel, Rack::SendReverb, 0.8f);
        (g == 1 ? sentGrouped : sentPlain) = f.run(kBlocks);
        delete f.engine.master.swapSend(0, nullptr);
    }
    ok("a member's sends still reach the send buses", largestDifference(sentPlain, sentGrouped) < 1e-4f,
       "largest difference " + std::to_string(largestDifference(sentPlain, sentGrouped)));

    // Soloing the group plays both members, and nothing is soloed on a track.
    std::vector<float> soloGroup;
    {
        GroupFixture f(true);
        f.groupParam(2, 1.0f); // solo
        soloGroup = f.run(kBlocks);
    }
    ok("a soloed group is heard whole", largestDifference(soloGroup, grouped) < 1e-6f);

    // An insert on the group processes both members; bypassed, it is not there.
    const auto withInsert = [&](bool bypass) {
        GroupFixture f(true);
        Effect *fx = EffectRegistry::create("Distortion");
        fx->prepare(kSampleRate);
        fx->reset();
        fx->params().set(fx->params().indexOf("drive"), 0.9f);
        fx->params().jumpAll();
        fx->setBypass(bypass);
        delete f.engine.master.swapGroupInsert(GroupFixture::kGroup, 0, fx);
        return f.run(kBlocks);
    };
    const auto driven = withInsert(false), bypassedFx = withInsert(true);
    ok("an insert on the group changes its members", largestDifference(grouped, driven) > 0.01f);
    ok("and bypassed it is not there at all", firstDifference(grouped, bypassedFx) == grouped.size());
}

/** The master's inserts: on the whole mix, before the fader and the limiter. */
void theMasterHasInserts() {
    printf("- master inserts\n");
    constexpr int32_t kBlocks = 600;
    std::vector<float> plain, driven, bypassed, again;
    { GroupFixture f(false); plain = f.run(kBlocks); }
    const auto withDrive = [&](bool bypass) {
        GroupFixture f(false);
        Effect *fx = EffectRegistry::create("Distortion");
        fx->prepare(kSampleRate);
        fx->reset();
        fx->params().set(fx->params().indexOf("drive"), 0.9f);
        fx->params().jumpAll();
        fx->setBypass(bypass);
        delete f.engine.master.swapInsert(0, fx);
        auto out = f.run(kBlocks);
        delete f.engine.master.swapInsert(0, nullptr);
        return out;
    };
    driven = withDrive(false);
    bypassed = withDrive(true);
    again = withDrive(false);
    ok("an insert on the master changes the mix", largestDifference(plain, driven) > 0.01f);
    ok("and bypassed it is not there at all", firstDifference(plain, bypassed) == plain.size());
    ok("and a render through it repeats", firstDifference(driven, again) == driven.size());
}

/**
 * A held effect recorded into a clip plays back from it, on the master.
 *
 * The lane is on rack 0's clip, addressed to `Unit::Perform`: the rack hands
 * it on to the master's held effects, which is the whole of how a recorded
 * press becomes something the song does again.
 */
std::vector<float> renderPerformed(int32_t blocks, bool stopHalfway, float *repeatAfterStop, bool heldFromTop = false) {
    Fixture f;
    auto c = std::make_shared<Clip>(*f.snap->clips[0]);
    c->rev = 9001;
    Lane lane;
    lane.unit = Unit::Perform;
    lane.index = Perform::Repeat;
    lane.linear = false;
    lane.points = {{0, 0.0f}, {kBar / 2, 0.6f}, {kBar, 0.0f}}; // a quarter-beat repeat for half a bar
    if (heldFromTop) lane.points = {{0, 0.6f}, {kBar / 2, 0.0f}};
    c->lanes.push_back(lane);
    f.keep.push_back(c);
    f.snap->setClip(0, 0, c);
    f.engine.panicFlag.store(true, std::memory_order_release);
    float scratch[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.requestPlay(0);
    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    for (int32_t b = 0; b < blocks; ++b) {
        if (stopHalfway && b == blocks / 2) f.engine.transport.requestStop();
        f.engine.renderBlock(nullptr, out.data() + static_cast<size_t>(b) * kBlockFrames * 2);
    }
    if (repeatAfterStop != nullptr) *repeatAfterStop = f.engine.master.perform.params().target(Perform::Repeat);
    return out;
}

void aClipCanPerform() {
    printf("- a held effect played from a clip\n");
    // A bar is 1500 blocks at 120; render to three quarters of one.
    constexpr int32_t kBlocks = 1125;
    const auto plain = render(kBlocks);
    const auto performed = renderPerformed(kBlocks, false, nullptr);
    const size_t press = size_t{750} * kBlockFrames * 2; // half a bar in: the lane's press
    const size_t first = firstDifference(plain, performed);
    ok("nothing changes before the press", first >= press, std::to_string(first) + " vs " + std::to_string(press));
    ok("and the repeat is heard after it", largestDifference(plain, performed) > 0.01f);
    ok("and the render repeats", firstDifference(performed, renderPerformed(kBlocks, false, nullptr)) == performed.size());
    // Stopped while the lane holds it down: the repeat is let go, or the
    // master would loop a quarter beat of silence until the next play.
    float held = -1.0f;
    renderPerformed(1000, true, &held);
    ok("stopping the transport lets go of a held repeat", held == 0.0f, std::to_string(held));
    // And a lane that holds it from the very top must not take it again
    // while stopped: the arranger goes back to the top on a stop.
    held = -1.0f;
    renderPerformed(1000, true, &held, true);
    ok("even when the lane holds it at the top of the song", held == 0.0f, std::to_string(held));
}

/**
 * A mute sent with a quantise waits for the next bar line and lands on it.
 *
 * The rack's own position decides where the bar is, so the check reads the
 * clock at the moment the mute takes: before the line it must not have, at
 * the line it must have.
 */
void aMuteWaitsForTheBar() {
    printf("- a mute that waits for the bar\n");
    Fixture f;
    f.engine.panicFlag.store(true, std::memory_order_release);
    float block[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, block);
    f.engine.transport.requestPlay(0);
    const auto until = [&](int64_t tick) {
        while (f.engine.clock.position() < tick) f.engine.renderBlock(nullptr, block);
    };
    ParamMessage mute;
    mute.rack = 0;
    mute.unit = Unit::Channel;
    mute.index = Rack::Mute;
    mute.value = 1.0f;
    mute.quantise = kBar;
    until(kBar / 3);
    f.engine.pushParam(mute);
    int64_t landed = -1;
    while (f.engine.clock.position() < kBar + kBar / 4) {
        f.engine.renderBlock(nullptr, block);
        if (landed < 0 && f.engine.racks[0].muted()) landed = f.engine.clock.position();
    }
    ok("sent a third of the way in, it lands on the next bar line (within a tick)",
       landed >= kBar && landed <= kBar + 1, std::to_string(landed) + " vs " + std::to_string(kBar));

    // Waiting when the song stops: it is dropped, not landed on the next play.
    mute.rack = 1;
    until(kBar + kBar / 2);
    f.engine.pushParam(mute);
    f.engine.renderBlock(nullptr, block);
    f.engine.transport.requestStop();
    f.engine.renderBlock(nullptr, block);
    f.engine.transport.requestPlay(0);
    until(3 * kBar); // well past where it was due
    ok("a stop drops a mute that was waiting", !f.engine.racks[1].muted());

    // Stopped, there is no bar to wait for: it lands at once.
    f.engine.transport.requestStop();
    f.engine.renderBlock(nullptr, block);
    f.engine.pushParam(mute);
    f.engine.renderBlock(nullptr, block);
    ok("stopped, it lands at once", f.engine.racks[1].muted());
}

/**
 * The held effects on one group: the group changes and nothing else does.
 *
 * Only rack A is routed into the group; B goes straight to the master. With
 * the limiter off the master is linear, so everything the effects did must
 * be the group's own change at the master's volume - which is what says B
 * was left alone.
 */
void theEffectsCanPlayOnAGroup() {
    printf("- the held effects on a group\n");
    constexpr int32_t kBlocks = 700;
    struct Take { std::vector<float> mix, group; };
    const auto take = [&](float target01, bool held) {
        GroupFixture f(false);
        f.engine.racks[GroupFixture::kA].setParam(Unit::Channel, Rack::Output, (GroupFixture::kGroup + 1) / 16.0f);
        f.engine.master.params().set(MasterBus::LimiterOn, 0.0f);
        f.engine.master.params().jumpAll();
        f.engine.master.perform.params().set(Perform::Target, target01);
        f.engine.master.perform.params().jumpAll();
        f.engine.panicFlag.store(true, std::memory_order_release);
        float scratch[kBlockFrames * 2];
        f.engine.renderBlock(nullptr, scratch);
        f.engine.transport.requestPlay(0);
        Take t;
        t.mix.resize(static_cast<size_t>(kBlocks) * kBlockFrames * 2);
        t.group.resize(static_cast<size_t>(kBlocks) * kBlockFrames);
        for (int32_t b = 0; b < kBlocks; ++b) {
            // Held from a little way in: the pad hard left, a deep low pass.
            if (held && b == 100) {
                f.engine.master.perform.params().set(Perform::X, 0.0f);
            }
            f.engine.renderBlock(nullptr, t.mix.data() + static_cast<size_t>(b) * kBlockFrames * 2);
            const float *gl = f.engine.master.groupOutL(GroupFixture::kGroup);
            std::copy(gl, gl + kBlockFrames, t.group.begin() + static_cast<long>(b) * kBlockFrames);
        }
        return t;
    };
    const Take plain = take(0.25f, false), onGroup = take(0.25f, true);
    ok("on the group, the group's sound changes", largestDifference(plain.group, onGroup.group) > 0.01f);
    // The master's change, less the group's change at the master's volume.
    const float volume = 0.8f;
    float rest = 0.0f;
    for (size_t i = 0; i < plain.group.size(); ++i) {
        const float mixChange = onGroup.mix[i * 2] - plain.mix[i * 2];
        const float groupChange = (onGroup.group[i] - plain.group[i]) * volume;
        rest = std::max(rest, std::fabs(mixChange - groupChange));
    }
    ok("and nothing outside the group does", rest < 1e-4f, std::to_string(rest));
    const Take onEmpty = take(0.5f, true), onAll = take(0.0f, true);
    ok("a group with nothing in it falls back to the whole mix", firstDifference(onEmpty.mix, onAll.mix) == onAll.mix.size());
}

/**
 * A step lock: a stepped lane that moves a parameter for one note and puts
 * it back. The app writes these as points at the step's start and end.
 *
 * What has to hold is the timing. Lanes are applied once a block, at the
 * value they have at the block's end, and a drum reads its tune when it is
 * struck - so the lock must already be in place for the note on its own
 * step, from that note's first sample, or it is a lock on the step after.
 * Rack 1's third note is the snare (38) on the half bar; the lock is on
 * Hexbeat's snare tune, which nothing else in the song touches.
 */
std::vector<float> renderLocked(int32_t blocks, bool locked, int32_t *noteFrame, int32_t from = kBar / 2) {
    Fixture f;
    if (locked) {
        auto c = std::make_shared<Clip>(*f.snap->clipFor(1, 0));
        c->rev = 9101;
        Lane lane;
        lane.unit = Unit::Machine;
        lane.index = f.engine.racks[1].currentMachine()->params().indexOf("snare_tune");
        lane.linear = false;
        const float base = f.engine.racks[1].currentMachine()->params().target(lane.index);
        lane.points = {{0, base}, {from, base < 0.5f ? 0.95f : 0.05f}, {kBar * 3 / 4, base}};
        c->lanes.push_back(lane);
        f.keep.push_back(c);
        f.snap->setClip(1, 0, c);
    }
    f.engine.panicFlag.store(true, std::memory_order_release);
    float scratch[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.requestPlay(0);
    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    for (int32_t b = 0; b < blocks; ++b) {
        f.engine.renderBlock(nullptr, out.data() + static_cast<size_t>(b) * kBlockFrames * 2);
    }
    // Half a bar at 120 is a second.
    if (noteFrame != nullptr) *noteFrame = kSampleRate;
    return out;
}

void aStepCanBeLocked() {
    printf("- a parameter locked on one step\n");
    // A bar and a quarter: past the lock and on to the next snare, at 5/4.
    constexpr int32_t kBlocks = 1875;
    int32_t at = 0;
    const auto plain = renderLocked(kBlocks, false, &at);
    const auto locked = renderLocked(kBlocks, true, &at);
    const size_t first = firstDifference(plain, locked) / 2;
    ok("nothing changes before the locked note", first >= static_cast<size_t>(at), std::to_string(first) + " vs " + std::to_string(at));
    ok("and the lock is heard", first < plain.size() / 2);
    // Struck with the lock, not one after it: exactly what the tune set an
    // eighth before the note sounds like. (The snare's first two blocks do
    // not depend on its tune at all, so where the difference starts says
    // nothing about when the lock arrived; this does.)
    const auto early = renderLocked(kBlocks, true, &at, kBar * 3 / 8);
    ok("and the note on the step is struck with it", firstDifference(locked, early) == locked.size(),
       std::to_string(firstDifference(locked, early) / 2));
    // The next snare, five quarters in, is struck with the knob again.
    const size_t next = static_cast<size_t>(kSampleRate) * 5 / 2 * 2;
    std::vector<float> a(plain.begin() + static_cast<long>(next), plain.end());
    std::vector<float> b(locked.begin() + static_cast<long>(next), locked.end());
    ok("and the next one is back to the knob", largestDifference(a, b) < 1e-4f, std::to_string(largestDifference(a, b)));
}

/**
 * A scene that slows into the next: 120 to 60 over its last bar. A bar at
 * 120 is 1500 blocks; a bar gliding evenly from 120 to 60 takes 4 ln 2 s,
 * 2.77 s, which is 2079 more - so the next scene starts about 3579 blocks
 * in, at the song's own tempo again.
 */
void aSceneCanSlowDown() {
    printf("- a tempo ramp inside a scene\n");
    Fixture f;
    f.snap->scenes[0].rampToBpm = 60.0f;
    f.snap->scenes[0].rampBars = 1;
    f.engine.panicFlag.store(true, std::memory_order_release);
    float scratch[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.requestPlay(0);
    float before = 0.0f, late = 0.0f, after = 0.0f;
    int32_t changed = -1;
    for (int32_t b = 0; b < 4000; ++b) {
        f.engine.renderBlock(nullptr, scratch);
        if (b == 1400) before = f.engine.clock.bpm();
        if (b == 3500) late = f.engine.clock.bpm();
        if (changed < 0 && f.engine.scheduler.currentScene() == 1) changed = b;
        if (b == 3700) after = f.engine.clock.bpm();
    }
    ok("the first bar keeps the scene's tempo", before == 120.0f, std::to_string(before));
    ok("and the last slows to nearly the target", late < 62.0f && late > 59.0f, std::to_string(late));
    ok("so the next scene starts late by the ramp's time", changed >= 3570 && changed <= 3590, std::to_string(changed));
    ok("at the song's tempo again", after == 120.0f, std::to_string(after));
}

int main() {
    printf("\nrendering a song, off a phone\n\n");
    aRenderRepeats();
    aRenderIsAlwaysFullQuality();
    aRenderDoesNotDependOnWhatPlayedBefore();
    aTracksCostIsAPercentile();
    aTrackCanListenToAnother();
    tracksCanBeGrouped();
    theMasterHasInserts();
    aClipCanPerform();
    aMuteWaitsForTheBar();
    theEffectsCanPlayOnAGroup();
    aStepCanBeLocked();
    aSceneCanSlowDown();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
