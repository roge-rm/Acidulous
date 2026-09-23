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
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/formulate/Program.h>
#include <memory>
#include "patchbank.h"
#include <cmath>
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
        // It opens on a chord with nothing leaning on it, the way a song's
        // first notes arrive, so whatever a voice kept from the prelude is
        // what that chord meets.
        if (b == 0) for (uint8_t n : {64, 69, 72}) m->noteOn(n, 100);
        if (b == 40) for (uint8_t n : {64, 69, 72}) m->noteOff(n);
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
        // Not on the first block: the opening chord is played with whatever
        // the panic left the controllers at, which has to be rest.
        if (b > 0 && b % 7 == 0) m->controlChange(1, static_cast<uint8_t>(b % 128));
        if (b > 0 && b % 11 == 0) m->channelPressure(static_cast<uint8_t>((b * 3) % 128));
        if (b > 0 && b % 23 == 0) m->pitchBend(static_cast<int16_t>((b % 200) - 100));

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

/**
 * Something else to have played before the second pass: a chord held, let
 * go, and a long silence - what a track in a song does before an export.
 *
 * The second pass used to follow the first, so both began from the same
 * history and a voice that kept something from its last note kept the same
 * thing both times. Brazen's tubes kept their pressure, which only shows when
 * the history differs: two exports of the demo did not match from the horns'
 * first chord, and this test said nothing.
 */
