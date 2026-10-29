// An insert's mod slot, in the whole Engine.
//
// A held Trinity chord through a Filter in FX4, and a Genesis kick on another
// track. Each source (LFO, random hold, steps, follower, a follower keyed from
// the kick) must move the filter's cutoff, read straight off the effect's
// smoothed parameter each block; with the source off the same reading must
// stay put. Then: a modulated song renders the same twice and after a panic,
// an off slot is bit-identical to no mod at all, the knob under the mod still
// works, turning the source off puts the knob back, the key is one block late,
// and the master's inserts have one too. A scene played on its own moves as
// the song moves through it, and a fast mod on every continuous knob of every
// effect is measured once a block and sliced against a step every sample.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Effects.h>
#include <engine/effect/SlotMod.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>
#include <sequencer/TickClock.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-58s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int32_t kBar = kPPQN * 4;
constexpr int32_t kPad = 2, kKick = 6, kSlot = 3; // FX4
constexpr Unit kUnit = Unit::Effect4;

int32_t modIndex(int32_t k) { return kSlotModIndexBase - k; }
float stepped(int32_t value, int32_t steps) { return static_cast<float>(value) / static_cast<float>(steps - 1); }
float depth01(float d) { return (d + 1.0f) * 0.5f; }

struct Mod {
    int32_t source = slotmod::Off;
    int32_t shape = slotmod::Sine;
    int32_t rate = 4; // 1/8 dotted .. see Lfo::kBeats; 4 is 3/8 of a beat
    float depth = 0.6f;
    int32_t key = kKick;
    bool steps = false;
};

void setMod(Rack &rack, Unit unit, const Mod &m, int32_t target = 0) {
    rack.setParam(unit, modIndex(slotmod::Shape), stepped(m.shape, slotmod::ShapeCount));
    rack.setParam(unit, modIndex(slotmod::Rate), stepped(m.rate, dsp::Lfo::kRates));
    rack.setParam(unit, modIndex(slotmod::Attack), 0.0f);
    rack.setParam(unit, modIndex(slotmod::Release), 0.3f);
    rack.setParam(unit, modIndex(slotmod::Key), stepped(m.key, kRackCount + 1));
    rack.setParam(unit, modIndex(slotmod::Target1), stepped(target + 1, kSlotModTargets + 1));
    rack.setParam(unit, modIndex(slotmod::Depth1), depth01(m.depth));
    rack.setParam(unit, modIndex(slotmod::Source), stepped(m.source, slotmod::SourceCount));
}

struct Fixture {
    std::unique_ptr<Engine> owned = std::make_unique<Engine>();
    Engine &engine = *owned;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    Effect *filter = nullptr;

