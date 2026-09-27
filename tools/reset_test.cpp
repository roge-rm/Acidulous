// Checks reset() really puts a machine back where it started.
//
// An offline render panics first, so every machine starts the render from
// reset(), not from construction. If reset() misses any state, the render
// depends on what was played before it, and exporting the same song twice
// gives two different files.
//
// Instead of checking each member variable, this plays a machine, resets it,
// plays exactly the same thing again and requires the two renders to match
// bit for bit. Anything reset() forgets (a seed, an oscillator phase, a
// filter's history, an envelope still releasing) shows up as a difference.
//
// It covers every machine in the registry, so new machines are covered
// automatically.

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
 * One identical performance, played twice.
 *
 * The notes, the controllers and the block clock must match on both passes,
 * so they come only from the block index.
 */
void performance(Machine *m, std::vector<float> &out) {
    out.clear();
    out.reserve(static_cast<size_t>(kBlocks) * kBlock * 2);
    float L[kBlock], R[kBlock];

    // A wide spread of notes, not a chord in one octave. The drum machines
    // respond to particular pads, not pitch, and need to be reached.
    for (int32_t b = 0; b < kBlocks; ++b) {
        // It opens on a chord with nothing leaning on it, like a song's first
        // notes, so it meets whatever a voice kept from the prelude.
        if (b == 0) for (uint8_t n : {64, 69, 72}) m->noteOn(n, 100);
        if (b == 40) for (uint8_t n : {64, 69, 72}) m->noteOff(n);
        if (b % 16 == 0 && b < 448) {
            const uint8_t note = static_cast<uint8_t>(36 + (b / 16) * 2); // 36..90
            const uint8_t vel = static_cast<uint8_t>(40 + (b / 16) * 3);
            m->noteOn(note, vel > 127 ? 127 : vel);
        }
        // Held long enough to overlap, so polyphony and voice stealing are
        // part of the comparison.
        if (b >= 96 && (b - 96) % 16 == 0 && b < 480) {
            m->noteOff(static_cast<uint8_t>(36 + ((b - 96) / 16) * 2));
        }
        // The performance controllers too, since several machines drift or
        // scatter from them and a reset has to rewind that. Not on the first
        // block: the opening chord plays with whatever the panic left the
        // controllers at, which must be rest.
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
 * Something else played before the second pass: a chord held, released, and
 * a long silence, like a track in a song before an export.
 *
 * Both passes need different histories. If the second pass just followed the
 * first, a voice that kept something from its last note would keep the same
 * thing both times and the test would miss it.
 */
void prelude(Machine *m) {
    float L[kBlock], R[kBlock];
    // Played with the controllers at rest, like a track in a song. Held
    // pressure would move every voice far enough from the next note that
    // leftover state couldn't show.
    m->controlChange(1, 0);
    m->channelPressure(0);
    m->pitchBend(0);
    for (uint8_t n : {64, 65, 66}) m->noteOn(n, 100);
    for (int32_t b = 0; b < 2100; ++b) {
        if (b == 50) for (uint8_t n : {64, 65, 66}) m->noteOff(n);
        // Strike every drum pad a few times so a kit has a history too.
        // Notes in the middle of the keyboard never reach a hi-hat, so a hat
        // that counted hits across a panic would otherwise pass.
        if (b >= 100 && b < 900 && b % 25 == 0) {
            const uint8_t pad = static_cast<uint8_t>(36 + (b / 25) % 16);
            m->noteOn(pad, static_cast<uint8_t>(60 + (b / 25) % 60));
        }
        if (b >= 110 && b < 910 && (b - 10) % 25 == 0) m->noteOff(static_cast<uint8_t>(36 + ((b - 10) / 25) % 16));
        // Leave the controllers raised after the notes stop, which a panic
        // has to reset: the performance below opens without sending them.
        if (b == 2000) { m->controlChange(1, 90); m->channelPressure(100); m->pitchBend(3000); }
        const int64_t tick = static_cast<int64_t>(b) * 4;
        m->onBlock(tick, tick + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
    }
}

/** What a panic does, using the engine's own function. */
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
 * Cumulus plays nothing without a cloud, and without one its read positions
 * weren't tested. It gets the table the app builds, from its own settings.
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
    // Formulate's formula and tables are text compiled into a program, like
    // EngineSync.ensureFormulas does in the app.
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

    // Panic before the first pass too, because an offline render does.
    // jumpAll() sets each parameter to map(unmap(def)), and a round trip
    // through an exponential curve isn't bit-exact, so a panicked machine
    // starts a few mantissa bits away from a newly built one. Both renders of
    // an export are panicked, so the export still repeats.
    std::vector<float> a, b, fresh;
    panic(m);
    performance(m, a);
    prelude(m);
    panic(m);
    performance(m, b);
    delete m;

    // A second machine, built from scratch and panicked the same way. If this
    // doesn't match the first pass, reset() isn't the problem: the machine
    // itself isn't deterministic.
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
 * The same check for an effect.
 *
 * An effect is fed a signal instead of notes, and it has to be the same both
 * times, so it comes from a counter instead of a machine. A reverb's tail, a
 * delay's buffer and a chorus's LFO are all state a render mustn't inherit.
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
    // or a reverb's tail holds a different history.
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
 * Every registered machine can be found by name for its parameters.
 *
 * `MachineRegistry` names a machine in three places: the name table,
 * `create`, and `paramDefs`. A machine missing from `paramDefs` has no
 * working knobs. `EngineHost::setParam` finds nothing and returns false, the
 * panel shows every control at zero, and the machine plays on at its built-in
 * defaults, so it looks like it works.
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
 * Drift, scatter and wobble are usually off by default, so a machine can
 * pass above and still not repeat once a patch turns them up. So the same
 * check runs on every patch in every bank.
 *
 * Skipped: machines whose sound is a file or a graph, since their patches
 * only mean something with it mounted. Their state is covered above.
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
