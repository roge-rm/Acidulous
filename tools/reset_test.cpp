// Does reset() actually put a machine back where it started?
//
// An offline render panics first, so every machine begins the render from
// reset() rather than from construction. If reset() misses any state, the
// render depends on whatever was played before it - and exporting the same
// song twice gives two different files.
//
// The test is deliberately not "is the RNG seed restored". That question has
// to be asked once per member variable and is answered wrong by omission.
// This asks the only question that matters: play a machine, reset it, play
// exactly the same thing again, and require the two renders to be identical
// bit for bit. Anything reset() forgets - a seed, an oscillator phase, a
// filter's history, an envelope still in release - shows up as a difference.
//
// Every machine in the registry is covered, so machine nineteen is covered
// the day it is registered.

#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace acidulous;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 512; // ~0.68 s

/**
 * One identical performance, twice over.
 *
 * The notes, the controllers and the block clock all have to be the same on
 * both passes or the comparison proves nothing, so they come from the block
 * index and nothing else.
 */
void performance(Machine *m, std::vector<float> &out) {
    out.clear();
    out.reserve(static_cast<size_t>(kBlocks) * kBlock * 2);
    float L[kBlock], R[kBlock];

    // A wide spread of notes, not a chord in one octave. The drum machines
    // answer to particular pads rather than to pitch, and a performance that
    // never reaches them proves nothing about them - the first version of
    // this test left nine machines rendering silence.
    for (int32_t b = 0; b < kBlocks; ++b) {
        if (b % 16 == 0 && b < 448) {
            const uint8_t note = static_cast<uint8_t>(36 + (b / 16) * 2); // 36..90
            const uint8_t vel = static_cast<uint8_t>(40 + (b / 16) * 3);
            m->noteOn(note, vel > 127 ? 127 : vel);
        }
        // Held long enough to overlap, so polyphony and voice stealing are
        // part of what is being compared.
        if (b >= 96 && (b - 96) % 16 == 0 && b < 480) {
            m->noteOff(static_cast<uint8_t>(36 + ((b - 96) / 16) * 2));
        }
        // The performance controllers too: several machines drift or scatter
        // from them, and that is exactly the state a reset has to rewind.
        if (b % 7 == 0) m->controlChange(1, static_cast<uint8_t>(b % 128));
        if (b % 11 == 0) m->channelPressure(static_cast<uint8_t>((b * 3) % 128));
        if (b % 23 == 0) m->pitchBend(static_cast<int16_t>((b % 200) - 100));

        const int64_t tick = static_cast<int64_t>(b) * 4;
        m->onBlock(tick, tick + 4, 120.0f);

        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            out.push_back(L[i]);
            out.push_back(stereo ? R[i] : L[i]);
        }
    }
}

/** What a panic does, in the order Engine::process does it. */
void panic(Machine *m) {
    m->allNotesOff();
    m->reset();
    m->params().jumpAll();
}

struct Result {
    std::string name;
    bool identical = false;
    bool reproducible = true;
    int64_t firstDiff = -1;
    double worst = 0.0;
    double peak = 0.0;
};

Result check(const char *name) {
    Result r;
    r.name = name;
    Machine *m = MachineRegistry::create(name);
    if (m == nullptr) return r;
    m->prepare(kRate);

    // Panic before the *first* pass as well, because an offline render does.
    // Without this the test fails everything by a hair for a reason that has
    // nothing to do with reset(): jumpAll() sets each parameter to
    // map(unmap(def)), and a round trip through an exponential curve is not
    // bit-exact, so a panicked machine starts a few mantissa bits away from
    // a newly built one. Both renders of an export are panicked, so both get
    // the same slightly-off value and the export still repeats.
    std::vector<float> a, b, fresh;
    panic(m);
    performance(m, a);
    panic(m);
    performance(m, b);
    delete m;

    // A second machine, built from scratch and panicked the same way. If this
    // does not match the first pass then reset() is innocent and the machine
    // itself is not deterministic - a different question with a different
    // answer.
    Machine *m2 = MachineRegistry::create(name);
    m2->prepare(kRate);
    panic(m2);
    performance(m2, fresh);
    delete m2;
    r.reproducible = (fresh == a);

    if (a.size() != b.size()) return r;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = static_cast<double>(a[i]) - static_cast<double>(b[i]);
        const double mag = d < 0 ? -d : d;
        if (mag > r.worst) r.worst = mag;
        if (mag != 0.0 && r.firstDiff < 0) r.firstDiff = static_cast<int64_t>(i);
        const double pa = a[i] < 0 ? -a[i] : a[i];
        if (pa > r.peak) r.peak = pa;
    }
    r.identical = (r.firstDiff < 0);
    return r;
}

/**
 * The same question of an effect.
 *
 * An effect is fed a signal rather than notes, and it has to be the same
 * signal both times - so it comes from a counter, not from a machine. A
 * reverb's tail, a delay's buffer and a chorus's LFO are all state a render
 * must not inherit from whatever played before it.
 */