    explicit Fixture(const Mod &mod, bool withMaster = false, const Mod &masterMod = Mod()) {
        add(kPad, "Trinity");
        add(kKick, "Genesis");
        filter = EffectRegistry::create("Filter");
        filter->prepare(kSampleRate);
        filter->reset();
        filter->params().set(effect::Filter::Cutoff, 0.5f);
        filter->params().set(effect::Filter::Reso, 0.5f);
        filter->params().jumpAll();
        delete engine.racks[kPad].swapEffect(kSlot, filter);
        setMod(engine.racks[kPad], kUnit, mod);
        if (withMaster) {
            Effect *fx = EffectRegistry::create("Filter");
            fx->prepare(kSampleRate);
            fx->reset();
            fx->params().set(effect::Filter::Cutoff, 0.6f);
            fx->params().jumpAll();
            delete engine.master.swapInsert(0, fx);
            // The master's slots take messages through the engine's queue; set it directly here.
            fx->setParamAt(modIndex(slotmod::Rate), stepped(masterMod.rate, dsp::Lfo::kRates));
            fx->setParamAt(modIndex(slotmod::Shape), stepped(masterMod.shape, slotmod::ShapeCount));
            fx->setParamAt(modIndex(slotmod::Target1), stepped(1, kSlotModTargets + 1));
            fx->setParamAt(modIndex(slotmod::Depth1), depth01(masterMod.depth));
            fx->setParamAt(modIndex(slotmod::Source), stepped(masterMod.source, slotmod::SourceCount));
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
        snap->setClip(kPad, 0, held);
        auto kick = std::make_shared<Clip>();
        kick->rev = 2;
        kick->bars = 2;
        kick->ticksPerBar = kBar;
        for (int32_t b = 0; b < 8; ++b) kick->notes.push_back(ClipNote{b * kPPQN + kPPQN / 2, kPPQN / 4, 36, 120});
        keep.push_back(kick);
        snap->setClip(kKick, 0, kick);
        snap->rackCount = kKick + 1;
        engine.scheduler.swapSnapshot(snap.get());
    }
    void add(int32_t rack, const char *machine) {
        Machine *m = MachineRegistry::create(machine);
        m->prepare(kSampleRate);
        m->reset();
        m->params().jumpAll();
        delete engine.racks[rack].swapMachine(m);
    }
    ~Fixture() {
        for (int32_t r = 0; r < kRackCount; ++r) {
            for (int32_t s = 0; s < kEffectSlots; ++s) delete engine.racks[r].swapEffect(s, nullptr);
            delete engine.racks[r].swapMachine(nullptr);
        }
        delete engine.master.swapInsert(0, nullptr);
    }
    void start() {
        engine.panicFlag.store(true, std::memory_order_release);
        float scratch[kBlockFrames * 2];
        engine.renderBlock(nullptr, scratch);
        engine.transport.requestPlay(0);
    }
    /** Renders [blocks]; the cutoff each block (Hz, smoothed) and the kick's key peak go in the optional lists. */
    void run(int32_t blocks, std::vector<float> &out, std::vector<float> *cutoff = nullptr,
             std::vector<float> *key = nullptr) {
        const size_t from = out.size();
        out.resize(from + static_cast<size_t>(blocks) * kBlockFrames * 2);
        for (int32_t b = 0; b < blocks; ++b) {
            engine.renderBlock(nullptr, out.data() + from + static_cast<size_t>(b) * kBlockFrames * 2);
            if (cutoff != nullptr) cutoff->push_back(filter->params().get(effect::Filter::Cutoff));
            if (key != nullptr) {
                float p = 0.0f;
                for (float v : engine.racks[kKick].keyBuf) p = std::fmax(p, std::fabs(v));
                key->push_back(p);
            }
        }
    }
};

size_t firstDifference(const std::vector<float> &a, const std::vector<float> &b) {
    const size_t n = std::min(a.size(), b.size());
    for (size_t i = 0; i < n; ++i) if (a[i] != b[i]) return i;
    return a.size() == b.size() ? a.size() : n;
}

/** How far the cutoff moves, as octaves between its lowest and highest, after the first [skip] blocks. */
float octavesMoved(const std::vector<float> &c, size_t skip = 20) {
    float lo = 1e9f, hi = 0.0f;
    for (size_t i = skip; i < c.size(); ++i) { lo = std::fmin(lo, c[i]); hi = std::fmax(hi, c[i]); }
    return std::log2(hi / lo);
}
/** The biggest change between two blocks, in octaves. */
float largestStep(const std::vector<float> &c, size_t skip = 20) {
    float s = 0.0f;
    for (size_t i = skip + 1; i < c.size(); ++i) s = std::fmax(s, std::fabs(std::log2(c[i] / c[i - 1])));
    return s;
}

constexpr int32_t kBlocks = 1500; // a bar at 120

void eachSourceMovesItsTarget() {
    printf("- each source moves the cutoff, and nothing moves it with the source off\n");
    // The pad is one held chord, so its own follower only moves as it comes in.
    struct Case { const char *name; Mod mod; float octaves; };
    Mod lfo; lfo.source = slotmod::Lfo;
    Mod hold; hold.source = slotmod::Lfo; hold.shape = slotmod::RandomHold; hold.rate = 2;
    Mod steps; steps.source = slotmod::Steps; steps.rate = 2;
    Mod follow; follow.source = slotmod::Follow;
    Mod keyed; keyed.source = slotmod::Keyed;
    const Case cases[] = {{"lfo sine", lfo, 1.0f}, {"lfo random hold", hold, 1.0f}, {"steps", steps, 1.0f}, {"follower", follow, 0.5f}, {"keyed follower", keyed, 1.0f}};
    for (const Case &c : cases) {
        std::vector<float> on, off, cOn, cOff;
        { Fixture f(c.mod); f.start(); f.run(kBlocks, on, &cOn); }
        Mod none = c.mod;
        none.source = slotmod::Off;
        { Fixture f(none); f.start(); f.run(kBlocks, off, &cOff); }
        const float movedOn = octavesMoved(cOn), movedOff = octavesMoved(cOff);
        char d[96];
        std::snprintf(d, sizeof d, "on %.2f oct, off %.4f oct, step %.3f oct", movedOn, movedOff, largestStep(cOn));
        ok((std::string(c.name) + ": moves the cutoff").c_str(), movedOn > c.octaves, d);
        ok((std::string(c.name) + ": the same measure reads still with it off").c_str(), movedOff < 1e-4f);
        ok((std::string(c.name) + ": and the audio differs").c_str(), firstDifference(on, off) < on.size());
    }
}

void anOffSlotIsNotThere() {
    printf("- an off slot with targets and depths set is bit-identical to none\n");
    Mod set; // source off, target and depth set
    std::vector<float> a, b;
    { Fixture f(set); f.start(); f.run(600, a); }
    {
        Fixture f(set);
        // Back to the slot's defaults: no target, no depth.
        f.engine.racks[kPad].setParam(kUnit, modIndex(slotmod::Target1), 0.0f);
        f.engine.racks[kPad].setParam(kUnit, modIndex(slotmod::Depth1), 0.5f);
        f.start();
        f.run(600, b);
    }
    ok("identical", firstDifference(a, b) == a.size());
}

Mod everything() {
    Mod m;
    m.source = slotmod::Lfo;
    m.shape = slotmod::RandomSmooth;
    return m;
}

void aModulatedSongRepeats() {
    printf("- a modulated render repeats\n");
    const auto renderAll = [](int32_t which) {
        Mod m = everything();
        m.source = which;
        Mod master;
        master.source = slotmod::Lfo;
        master.shape = slotmod::RandomHold;
        master.rate = 1;
        Fixture f(m, true, master);
        // A second slot on the kick, keyed from the pad, and steps on FX1 of the pad.
        Effect *crush = EffectRegistry::create("Filter");
        crush->prepare(kSampleRate);
        crush->reset();
        crush->params().jumpAll();
        delete f.engine.racks[kKick].swapEffect(0, crush);
        Mod back;
        back.source = slotmod::Keyed;
        back.key = kPad;
        setMod(f.engine.racks[kKick], Unit::Effect1, back);
        std::vector<float> out;
        f.start();
        f.run(900, out);
        // And after a panic, from the top again.
        std::vector<float> again;
        f.start();
        f.run(900, again);
        return std::make_pair(out, again);
    };
    for (int32_t s : {static_cast<int32_t>(slotmod::Off), static_cast<int32_t>(slotmod::Lfo), static_cast<int32_t>(slotmod::Steps),
                      static_cast<int32_t>(slotmod::Follow), static_cast<int32_t>(slotmod::Keyed)}) {
        const auto one = renderAll(s), two = renderAll(s);
        const std::string n = "source " + std::to_string(s);
        ok((n + ": two renders are identical").c_str(), firstDifference(one.first, two.first) == one.first.size());
        ok((n + ": and the same after a panic").c_str(), firstDifference(one.first, one.second) == one.first.size());
    }
}

void theKnobStillWorks() {
    printf("- the knob under the mod\n");
    Mod lfo;
    lfo.source = slotmod::Lfo;
    lfo.depth = 0.2f;
    std::vector<float> out, c1, c2;
    Fixture f(lfo);
    f.start();
    f.run(750, out, &c1);
    ok("the knob keeps its own value", f.filter->params().normalized(effect::Filter::Cutoff) == 0.5f);
    f.engine.racks[kPad].setParam(kUnit, effect::Filter::Cutoff, 0.8f);
    f.run(750, out, &c2);
    float m1 = 0, m2 = 0;
    for (float v : c1) m1 += std::log2(v);
    for (float v : c2) m2 += std::log2(v);
    m1 /= c1.size();
    m2 /= c2.size();
    char d[64];
    std::snprintf(d, sizeof d, "centre %.0f Hz -> %.0f Hz", std::exp2(m1), std::exp2(m2));
    ok("turning it moves the centre the mod swings around", m2 - m1 > 2.0f, d);
    ok("and it still swings", octavesMoved(c2) > 0.5f);
    // Off: the knob's own value again, within a few blocks.
    f.engine.racks[kPad].setParam(kUnit, modIndex(slotmod::Source), 0.0f);
    std::vector<float> c3;
    f.run(40, out, &c3);
    const float own = f.filter->params().target(effect::Filter::Cutoff);
    ok("source off puts it back on the knob", std::fabs(c3.back() - own) < 0.01f * own);
}

void theKeyIsOneBlockLate() {
    printf("- keyed: last block's level\n");
    Mod keyed;
    keyed.source = slotmod::Keyed;
    std::vector<float> out, cutoff, key;
    Fixture f(keyed);
    f.start();
    f.run(400, out, &cutoff, &key);
    size_t kick = 0;
    while (kick < key.size() && key[kick] < 0.05f) ++kick;
    size_t moved = 0;
    while (moved < cutoff.size() && std::fabs(cutoff[moved] - cutoff[0]) < 1.0f) ++moved;
    ok("the cutoff moves the block after the kick", kick < key.size() && moved == kick + 1,
       "kick in block " + std::to_string(kick) + ", cutoff moves in " + std::to_string(moved));
}

void theMasterHasOne() {
    printf("- the master's inserts\n");
    Mod off;
    Mod master;
    master.source = slotmod::Lfo;
    std::vector<float> a, b;
    { Fixture f(off, true, Mod()); f.start(); f.run(600, a); }
    { Fixture f(off, true, master); f.start(); f.run(600, b); }
    ok("an lfo on a master insert changes the mix", firstDifference(a, b) < a.size());
}

/**
 * The mod follows the song's position: scene 2 played on its own, or frozen,
 * moves as it does when the song plays through scene 1 into it, from the
 * first block. The pad is silent in scene 1, so only the mod has run. The
 * frozen start resets the rack by hand as EngineHost::freezeClip does.
 */
void aSceneStartsWhereTheSongHasIt() {
    printf("- a scene played on its own moves as it does in the song\n");
    struct Run { std::vector<float> audio, cutoff; };
    const auto render = [](int32_t source, int32_t shape, int32_t rate, int32_t fromScene, int32_t blocks,
                           bool frozen = false) {
        Mod m;
        m.source = source;
        m.shape = shape;
        m.rate = rate;
        Fixture f(m);
        // Scene 1: three bars, the kick alone. Scene 2: the fixture's two bars.
        auto snap = std::make_shared<SongSnapshot>(*f.snap);
        SceneInfo first;
        first.id = 7;
        first.bars = 3;
        first.repeat = 1;
        first.ticksPerBar = kBar;
        snap->scenes.insert(snap->scenes.begin(), first);
        snap->clips.clear();
        snap->clips.resize(static_cast<size_t>(snap->rackCount) * snap->scenes.size());
        snap->setClip(kPad, 1, f.keep[0]);
        snap->setClip(kKick, 1, f.keep[1]);
        auto kick = std::make_shared<Clip>(*f.keep[1]);
        kick->bars = 3;
        kick->rev = 3;
        f.keep.push_back(kick);
        snap->setClip(kKick, 0, kick);
        f.engine.scheduler.swapSnapshot(snap.get());
        f.snap = snap;
        float scratch[kBlockFrames * 2];
        if (frozen) {
            f.engine.transport.setLoopScene(true);
            f.engine.transport.requestStop();
            f.engine.renderBlock(nullptr, scratch);
            Rack &r = f.engine.racks[kPad];
            r.allNotesOff();
            r.clipPlayer.reset();
            r.currentMachine()->reset();
            r.currentMachine()->params().jumpAll();
            for (int32_t sl = 0; sl < kEffectSlots; ++sl) {
                if (Effect *e = r.currentEffect(sl)) {
                    e->reset();
                    e->params().jumpAll();
                    e->resetMod();
                }
            }
        } else {
            f.engine.panicFlag.store(true, std::memory_order_release);
            f.engine.renderBlock(nullptr, scratch);
        }
        f.engine.transport.requestPlay(fromScene);
        // The pad's own sound after its inserts, so the kick's drift doesn't count.
        Run r;
        std::vector<float> mix;
        for (int32_t b = 0; b < blocks; ++b) {
            f.run(1, mix, &r.cutoff);
            r.audio.insert(r.audio.end(), f.engine.racks[kPad].keyBuf, f.engine.racks[kPad].keyBuf + kBlockFrames);
        }
        return r;
    };
    // Three bars at 120 is 6 s = 4500 blocks (2880 ticks at 0.64 a block).
    const int32_t firstBlocks = 4500, secondBlocks = 1400;
    struct Case { const char *name; int32_t source, shape, rate; bool frozen; };
    const Case cases[] = {{"two-bar sine", slotmod::Lfo, slotmod::Sine, 14, false},
                          {"random hold on the beat", slotmod::Lfo, slotmod::RandomHold, 8, false},
                          {"drift on the beat", slotmod::Lfo, slotmod::RandomSmooth, 8, false},
                          {"steps on the beat", slotmod::Steps, 0, 8, false},
                          {"random hold on the beat, frozen", slotmod::Lfo, slotmod::RandomHold, 8, true},
                          {"steps on the beat, frozen", slotmod::Steps, 0, 8, true}};
    for (const Case &c : cases) {
        const Run song = render(c.source, c.shape, c.rate, 0, firstBlocks + secondBlocks);
        const Run alone = render(c.source, c.shape, c.rate, 1, secondBlocks, c.frozen);
        // From the first block: play runs the knob's smoother through the
        // blocks before the scene, so it lands where the song has it. The
        // steps land on the same step at the song's top, so only the lfos
        // are checked against it.
        float worst = 0.0f, fromTop = 0.0f;
        for (size_t b = 0; b < alone.cutoff.size(); ++b) {
            worst = std::fmax(worst, std::fabs(std::log2(alone.cutoff[b] / song.cutoff[firstBlocks + b])));
            fromTop = std::fmax(fromTop, std::fabs(std::log2(alone.cutoff[b] / song.cutoff[b])));
        }
        double err = 0.0, sig = 0.0;
        const size_t base = static_cast<size_t>(firstBlocks) * kBlockFrames;
        for (size_t i = 0; i < alone.audio.size(); ++i) {
            const double d = alone.audio[i] - song.audio[base + i];
            err += d * d;
            sig += static_cast<double>(alone.audio[i]) * alone.audio[i];
        }
        const double db = 10.0 * std::log10((err + 1e-30) / (sig + 1e-30));
        char d[128];
        std::snprintf(d, sizeof d, "cutoff within %.4f oct (%.2f oct from the top), audio %.1f dB", worst, fromTop, db);
        ok((std::string(c.name) + ": the cutoff follows the song").c_str(), worst < 1e-4f && (c.source == slotmod::Steps || fromTop > 0.2f), d);
        ok((std::string(c.name) + ": and so does the sound").c_str(), db < -120.0, d);
    }
}

/**
 * One SlotMod on a one-knob ParamSet, driven by a TickClock as the engine
 * drives it: the knob reads the source straight (knob 0, depth 1).
 */
struct Probe {
    static constexpr ParamDef kKnob[1] = {{"x", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""}};
    SlotMod mod;
    ParamSet ps;
    TickClock clock;
    Probe(int32_t source, float bpm, float attackMs = 10.0f) {
        ps.init(kKnob, 1);
        clock.setSampleRate(kSampleRate);
        clock.setTempo(bpm);
        int32_t n = 0;
        const ParamDef *d = slotmod::defs(n);
        mod.set(slotmod::Attack, d[slotmod::Attack].unmap(attackMs), &ps);
        mod.set(slotmod::Release, d[slotmod::Release].unmap(10.0f), &ps);
        mod.set(slotmod::Target1, stepped(1, kSlotModTargets + 1), &ps);
        mod.set(slotmod::Depth1, 1.0f, &ps);
        mod.set(slotmod::Source, stepped(source, slotmod::SourceCount), &ps);
    }
    /** One block of a steady [level] in; the knob after it. */
    float step(float level, bool started = false) {
        clock.advance(kBlockFrames);
        ModTransport t;
        t.started = started;
        mod.block(clock.blockStart(), clock.bpm(), t);
        float L[kBlockFrames], R[kBlockFrames];
        std::fill(L, L + kBlockFrames, level);
        std::fill(R, R + kBlockFrames, level);
        mod.apply(L, R, kBlockFrames, true, ps);
        ps.tick();
        return ps.get(0);
    }
};

/**
 * A block is under a tick at most tempos, so many blocks cross none. The
 * follower must rise through them, play must empty it whatever came before,
 * and an LFO's position must move by the same fraction every block. The
 * engine's block is kBlockFrames, so the tempos vary the blocks per tick.
 */
void blocksThatCrossNoTick() {
    printf("- blocks that cross no tick: the follower rises, play empties it, the lfo's phase is smooth\n");
    const float bpms[] = {60.0f, 90.0f, 120.0f, 150.0f, 180.0f, 240.0f};
    const float level = 0.5f; // -6 dBFS
    const float expect = 1.0f + 20.0f * std::log10(level) / 48.0f;
    for (float bpm : bpms) {
        Probe p(slotmod::Follow, bpm, 500.0f);
        float at = p.step(level, true);
        for (int32_t b = 1; b < 3 * kSampleRate / kBlockFrames; ++b) at = p.step(level);
        char d[96];
        std::snprintf(d, sizeof d, "%.4f, steady %.4f", at, expect);
        ok((std::to_string(static_cast<int>(bpm)) + " bpm: a 500 ms attack reaches the level in 3 s").c_str(),
           std::fabs(at - expect) < 0.01f, d);
    }
    // Play empties it: after a loud stop, a loud play, or a slow tempo where
    // no tick passes in many blocks.
    struct Before { const char *name; float bpm; };
    const Before befores[] = {{"after a loud stop", 120.0f}, {"after loud play", 120.0f}, {"at 20 bpm", 20.0f}};
    for (const Before &c : befores) {
        Probe p(slotmod::Follow, c.bpm);
        if (std::string(c.name) == "after loud play") p.step(0.9f, true);
        for (int32_t b = 0; b < 500; ++b) p.step(0.9f);
        const float loud = p.ps.get(0);
        p.clock.reset();
        const float at = p.step(0.0f, true);
        char d[96];
        std::snprintf(d, sizeof d, "%.4f before, %.6f at play", loud, at);
        ok((std::string("play empties the follower ") + c.name).c_str(), loud > 0.5f && at == 0.0f, d);
    }
    for (float bpm : bpms) {
        Probe p(slotmod::Lfo, bpm);
        p.step(0.0f, true);
        const double per = static_cast<double>(kBlockFrames) * bpm * kPPQN / (60.0 * kSampleRate);
        double was = p.mod.position(), worst = 0.0;
        int32_t still = 0;
        for (int32_t b = 0; b < 2000; ++b) {
            const int64_t tick = p.clock.blockEnd();
            p.step(0.0f);
            if (p.clock.blockEnd() == tick) ++still;
            worst = std::fmax(worst, std::fabs(p.mod.position() - was - per));
            was = p.mod.position();
        }
        char d[96];
        std::snprintf(d, sizeof d, "%d of 2000 blocks cross no tick, step off by %.2e ticks", still, worst);
        ok((std::to_string(static_cast<int>(bpm)) + " bpm: the lfo moves the same each block").c_str(), worst < 1e-5, d);
    }
}

/**
 * A count-in before a scene part way through the song: the LFO runs on the
 * song's phase through the clicks, so it doesn't jump when the song starts.
 */
void aCountInLandsOnTheSong() {
    printf("- a count-in runs the lfo on the phase the song starts with\n");
    Mod m;
    m.source = slotmod::Lfo;
    m.rate = 15; // four bars, which the three-bar first scene doesn't fit
    Fixture f(m);
    auto snap = std::make_shared<SongSnapshot>(*f.snap);
    SceneInfo first;
    first.id = 7;
    first.bars = 3;
    first.repeat = 1;
    first.ticksPerBar = kBar;
    snap->scenes.insert(snap->scenes.begin(), first);
    snap->clips.clear();
    snap->clips.resize(static_cast<size_t>(snap->rackCount) * snap->scenes.size());
    snap->setClip(kPad, 1, f.keep[0]);
    f.engine.scheduler.swapSnapshot(snap.get());
    f.snap = snap;
    f.engine.panicFlag.store(true, std::memory_order_release);
    float scratch[kBlockFrames * 2];
    f.engine.renderBlock(nullptr, scratch);
    f.engine.transport.setCountInBars(1);
    f.engine.transport.setRecordArmed(true);
    f.engine.transport.requestPlay(1);
    std::vector<float> out, cutoff;
    // One bar of count-in at 120 is 1500 blocks.
    f.run(3000, out, &cutoff);
    float atStart = 0.0f, elsewhere = 0.0f;
    for (size_t b = 20; b + 1 < cutoff.size(); ++b) {
        const float s = std::fabs(std::log2(cutoff[b + 1] / cutoff[b]));
        if (b >= 1480 && b <= 1520) atStart = std::fmax(atStart, s);
        else elsewhere = std::fmax(elsewhere, s);
    }
    char d[96];
    std::snprintf(d, sizeof d, "largest step %.4f oct at the start, %.4f oct elsewhere", atStart, elsewhere);
    ok("the cutoff doesn't jump as the song starts", atStart < 2.0f * elsewhere + 1e-4f, d);
}

/** A keyed mod whose key is none hears silence: the cutoff stays on the knob. */
void aKeyOfNoneIsSilence() {
    printf("- a keyed mod with no key track\n");
    Mod m;
    m.source = slotmod::Keyed;
    m.key = kRackCount;
    std::vector<float> out, cutoff;
    { Fixture f(m); f.start(); f.run(kBlocks, out, &cutoff); }
    char d[64];
    std::snprintf(d, sizeof d, "%.5f oct", octavesMoved(cutoff));
    ok("hears nothing, so the cutoff stays put", octavesMoved(cutoff) < 1e-4f, d);
}

/** The fixture's scene made [bars] long, round its two-bar clips; a one-scene song loops. */
void lengthen(Fixture &f, int32_t bars) {
    auto snap = std::make_shared<SongSnapshot>(*f.snap);
    snap->scenes[0].bars = bars;
    f.engine.scheduler.swapSnapshot(snap.get());
    f.snap = snap;
}

/**
 * A two-bar scene looping against the same clips in a four-bar scene, where
 * nothing jumps: across the loop a follower carries on rather than dropping
 * to nothing and attacking back, and an LFO that fits the loop lands on the
 * same phase.
 */
void aLoopDoesNotDip() {
    printf("- a one-scene song looping: followers carry on, an lfo keeps its phase\n");
    struct Case { const char *name; Mod mod; };
    Mod follow; follow.source = slotmod::Follow;
    Mod keyed; keyed.source = slotmod::Keyed;
    Mod fast; fast.source = slotmod::Lfo; fast.rate = 0; // 1/32
    Mod slow; slow.source = slotmod::Lfo; slow.rate = 14; // two bars
    const Case cases[] = {{"follower", follow}, {"keyed follower", keyed}, {"1/32 sine", fast}, {"two-bar sine", slow}};
    for (const Case &c : cases) {
        std::vector<float> out, looped, straight;
        // The loop is two bars in: 4 s at 120.
        const int32_t loop = 4 * kSampleRate / kBlockFrames;
        { Fixture f(c.mod); f.start(); f.run(loop + 700, out, &looped); }
        out.clear();
        { Fixture f(c.mod); lengthen(f, 4); f.start(); f.run(loop + 700, out, &straight); }
        float worst = 0.0f, dip = 0.0f;
        for (size_t b = static_cast<size_t>(loop) - 100; b < looped.size(); ++b) {
            const float d = std::log2(looped[b] / straight[b]);
            worst = std::fmax(worst, std::fabs(d));
            dip = std::fmin(dip, d);
        }
        char d[96];
        std::snprintf(d, sizeof d, "within %.5f oct of no loop, lowest %.4f oct", worst, dip);
        ok((std::string(c.name) + ": across the loop as if there were none").c_str(), worst < 0.005f, d);
    }
}

/** A sine and a little noise, stereo, for the zipper renders. */
void testTone(float *L, float *R, int64_t at) {
    static uint32_t rng = 1;
    if (at == 0) rng = 1;
    for (int32_t i = 0; i < kBlockFrames; ++i) {
        const double t = static_cast<double>(at + i) / kSampleRate;
        rng = rng * 1664525u + 1013904223u;
        const float noise = (static_cast<float>(rng >> 9) * (1.0f / 4194304.0f) - 1.0f) * 0.02f;
        L[i] = 0.4f * static_cast<float>(std::sin(2.0 * M_PI * 220.0 * t)) + noise;
        R[i] = 0.4f * static_cast<float>(std::sin(2.0 * M_PI * 220.0 * t + 0.3)) - noise;
    }
}

/** One effect with a 1/32 sine on [param] at full depth, [slice] frames a step; interleaved. */
std::vector<float> zipperRender(const char *type, int32_t param, int32_t slice, int32_t blocks, bool stereoIn = true) {
    Effect *fx = EffectRegistry::create(type);
    fx->prepare(kSampleRate);
    fx->reset();
    fx->params().jumpAll();
    if (param >= 0) {
        fx->setParamAt(modIndex(slotmod::Rate), 0.0f); // 1/32
        fx->setParamAt(modIndex(slotmod::Target1), stepped(param + 1, kSlotModTargets + 1));
        fx->setParamAt(modIndex(slotmod::Depth1), 1.0f);
        fx->setParamAt(modIndex(slotmod::Source), stepped(slotmod::Lfo, slotmod::SourceCount));
    }
    fx->resetMod();
    const int32_t was = Effect::modSlice;
    Effect::modSlice = slice;
    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    float L[kBlockFrames], R[kBlockFrames];
    const double perBlock = kBlockFrames * 120.0 * kPPQN / (60.0 * kSampleRate);
    for (int32_t b = 0; b < blocks; ++b) {
        testTone(L, R, static_cast<int64_t>(b) * kBlockFrames);
        if (!stereoIn) std::fill(R, R + kBlockFrames, 0.0f);
        const auto t0 = static_cast<int64_t>(b * perBlock), t1 = static_cast<int64_t>((b + 1) * perBlock);
        fx->block(t0, t1, 120.0f);
        const bool st = fx->run(L, R, kBlockFrames, stereoIn);
        for (int32_t i = 0; i < kBlockFrames; ++i) {
            out[(static_cast<size_t>(b) * kBlockFrames + i) * 2] = L[i];
            out[(static_cast<size_t>(b) * kBlockFrames + i) * 2 + 1] = st ? R[i] : L[i];
        }
    }
    Effect::modSlice = was;
    delete fx;
    return out;
}

/** How far [x] is from [ref], in dB under ref's level; -200 for identical. */
double errorDb(const std::vector<float> &x, const std::vector<float> &ref) {
    double e = 0.0, s = 0.0;
    for (size_t i = 0; i < ref.size(); ++i) {
        const double d = static_cast<double>(x[i]) - ref[i];
        if (!std::isfinite(d)) return 999.0;
        e += d * d;
        s += static_cast<double>(ref[i]) * ref[i];
    }
    return e == 0.0 ? -200.0 : 10.0 * std::log10(e / (s + 1e-30));
}

/**
 * Every effect, every continuous knob, a fast mod at full depth: once a block
 * against a step every sample (the reference) and the slices runModulated
 * takes. A knob the block steps zipper on comes out far from the reference
 * once a block; sliced it has to be closer, and never further. Mono in, an
 * effect can come out stereo in some slices and mono in others.
 */
void fastModsDoNotZipper(bool stereoIn) {
    printf("- a fast mod on every continuous knob, %s in: error against a step every sample, once a block -> sliced\n",
           stereoIn ? "stereo" : "mono");
    constexpr int32_t kZipBlocks = 300;
    constexpr double kAudible = -40.0;
    int32_t worse = 0, fixed = 0;
    std::string loud;
    for (int32_t e = 0; EffectRegistry::name(e)[0] != '\0'; ++e) {
        const char *type = EffectRegistry::name(e);
        Effect *probe = EffectRegistry::create(type);
        int32_t n = 0;
        const ParamDef *defs = probe->paramDefs(n);
        delete probe;
        std::string line;
        for (int32_t k = 0; k < n; ++k) {
            if (defs[k].curve == Curve::Stepped) continue;
            const auto ref = zipperRender(type, k, 1, kZipBlocks, stereoIn);
            const double block = errorDb(zipperRender(type, k, kBlockFrames, kZipBlocks, stereoIn), ref);
            const double sliced = errorDb(zipperRender(type, k, Effect::modSlice, kZipBlocks, stereoIn), ref);
            // A rate reducer's hold lands elsewhere once the rate moves, so
            // its render is no nearer the reference than another sound would
            // be and a dB either way says nothing.
            const bool exempt = std::string(type) == "Bitcrusher" && std::string(defs[k].name) == "rate";
            if (sliced > block + 1.0 && !exempt) ++worse;
            if (block > kAudible && sliced <= kAudible) ++fixed;
            if (sliced > kAudible) {
                loud += std::string(" ") + type + "." + defs[k].name;
            }
            char c[96];
            std::snprintf(c, sizeof c, " %s %.0f>%.0f", defs[k].name, block, sliced);
            line += c;
        }
        printf("    %-11s%s\n", type, line.c_str());
    }
    char d[64];
    std::snprintf(d, sizeof d, "%d past %.0f dB brought under it", fixed, kAudible);
    ok(stereoIn ? "stereo in: slicing is never further from the reference" : "mono in: slicing is never further from the reference",
       worse == 0, d);
    printf("    still past %.0f dB sliced:%s\n", kAudible, loud.empty() ? " none" : loud.c_str());
}


/** A 16-bit stereo WAV of an interleaved render. */
void writeWav(const std::string &path, const std::vector<float> &x) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) return;
    const auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    const auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    const auto bytes = static_cast<uint32_t>(x.size() * 2);
    std::fwrite("RIFF", 1, 4, f); u32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    u32(16); u16(1); u16(2); u32(kSampleRate); u32(kSampleRate * 4); u16(4); u16(16);
    std::fwrite("data", 1, 4, f); u32(bytes);
    for (float v : x) {
        const auto s = static_cast<int16_t>(std::lround(std::fmax(-1.0f, std::fmin(1.0f, v)) * 32767.0f));
        std::fwrite(&s, 2, 1, f);
    }
    std::fclose(f);
}

