// What the machines and effects actually cost, per block, worst case.
//
// The first harness in this tree about **cost** rather than correctness, and
// it exists because the app could not answer the only question a dropout asks.
// The in-app meter now reports the worst callback against its budget; this is
// the same question asked offline, where a number can be compared against last
// week's instead of against a phone that was also running something else.
//
// **Worst block, not mean.** A mean hides exactly the thing that causes a
// click - one block in eleven doing ten times the work - so every figure here
// is the slowest single block of the run, with the mean beside it only to show
// how far apart they are. A unit whose worst is far above its mean is spiky,
// and spiky is what drops audio at an average load of twenty-six per cent.
//
// Run:  tools/cpu_test.sh            all of them, sorted by worst block
//       tools/cpu_test.sh Resonance  one, with its per-phase detail
#include <algorithm>
#include <ctime>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/Settings.h>
#include <engine/dsp/Denormals.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/core/Frozen.h>
#include <engine/machine/MachineRegistry.h>
#include "audition_material.h"
#include "patchbank.h"
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/dsp/Wsola.h>
#include <engine/rack/Rack.h>
#include <memory>

using namespace acidulous;

namespace {

constexpr int32_t kSr = kSampleRate;
constexpr int32_t kBlock = kBlockFrames;
/** Long enough that a periodic spike (a grain, a WSOLA hop) is certain to land. */
constexpr int32_t kBlocks = 2000;

/**
 * This thread's own CPU time, in microseconds.
 *
 * **Not the wall clock**, and the first version of this harness used the wall
 * clock and was wrong in the same way the app's meter was wrong: a benchmark
 * process is descheduled like any other, so the slowest block it records is
 * whichever one the host interrupted. That showed up as a dozen unrelated
 * units all reporting a worst block between 250 and 455 us - a number about
 * this machine's scheduler, not about any of them.
 */
double nowUs() {
    timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<double>(ts.tv_sec) * 1e6 + static_cast<double>(ts.tv_nsec) / 1e3;
}

/** One block's worth of budget, which is what every figure is measured against. */
constexpr double kBudgetUs = 1000000.0 * kBlock / kSr;

/**
 * Every block's cost, reduced to the two numbers worth quoting.
 *
 * **A percentile, not the maximum.** The maximum of two thousand samples is
 * whichever one the host interrupted - even measured in thread CPU time it is
 * dominated by artefacts, and it read 250-390 us for a dozen units that have
 * nothing in common. The 99th survives that and still catches what this is
 * for: a spike that fires one block in eleven is 9% of the run, far inside the
 * top percentile, so a real periodic cost shows up and a one-in-two-thousand
 * scheduler hiccup does not.
 */
struct Result {
    std::string name;
    std::vector<double> samples;
    double p99 = 0.0;
    double mean = 0.0;

