// Does the follower lock, hold, and follow - and how badly does jitter hurt?
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <sequencer/ClockFollower.h>

using namespace acidulous;
using namespace acidulous::seq;
namespace { int failures = 0, checks = 0; uint32_t rng = 12345; }

static double frand() { rng = rng * 1664525u + 1013904223u; return (rng >> 8) * (1.0 / 16777216.0); }
static void ok(const char *what, double got, double lo, double hi, const char *unit = "") {
    ++checks;
    const bool good = got >= lo && got <= hi;
    if (!good) ++failures;
    printf("  %s %-48s %8.3f %s\n", good ? "ok  " : "FAIL", what, got, unit);
}

/** Feed a pulse train at `bpm` with +-`jitterMs` of arrival noise. */
struct Rig {
    ClockFollower f;
    double frame = 0;
    static constexpr double kSr = 48000.0;
    Rig() { f.reset((float)kSr); }                       // the follower's own bandwidth
    Rig(float bw) { f.reset((float)kSr); f.setBandwidth(bw); }
    void run(double bpm, int pulses, double jitterMs, std::vector<double> *errs = nullptr) {
        const double period = kSr * 60.0 / (bpm * 24.0);
        for (int i = 0; i < pulses; ++i) {
            frame += period;
            const double j = (frand() * 2.0 - 1.0) * jitterMs * kSr / 1000.0;
            f.pulse((int64_t)llround(frame + j));
            if (errs) errs->push_back(std::fabs(f.phaseErrorMs()));
        }
    }
};

int main() {
    printf("--- a clean 120 bpm clock ---\n");
    {
        Rig r; r.run(120.0, 200, 0.0);
        ok("tempo read back", r.f.bpm(), 119.9, 120.1, "bpm");
        ok("locked", r.f.locked() ? 1 : 0, 1, 1);
        ok("phase error", std::fabs(r.f.phaseErrorMs()), 0.0, 0.05, "ms");
    }

    printf("--- how long it takes to lock, from cold ---\n");
    for (double bpm : {90.0, 128.0, 174.0}) {
        Rig r; int at = -1;
        const double period = Rig::kSr * 60.0 / (bpm * 24.0);
        for (int i = 0; i < 400 && at < 0; ++i) {
            r.frame += period;
            r.f.pulse((int64_t)llround(r.frame));
            if (r.f.locked()) at = i + 1;
        }
        char n[80]; snprintf(n, sizeof n, "%.0f bpm: pulses to lock", bpm);
        ok(n, at < 0 ? 999 : at, 1, 40, "pulses");
    }

    printf("--- USB-ish jitter, +-0.2 ms ---\n");
    {
        Rig r; std::vector<double> e; r.run(120.0, 100, 0.2);
        e.clear(); r.run(120.0, 300, 0.2, &e);
        double sum = 0, peak = 0;
        for (double v : e) { sum += v * v; peak = std::max(peak, v); }
        ok("tempo held", r.f.bpm(), 119.5, 120.5, "bpm");
        ok("phase error, rms", std::sqrt(sum / e.size()), 0.0, 0.35, "ms");
        ok("phase error, worst", peak, 0.0, 1.0, "ms");
    }

    printf("--- BLE-ish jitter, +-8 ms bursty ---\n");
    {
        Rig r; std::vector<double> e; r.run(120.0, 200, 8.0);
        e.clear(); r.run(120.0, 400, 8.0, &e);
        double sum = 0;
        for (double v : e) sum += v * v;
        ok("tempo still held", r.f.bpm(), 118.0, 122.0, "bpm");
        ok("phase error, rms", std::sqrt(sum / e.size()), 0.0, 12.0, "ms");
        printf("       (the loop averages it; the residual is the transport's own)\n");
    }

    printf("--- following a tempo change ---\n");
    {
        Rig r; r.run(120.0, 200, 0.1);
        r.run(140.0, 200, 0.1);
        ok("arrived at the new tempo", r.f.bpm(), 139.0, 141.0, "bpm");
        // and how many pulses it took
        Rig r2; r2.run(120.0, 200, 0.0);
        int at = -1;
        const double period = Rig::kSr * 60.0 / (140.0 * 24.0);
        for (int i = 0; i < 600 && at < 0; ++i) {
            r2.frame += period;
            r2.f.pulse((int64_t)llround(r2.frame));
            if (std::fabs(r2.f.bpm() - 140.0) < 1.0) at = i + 1;
        }
        ok("pulses to come within 1 bpm", at < 0 ? 999 : at, 1, 250, "pulses");
    }

    printf("--- position is read off the model, not the last arrival ---\n");
    {
        Rig r; r.run(120.0, 300, 0.0);
        // half a pulse on from the last one, the tick should be half of ten
        const double period = Rig::kSr * 60.0 / (120.0 * 24.0);
        const double t0 = r.f.tickAt((int64_t)llround(r.frame));
        const double t1 = r.f.tickAt((int64_t)llround(r.frame + period * 0.5));
        ok("half a pulse is five ticks", t1 - t0, 4.9, 5.1, "ticks");
        const double t2 = r.f.tickAt((int64_t)llround(r.frame + period * 24.0));
        ok("twenty-four pulses is a bar", t2 - t0, 239.0, 241.0, "ticks");
    }

    printf("--- a master that jumps is not chased through the loop ---\n");
    {
        Rig r; r.run(120.0, 200, 0.0);
        const double before = r.f.bpm();
        r.frame += Rig::kSr * 2.0;            // two seconds of silence, then resume
        r.f.pulse((int64_t)llround(r.frame));
        r.run(120.0, 20, 0.0);
        ok("tempo survived the gap", r.f.bpm(), before - 1.0, before + 1.0, "bpm");
    }

    printf("--- and it knows when the master has gone ---\n");
    {
        Rig r; r.run(120.0, 100, 0.0);
        ok("not stale while running", r.f.stale((int64_t)r.frame) ? 1 : 0, 0, 0);
        ok("stale a second later", r.f.stale((int64_t)(r.frame + 48000)) ? 1 : 0, 1, 1);
    }

    // The regression that mattered. Seeding the period from a *single*
    // first interval reads half the true tempo whenever that one interval
    // happens to fall long, and the guard that catches a runaway is itself
    // scaled to the period, so it shrinks with the bad estimate and the
    // loop never recovers. One seed in seven diverged, always downward, and
    // one seed's worth of testing would have missed it. So: every seed.
    printf("--- forty cold starts into BLE-ish jitter ---\n");
    {
        int worst = 0; double furthest = 0.0;
        for (uint32_t seed = 1; seed <= 40; ++seed) {
            rng = seed * 2654435761u + 1;
            Rig r; r.run(120.0, 300, 8.0);
            const double off = std::fabs(r.f.bpm() - 120.0);
            if (off > furthest) { furthest = off; worst = (int)seed; }
        }
        char n[80]; snprintf(n, sizeof n, "worst of 40 seeds (#%d)", worst);
        ok(n, furthest, 0.0, 2.0, "bpm off");
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