/** INSERT_MOD_WAVS=folder: four seconds of each source, numbered, for listening, and a fast mod on a distortion. */
void writeAuditions(const char *dir) {
    printf("- auditions into %s\n", dir);
    Mod off;
    Mod lfo; lfo.source = slotmod::Lfo; lfo.rate = 5; lfo.depth = 0.35f;
    Mod hold; hold.source = slotmod::Lfo; hold.shape = slotmod::RandomHold; hold.rate = 2; hold.depth = 0.35f;
    Mod drift; drift.source = slotmod::Lfo; drift.shape = slotmod::RandomSmooth; drift.rate = 8; drift.depth = 0.4f;
    Mod steps; steps.source = slotmod::Steps; steps.rate = 2; steps.depth = 0.4f;
    Mod follow; follow.source = slotmod::Follow; follow.depth = 0.4f;
    Mod duck; duck.source = slotmod::Keyed; duck.depth = -0.4f;
    const std::pair<const char *, Mod> cases[] = {
        {"01-off", off}, {"02-lfo-sine-eighth", lfo}, {"03-random-hold-sixteenth", hold}, {"04-drift-quarter", drift},
        {"05-steps-sixteenth", steps}, {"06-follower", follow}, {"07-filter-closes-on-the-kick", duck},
    };
    for (const auto &c : cases) {
        std::vector<float> out;
        Fixture f(c.second);
        f.start();
        f.run(3000, out);
        writeWav(std::string(dir) + "/" + c.first + ".wav", out);
    }
    // A fast LFO on a knob that isn't a filter's: the distortion's drive at
    // 1/32, full depth, gliding across each block and then once a block.
    const std::pair<const char *, int32_t> fast[] = {{"08-drive-thirty-second", Effect::modSlice},
                                                     {"09-drive-thirty-second-once-a-block", kBlockFrames}};
    for (const auto &c : fast) {
        Fixture f(off);
        Effect *drive = EffectRegistry::create("Distortion");
        drive->prepare(kSampleRate);
        drive->reset();
        drive->params().set(effect::Distortion::Drive, 0.4f);
        drive->params().jumpAll();
        delete f.engine.racks[kPad].swapEffect(kSlot, drive);
        f.filter = nullptr;
        Mod m;
        m.source = slotmod::Lfo;
        m.rate = 0; // 1/32
        m.depth = 0.6f;
        setMod(f.engine.racks[kPad], kUnit, m, effect::Distortion::Drive);
        const int32_t was = Effect::modSlice;
        Effect::modSlice = c.second;
        std::vector<float> out;
        f.start();
        f.run(3000, out);
        Effect::modSlice = was;
        writeWav(std::string(dir) + "/" + c.first + ".wav", out);
    }
    // A follower opening the filter over a one-bar scene looping four times.
    {
        Mod m;
        m.source = slotmod::Follow;
        m.depth = 0.6f;
        Fixture f(m);
        lengthen(f, 1);
        std::vector<float> out;
        f.start();
        f.run(4 * 4 * kSampleRate / kBlockFrames / 2, out);
        writeWav(std::string(dir) + "/10-follower-over-a-looping-scene.wav", out);
    }
}

} // namespace

int main() {
    if (const char *slice = std::getenv("INSERT_MOD_SLICE")) Effect::modSlice = std::atoi(slice);
    if (const char *dir = std::getenv("INSERT_MOD_WAVS")) {
        writeAuditions(dir);
        return 0;
    }
    eachSourceMovesItsTarget();
    anOffSlotIsNotThere();
    aModulatedSongRepeats();
    theKnobStillWorks();
    theKeyIsOneBlockLate();
    theMasterHasOne();
    aSceneStartsWhereTheSongHasIt();
    aLoopDoesNotDip();
    blocksThatCrossNoTick();
    aCountInLandsOnTheSong();
    aKeyOfNoneIsSilence();
    fastModsDoNotZipper(true);
    fastModsDoNotZipper(false);
    printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
