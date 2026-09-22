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

int main() {
    printf("\nrendering a song, off a phone\n\n");
    aRenderRepeats();
    aRenderIsAlwaysFullQuality();
    aRenderDoesNotDependOnWhatPlayedBefore();
    aTracksCostIsAPercentile();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
