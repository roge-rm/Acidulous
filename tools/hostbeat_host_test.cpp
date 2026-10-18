// EngineHost::alignBeat, releaseBeat and framesRendered, through the host
// itself with the lines driver (platform/lines), which renders in real time
// into nothing. The phase maths is in hostbeat_test.cpp; this checks the
// calls take the tempo, hand it back on release and when they stop, and
// stamp with a frame count that moves.
#include <EngineHost.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool good, double got) {
    ++checks;
    if (!good) ++failures;
    std::printf("  %s %-56s %.3f\n", good ? "ok  " : "FAIL", what, got);
}

void wait(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

/** Calls alignBeat every 100 ms for [ms], a host at [bpm] whose beat 0 was frame [origin]. */
void callFor(EngineHost &h, int ms, double bpm, int64_t origin) {
    for (int t = 0; t < ms; t += 100) {
        const int64_t at = h.framesRendered();
        h.alignBeat(static_cast<double>(at - origin) * bpm / (60.0 * kSampleRate), bpm, at);
        wait(100);
    }
}
} // namespace

int main() {
    std::printf("hostbeat_host_test\n");
    EngineHost &h = EngineHost::instance();
    if (!h.start()) {
        std::printf("  FAIL engine did not start\n");
        return 1;
    }
    h.setTempo(100.0f);
    const int64_t f0 = h.framesRendered();
    wait(300);
    const int64_t f1 = h.framesRendered();
    ok("framesRendered moves on, frames in 0.3 s", f1 - f0 > 4800, static_cast<double>(f1 - f0));
    ok("the song's tempo before any call", std::fabs(h.tempo() - 100.0f) < 0.01f, h.tempo());

    callFor(h, 1000, 130.0, f1);
    ok("alignBeat takes the tempo", std::fabs(h.tempo() - 130.0f) < 2.0f, h.tempo());
    h.releaseBeat();
    wait(200);
    ok("releaseBeat hands it back to the song", std::fabs(h.tempo() - 100.0f) < 0.01f, h.tempo());

    callFor(h, 1000, 130.0, f1);
    ok("alignBeat takes it again", std::fabs(h.tempo() - 130.0f) < 2.0f, h.tempo());
    wait(600);
    ok("half a second with no call: still the host's", std::fabs(h.tempo() - 130.0f) < 2.0f, h.tempo());
    wait(1000);
    ok("over a second with no call: the song's again", std::fabs(h.tempo() - 100.0f) < 0.01f, h.tempo());

    h.stop();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
