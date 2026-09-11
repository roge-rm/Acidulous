// Does a launch land on exactly the tick it was promised?
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <sequencer/Launcher.h>

using namespace acidulous::seq;
namespace { int failures = 0; int checks = 0; }

static void eq(const char *what, int64_t got, int64_t want) {
    ++checks;
    if (got != want) { ++failures; printf("  FAIL %-52s got %lld want %lld\n", what, (long long)got, (long long)want); }
    else printf("  ok   %-52s %lld\n", what, (long long)got);
}

constexpr int64_t kBar = 4 * acidulous::kPPQN;   // 960 ticks

/** Run the launcher forward like the scheduler does, collecting the tick at
 *  which each rack's clip changed. */
struct Timeline {
    Launcher l;
    std::vector<std::pair<int64_t, int32_t>> changes;  // (tick, rack)
    int64_t now = 0;
    int splits = 0;
    void run(int64_t until, int64_t blockTicks = 5) {
        while (now < until) {
            const int64_t blockEnd = now + blockTicks;
            int64_t cur = now;
            int guard = 0;
            while (cur < blockEnd) {
                l.applyDue(cur);
                uint32_t ch = l.takeChanged();
                for (int32_t r = 0; r < acidulous::kRackCount; ++r)
                    if (ch & (1u << r)) changes.push_back({cur, r});
                const int64_t next = std::min(blockEnd, l.nextEvent(cur));
                cur = next;
                if (++guard > 64) { printf("  FAIL boundary storm: block never finished\n"); ++failures; break; }
                ++splits;
            }
            now = blockEnd;
        }
    }
    int64_t firstChangeOn(int32_t rack) const {
        for (auto &c : changes) if (c.second == rack) return c.first;
        return -1;
    }
    int64_t nthChangeOn(int32_t rack, int n) const {
        int seen = 0;
        for (auto &c : changes) if (c.second == rack && seen++ == n) return c.first;
        return -1;
    }
};

