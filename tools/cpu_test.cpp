// What the machines and effects cost per block, worst case. The in-app meter
// shows the worst callback on a device. This measures the same offline so
// numbers can be compared between changes.
//
// Figures are for the slow blocks, not the mean, since one slow block in
// eleven is what causes a dropout. The mean is shown beside it. A unit whose
// worst is far above its mean is spiky, and spiky units drop audio even at
// low average load.
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
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>
#include <engine/machine/diction/RecordedVoice.h>
#include <fstream>
#include <sstream>
#include "audition_material.h"
#include "patchbank.h"
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/dsp/Wsola.h>
#include <engine/rack/Rack.h>
#include <chrono>
#include <memory>

using namespace acidulous;

namespace {

constexpr int32_t kSr = kSampleRate;
constexpr int32_t kBlock = kBlockFrames;
/** Long enough that a periodic spike (a grain, a WSOLA hop) is certain to land. */
constexpr int32_t kBlocks = 2000;

/**
 * This thread's CPU time, in microseconds. Not wall clock time, since the
 * process can be descheduled and the slowest block would then just be the
 * one the OS interrupted.
 */
double nowUs() {
    timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<double>(ts.tv_sec) * 1e6 + static_cast<double>(ts.tv_nsec) / 1e3;
}

/** One block's worth of budget, which is what every figure is measured against. */
constexpr double kBudgetUs = 1000000.0 * kBlock / kSr;

/**
 * Every block's cost, reduced to the 99th percentile and the mean. Not the
 * maximum, which is mostly scheduler noise even in thread CPU time. A spike
 * that fires one block in eleven still shows in the 99th percentile.
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
 * Note-ons per second. The default is about 23, roughly three times faster
 * than sixteenths at 124 bpm, so patches with long releases hold about three
 * times the voices they would in a song.
 *
 * That's good for comparing units and changes, but overstates what a song
 * costs. It's printed on every run and `--rate` changes it. Percussive
 * patches measure the same either way. A big difference between rates means
 * a long release.
 */
double gNotesPerSecond = static_cast<double>(kSr) / (16.0 * 2.0 * kBlock); // ~23.4

/**
 * A note pattern that keeps notes coming. Most units are cheap until a note
 * starts, and the note-on work is part of what's being measured.
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
        // Notes start at 36, where the drum machines' pads start (Hexbeat,
        // Genesis, Forage, Resonance), so they actually get played.
        const uint8_t pitch = static_cast<uint8_t>(36 + (step % 8));
        if (step % 2 == 0) m->noteOn(pitch, 100);
        else m->noteOff(static_cast<uint8_t>(36 + ((step - 1) % 8)));
        ++step;
        next = block + stride();
    }
};

/**
 * Material for machines that are silent without it, so their real cost is
 * measured.
 *
 * Cumulus gets its wavetables through `swapObject`, built the same way
 * `EngineHost::buildCloud` does. The samplers (Mosaic, Forage, Dice, Pollen,
 * Molt) get the audition harness's synthetic material: a zone map, a drum
 * kit, a break and a spoken phrase. That's built once per machine and kept
 * for the whole process, so a sweep doesn't rebuild it every round.
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

/** Undoes mountCloudIfNeeded. A sweep mounts and unmounts every round. */
void unmountCloud(Machine *m, const std::string &machine) {
    if (m == nullptr) return;
    if (holdsAudio(machine)) {
        // The material is kept. The machine just lets go of it.
        const int32_t slots = machine == "Forage" ? static_cast<int32_t>(mounted(machine).pieces.size()) : 1;
        for (int32_t i = 0; i < slots; ++i) m->swapObject(i, nullptr);
        return;
    }
    if (machine != "Cumulus") return;
    auto *cum = static_cast<machine::Cumulus *>(m);
    delete static_cast<machine::cumulus::CloudSet *>(cum->swapObject(0, nullptr));
}

/**
 * A whole rack, live against frozen, to see how much freezing saves. Times
 * `Rack::render` with a machine and two inserts in each of three states:
 *
 *   live    the machine and both effects running, notes arriving
 *   frozen  the same rack playing back its frozen audio instead
 *   bare    a rack with nothing mounted, the lowest possible cost
 *
 * The channel strip, pan and peak meter run in all three, so live minus
 * frozen is the saving.
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

    // A one-bar freeze with a second of tail, like the renderer produces.
    // The content doesn't affect the cost.
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
 * What a machine costs with nothing to play, like a track that isn't used in
 * the current scene. Most tracks are idle most of the time, so if idle isn't
 * nearly free a song pays for every track all the time.
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
 * The cost of a frozen clip following a tempo ramp by time-stretching. A
 * freeze is tied to one tempo, so during a ramp `Rack::updateFrozen` falls
 * back to the live machine. Stretching with `dsp::Wsola` would avoid that.
 *
 * WSOLA does one hop per 720 output frames (one block in eleven), and each
 * hop searches 181 lags over a 180-tap decimated overlap. So the block with
 * the hop is what matters, not the mean, and it's paid per channel per frozen
 * rack.
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
    // A tone, a fifth above it and some noise, so the correlation search has
    // something real to lock onto.
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
    // 124 to 132 bpm, like the demo's Lift scene.
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
 * Parameters to force before timing, as `name=value`. The value is
 * normalised 0 to 1, where 1 is the knob's maximum.
 *
 * Some lean-mode savings only show at certain settings (e.g. Trinity's
 * `density`), so `paired Trinity o1_density=1` times what the saving is for.
 */
std::vector<std::pair<std::string, float>> forced;

/**
 * A factory patch to time instead of the defaults, since the defaults aren't
 * what anyone plays. `--patch "Bell Keys"` loads it from `tools/banks/`, the
 * same files the app's factory bank is built from.
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
    // After the patch, since the cloud is built from its parameters.
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
        // Skip the first few blocks, which pay for cold caches.
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    unmountCloud(m, name);
    delete m;
    return r;
}

/**
 * Diction singing words, in its own voice or a recorded one from [spec] (the
 * lines the app sends the engine; empty for the built-in voice), [rate] notes
 * a second. Every note gets words, with clusters and diphthongs, since a
 * recorded voice's consonants cost more than its vowels.
 */
/**
 * Diction singing [rate] words a second, legato, in its own voice or the
 * recorded one [spec], with [knobs] set by name, and with harmony a chord of
 * [chord] notes held under each.
 */
Result timeDiction(const std::string &label, const std::string &spec, double rate,
                   std::initializer_list<std::pair<const char *, float>> knobs = {}, int32_t chord = 1) {
    using machine::diction::RecordedVoice;
    static const char *kWords[] = {"T W IHC NG", "K AX L", "S T R IY M", "L IHC", "R OWP", "B OWP T",
                                   "HH AW S", "Y UW", "D AWP N", "AY", "W AH N", "DH AX"};
    Result r;
    r.name = label;
    std::unique_ptr<RecordedVoice> voice;
    if (!spec.empty()) {
        // What loading the voice costs, once: every take read and its pulses
        // found, which the app does on a worker when the voice is chosen.
        const auto wall0 = std::chrono::steady_clock::now();
        std::ifstream in(spec);
        std::stringstream all;
        all << in.rdbuf();
        std::string error;
        voice = RecordedVoice::fromSpec(all.str(), static_cast<float>(kSr), 4, error);
        if (voice->vowels.empty()) { printf("  %s: no voice in %s\n", label.c_str(), spec.c_str()); return r; }
        static bool told = false;
        if (!told) {
            told = true;
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - wall0).count();
            printf("loading the voice: %.0f ms here for %zu vowels, %zu diphthongs, %zu consonants\n\n", ms,
                   voice->vowels.size(), voice->diphthongs.size(), voice->joins.size());
        }
    }
    std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
    m->prepare(kSr);
    for (const auto &k : knobs) {
        const int32_t i = m->params().indexOf(k.first);
        if (i >= 0) m->params().set(i, m->params().def(i).unmap(k.second));
    }
    m->reset();
    m->params().jumpAll();
    if (voice) m->swapObject(0, voice.get());
    const auto stride = std::max(1, static_cast<int32_t>(static_cast<double>(kSr) / (rate * kBlock) + 0.5));
    std::vector<float> L(kBlock), R(kBlock);
    int32_t word = 0;
    uint8_t pitch = 0;
    for (int32_t b = 0; b < kBlocks; ++b) {
        if (b % stride == 0) {
            // Legato, a note to the next, as a singer's notes are.
            uint8_t phones[machine::Diction::kMaxPhones];
            const int32_t n = machine::diction::parsePhones(kWords[word % 12], phones, machine::Diction::kMaxPhones);
            const auto next = static_cast<uint8_t>(45 + (word * 5) % 12);
            m->lyric(phones, n);
            m->noteOn(next, 100);
            if (pitch != 0) {
                m->noteOff(pitch);
                for (int32_t c = 1; c < chord; ++c) m->noteOff(static_cast<uint8_t>(pitch - 4 * c));
            }
            for (int32_t c = 1; c < chord; ++c) m->noteOn(static_cast<uint8_t>(next - 4 * c), 100);
            pitch = next;
            ++word;
        }
        std::fill(L.begin(), L.end(), 0.0f);
        std::fill(R.begin(), R.end(), 0.0f);
        const double t0 = nowUs();
        m->render(L.data(), R.data(), kBlock);
        const double us = nowUs() - t0;
        if (b > 8) r.samples.push_back(us);
    }
    r.finish();
    if (voice) m->swapObject(0, nullptr);
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
        // Bursts of tone with silence between, so feedback paths decay and
        // any denormal slowdown shows.
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
 * What a unit costs after the note has stopped, which is where denormals
 * show. Each unit gets one burst of sound and then two silent seconds, and
 * the silent blocks are timed (reverbs emptying, modes ringing down,
 * envelopes approaching zero).
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

