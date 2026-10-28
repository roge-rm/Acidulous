// An insert's mod slot, in the whole Engine.
//
// A held Trinity chord through a Filter in FX4, and a Genesis kick on another
// track. Each source (LFO, random hold, steps, follower, a follower keyed from
// the kick) must move the filter's cutoff, read straight off the effect's
// smoothed parameter each block; with the source off the same reading must
// stay put. Then: a modulated song renders the same twice and after a panic,
// an off slot is bit-identical to no mod at all, the knob under the mod still
// works, turning the source off puts the knob back, the key is one block late,
// and the master's inserts have one too.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Effects.h>
#include <engine/effect/SlotMod.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

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
    rack.setParam(unit, modIndex(slotmod::Key), stepped(m.key, kRackCount));
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

constexpr int32_t kBlocks = 1500; // two bars at 120

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

} // namespace

int main() {
    eachSourceMovesItsTarget();
    anOffSlotIsNotThere();
    aModulatedSongRepeats();
    theKnobStillWorks();
    theKeyIsOneBlockLate();
    theMasterHasOne();
    printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