    void finish() {
        if (samples.empty()) return;
        double total = 0.0;
        for (double v : samples) total += v;
        mean = total / static_cast<double>(samples.size());
        std::sort(samples.begin(), samples.end());
        const size_t at = static_cast<size_t>(static_cast<double>(samples.size()) * 0.99);
        p99 = samples[std::min(at, samples.size() - 1)];
    }
    double spikiness() const { return mean > 0.0 ? p99 / mean : 0.0; }
};

/**
 * How fast notes arrive, in note-ons a second.
 *
 * **This was a hidden assumption and it made a number wrong.** The pattern
 * below has always fired an event every sixteen blocks, alternating on and
 * off - a note-on every 43 ms, about 23 a second. A sixteenth at 124 bpm is
 * 121 ms, about eight. So the harness plays roughly **three times faster than
 * any music**, and any patch whose release outlives a note therefore holds
 * three times the voices here that it would in a song: Trinity's `Bell Keys`
 * measures 122 us at this rate and 53 at a musical one, and it was the 122
 * that got reported as what the demo's Keys track costs.
 *
 * The density is right for what this harness is mostly for - comparing units,
 * and measuring a change against itself, where more voices is more signal -
 * and wrong for "what does this song cost". So it stays the default, and it
 * is **printed** rather than assumed, and `--rate` changes it.
 *
 * Percussive patches do not care: `Pump` and `Squelch` measure the same at
 * both. A patch that moves a lot between the two rates is telling you its
 * release is long, which is worth knowing on its own.
 */
double gNotesPerSecond = static_cast<double>(kSr) / (16.0 * 2.0 * kBlock); // ~23.4

/**
 * A note pattern with something happening on most blocks.
 *
 * Silence measures nothing: most of these are cheap until a note starts, and
 * the per-note-on work is exactly what this harness is looking for.
 */
struct Player {
    int32_t next = 0;
    int32_t step = 0;
    /** Blocks between events; two events make one note, so this is half a period. */
    static int32_t stride() {
        const double blocks = static_cast<double>(kSr) / (gNotesPerSecond * 2.0 * kBlock);
        return std::max(1, static_cast<int32_t>(blocks + 0.5));
    }
    void tick(Machine *m, int32_t block) {
        if (block != next) return;
        // **36 upward, one semitone at a time.** The first version played 48
        // and up in whole tones, which is a fine melodic range and misses
        // every drum machine in the app: the pads of Hexbeat, Genesis, Forage
        // and Resonance all start at 36 and run eight or thirteen semitones,
        // so four machines were being timed with no note ever reaching them.
        const uint8_t pitch = static_cast<uint8_t>(36 + (step % 8));
        if (step % 2 == 0) m->noteOn(pitch, 100);
        else m->noteOff(static_cast<uint8_t>(36 + ((step - 1) % 8)));
        ++step;
        next = block + stride();
    }
};

/**
 * The cloud Cumulus plays, which nothing here was building.
 *
 * Cumulus takes its wavetables through `swapObject`, the way the samplers take
 * a file - so with nothing mounted it renders **exact silence**, and this
 * harness has been reporting 1.4 us for a machine doing nothing at all while
 * the same track was the second dearest on a phone. Unlike a sampler's, its
 * tables are *computed*, so nothing outside the tree is needed to build them:
 * the app does this in `EngineHost::buildCloud` and so does this.
 *
 * The set is leaked on purpose. It lives as long as the machine does and the
 * process is about to end.
 */
/**
 * **And the samplers get something to play.**
 *
 * The same was true of Mosaic, Forage, Dice, Pollen and Molt, and it hid
 * the dearest fault outside the demo: Mosaic worked out every layer's playback
 * rate - two `exp2` and a double divide - and its pan - a `cos` and a `sin` -
 * per sample, for numbers that hold still for the block. It was reported here
 * as a floor because it had nothing mounted.
 *
 * The material is the audition harness's own synthetic set, the one
 * `bank_test` plays every factory patch against: a zone map, a drum kit, a
 * break and a spoken phrase, all built in code. Built once per machine and
 * kept for the life of the process, so a forty-round sweep does not build
 * forty of each.
 */
struct Mounted {
    std::vector<std::unique_ptr<SampleData>> pieces;
    std::unique_ptr<audio::Take> take;
    std::unique_ptr<SampleMap> map;
    std::unique_ptr<audio::Utterance> utterance;
};

Mounted &mounted(const std::string &machine) {
    static Mounted forage, dice, pollen, mosaic, molt;
    if (machine == "Forage") {
        if (forage.pieces.empty()) {
            for (int i = 0; i < static_cast<int>(audition::Piece::Count); ++i)
                forage.pieces.push_back(audition::pieceSample(static_cast<audition::Piece>(i)));
        }
        return forage;
    }
    if (machine == "Dice") { if (!dice.take) dice.take = audition::breakLoop(); return dice; }
    if (machine == "Pollen") { if (!pollen.take) pollen.take = audition::breakLoop(); return pollen; }
    if (machine == "Mosaic") { if (!mosaic.map) mosaic.map = audition::zoneMap(); return mosaic; }
    if (!molt.utterance) molt.utterance = audition::voiceUtterance();
    return molt;
}

bool holdsAudio(const std::string &machine) {
    return machine == "Forage" || machine == "Dice" || machine == "Pollen" || machine == "Mosaic" ||
           machine == "Molt";
}

void mountCloudIfNeeded(Machine *m, const std::string &machine) {
    if (m == nullptr) return;
    if (holdsAudio(machine)) {
        Mounted &mat = mounted(machine);
        if (!mat.pieces.empty()) {
            for (size_t i = 0; i < mat.pieces.size(); ++i) m->swapObject(static_cast<int32_t>(i), mat.pieces[i].get());
        } else if (mat.take) {
            m->swapObject(0, mat.take.get());
        } else if (mat.map) {
            m->swapObject(0, mat.map.get());
        } else {
            m->swapObject(0, mat.utterance.get());
        }
        return;
    }
    if (machine != "Cumulus") return;
    auto *cum = static_cast<machine::Cumulus *>(m);
    auto set = machine::cumulus::buildCloud(cum->spec(), kSr);
    delete static_cast<machine::cumulus::CloudSet *>(cum->swapObject(0, set.release()));
}

/** And the other half of it: a sweep builds one of these per round. */
void unmountCloud(Machine *m, const std::string &machine) {
    if (m == nullptr) return;
    if (holdsAudio(machine)) {
        // The material is kept; the machine only lets go of it.
        const int32_t slots = machine == "Forage" ? static_cast<int32_t>(mounted(machine).pieces.size()) : 1;
        for (int32_t i = 0; i < slots; ++i) m->swapObject(i, nullptr);
        return;
    }
    if (machine != "Cumulus") return;
    auto *cum = static_cast<machine::Cumulus *>(m);
    delete static_cast<machine::cumulus::CloudSet *>(cum->swapObject(0, nullptr));
}

/**
 * A whole rack, live against frozen: what freezing actually gives back.
 *
 * Dan asked the only question that matters about it - *"is freezing the tracks
 * really leading to reduced load?"* - and neither the per-unit table above nor
 * the app's meter answers it, because both measure parts rather than the rack
 * as the engine runs it. This times `Rack::render` itself, with a machine and
 * two inserts, in each of the three states a rack can be in:
 *
 *   live    the machine and both effects running, notes arriving
 *   frozen  the same rack reading its own audio back instead
 *   bare    a rack with nothing mounted, which is the floor nothing can go below
 *
 * The channel strip, the pan and the peak loop run in every one of them, so
 * the difference between live and frozen is the whole of the saving and the
 * frozen figure is the whole of the remaining cost.
 */
Result timeRack(const std::string &machine, const std::string &fx1, const std::string &fx2, int mode) {
    Result r;
    r.name = mode == 0 ? machine + " live" : (mode == 1 ? machine + " frozen" : "bare rack");
    auto rack = std::make_unique<Rack>();

    if (mode != 2) {
        rack->swapMachine(MachineRegistry::create(machine.c_str()));
        if (rack->currentMachine() == nullptr) return r;
        rack->swapEffect(0, EffectRegistry::create(fx1.c_str()));
        rack->swapEffect(1, EffectRegistry::create(fx2.c_str()));
        if (Machine *m = rack->currentMachine()) {
            m->prepare(kSr); m->reset(); m->params().jumpAll();
            mountCloudIfNeeded(m, machine);
        }
        for (int32_t sl = 0; sl < kEffectSlots; ++sl) {
            if (Effect *e = rack->currentEffect(sl)) { e->prepare(kSr); e->reset(); e->params().jumpAll(); }
        }
    }

    // A freeze of one bar, with a second of ring-out after it, which is what
    // the renderer now produces. The content does not matter to the cost: a
    // buffer read is a buffer read.
    FrozenSet set;
    auto fc = std::make_shared<FrozenClip>();
    if (mode == 1) {
        fc->frames = kSr * 2;
        fc->tail = kSr;
        fc->ticks = kPPQN * 4;
        fc->bpm = 120.0f;
        fc->left.assign(static_cast<size_t>(fc->frames + fc->tail), 0.25f);
        fc->right = fc->left;
        set.entries.push_back({1, fc});
        rack->swapFrozen(&set);
        rack->updateFrozen(1, 120.0f, true);
        if (!rack->frozenActive()) { r.name += " (NOT FROZEN)"; }
    }

    Player player;
    for (int32_t b = 0; b < kBlocks; ++b) {
        if (mode == 0) player.tick(rack->currentMachine(), b);
        const double t0 = nowUs();
        // Exactly what Engine::renderBlock does per rack, in the same order.
        if (rack->frozenActive()) rack->syncFrozen(b * kBlock / 100, 120.0f);
        else rack->onBlock(b * 10, (b + 1) * 10, 120.0f);
        rack->render(kBlock);
        const double us = nowUs() - t0;
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    rack->swapFrozen(nullptr);
    unmountCloud(rack->currentMachine(), machine);
    delete rack->swapMachine(nullptr);
    for (int32_t sl = 0; sl < kEffectSlots; ++sl) delete rack->swapEffect(sl, nullptr);
    return r;
}

/**
 * What a machine costs with nothing to play.
 *
 * Not the tail above, which is the two seconds after a note and is mostly a
 * release. This is a track that is simply not in this scene - and on Dan's
 * phone that is most of them most of the time: nine tracks, and the busiest
 * scene uses six. If a silent machine is not free then a song pays for every
 * track in every scene whether it sounds or not, which is a very different
 * problem from any single machine being dear.
 */
Result timeIdle(const std::string &name) {
    Machine *m = MachineRegistry::create(name.c_str());
    Result r;
    r.name = name;
    if (m == nullptr) return r;
    m->prepare(kSr);
    m->reset();
    m->params().jumpAll();
    mountCloudIfNeeded(m, name);

    std::vector<float> L(kBlock), R(kBlock);
    // No Player: not one note, ever.
    for (int32_t b = 0; b < kBlocks; ++b) {
        std::fill(L.begin(), L.end(), 0.0f);
        std::fill(R.begin(), R.end(), 0.0f);
        const double t0 = nowUs();
        m->render(L.data(), R.data(), kBlock);
        const double us = nowUs() - t0;
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    unmountCloud(m, name);
    delete m;
    return r;
}

/**
 * What it would cost for a frozen clip to follow a tempo ramp by stretching.
 *
 * Dan asked for this after finding that a scene with a smooth tempo change
 * hands back every freeze in it for a bar - the freeze is tempo-bound, the
 * ramp is between two tempos, so `Rack::updateFrozen` can match neither and
 * falls back to the machine. Time-stretching the audio instead is the obvious
 * answer and the tree already has the stretcher, in `dsp::Wsola` for Bias.
 *
 * The question is what it costs in the *audio* path rather than over a take,
 * and the shape of the answer is the point: WSOLA lays one hop per 720 output
 * frames, which is one block in eleven, and that hop searches 181 lags over a
 * 180-tap decimated overlap. So the mean is not the number - the block the hop
 * lands in is, and it is paid per channel per frozen rack.
 */
Result timeStretchStereo() {
    Result r;
    r.name = "StereoStretch, as the rack uses it";
    std::vector<float> l(static_cast<size_t>(kSr) * 4), rr(static_cast<size_t>(kSr) * 4);
    uint32_t seed = 4242;
    for (size_t i = 0; i < l.size(); ++i) {
        const double t = static_cast<double>(i) / kSr;
        seed = seed * 1664525u + 1013904223u;
        const double noise = static_cast<double>(static_cast<int32_t>(seed >> 9) % 2000 - 1000) / 1000.0;
        const float v = static_cast<float>(0.4 * std::sin(6.283185 * 110.0 * t) +
                                           0.3 * std::sin(6.283185 * 165.0 * t) + 0.1 * noise);
        l[i] = v;
        rr[i] = v * 0.9f;
    }
    dsp::StereoStretch st;
    st.prepare();
    st.seek(0);
    std::vector<float> outL(kBlock), outR(kBlock);
    float *dst[2] = {outL.data(), outR.data()};
    const float *src[2] = {l.data(), rr.data()};
    for (int32_t b = 0; b < kBlocks; ++b) {
        const float ramp = 124.0f + 8.0f * (static_cast<float>(b % 200) / 200.0f);
        const double t0 = nowUs();
        const int32_t made = st.fill(dst, src, 0, static_cast<int64_t>(l.size()), kBlock, ramp / 124.0f);
        const double us = nowUs() - t0;
        if (made < kBlock) { st.seek(0); continue; }
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    return r;
}

Result timeStretch() {
    Result r;
    r.name = "Wsola, one channel";
    // A few seconds of something with structure to lock onto: a tone, a fifth
    // above it, and noise, because a correlation search on silence measures
    // the loop and not the work.
    std::vector<int16_t> src(static_cast<size_t>(kSr) * 4);
    uint32_t seed = 22222;
    for (size_t i = 0; i < src.size(); ++i) {
        const double t = static_cast<double>(i) / kSr;
        seed = seed * 1664525u + 1013904223u;
        const double noise = static_cast<double>(static_cast<int32_t>(seed >> 9) % 2000 - 1000) / 1000.0;
        const double v = 0.4 * std::sin(6.283185 * 110.0 * t) + 0.3 * std::sin(6.283185 * 165.0 * t) +
                         0.1 * noise;
        src[i] = static_cast<int16_t>(v * 12000.0);
    }

    dsp::Wsola w;
    w.prepare();
    w.seek(0);
    std::vector<float> dst(kBlock);
    // 124 to 132, which is what the demo's Lift asks for.
    for (int32_t b = 0; b < kBlocks; ++b) {
        const float ramp = 124.0f + 8.0f * (static_cast<float>(b % 200) / 200.0f);
        const float rate = ramp / 124.0f;
        const double t0 = nowUs();
        const int32_t made = w.fill(dst.data(), kBlock, src.data(), 0,
                                    static_cast<int64_t>(src.size()), rate);
        const double us = nowUs() - t0;
        if (made < kBlock) { w.seek(0); continue; }
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    return r;
}

/**
 * Parameters to force before timing, as `name=value` in the machine's own
 * units.
 *
 * **A lever that depends on a patch setting is invisible at defaults**, and
 * this harness times machines at their defaults. Resonance caps `modes` in
 * lean, and `modes` defaults to 12 against a cap of 12 - nothing. Trinity
 * halves the unison stack, and `density` defaults to 1 - nothing. Both levers
 * measured 0% and both were working; the sweep was asking the machine to play
 * a patch nobody would.
 *
 * So a measurement can say what it is measuring: `paired Trinity o1_density=1`
 * times the thing the lever is for. The value is **normalised**, 0 to 1, as
 * every parameter is on the way in - 1 is whatever that knob's maximum means.
 */
std::vector<std::pair<std::string, float>> forced;

/**
 * A factory patch to time instead of the defaults.
 *
 * **Defaults are a patch nobody plays**, and timing them has been wrong twice
 * over: Resonance's `modes` defaults to the number lean caps it at, and
 * Trinity's `density` to one, so both levers measured nought while both were
 * working. The same blindness runs the other way - the demo's `Brass` is four
 * players and its `Keys` is a wavetable through a ring modulator, and neither
 * is what this harness was timing when it said what those machines cost.
 *
 * So `--patch "Bell Keys"` loads that patch out of `tools/banks/`, which is
 * the same file the audition harness and the app's factory bank come from.
 */
std::string patchName;

/** `tools/banks`, from the root the shell script hands over. */
std::string bankDir() {
    const char *root = getenv("ACIDULOUS_ROOT");
    return std::string(root != nullptr ? root : ".") + "/tools/banks";
}

void force(Machine *m, const std::string &machine) {
    if (m == nullptr) return;
    if (!patchName.empty()) {
        acidulous::audition::Bank bank;
        std::string error;
        const std::string path = bankDir() + "/" + machine + ".bank";
        if (!acidulous::audition::readBank(path, bank, error)) {
            printf("  %s\n", error.c_str());
        } else {
            bool found = false;
            for (const auto &p : bank.patches) {
                if (p.name != patchName) continue;
                int32_t count = 0;
                const ParamDef *defs = MachineRegistry::paramDefs(machine.c_str(), count);
                const auto r = acidulous::audition::resolve(p, defs, count);
                for (size_t i = 0; i < r.norm.size(); ++i) m->params().set(static_cast<int32_t>(i), r.norm[i]);
                found = true;
                break;
            }
            if (!found) printf("  no patch called '%s' in %s\n", patchName.c_str(), path.c_str());
        }
    }
    for (const auto &kv : forced) {
        const int32_t i = m->params().indexOf(kv.first.c_str());
        if (i >= 0) m->params().set(i, kv.second);
    }
    m->params().jumpAll();
    // After the patch: the spectrum the cloud is built from is parameters.
    mountCloudIfNeeded(m, machine);
}

Result timeMachine(const std::string &name) {
    Machine *m = MachineRegistry::create(name.c_str());
    Result r;
    r.name = name;
    if (m == nullptr) return r;
    m->prepare(kSr);
    m->reset();
    m->params().jumpAll();
    force(m, name);

    std::vector<float> L(kBlock), R(kBlock);
    Player player;
    for (int32_t b = 0; b < kBlocks; ++b) {
        player.tick(m, b);
        std::fill(L.begin(), L.end(), 0.0f);
        std::fill(R.begin(), R.end(), 0.0f);
        const double t0 = nowUs();
        m->render(L.data(), R.data(), kBlock);
        const double us = nowUs() - t0;
        // The first few blocks are cold cache and first-touch, which is a real
        // cost but not the one this is looking for.
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    unmountCloud(m, name);
    delete m;
    return r;
}

Result timeEffect(const std::string &name) {
    Effect *fx = EffectRegistry::create(name.c_str());
    Result r;
    r.name = "fx." + name;
    if (fx == nullptr) return r;
    fx->prepare(kSr);
    fx->reset();
    fx->params().jumpAll();

    std::vector<float> L(kBlock), R(kBlock);
    double phase = 0.0;
    for (int32_t b = 0; b < kBlocks; ++b) {
        // Something to chew on, and something that decays: a feedback path
        // full of denormals is one of the things this is here to catch.
        const bool loud = (b % 64) < 8;
        for (int32_t i = 0; i < kBlock; ++i) {
            phase += 220.0 / kSr;
            if (phase >= 1.0) phase -= 1.0;
            const float v = loud ? 0.3f * static_cast<float>(std::sin(phase * 2.0 * M_PI)) : 0.0f;
            L[i] = v;
            R[i] = v * 0.97f;
        }
        const double t0 = nowUs();
        fx->run(L.data(), R.data(), kBlock, true);
        const double us = nowUs() - t0;
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    delete fx;
    return r;
}

/**
 * What a unit costs **after** the note has stopped.
 *
 * The one measurement that finds denormals, and the general run above cannot:
 * its material never sits still long enough for a tail to walk down into the
 * subnormal range. Here each unit gets one burst and is then left alone for
 * two seconds, and it is those silent blocks that are timed - a reverb emptying
 * out, a modal bank ringing down, an envelope approaching zero. On a machine
 * whose FPU takes the slow path for subnormals this is where the cost is, and
 * it arrives as a spike at the end of every note rather than as load.
 */
Result timeTail(const std::string &name, bool isEffect) {
    Result r;
    r.name = name;
    Machine *m = isEffect ? nullptr : MachineRegistry::create(name.c_str());
    Effect *fx = isEffect ? EffectRegistry::create(name.c_str()) : nullptr;
    if (m == nullptr && fx == nullptr) return r;
    if (m != nullptr) { m->prepare(kSr); m->reset(); m->params().jumpAll(); mountCloudIfNeeded(m, name); }
    if (fx != nullptr) { fx->prepare(kSr); fx->reset(); fx->params().jumpAll(); }

    std::vector<float> L(kBlock), R(kBlock);
    double phase = 0.0;
    // Half a second of sound, so anything with a long attack has spoken.
    const int32_t excite = kSr / 2 / kBlock;
    if (m != nullptr) m->noteOn(38, 110);
    for (int32_t b = 0; b < excite; ++b) {
        for (int32_t i = 0; i < kBlock; ++i) {
            phase += 220.0 / kSr;
            if (phase >= 1.0) phase -= 1.0;
            const float v = 0.4f * static_cast<float>(std::sin(phase * 2.0 * M_PI));
            L[i] = fx != nullptr ? v : 0.0f;
            R[i] = L[i];
        }
        if (m != nullptr) m->render(L.data(), R.data(), kBlock);
        else fx->run(L.data(), R.data(), kBlock, true);
    }
    if (m != nullptr) m->noteOff(38);

    // And then nothing at all, for two seconds.
    const int32_t tail = 2 * kSr / kBlock;
    for (int32_t b = 0; b < tail; ++b) {
        std::fill(L.begin(), L.end(), 0.0f);
        std::fill(R.begin(), R.end(), 0.0f);
        const double t0 = nowUs();
        if (m != nullptr) m->render(L.data(), R.data(), kBlock);
        else fx->run(L.data(), R.data(), kBlock, true);
        const double us = nowUs() - t0;
        if (b > 4) r.samples.push_back(us);
    }
    r.finish();
    unmountCloud(m, name);
    delete m;
    delete fx;
    return r;
}

void report(std::vector<Result> &rows) {
    std::sort(rows.begin(), rows.end(), [](const Result &a, const Result &b) { return a.mean > b.mean; });
    printf("  %-14s %9s %9s %8s %8s\n", "unit", "mean us", "p99 us", "mean%", "spiky");
    printf("  %-14s %9s %9s %8s %8s\n", "----", "-------", "------", "-----", "-----");
    for (const Result &r : rows) {
        if (r.mean <= 0.0) continue;
        printf("  %-14s %9.1f %9.1f %7.1f%% %7.1fx%s\n", r.name.c_str(), r.mean, r.p99,
               r.mean / kBudgetUs * 100.0, r.spikiness(), r.spikiness() > 6.0 ? "  <-- spiky" : "");
    }
    printf("\n  The samplers play the audition harness's synthetic material - a zone\n"
           "  map, a kit, a break, a phrase - and Cumulus its computed tables. Bias\n"
           "  alone has nothing mounted: an audio track plays what was recorded into\n"
           "  it, so its figure is a floor, not a cost.\n");
}

} // namespace

/**
 * Full against lean, for one unit, measured so the difference is legible.
 *
 * **Two runs of this harness cannot answer this question.** Three of each on
 * this machine put the run-to-run spread at ±30% to ±50% for most units, which
 * swamps everything the comparison is for: it reported Filament 19% *slower*
 * in lean and a dozen units "reached" that lean does not touch at all. Both
 * were noise, and the second was repeated to Dan as fact before being checked.
 *
 * Three things fix it, and they are all about comparing like with like:
 *
 *  - **One process**, so both modes meet the same cache, the same page layout
 *    and the same governor.
 *  - **Alternating**, so a machine that gets busy half way through spoils both
 *    equally instead of whichever ran second.
 *  - **The minimum, not the mean.** The fastest pass is the one that was
 *    interrupted least, and is the closest this can get to what the work
 *    costs; a mean averages in whatever else the machine was doing.
 *
 * And the spread of the full-mode minima is printed beside the result as the
 * **floor**: a saving smaller than that is not a saving, and the row says so
 * rather than leaving it to be read into.
 */
struct Paired {
    std::string name;
    double full = 0.0, lean = 0.0, floorPct = 0.0;
    double savedPct() const { return full > 0.0 ? 100.0 * (full - lean) / full : 0.0; }
    bool real() const { return std::fabs(savedPct()) > floorPct && std::fabs(savedPct()) >= 5.0; }
};

Paired timePaired(const std::string &name, bool isEffect, int rounds) {
    Paired p;
    p.name = isEffect ? "fx." + name : name;
    std::vector<double> fulls, leans;
    for (int i = 0; i < rounds; ++i) {
        for (int mode = 0; mode < 2; ++mode) {
            // Full first on even rounds, lean first on odd, so neither mode
            // always pays for whatever a fresh unit does on its first blocks.
            const bool full = (i % 2 == 0) ? (mode == 0) : (mode == 1);
            EngineSettings::get().quality.store(full ? 1 : 0, std::memory_order_relaxed);
            Result r = isEffect ? timeEffect(name) : timeMachine(name);
            if (r.samples.empty()) return p;
            // **The round's own mean, and the minimum is taken across rounds.**
            //
            // Not the cheapest *block* in the round, which was the first
            // attempt and measured the wrong thing entirely: the cheapest
            // block of a machine is one where nothing is sounding, so Trinity
            // came out at 6.9 us against the 87 it costs with notes in it. The
            // two minimums are at different levels - within a round it picks
            // silence, across rounds it picks the pass the machine interfered
            // with least - and only the second one is wanted.
            (full ? fulls : leans).push_back(r.mean);
        }
    }
    EngineSettings::get().quality.store(1, std::memory_order_relaxed);
    if (fulls.size() < 4 || leans.empty()) return p;
    std::sort(fulls.begin(), fulls.end());
    std::sort(leans.begin(), leans.end());
    const auto at = [](const std::vector<double> &v, double q) {
        return v[static_cast<size_t>(q * static_cast<double>(v.size() - 1))];
    };

    // **The median, and a floor that does not grow when you measure harder.**
    //
    // Three estimators were tried and the first two were wrong in instructive
    // ways. The *minimum* of the rounds looks right - the least interfered-with
    // pass - but pairing it with a max-minus-min floor is self-defeating: a
    // range grows with the sample, so asking for forty rounds instead of ten
    // took Trinity's floor from 9% to 64% and made measuring harder look like
    // knowing less. Splitting the full rounds in half and comparing those was
    // the other, and it is far too kind at small counts - the minimum of four
    // agrees with the minimum of four much more closely than either agrees
    // with the truth, and it passed Molt at 18% when Molt has no lean branch
    // to save anything with.
    //
    // A median is stable and the middle half is a spread that settles rather
    // than climbs. So the answer is the median of the rounds, and the floor is
    // how wide the middle half of the *full* rounds is: how much this
    // measurement moves when nothing has changed at all.
    p.full = at(fulls, 0.5);
    p.lean = at(leans, 0.5);
    p.floorPct = p.full > 0.0 ? 100.0 * (at(fulls, 0.75) - at(fulls, 0.25)) / p.full : 0.0;
    return p;
}

void reportPaired(std::vector<Paired> &rows) {
    std::sort(rows.begin(), rows.end(), [](const Paired &a, const Paired &b) {
        return (a.full - a.lean) > (b.full - b.lean);
    });
    printf("  %-16s %8s %8s %8s %7s\n", "unit", "full us", "lean us", "saved", "floor");
    printf("  %-16s %8s %8s %8s %7s\n", "----", "-------", "-------", "-----", "-----");
    for (const Paired &r : rows) {
        if (r.full <= 0.0) continue;
        printf("  %-16s %8.1f %8.1f %7.0f%% %6.0f%%  %s\n", r.name.c_str(), r.full, r.lean,
               r.savedPct(), r.floorPct, r.real() ? "" : "(inside the floor)");
    }
    printf("\n  A saving inside the floor is not a saving: the floor is how much the\n");
    printf("  full-mode figure moved between rounds on this machine, and nothing\n");
    printf("  smaller than that can be told apart from it.\n");
}

int main(int argc, char **argv) {
    // `--rate N` anywhere, in every mode: the note density, in note-ons a
    // second. It is stripped out here so each mode's own argument handling
    // sees the arguments it expects.
    std::vector<std::string> args;
    for (int i = 0; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--rate" && i + 1 < argc) {
            gNotesPerSecond = std::max(0.1, atof(argv[++i]));
            continue;
        }
        args.push_back(a);
    }
    argc = static_cast<int>(args.size());
    std::vector<char *> argp;
    for (auto &a : args) argp.push_back(a.data());
    argv = argp.data();

    const std::string only = argc > 1 ? argv[1] : "";
    // Off with ACIDULOUS_NO_FTZ=1, so the cost of denormals can be measured
    // rather than argued about.
    // Lean quality with ACIDULOUS_LEAN=1, so what the setting buys is a
    // measurement rather than a claim.
    if (getenv("ACIDULOUS_LEAN") != nullptr) {
        EngineSettings::get().quality.store(0, std::memory_order_relaxed);
        printf("quality: lean\n");
    }
    const bool ftz = getenv("ACIDULOUS_NO_FTZ") == nullptr;
    if (ftz) dsp::flushDenormals();
    printf("flush-to-zero: %s\n", ftz ? "on" : "off");
    printf("cost per %d-frame block, budget %.0f us\n", kBlock, kBudgetUs);
    // Said out loud, because it decides what the sustained patches cost and
    // nothing else here reveals it.
    printf("notes: %.1f a second, one every %.0f ms%s\n\n", gNotesPerSecond,
           1000.0 / gNotesPerSecond,
           gNotesPerSecond > 15.0 ? " - a stress rate, about three times sixteenths at 124 bpm"
                                  : "");

    std::vector<Result> rows;
    if (only == "paired") {
        // A second argument names one unit and buys it more rounds. The floor
        // falls as the rounds rise - it is the spread of a sample - so a unit
        // being changed is worth measuring harder than the sweep can afford
        // to measure all thirty-six.
        const std::string one = argc > 2 ? argv[2] : "";
        // Anything after the unit is `name=normalised`, applied before timing.
        for (int i = 3; i < argc; ++i) {
            const std::string kv = argv[i];
            if (kv == "--patch" && i + 1 < argc) {
                patchName = argv[++i];
                printf("patch: %s\n", patchName.c_str());
                continue;
            }
            const size_t eq = kv.find('=');
            if (eq == std::string::npos) continue;
            forced.emplace_back(kv.substr(0, eq), std::stof(kv.substr(eq + 1)));
            printf("forcing %s to %s\n", kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str());
        }
        const int rounds = one.empty() ? 10 : 40;
        printf("full against lean, alternating in one process, best of each\n");
        printf("%d rounds%s\n\n", rounds, one.empty() ? "" : (", " + one + " alone").c_str());
        std::vector<Paired> rows;
        for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
            const std::string n = MachineRegistry::name(i);
            if (!one.empty() && one != n) continue;
            rows.push_back(timePaired(n, false, rounds));
        }
        for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
            const std::string n = EffectRegistry::name(i);
            if (!one.empty() && one != n && one != "fx." + n) continue;
            rows.push_back(timePaired(n, true, rounds));
        }
        reportPaired(rows);
        return 0;
    }
    if (only == "rack") {
        printf("a whole rack, live against frozen - what freezing gives back\n\n");
        // The demo's two dearest tracks, with the inserts they actually carry.
        rows.push_back(timeRack("Trinity", "Delay", "", 0));
        rows.push_back(timeRack("Trinity", "Delay", "", 1));
        rows.push_back(timeRack("Filament", "Chorus", "", 0));
        rows.push_back(timeRack("Filament", "Chorus", "", 1));
        rows.push_back(timeRack("Resonance", "Reverb", "", 0));
        rows.push_back(timeRack("Resonance", "Reverb", "", 1));
        rows.push_back(timeRack("", "", "", 2));
        report(rows);
        return 0;
    }
    if (only == "stretch") {
        printf("time-stretching a frozen clip to follow a tempo ramp\n\n");
        rows.push_back(timeStretch());
        rows.push_back(timeStretchStereo());
        rows.push_back(timeRack("Trinity", "Delay", "", 0));
        rows.push_back(timeRack("Trinity", "Delay", "", 1));
        report(rows);
        return 0;
    }
    if (only == "idle") {
        printf("cost of a machine with NOTHING to play\n\n");
        for (int32_t i = 0; i < MachineRegistry::count(); ++i) rows.push_back(timeIdle(MachineRegistry::name(i)));
        report(rows);
        return 0;
    }
    if (only == "tail") {
        printf("cost of the two seconds AFTER a note stops\n\n");
        for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
            rows.push_back(timeTail(MachineRegistry::name(i), false));
        }
        for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
            Result r = timeTail(EffectRegistry::name(i), true);
            r.name = "fx." + r.name;
            rows.push_back(r);
        }
        report(rows);
        return 0;
    }
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const std::string name = MachineRegistry::name(i);
        if (!only.empty() && only != name) continue;
        rows.push_back(timeMachine(name));
    }
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
        const std::string name = EffectRegistry::name(i);
        if (!only.empty() && only != name && only != "fx." + name) continue;
        rows.push_back(timeEffect(name));
    }
    report(rows);
    printf("\n%zu units timed\n", rows.size());
    return 0;
}