void prelude(Machine *m) {
    float L[kBlock], R[kBlock];
    // Played with the controllers at rest, as a track in a song is: a held
    // pressure would leave every voice far enough from the next note's
    // that the fault above could not show.
    m->controlChange(1, 0);
    m->channelPressure(0);
    m->pitchBend(0);
    for (uint8_t n : {64, 65, 66}) m->noteOn(n, 100);
    for (int32_t b = 0; b < 2100; ++b) {
        if (b == 50) for (uint8_t n : {64, 65, 66}) m->noteOff(n);
        // Every drum pad struck a few times, so a kit has a history too. A
        // prelude of three notes in the middle of the keyboard never touched
        // a hi-hat, and a hat that kept count of its hits across a panic
        // passed here while Riddim's fourth hat came out different.
        if (b >= 100 && b < 900 && b % 25 == 0) {
            const uint8_t pad = static_cast<uint8_t>(36 + (b / 25) % 16);
            m->noteOn(pad, static_cast<uint8_t>(60 + (b / 25) % 60));
        }
        if (b >= 110 && b < 910 && (b - 10) % 25 == 0) m->noteOff(static_cast<uint8_t>(36 + ((b - 10) / 25) % 16));
        // And left raised once the notes have gone, which a panic has to
        // put back: the performance below opens without sending them.
        if (b == 2000) { m->controlChange(1, 90); m->channelPressure(100); m->pitchBend(3000); }
        const int64_t tick = static_cast<int64_t>(b) * 4;
        m->onBlock(tick, tick + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
    }
}

/** What a panic does: the engine's own function, not a copy of it. */
void panic(Machine *m) { panicMachine(*m); }

struct Result {
    std::string name;
    bool identical = false;
    bool reproducible = true;
    int64_t firstDiff = -1;
    double worst = 0.0;
    double peak = 0.0;
};

/** A patch's parameters, all of them, as the app's applyAll leaves them. */
using Patch = std::vector<float>;

void apply(ParamSet &params, const Patch *patch) {
    if (patch == nullptr) return;
    for (size_t i = 0; i < patch->size(); ++i) params.set(static_cast<int32_t>(i), (*patch)[i]);
}

/**
 * What a machine needs mounted before it makes the sound a song hears.
 *
 * Cumulus plays nothing without a cloud, and a harness that gave it none said
 * "ok" about a machine whose every read position came from a generator
 * reset() never put back - so every export of a song with a pad in it was
 * different. It gets the table the app builds, from its own settings.
 */
/** Whatever was mounted, kept alive for as long as the machine plays it. */
struct Dressing {
    std::unique_ptr<machine::cumulus::CloudSet> cloud;
    decltype(machine::formulate::compile("", "", "", "", std::declval<std::string &>())) program;
};

using Settings = std::vector<std::pair<std::string, std::string>>;

Dressing dress(const char *name, Machine *m, const Settings *settings) {
    Dressing d;
    if (std::strcmp(name, "Cumulus") == 0) {
        auto *cumulus = static_cast<machine::Cumulus *>(m);
        d.cloud = machine::cumulus::buildCloud(cumulus->spec(), kRate);
        m->swapObject(0, d.cloud.get());
    }
    // Formulate's formula and tables are text compiled into a program, the
    // way EngineSync.ensureFormulas has the app do it.
    if (std::strcmp(name, "Formulate") == 0 && settings != nullptr) {
        std::string formula, arp, duty, vol, error;
        for (const auto &kv : *settings) {
            if (kv.first == "formula") formula = kv.second;
            else if (kv.first == "arp") arp = kv.second;
            else if (kv.first == "duty") duty = kv.second;
            else if (kv.first == "vol") vol = kv.second;
        }
        if (!(formula.empty() && arp.empty() && duty.empty() && vol.empty())) {
            d.program = machine::formulate::compile(formula, arp, duty, vol, error);
            if (d.program) m->swapObject(0, d.program.get());
        }
    }
    return d;
}

Result check(const char *name, const Patch *patch = nullptr, const Settings *settings = nullptr) {
    Result r;
    r.name = name;
    Machine *m = MachineRegistry::create(name);
    if (m == nullptr) return r;
    m->prepare(kRate);
    apply(m->params(), patch);
    const auto dressed = dress(name, m, settings);

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
    prelude(m);
    panic(m);
    performance(m, b);
    delete m;

    // A second machine, built from scratch and panicked the same way. If this
    // does not match the first pass then reset() is innocent and the machine
    // itself is not deterministic - a different question with a different
    // answer.
    Machine *m2 = MachineRegistry::create(name);
    m2->prepare(kRate);
    apply(m2->params(), patch);
    const auto dressed2 = dress(name, m2, settings);
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
Result checkEffect(const char *name, const Patch *patch = nullptr) {
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
    apply(e->params(), patch);
    std::vector<float> a, b, other;
    panicE(e); pass(e, a);
    // Something else through it before the second pass, so a delay's buffer
    // or a reverb's tail holds a different history rather than the same one.
    {
        float L[kBlock], R[kBlock];
        for (int32_t k = 0; k < 400; ++k) {
            for (int32_t i = 0; i < kBlock; ++i) { L[i] = std::sin(0.01f * static_cast<float>(k * kBlock + i)); R[i] = L[i] * 0.3f; }
            e->onBlock(k * 4, k * 4 + 4, 97.0f);
            e->run(L, R, kBlock, true);
        }
    }
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

/**
 * Every factory patch, not only each machine at its defaults.
 *
 * The defaults are where drift, scatter and wobble are usually off, so a
 * machine can pass above and still not repeat once a patch turns them up -
 * which is how two exports of the demo came to differ on its bass, its
 * melodica and its horns while every line above said "ok". So the same
 * question, asked of every patch in every bank.
 *
 * Skipped: the machines whose sound is a file or a graph (their patches
 * only mean something with it mounted; their state is covered above).
 */
int everyPatch(const std::string &banks) {
    static const char *const kNeedsMore[] = {"Forage", "Mosaic", "Pollen", "Dice", "Molt", "Bias", "Cipher", "Nexus"};
    int checked = 0, failed = 0;
    const auto skip = [](const std::string &unit) {
        for (const char *n : kNeedsMore) if (unit == n) return true;
        return false;
    };
    const auto one = [&](const std::string &unit, bool effect) {
        audition::Bank bank;
        std::string error;
        if (!audition::readBank(banks + "/" + (effect ? "fx." : "") + unit + ".bank", bank, error)) return;
        int32_t count = 0;
        const ParamDef *defs = effect ? EffectRegistry::paramDefs(unit.c_str(), count)
                                      : MachineRegistry::paramDefs(unit.c_str(), count);
        if (defs == nullptr) return;
        for (const audition::BankPatch &p : bank.patches) {
            const audition::Resolved resolved = audition::resolve(p, defs, count);
            const Patch &values = resolved.norm;
            const Result r = effect ? checkEffect(unit.c_str(), &values) : check(unit.c_str(), &values, &resolved.settings);
            ++checked;
            if (r.identical && r.reproducible) continue;
            ++failed;
            std::printf("  %-12s %-20s %-9s worst %.3e from sample %lld\n", unit.c_str(), p.name.c_str(),
                        r.reproducible ? "DIFFERS" : "UNSTABLE", r.worst, static_cast<long long>(r.firstDiff));
        }
    };
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        if (!skip(MachineRegistry::name(i))) one(MachineRegistry::name(i), false);
    }
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) one(EffectRegistry::name(i), true);
    std::printf("%d of %d factory patches differ after a panic.\n", failed, checked);
    return failed;
}

} // namespace

int main(int argc, char **argv) {
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
    int patchesFailed = 0;
    if (argc > 1) {
        std::printf("\nevery factory patch\n");
        patchesFailed = everyPatch(argv[1]);
    }
    return (failed == 0 && missing == 0 && patchesFailed == 0) ? 0 : 1;
}