    // Then two seconds of silence.
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
 * Full against lean mode for one unit. Two separate runs vary by 30 to 50%,
 * which hides the difference, so:
 *
 *  - both modes run in one process, with the same cache and CPU governor;
 *  - they alternate, so background load affects both equally;
 *  - the minimum is used, not the mean, since the fastest pass was
 *    interrupted least.
 *
 * The spread of the full-mode minima is printed as the noise floor. A saving
 * smaller than that isn't real, and the row says so.
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
            // always pays the first-run cost.
            const bool full = (i % 2 == 0) ? (mode == 0) : (mode == 1);
            EngineSettings::get().quality.store(full ? 1 : 0, std::memory_order_relaxed);
            Result r = isEffect ? timeEffect(name) : timeMachine(name);
            if (r.samples.empty()) return p;
            // Use each round's mean. The cheapest single block would just be
            // one where nothing was sounding.
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

    // The result is the median of the rounds, and the floor is the width of
    // the middle half of the full-mode rounds: how much the measurement moves
    // when nothing has changed. A max-minus-min range would grow with more
    // rounds, and the minimum of a few rounds is too optimistic.
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
    // `--rate N` works anywhere in every mode and sets note-ons per second.
    // It's removed here so each mode sees only its own arguments.
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
    // ACIDULOUS_NO_FTZ=1 turns off flush-to-zero, to measure what denormals
    // cost. ACIDULOUS_LEAN=1 uses lean quality.
    if (getenv("ACIDULOUS_LEAN") != nullptr) {
        EngineSettings::get().quality.store(0, std::memory_order_relaxed);
        printf("quality: lean\n");
    }
    const bool ftz = getenv("ACIDULOUS_NO_FTZ") == nullptr;
    if (ftz) dsp::flushDenormals();
    printf("flush-to-zero: %s\n", ftz ? "on" : "off");
    printf("cost per %d-frame block, budget %.0f us\n", kBlock, kBudgetUs);
    // Printed because it changes what sustained patches cost.
    printf("notes: %.1f a second, one every %.0f ms%s\n\n", gNotesPerSecond,
           1000.0 / gNotesPerSecond,
           gNotesPerSecond > 15.0 ? " - a stress rate, about three times sixteenths at 124 bpm"
                                  : "");

