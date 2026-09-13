// Does following a Link session work - and does Link itself, as vendored?
//
// Two questions, and they are answered differently. The arithmetic of
// following (LinkFollower) is pure and is checked against numbers that were
// worked out by hand. The library is checked by running *two* sessions in
// this one process and watching them agree: peers found, a tempo set on one
// arriving at the other, and both reading the same beat at the same instant.
//
// The second half needs a network interface that carries multicast, which a
// build machine has and an Android emulator does not - which is why the
// emulator proof of M40 is the plumbing and Dan's two phones are the rest.
#include <ableton/Link.hpp>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <sequencer/LinkFollower.h>

using namespace acidulous;
using seq::LinkFollower;

namespace {
int failures = 0, checks = 0;

void ok(const char *what, bool good, const char *detail = "") {
    ++checks;
    if (!good) ++failures;
    printf("  %s %-52s %s\n", good ? "ok  " : "FAIL", what, detail);
}

void near(const char *what, double got, double want, double tol) {
    char note[96];
    snprintf(note, sizeof note, "%.6f, wanted %.6f", got, want);
    ok(what, std::fabs(got - want) <= tol, note);
}

Timebase::State state(double bpm, double beat, double quantum, double beatsPerBlock) {
    Timebase::State s;
    s.valid = true;
    s.bpm = bpm;
    s.beat = beat;
    s.quantum = quantum;
    s.beatsPerBlock = beatsPerBlock;
    return s;
}

/** 64 frames at 48 kHz, in beats, at this tempo. */
double blockBeats(double bpm) { return 64.0 / 48000.0 * bpm / 60.0; }

void followerTests() {
    printf("--- the following, as arithmetic ---\n");
    const double bar = 4.0 * kPPQN; // 4/4 at 240 PPQN = 960 ticks
    const double perTick = 48000.0 * 60.0 / (120.0 * kPPQN); // 100 frames at 120 bpm

    // In phase: nothing to correct, and the tempo is taken as it is.
    {
        const auto a = LinkFollower::advise(state(120.0, 8.0, 4.0, blockBeats(120.0)),
                                            0.0, bar, perTick, true);
        near("in phase: no correction", a.framesPerTick, perTick, 1e-9);
        near("in phase: no error", a.errorTicks, 0.0, 1e-9);
    }

    // A quarter of a bar ahead: 240 ticks of error, clamped to eight, and
    // the ticks get *longer* so we fall back towards them.
    {
        const auto a = LinkFollower::advise(state(120.0, 8.0, 4.0, blockBeats(120.0)),
                                            bar / 4.0, bar, perTick, true);
        near("a quarter bar ahead: error", a.errorTicks, 240.0, 1e-9);
        near("a quarter bar ahead: clamped pull", a.framesPerTick,
             perTick * (1.0 + LinkFollower::kMaxPullTicks * LinkFollower::kPull), 1e-9);
    }

    // Behind by a quarter: the mirror image, and the ticks get shorter.
    {
        const auto a = LinkFollower::advise(state(120.0, 9.0, 4.0, blockBeats(120.0)),
                                            0.0, bar, perTick, true);
        near("a quarter bar behind: error", a.errorTicks, -240.0, 1e-9);
        ok("a quarter bar behind: ticks shorten", a.framesPerTick < perTick);
    }

    // The wrap, which is the one that is easy to get wrong: a hair before
    // the bar line against a hair after it is a small error, not a huge one.
    {
        const double tick = bar - 1.0;                    // one tick short of the bar
        const auto a = LinkFollower::advise(state(120.0, 8.0, 4.0, blockBeats(120.0)),
                                            tick, bar, perTick, true);
        near("just before their downbeat: error is -1 tick", a.errorTicks, -1.0, 1e-9);
    }
    {
        const auto a = LinkFollower::advise(state(120.0, 8.0 - 1.0 / kPPQN, 4.0, blockBeats(120.0)),
                                            0.0, bar, perTick, true);
        near("just after their downbeat: error is +1 tick", a.errorTicks, 1.0, 1e-9);
    }

    // Half a bar out is the one ambiguous case; it must not blow up.
    {
        const auto a = LinkFollower::advise(state(120.0, 8.0, 4.0, blockBeats(120.0)),
                                            bar / 2.0, bar, perTick, true);
        ok("half a bar out stays bounded",
           std::fabs(a.errorTicks) <= bar / 2.0 + 1e-9 &&
               std::fabs(a.framesPerTick / perTick - 1.0) <= LinkFollower::kMaxPullTicks * LinkFollower::kPull + 1e-12);
    }

    // Not pulling: stopped, or waiting to start. Tempo yes, phase no.
    {
        const auto a = LinkFollower::advise(state(140.0, 3.5, 4.0, blockBeats(140.0)),
                                            bar / 3.0, bar, perTick, false);
        near("stopped: the tempo is still taken", a.framesPerTick, perTick, 1e-12);
        near("stopped: no phase correction", a.errorTicks, 0.0, 1e-12);
    }

    // The downbeat has to be caught in the block it lands in. At 120 bpm a
    // block is 0.0032 beats, so a beat sat 0.001 before the line is caught
    // and one sat 0.01 before it is not - yet.
    {
        const double bb = blockBeats(120.0);
        ok("downbeat inside this block is caught",
           LinkFollower::advise(state(120.0, 4.0 - bb / 2.0, 4.0, bb), 0.0, bar, perTick, false).downbeat);
        ok("downbeat after this block is not",
           !LinkFollower::advise(state(120.0, 4.0 - bb * 3.0, 4.0, bb), 0.0, bar, perTick, false).downbeat);
        ok("no downbeat mid-bar",
           !LinkFollower::advise(state(120.0, 2.0, 4.0, bb), 0.0, bar, perTick, false).downbeat);
    }

    // A 7/8 bar against a four-beat quantum: both are read as a fraction of
    // their own bar, so the phases still compare.
    {
        const double bar78 = 7 * kPPQN / 2; // seven eighths = 840 ticks
        const auto a = LinkFollower::advise(state(120.0, 3.5, 3.5, blockBeats(120.0)),
                                            bar78 / 2.0, bar78, perTick, true);
        near("7/8: half a bar out reads as half a bar", std::fabs(a.errorTicks), bar78 / 2.0, 1e-9);
    }
}

/** Two sessions, in this one process, over the machine's own network. */
void libraryTests() {
    printf("\n--- two Link peers, for real ---\n");
    ableton::Link a(120.0), b(120.0);
    // Both ends have to want it: start and stop are only carried between
    // peers that have asked for them, which is what LinkTimebase turns on.
    a.enableStartStopSync(true);
    b.enableStartStopSync(true);
    a.enable(true);
    b.enable(true);

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(12);
    while (std::chrono::steady_clock::now() < deadline && (a.numPeers() == 0 || b.numPeers() == 0)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    const bool found = a.numPeers() > 0 && b.numPeers() > 0;
    char note[96];
    snprintf(note, sizeof note, "a sees %zu, b sees %zu", a.numPeers(), b.numPeers());
    ok("they find each other", found, note);
    if (!found) {
        printf("  .... no multicast on this machine's interfaces; the rest needs peers\n");
        a.enable(false);
        b.enable(false);
        return;
    }

    // A tempo set on one arrives at the other.
    {
        auto state = a.captureAppSessionState();
        state.setTempo(143.5, a.clock().micros());
        a.commitAppSessionState(state);
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (std::chrono::steady_clock::now() < until &&
               std::fabs(b.captureAppSessionState().tempo() - 143.5) > 0.01) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        near("a tempo set on one reaches the other", b.captureAppSessionState().tempo(), 143.5, 0.01);
    }

    // And they agree about where the beat is, at the same instant, to well
    // under a millisecond - which at 143.5 bpm is 0.0024 of a beat.
    {
        const auto at = a.clock().micros();
        const double beatA = a.captureAppSessionState().beatAtTime(at, 4.0);
        const double beatB = b.captureAppSessionState().beatAtTime(at, 4.0);
        snprintf(note, sizeof note, "%.6f against %.6f", beatA, beatB);
        ok("and agree where the beat is", std::fabs(beatA - beatB) < 0.0024, note);
    }

    // Start and stop carries too.
    {
        auto state = a.captureAppSessionState();
        state.setIsPlaying(true, a.clock().micros());
        a.commitAppSessionState(state);
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (std::chrono::steady_clock::now() < until && !b.captureAppSessionState().isPlaying()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        ok("start travels between peers", b.captureAppSessionState().isPlaying());
    }

    a.enable(false);
    b.enable(false);
}
} // namespace

int main() {
    followerTests();
    libraryTests();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
