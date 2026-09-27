// Checks every mod source in every matrix actually changes the sound.
//
// Routes each source of every machine with a matrix to a destination and
// checks the audio changes, whether or not a factory patch uses it. A source
// that's never triggered (like envelopes nobody calls `trigger` on) otherwise
// goes unnoticed if no patch routes it.
//
// It can't catch a source that's wired but wrong, or one that only moves when
// something outside the machine does. For the second, the machine is played
// below with a mod wheel, pressure, a bend and two notes before anything is
// judged.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <engine/core/InputBus.h>
#include <engine/machine/MachineRegistry.h>
#include "audition_material.h"

using namespace acidulous;

namespace {
int checks = 0, failures = 0;
constexpr int32_t kSr = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 240; // five seconds, so a slow envelope arrives

/**
 * Audio for machines that listen to their input.
 *
 * Cipher's loudness, brightness and pitch sources all measure the incoming
 * modulator, so on a silent bus all three read zero and look unwired. Three
 * formants over a moving fundamental, loud then quiet, gives every
 * measurement something to measure. Only whether the numbers move matters
 * here, not how realistic the signal is.
 */
struct Modulator {
    std::vector<float> buf;
    double phase = 0.0;
    void fill(int32_t block, int32_t frames) {
        buf.assign(static_cast<size_t>(frames) * 2, 0.0f);
        const double f0 = 90.0 + 40.0 * std::sin(block * 0.05);
        const float loud = block % 90 < 45 ? 0.5f : 0.08f; // loudness has to move
        for (int32_t i = 0; i < frames; ++i) {
            phase += f0 / kSr;
            if (phase >= 1.0) phase -= 1.0;
            const auto p = static_cast<float>(phase);
            // A buzz with three formants on top, so the brightness measure
            // has something to measure.
            float x = 2.0f * p - 1.0f;
            x += 0.5f * std::sin(6.2831853f * p * 7.0f);
            x += 0.3f * std::sin(6.2831853f * p * 13.0f);
            x += 0.2f * std::sin(6.2831853f * p * 26.0f);
            buf[static_cast<size_t>(i) * 2] = buf[static_cast<size_t>(i) * 2 + 1] = x * loud * 0.4f;
        }
        InputBus::get().publish(buf.data(), frames);
    }
    static void silence() { InputBus::get().publish(nullptr, 0); }
};

/**
 * What a machine has to be doing before its sources can be judged.
 *
 * A source reading zero looks the same as one that isn't wired, and some read
 * zero until the machine is doing something. For example the organ's rotor
 * ships braked, so `horn` is exactly zero on a default patch.
 *
 * So a machine can name knobs to turn up first, as normalised values. Keep
 * this list short, or the harness stops testing anything.
 */
struct Setup {
    const char *machine;
    const char *name;
    float value;
};
constexpr Setup kSetup[] = {
    {"Manual", "rotspeed", 1.0f}, // off the brake, or `horn` and `drum` stand still
    // A vocoder is silent with nothing on its modulator, however loud the
    // carrier. The dry path is the carrier itself, so turning it up gives
    // this something to listen to.
    {"Cipher", "dry", 1.0f},
    // Its pitch source is the tracker's output, which isn't computed until
    // the tracker is on.
    {"Cipher", "track", 1.0f},
};

/**
 * Where a destination can be heard at all.
 *
 * A destination is only audible in a patch that uses what it moves: pulse
 * width on a square, oscillator three with its level up, a second filter
 * switched in, an LFO's rate while that LFO is routed. A default patch has
 * none of those. So each machine names a few contexts (sets of knob values
 * applied before the slot under test), and a destination passes if any of
 * them lets it be heard. Every destination is still routed and still has to
 * change the sound.
 *
 * Knobs are `name=value`, normalised, or `name=#n` for step n.
 */
struct Context {
    const char *machine;
    const char *knobs;
};
constexpr Context kContexts[] = {
    // Every oscillator heard, on a table, stacked; both filters in, in
    // parallel, with a drive; and all three LFOs routed so their rates matter.
    {"Trinity", "o1_wave=#4 o2_wave=#5 o3_wave=#6 o3_level=0.8 o1_density=#3 o2_density=#3 o3_density=#3 "
                "f2_type=#1 f2_freq=0.5 route=#1 balance=0.5 f1_drivetype=#1 "
                "m02_src=#13 m02_dest=#1 m02_depth=0.7 m03_src=#14 m03_dest=#1 m03_depth=0.7 "
                "m04_src=#15 m04_dest=#1 m04_depth=0.7"},
    // And every oscillator a pulse, which is the only thing pulse width moves.
    {"Trinity", "o1_wave=#1 o2_wave=#1 o3_wave=#1 o3_level=0.8"},
    // All six operators sounding on ratios other than one (skew bends a ratio
    // by a power, and one to any power is one), and the LFOs routed.
    {"Ratio", "o1_level=0.8 o2_level=0.8 o3_level=0.8 o4_level=0.8 o5_level=0.8 o6_level=0.8 "
              "o1_ratio=0.375 o2_ratio=0.448 o3_ratio=0.5 o4_ratio=0.55 o5_ratio=0.6 o6_ratio=0.65 "
              "m02_src=#11 m02_dest=#1 m02_depth=0.7 m03_src=#12 m03_dest=#1 m03_depth=0.7 "
              "m04_src=#13 m04_dest=#1 m04_depth=0.7"},
    // The lower manual switched on, so the low note lands on it.
    {"Manual", "loweron=#1"},
    // Pipes, for the chiff.
    {"Manual", "model=#2"},
    // A bow, for bow pressure; a damper that presses, for where it presses;
    // the sympathetic strings, for how much they answer.
    {"Filament", "exciter=#3 damper=0.6 sympathy=#1"},
    // The cloud, for the grain destinations; both LFOs routed, for their rates.
    {"Mosaic", "grain=#1 m02_src=#11 m02_dest=#1 m02_depth=0.7 m03_src=#12 m03_dest=#1 m03_depth=0.7"},
    // Layers scanned by a knob rather than by velocity, for the scan.
    {"Mosaic", "scanamt=1"},
    // A frozen bank, for the freeze morph; a remap that isn't straight
    // through, for how much of it.
    {"Cipher", "freeze=#1 remap=#1"},
};

/** The two spellings a matrix slot goes by in this tree. */
struct Slot {
    int32_t src = -1, dest = -1, depth = -1;
    int32_t srcSteps = 0, destSteps = 0;
    bool found() const { return src >= 0 && dest >= 0 && depth >= 0; }
};

Slot firstSlot(const ParamSet &p) {
    static const char *spellings[][3] = {
        {"m1_src", "m1_dst", "m1_amt"},      // Cipher, Filament, Manual
        {"m01_src", "m01_dest", "m01_depth"} // Trinity, Ratio
    };
    Slot s;
    for (const auto &sp : spellings) {
        const int32_t a = p.indexOf(sp[0]), b = p.indexOf(sp[1]), c = p.indexOf(sp[2]);
        if (a >= 0 && b >= 0 && c >= 0) {
            s.src = a; s.dest = b; s.depth = c;
            s.srcSteps = p.def(a).steps;
            s.destSteps = p.def(b).steps;
            return s;
        }
    }
    return s;
}

/**
 * Five seconds of the machine being played, with everything a source might
 * follow actually moving.
 *
 * A source that needs a mod wheel reads zero if nobody touches it, which
 * would fail correct wiring. So the wheel is up, the key is under pressure,
 * the bend is off centre, and two notes play at different velocities in
 * different octaves.
 */
void applyKnobs(Machine *m, const char *knobs) {
    if (knobs == nullptr) return;
    std::string all = knobs;
    size_t at = 0;
    while (at < all.size()) {
        size_t end = all.find(' ', at);
        if (end == std::string::npos) end = all.size();
        const std::string kv = all.substr(at, end - at);
        at = end + 1;
        const size_t eq = kv.find('=');
        if (eq == std::string::npos) continue;
        const int32_t i = m->params().indexOf(kv.substr(0, eq).c_str());
        if (i < 0) { printf("  ??   no knob called %s\n", kv.substr(0, eq).c_str()); continue; }
        const std::string v = kv.substr(eq + 1);
        const int32_t steps = m->params().def(i).steps;
        const float norm = v[0] == '#' ? std::stof(v.substr(1)) / static_cast<float>(steps - 1) : std::stof(v);
        m->params().set(i, norm);
    }
}

std::vector<float> play(const std::string &name, int32_t srcStep, int32_t destStep, int32_t slotSteps,
                        const Slot &slot, float depth = 1.0f, const char *context = nullptr) {
    Machine *m = MachineRegistry::create(name.c_str());
    if (m == nullptr) return {};
    m->prepare(kSr);
    m->reset();
    // Mosaic plays a zone map, and with none mounted its matrix couldn't be
    // tested. Uses the audition harness's synthetic map, built once and kept.
    if (name == "Mosaic") {
        static const std::unique_ptr<SampleMap> map = audition::zoneMap();
        m->swapObject(0, map.get());
    }
    for (const auto &su : kSetup) {
        if (name == su.machine) {
            const int32_t i = m->params().indexOf(su.name);
            if (i >= 0) m->params().set(i, su.value);
        }
    }
    applyKnobs(m, context);
    if (srcStep > 0) {
        m->params().set(slot.src, static_cast<float>(srcStep) / static_cast<float>(slot.srcSteps - 1));
        m->params().set(slot.dest, static_cast<float>(destStep) / static_cast<float>(slot.destSteps - 1));
        m->params().set(slot.depth, depth); // 1 is the top of a -1..1 knob, 0 the bottom
    }
    (void)slotSteps;
    m->params().jumpAll();
    m->onBlock(0, kBlock, 120.0f);
    m->controlChange(1, 100); // the wheel
    m->channelPressure(90);
    m->pitchBend(10000);

    std::vector<float> out;
    out.reserve(static_cast<size_t>(kBlocks) * kBlock);
    float L[kBlock], R[kBlock];
    Modulator mod;
    for (int32_t b = 0; b < kBlocks; ++b) {
        mod.fill(b, kBlock);
        if (b == 0) m->noteOn(48, 100);
        if (b == 80) m->noteOn(60, 40);
        if (b == 160) { m->noteOff(48); m->noteOff(60); }
        for (int32_t i = 0; i < kBlock; ++i) L[i] = R[i] = 0.0f;
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    Modulator::silence();
    if (name == "Mosaic") m->swapObject(0, nullptr); // the map is kept, not freed
    delete m;
    return out;
}

double difference(const std::vector<float> &a, const std::vector<float> &b) {
    double d = 0.0;
    const size_t n = a.size() < b.size() ? a.size() : b.size();
    for (size_t i = 0; i < n; ++i) d += std::fabs(a[i] - b[i]);
    return d;
}

/**
 * Whether two renders differ anywhere by more than -120 dB of the louder's peak.
 *
 * For the destination pass. The renders are deterministic, so a destination
 * nothing reads comes out bit-identical and any difference means it's wired.
 * An energy measure would miss small effects like the organ's key click,
 * which is one sample at each of nine contacts.
 */
bool differsAtAll(const std::vector<float> &a, const std::vector<float> &b) {
    float peak = 0.0f, d = 0.0f;
    const size_t n = a.size() < b.size() ? a.size() : b.size();
    for (size_t i = 0; i < n; ++i) {
        peak = std::fmax(peak, std::fmax(std::fabs(a[i]), std::fabs(b[i])));
        d = std::fmax(d, std::fabs(a[i] - b[i]));
    }
    return d > peak * 1e-6f;
}

double energyOf(const std::vector<float> &a) {
    double e = 0.0;
    for (float s : a) e += std::fabs(s);
    return e;
}

void aMachine(const std::string &name) {
    Machine *probe = MachineRegistry::create(name.c_str());
    if (probe == nullptr) return;
    const Slot slot = firstSlot(probe->params());
    delete probe;
    if (!slot.found()) return; // no matrix; nothing to ask

    const auto silent = play(name, 0, 0, 0, slot);
    const double base = energyOf(silent);
    if (base <= 0.0) {
        // A sample player with nothing mounted has nothing to play, and
        // mounting a file would make this depend on outside material.
        // `bank_test` reports the same machines the same way.
        printf("  ..   %-10s makes no sound unrouted; nothing to compare\n", name.c_str());
        return;
    }

    std::vector<int32_t> dead;
    for (int32_t s = 1; s < slot.srcSteps; ++s) {
        bool moved = false;
        // Every destination, until one of them moves. A source is wired if
        // anything it can be pointed at responds. Which destinations suit it
        // is for the ear to judge.
        for (int32_t d = 1; d < slot.destSteps && !moved; ++d) {
            const auto routed = play(name, s, d, 0, slot);
            if (difference(silent, routed) > base * 0.001) moved = true;
        }
        if (!moved) dead.push_back(s);
    }

    const auto listOf = [](const std::vector<int32_t> &v) {
        std::string list;
        for (size_t i = 0; i < v.size(); ++i) {
            if (i != 0) list += ", ";
            list += std::to_string(v[i]);
        }
        return list;
    };

    ++checks;
    if (dead.empty()) {
        printf("  ok   %-10s all %d sources reach the sound\n", name.c_str(), slot.srcSteps - 1);
    } else {
        ++failures;
        printf("  FAIL %-10s source%s %s move nothing, whatever they are pointed at\n", name.c_str(),
               dead.size() == 1 ? "" : "s", listOf(dead).c_str());
    }

    // And every destination, from the other end.
    //
    // A source passes above if it reaches anything, so a destination the
    // render never reads would slip through. Every source is tried against
    // each destination at both ends of the depth knob, since a destination
    // already at the top of its range can only move down.
    std::vector<const char *> contexts = {nullptr};
    for (const auto &c : kContexts) if (name == c.machine) contexts.push_back(c.knobs);
    std::vector<std::vector<float>> quiet;
    std::vector<double> quietBase;
    for (const char *c : contexts) {
        quiet.push_back(c == nullptr ? silent : play(name, 0, 0, 0, slot, 1.0f, c));
        quietBase.push_back(energyOf(quiet.back()));
    }
    std::vector<int32_t> deaf;
    for (int32_t d = 1; d < slot.destSteps; ++d) {
        bool moved = false;
        for (size_t c = 0; c < contexts.size() && !moved; ++c) {
            for (int32_t s = 1; s < slot.srcSteps && !moved; ++s) {
                for (float depth : {1.0f, 0.0f}) {
                    const auto routed = play(name, s, d, 0, slot, depth, contexts[c]);
                    if (differsAtAll(quiet[c], routed)) { moved = true; break; }
                }
            }
        }
        if (!moved) deaf.push_back(d);
    }
    ++checks;
    if (deaf.empty()) {
        printf("  ok   %-10s all %d destinations answer\n", name.c_str(), slot.destSteps - 1);
        return;
    }
    ++failures;
    printf("  FAIL %-10s destination%s %s answer to nothing\n", name.c_str(), deaf.size() == 1 ? "" : "s",
           listOf(deaf).c_str());
}

} // namespace

int main(int argc, char **argv) {
    const std::string only = argc > 1 ? argv[1] : "";
    printf("\nmodulation sources and destinations, and whether they are wired\n\n");
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const std::string n = MachineRegistry::name(i);
        if (!only.empty() && only != n) continue;
        aMachine(n);
    }
    printf("\n%d checks, %d with a source or destination wired to nothing\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