Result checkEffect(const char *name) {
    Result r;
    r.name = name;
    auto pass = [](Effect *e, std::vector<float> &out) {
        out.clear();
        float L[kBlock], R[kBlock];
        uint32_t n = 0x12345678u;
        for (int32_t b = 0; b < kBlocks; ++b) {
            for (int32_t i = 0; i < kBlock; ++i) {
                n = n * 1664525u + 1013904223u;
                L[i] = static_cast<float>(n >> 9) * (2.0f / 8388608.0f) - 1.0f;
                R[i] = -L[i] * 0.5f;
            }
            const int64_t tick = static_cast<int64_t>(b) * 4;
            e->onBlock(tick, tick + 4, 120.0f);
            e->run(L, R, kBlock, true);
            for (int32_t i = 0; i < kBlock; ++i) { out.push_back(L[i]); out.push_back(R[i]); }
        }
    };
    auto panicE = [](Effect *e) { e->reset(); e->params().jumpAll(); };

    Effect *e = EffectRegistry::create(name);
    if (e == nullptr) return r;
    e->prepare(kRate);
    std::vector<float> a, b;
    panicE(e); pass(e, a);
    panicE(e); pass(e, b);
    delete e;

    if (a.size() != b.size()) return r;
    for (size_t i = 0; i < a.size(); ++i) {
        const double d = static_cast<double>(a[i]) - static_cast<double>(b[i]);
        const double mag = d < 0 ? -d : d;
        if (mag > r.worst) r.worst = mag;
        if (mag != 0.0 && r.firstDiff < 0) r.firstDiff = static_cast<int64_t>(i);
        const double pa = a[i] < 0 ? -a[i] : a[i];
        if (pa > r.peak) r.peak = pa;
    }
    r.identical = (r.firstDiff < 0);
    return r;
}

/**
 * Every registered machine can be reached by name for its parameters.
 *
 * `MachineRegistry` says a machine's name in three places - the name table,
 * `create`, and `paramDefs` - and a machine added to the first two but not the
 * third **has no working knobs at all**, silently. `EngineHost::setParam` looks
 * its index up through `paramDefs`, finds nothing, and returns false; the panel
 * draws every control at zero, the document's values are refused on every push,
 * and the machine plays on at its built-in defaults, which is exactly loud
 * enough to look like it is working.
 *
 * Bias shipped that way on 2026-09-20 and was found by tapping a mute that did
 * nothing. One loop over the names is the whole defence.
 */
int registryIsComplete() {
    int missing = 0;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const char *name = MachineRegistry::name(i);
        int32_t n = 0;
        const ParamDef *defs = MachineRegistry::paramDefs(name, n);
        Machine *m = MachineRegistry::create(name);
        int32_t own = 0;
        if (m != nullptr) m->paramDefs(own);
        delete m;
        if (defs == nullptr || n <= 0) {
            std::printf("  FAIL %-12s has no parameter table in MachineRegistry::paramDefs\n", name);
            ++missing;
        } else if (own != n) {
            std::printf("  FAIL %-12s: the registry says %d parameters, the machine says %d\n", name, n, own);
            ++missing;
        }
    }
    std::printf("%d of %d machines are missing their parameter table.\n", missing,
                MachineRegistry::count());
    return missing;
}

} // namespace

int main() {
    std::printf("every machine can be reached by name for its parameters\n");
    const int missing = registryIsComplete();
    std::printf("\nreset() determinism: render, panic, render the same again\n");
    std::printf("%-12s  %-10s  %-12s  %-12s  %s\n",
                "machine", "verdict", "peak", "worst diff", "first differing sample");

    int failed = 0, silent = 0, unstable = 0;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const Result r = check(MachineRegistry::name(i));
        const bool quiet = r.peak == 0.0;
        if (quiet) ++silent;
        if (!r.identical) ++failed;
        if (!r.reproducible) ++unstable;
        std::printf("%-12s  %-10s  %-12.6f  %-12.3e  %s\n",
                    r.name.c_str(),
                    !r.reproducible ? "UNSTABLE" : r.identical ? (quiet ? "ok(silent)" : "ok") : "DIFFERS",
                    r.peak, r.worst,
                    r.firstDiff < 0 ? "-" : std::to_string(r.firstDiff).c_str());
    }

    std::printf("\neffects\n");
    int effectsFailed = 0;
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
        const Result r = checkEffect(EffectRegistry::name(i));
        if (!r.identical) ++effectsFailed;
        std::printf("%-12s  %-10s  %-12.6f  %-12.3e  %s\n",
                    r.name.c_str(), r.identical ? "ok" : "DIFFERS", r.peak, r.worst,
                    r.firstDiff < 0 ? "-" : std::to_string(r.firstDiff).c_str());
    }

    std::printf("\n%d of %d machines differ after a panic.\n", failed, MachineRegistry::count());
    std::printf("%d of %d effects differ after a panic.\n", effectsFailed, EffectRegistry::count());
    failed += effectsFailed;
    if (unstable > 0) {
        std::printf("%d gave two different renders from two fresh machines - those are not a\n"
                    "reset() problem at all, and have to be chased separately.\n", unstable);
    }
    if (silent > 0) {
        std::printf("%d rendered silence (they need a mounted sample to make a sound);\n"
                    "their verdict covers every other piece of state they carry.\n", silent);
    }
    return (failed == 0 && missing == 0) ? 0 : 1;
}