int main() {
    printf("--- the first clip starts at once ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 2 * kBar, 0);
        t.run(kBar);
        eq("first launch on a silent rack, at tick 0", t.firstChangeOn(0), 0);
        eq("...and its origin is 0", t.l.origin(0), 0);
        eq("...and it is playing", t.l.playing(0) ? 1 : 0, 1);
    }

    printf("--- a silent rack waits for a line its own length falls on ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 1 * kBar, 0);   // 1-bar clip, starts at once
        t.run(kBar / 2);
        // ask for a 4-bar clip on another rack, half a bar in
        t.l.request(1, 222, 4 * kBar, t.now);
        t.run(20 * kBar);
        eq("4-bar clip tapped at 0.5 bars starts at bar 4", t.firstChangeOn(1), 4 * kBar);
    }
    {
        Timeline t;
        t.l.request(0, 111, 1 * kBar, 0);
        t.run(5 * kBar + kBar / 3);
        t.l.request(1, 222, 2 * kBar, t.now);
        t.run(20 * kBar);
        eq("2-bar clip tapped at 5.3 bars starts at bar 6", t.firstChangeOn(1), 6 * kBar);
    }

    printf("--- a playing clip is replaced at the end of its cycle ---\n");
    {
        // 2 bars x repeat 3 = a 6-bar cycle
        Timeline t;
        t.l.request(0, 111, 6 * kBar, 0);
        t.run(kBar);                        // one bar in
        t.l.request(0, 222, 1 * kBar, t.now);
        t.run(20 * kBar);
        eq("replacing a 6-bar cycle one bar in lands at bar 6", t.nthChangeOn(0, 1), 6 * kBar);
        // the new clip is one bar long, so by now it has looped on from there;
        // what must hold is that every loop since has been in phase with it
        eq("...and it has kept that boundary's phase ever since", (t.l.origin(0) - 6 * kBar) % kBar, 0);
        eq("...and it is the new scene", t.l.sceneId(0), 222);
    }
    {
        Timeline t;
        t.l.request(0, 111, 4 * kBar, 0);
        t.run(4 * kBar);                    // exactly on the boundary
        t.l.request(0, 222, 1 * kBar, t.now);
        t.run(20 * kBar);
        eq("a tap exactly on a boundary waits a whole cycle", t.nthChangeOn(0, 1), 8 * kBar);
    }

    printf("--- a fixed launch quantise is a plain grid ---\n");
    for (int bars : {1, 2, 4}) {
        Timeline t;
        t.l.setQuantise(bars * kBar);
        t.l.request(0, 111, 8 * kBar, 0);
        t.run(bars * kBar + 7);             // a few ticks past a line
        t.l.request(0, 222, 8 * kBar, t.now);
        t.run(40 * kBar);
        char name[80];
        snprintf(name, sizeof name, "quantise %d bar: swap lands on the grid", bars);
        eq(name, t.nthChangeOn(0, 1), 2ll * bars * kBar);
    }

    printf("--- tapping twice ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 4 * kBar, 0);
        t.run(kBar);
        t.l.request(0, 222, 4 * kBar, t.now);   // queue
        eq("a queued launch is pending", t.l.pendingId(0), 222);
        t.l.request(0, 222, 4 * kBar, t.now);   // tap it again
        eq("tapping it again cancels the queue", t.l.pendingId(0), Launcher::kNone);
        t.run(20 * kBar);
        eq("...so the first clip is still playing", t.l.sceneId(0), 111);
    }
    {
        Timeline t;
        t.l.request(0, 111, 4 * kBar, 0);
        t.run(kBar);
        t.l.request(0, 111, 4 * kBar, t.now);   // tap the playing clip
        eq("tapping the playing clip queues a stop", t.l.pendingId(0), Launcher::kStopId);
        t.run(20 * kBar);
        eq("...which lands at the cycle end", t.nthChangeOn(0, 1), 4 * kBar);
        eq("...and it is silent", t.l.playing(0) ? 1 : 0, 0);
    }
    {
        Timeline t;
        t.l.request(0, 111, 4 * kBar, 0);
        t.run(kBar);
        t.l.request(0, 111, 4 * kBar, t.now);   // queue the stop
        t.l.request(0, 111, 4 * kBar, t.now);   // change your mind
        eq("tapping again cancels the stop", t.l.pendingId(0), Launcher::kNone);
        t.run(20 * kBar);
        eq("...so it keeps playing", t.l.playing(0) ? 1 : 0, 1);
    }

    printf("--- an explicit cancel, which is what a double tap sends ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 4 * kBar, 0);
        t.run(kBar);
        t.l.request(0, 222, 4 * kBar, t.now);
        t.l.cancel(0);
        eq("cancel clears the queue", t.l.pendingId(0), Launcher::kNone);
        t.run(20 * kBar);
        eq("...and the playing clip is untouched", t.l.sceneId(0), 111);
    }
    {
        // The case the toggle could not handle: the first tap has already
        // landed by the time the second arrives.
        Timeline t;
        t.l.request(0, 111, 1 * kBar, 0);
        t.run(3 * kBar);
        t.l.cancel(0);
        eq("cancelling with nothing queued changes nothing", t.l.pendingId(0), Launcher::kNone);
        eq("...and does not queue a stop", t.l.playing(0) ? 1 : 0, 1);
        eq("...on the clip that was already playing", t.l.sceneId(0), 111);
    }

    printf("--- a scene column launches every track on the same tick ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 2 * kBar, 0);
        t.l.request(1, 111, 2 * kBar, 0);
        t.run(kBar);
        for (int32_t r = 0; r < 4; ++r) t.l.request(r, 222, 2 * kBar, t.now);
        t.run(20 * kBar);
        eq("track 0 of the column", t.nthChangeOn(0, 1), 2 * kBar);
        eq("track 1 of the column", t.nthChangeOn(1, 1), 2 * kBar);
        eq("track 2 was silent, so it waits for its own line", t.firstChangeOn(2), 2 * kBar);
        eq("track 3 likewise", t.firstChangeOn(3), 2 * kBar);
    }

    printf("--- stop all ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 1 * kBar, 0);
        t.l.request(1, 222, 4 * kBar, 0);
        t.run(kBar / 2);
        t.l.requestStopAll(t.now);
        t.run(20 * kBar);
        eq("a 1-bar clip stops at bar 1", t.nthChangeOn(0, 1), 1 * kBar);
        eq("a 4-bar clip stops at bar 4", t.nthChangeOn(1, 1), 4 * kBar);
        eq("nothing is playing afterwards", t.l.anyPlaying() ? 1 : 0, 0);
    }

    printf("--- origins stay independent over sixty-four cycles ---\n");
    {
        Timeline t;
        t.l.request(0, 111, 1 * kBar, 0);
        t.l.request(1, 222, 2 * kBar, 0);
        t.l.request(2, 333, 8 * kBar, 0);
        t.run(64 * 8 * kBar + 17);   // a few ticks past the line, not on it
        // After 512 bars each origin must still be an exact multiple of that
        // rack's own cycle - one tick of drift anywhere and this fails - and
        // must be the *current* cycle, not one left behind.
        eq("1-bar rack is still on a 1-bar line", t.l.origin(0) % kBar, 0);
        eq("2-bar rack is still on a 2-bar line", t.l.origin(1) % (2 * kBar), 0);
        eq("8-bar rack is still on an 8-bar line", t.l.origin(2) % (8 * kBar), 0);
        eq("1-bar rack is inside its current cycle", (t.now - t.l.origin(0)) < kBar ? 1 : 0, 1);
        eq("2-bar rack is inside its current cycle", (t.now - t.l.origin(1)) < 2 * kBar ? 1 : 0, 1);
        eq("8-bar rack is inside its current cycle", (t.now - t.l.origin(2)) < 8 * kBar ? 1 : 0, 1);
    }

    printf("--- worst case: sixteen one-bar clips, and the block never storms ---\n");
    {
        Timeline t;
        for (int32_t r = 0; r < acidulous::kRackCount; ++r) t.l.request(r, 100 + r, kBar, 0);
        // 200 bpm, 64-frame blocks: a block is about 6.4 ticks
        t.run(32 * kBar, 7);
        const double perBlock = double(t.splits) / (32.0 * kBar / 7.0);
        printf("  ok   %-52s %.2f segments per block\n", "block splits stay bounded", perBlock);
        ++checks;
        if (perBlock > 3.0) { ++failures; printf("  FAIL too many splits per block\n"); }
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
