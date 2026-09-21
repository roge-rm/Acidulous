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
#include <engine/dsp/Denormals.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>

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
 * A note pattern with something happening on most blocks.
 *
 * Silence measures nothing: most of these are cheap until a note starts, and
 * the per-note-on work is exactly what this harness is looking for.
 */
struct Player {
    int32_t next = 0;
    int32_t step = 0;
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
        next = block + 16; // a note every ~21 ms
    }
};

Result timeMachine(const std::string &name) {
    Machine *m = MachineRegistry::create(name.c_str());
    Result r;
    r.name = name;
    if (m == nullptr) return r;
    m->prepare(kSr);
    m->reset();
    m->params().jumpAll();

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
    if (m != nullptr) { m->prepare(kSr); m->reset(); m->params().jumpAll(); }
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
    printf("\n  Units that hold audio - Bias, Dice, Forage, Mosaic, Pollen - have no\n"
           "  material mounted here, and Cumulus has no prewarmed tables, so they are\n"
           "  measured close to idle and their figures are a floor, not a cost.\n");
}

} // namespace

int main(int argc, char **argv) {
    const std::string only = argc > 1 ? argv[1] : "";
    // Off with ACIDULOUS_NO_FTZ=1, so the cost of denormals can be measured
    // rather than argued about.
    const bool ftz = getenv("ACIDULOUS_NO_FTZ") == nullptr;
    if (ftz) dsp::flushDenormals();
    printf("flush-to-zero: %s\n", ftz ? "on" : "off");
    printf("cost per %d-frame block, budget %.0f us\n\n", kBlock, kBudgetUs);

    std::vector<Result> rows;
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
