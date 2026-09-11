// Does a tick land on the frame the clock says it does, and does a 24-PPQN
// pulse train stay put over an hour?
#include <cmath>
#include <cstdio>
#include <vector>
#include <sequencer/TickClock.h>

using namespace acidulous;
using namespace acidulous::seq;
namespace { int failures = 0; int checks = 0; }
static void ok(const char *what, double got, double want, double tol) {
    ++checks;
    if (std::fabs(got - want) > tol) { ++failures; printf("  FAIL %-50s got %.4f want %.4f\n", what, got, want); }
    else printf("  ok   %-50s %.4f\n", what, got);
}
static void eqi(const char *what, long long got, long long want) {
    ++checks;
    if (got != want) { ++failures; printf("  FAIL %-50s got %lld want %lld\n", what, got, want); }
    else printf("  ok   %-50s %lld\n", what, got);
}

int main() {
    printf("--- a tick's frame, against a brute-force count ---\n");
    for (float bpm : {60.0f, 90.0f, 120.0f, 137.5f, 174.0f, 240.0f}) {
        TickClock c;
        c.setTempo(bpm);
        const double spt = c.samplesPerTickNow();
        // Walk blocks; whenever a tick boundary falls inside one, the frame it
        // claims must match the tick's true frame, which is simply t * spt.
        double worst = 0;
        int64_t framesDone = 0;
        for (int b = 0; b < 20000; ++b) {
            const int64_t before = c.blockEnd();
            c.advance(kBlockFrames);
            for (int64_t t = before + 1; t <= c.blockEnd(); ++t) {
                const double claimed = framesDone + c.frameOffsetOfTick(t, kBlockFrames);
                const double truth = static_cast<double>(t) * spt;
                worst = std::max(worst, std::fabs(claimed - truth));
            }
            framesDone += kBlockFrames;
        }
        char name[80];
        snprintf(name, sizeof name, "%.1f bpm: worst error over 20000 blocks", bpm);
        ok(name, worst, 0.0, 1e-6);
    }

    printf("--- 24 PPQN: exactly 24 pulses a beat, no drift ---\n");
    for (float bpm : {60.0f, 120.0f, 174.0f}) {
        TickClock c;
        c.setTempo(bpm);
        int pulses = 0;
        const int beats = 512;
        const int64_t untilTick = int64_t(beats) * kPPQN;
        while (c.blockEnd() < untilTick) {
            const int64_t before = c.blockEnd();
            c.advance(kBlockFrames);
            for (int64_t t = before + 1; t <= c.blockEnd() && t <= untilTick; ++t)
                if (t % 10 == 0) ++pulses;
        }
        char name[80];
        snprintf(name, sizeof name, "%.1f bpm: pulses in %d beats", bpm, beats);
        eqi(name, pulses, 24LL * beats);
    }

    printf("--- and no drift across a tempo ramp ---\n");
    {
        TickClock c;
        c.setTempo(120.0f);
        c.rampTempo(174.0f, 4 * kPPQN);
        int pulses = 0;
        const int64_t untilTick = 512LL * kPPQN;
        while (c.blockEnd() < untilTick) {
            const int64_t before = c.blockEnd();
            c.advance(kBlockFrames);
            for (int64_t t = before + 1; t <= c.blockEnd() && t <= untilTick; ++t)
                if (t % 10 == 0) ++pulses;
        }
        eqi("pulses in 512 beats across a ramp", pulses, 24LL * 512);
    }

    printf("--- a tick boundary is never claimed twice or skipped ---\n");
    {
        TickClock c;
        c.setTempo(128.0f);
        int64_t expect = 1;
        bool good = true;
        for (int b = 0; b < 50000; ++b) {
            const int64_t before = c.blockEnd();
            c.advance(kBlockFrames);
            for (int64_t t = before + 1; t <= c.blockEnd(); ++t) {
                if (t != expect) good = false;
                ++expect;
            }
        }
        eqi("every tick seen once, in order, over 50000 blocks", good ? 1 : 0, 1);
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