    std::vector<Result> rows;
    if (only == "paired") {
        // A second argument picks one unit and gives it more rounds, which
        // lowers the noise floor.
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
        // The demo's most expensive tracks, with their actual inserts.
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
    if (only == "diction") {
        // cpu_test diction [voice spec]: its own voice, and a recorded one.
        const std::string spec = argc > 2 ? argv[2] : "";
        printf("Diction singing a word every note, legato\n\n");
        rows.push_back(timeMachine("Diction"));
        rows.back().name = "held vowel";
        for (double rate : {4.0, gNotesPerSecond}) {
            char label[64];
            std::snprintf(label, sizeof(label), "built-in %.0f/s", rate);
            rows.push_back(timeDiction(label, "", rate));
            if (!spec.empty()) {
                std::snprintf(label, sizeof(label), "recorded %.0f/s", rate);
                rows.push_back(timeDiction(label, spec, rate));
            }
        }
        // More singers: a choir, a chord, and a chord of choirs (12 clocks).
        rows.push_back(timeDiction("built-in 6 singers", "", 4.0, {{"singers", 6.0f}}));
        rows.push_back(timeDiction("built-in chord of 3", "", 4.0, {{"harmony", 1.0f}}, 3));
        if (!spec.empty()) {
            rows.push_back(timeDiction("recorded 6 singers", spec, 4.0, {{"singers", 6.0f}}));
            rows.push_back(timeDiction("recorded chord of 3", spec, 4.0, {{"harmony", 1.0f}}, 3));
            rows.push_back(timeDiction("recorded 3 x 3 singers", spec, 4.0, {{"harmony", 1.0f}, {"singers", 3.0f}}, 3));
            rows.push_back(timeDiction("recorded 4 x 3 singers", spec, 4.0, {{"harmony", 1.0f}, {"singers", 3.0f}}, 4));
        }
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
