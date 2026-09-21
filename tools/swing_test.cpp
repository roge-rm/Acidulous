// Swing: the arithmetic, and the two properties everything else rests on.
//
// A swing that is not monotonic reorders notes that are a tick apart, and one
// that is not the identity at fifty percent changes a straight song the day
// the setting is added. Both are cheap to assert over the whole of tick space
// and neither is visible by ear until somebody has written a part.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <sequencer/Clip.h>
#include <sequencer/ClipPlayer.h>
#include <sequencer/Swing.h>

using namespace acidulous;
using namespace acidulous::seq;
using acidulous::kPPQN;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-50s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int64_t kBar = 4 * kPPQN;

void straightIsTheIdentity() {
    printf("- fifty percent changes nothing\n");
    bool same = true;
    int64_t firstBad = -1;
    for (int64_t t = 0; t < kBar * 4; ++t) {
        if (Swing::at(t, 50.0f, Swing::kSixteenths) != t) { same = false; if (firstBad < 0) firstBad = t; }
        if (Swing::at(t, 0.0f, Swing::kSixteenths) != t) { same = false; if (firstBad < 0) firstBad = t; }
    }
    ok("every tick of four bars is untouched", same,
       firstBad < 0 ? "" : std::string("first moved at ") + std::to_string(firstBad));
}

void itIsMonotonic() {
    printf("- no note ever overtakes another\n");
    for (float pct : {55.0f, 60.0f, Swing::kTriplet, 70.0f, 75.0f}) {
        for (int64_t pair : {Swing::kSixteenths, Swing::kEighths}) {
            bool rising = true;
            int64_t at = -1;
            int64_t prev = Swing::at(0, pct, pair);
            for (int64_t t = 1; t < kBar * 2; ++t) {
                const int64_t now = Swing::at(t, pct, pair);
                if (now < prev) { rising = false; if (at < 0) at = t; }
                prev = now;
            }
            char label[72];
            snprintf(label, sizeof(label), "%.1f%% over %lld ticks never goes back", pct, static_cast<long long>(pair));
            ok(label, rising, at < 0 ? "" : std::string("fell at tick ") + std::to_string(at));
        }
    }
}

void itStaysInsideItsOwnPair() {
    printf("- a pair is mapped onto itself\n");
    // If a tick could leave its pair, a note at the end of a bar would move
    // into the next one - or out of the clip entirely.
    bool inside = true;
    for (float pct : {55.0f, Swing::kTriplet, 75.0f}) {
        for (int64_t t = 0; t < kBar * 2; ++t) {
            const int64_t out = Swing::at(t, pct, Swing::kSixteenths);
            const int64_t base = t - t % Swing::kSixteenths;
            if (out < base || out >= base + Swing::kSixteenths) inside = false;
        }
    }
    ok("nothing leaves the pair it started in", inside);
}

void theOffbeatIsLate() {
    printf("- what it is for\n");
    const int64_t pair = Swing::kSixteenths;
    // The downbeats of each pair do not move; the offbeats do.
    ok("the first sixteenth of a pair does not move",
       Swing::at(0, Swing::kTriplet, pair) == 0 && Swing::at(pair, Swing::kTriplet, pair) == pair);
    const int64_t off = Swing::at(pair / 2, Swing::kTriplet, pair);
    ok("at triplet the second sits two thirds of the way",
       std::llabs(off - (pair * 2 / 3)) <= 1,
       std::string("landed at ") + std::to_string(off) + " of " + std::to_string(pair));
    const int64_t more = Swing::at(pair / 2, 75.0f, pair);
    ok("and further at seventy-five", more > off,
       std::string("triplet ") + std::to_string(off) + ", far " + std::to_string(more));
}

void theWayBack() {
    printf("- the inverse, which is what recording needs\n");
    for (float pct : {55.0f, 60.0f, Swing::kTriplet, 70.0f, 75.0f}) {
        int64_t worst = 0;
        int64_t at = 0;
        for (int64_t t = 0; t < kBar * 2; ++t) {
            const int64_t round = Swing::from(Swing::at(t, pct, Swing::kSixteenths), pct, Swing::kSixteenths);
            const int64_t err = std::llabs(round - t);
            if (err > worst) { worst = err; at = t; }
        }
        // Not exact, and cannot be: the map is from ticks to ticks and the
        // compressed half of the pair has fewer of them to land on, so two
        // straight ticks can share a swung one. A tick at 240 PPQN is a fifth
        // of a millisecond at 120 bpm; three of them is inaudible and is the
        // price of keeping the document in whole ticks.
        char label[72];
        snprintf(label, sizeof(label), "%.1f%% round trips to within three ticks", pct);
        ok(label, worst <= 3, std::string("worst ") + std::to_string(worst) + " at tick " + std::to_string(at));
    }
}

void aPartPlayedInStaysWhereItWasPlayed() {
    printf("- the trap the inverse exists for\n");
    // Somebody plays a straight sixteenth line against a swung song. What
    // arrives is swung, because that is what they heard and played along to.
    // Stored as it arrives and played back, it is swung twice.
    const float pct = Swing::kTriplet;
    const int64_t pair = Swing::kSixteenths;
    int64_t worstTwice = 0, worstOnce = 0;
    for (int64_t step = 0; step < 32; ++step) {
        const int64_t wrote = step * (pair / 2);        // on the grid, as played
        const int64_t heard = Swing::at(wrote, pct, pair); // what they played along to
        // Kept as it arrived:
        worstTwice = std::max<int64_t>(worstTwice, std::llabs(Swing::at(heard, pct, pair) - heard));
        // Put back into straight time first:
        const int64_t stored = Swing::from(heard, pct, pair);
        worstOnce = std::max<int64_t>(worstOnce, std::llabs(Swing::at(stored, pct, pair) - heard));
    }
    ok("stored raw, it drifts", worstTwice > 8,
       std::string("up to ") + std::to_string(worstTwice) + " ticks further late");
    ok("un-swung on the way in, it lands where it was played", worstOnce <= 3,
       std::string("within ") + std::to_string(worstOnce) + " ticks");
}

// --- the player, which is where the map is actually used ---------------------------

/**
 * A straight sixteenth line, played through the player at a swing setting.
 *
 * The arithmetic above proves the map; this proves the *wiring* - that the
 * player asks for it, applies it to the clip-relative tick, and still fires
 * every note exactly once.
 */
std::vector<int64_t> lineAt(float percent, int64_t pair, int64_t step = 1) {
    Clip clip;
    clip.rev = 1;
    clip.bars = 1;
    clip.ticksPerBar = static_cast<int32_t>(kBar);
    for (int i = 0; i < 16; ++i) {
        ClipNote n{};
        n.tick = i * (kPPQN / 4);
        n.length = kPPQN / 8;
        n.pitch = 60;
        n.velocity = 100;
        n.trig = packTrig(100, 0, 1); // certain, unconditional, no ratchet - trig 0 is a chance of nought
        clip.notes.push_back(n);
    }
    ClipPlayer p;
    p.setClip(&clip);
    p.setSwing(percent, pair);
    std::vector<int64_t> ons;
    for (int64_t t = 0; t < kBar; t += step) {
        const int64_t to = std::min(t + step, kBar);
        p.process(t, to, 0, [&ons, t](uint8_t c, uint8_t, uint8_t) {
            if ((c & 0xf0) == 0x90) ons.push_back(t);
        });
    }
    return ons;
}

void thePlayerSwingsTheLine() {
    printf("- a sixteenth line through the player\n");
    const auto straight = lineAt(Swing::kStraight, Swing::kSixteenths);
    ok("straight: sixteen notes on the sixteenths", straight.size() == 16,
       std::string("got ") + std::to_string(straight.size()));
    bool onGrid = true;
    for (size_t i = 0; i < straight.size(); ++i) {
        if (straight[i] != static_cast<int64_t>(i) * (kPPQN / 4)) onGrid = false;
    }
    ok("and every one where it was written", onGrid);

    const auto swung = lineAt(Swing::kTriplet, Swing::kSixteenths);
    ok("swung: still sixteen notes, none lost or doubled", swung.size() == 16,
       std::string("got ") + std::to_string(swung.size()));
    // The downbeats of each pair have not moved; the offbeats have.
    bool evensHeld = true, oddsMoved = true;
    for (size_t i = 0; i < swung.size() && i < straight.size(); ++i) {
        if (i % 2 == 0) { if (swung[i] != straight[i]) evensHeld = false; }
        else if (swung[i] <= straight[i]) oddsMoved = false;
    }
    ok("the downbeats are where they were", evensHeld);
    ok("and the offbeats are later", oddsMoved,
       std::string("first offbeat ") + std::to_string(straight.size() > 1 ? swung[1] : -1) +
           " against " + std::to_string(straight.size() > 1 ? straight[1] : -1));
}

void theBlockSizeDoesNotMatter() {
    printf("- and the same however the blocks fall\n");
    // The one thing most likely to break a wrong implementation: a note that
    // is swung past the end of the block it was scanned in.
    const auto fine = lineAt(Swing::kTriplet, Swing::kSixteenths, 1);
    for (int64_t step : {4, 16, 64, 240}) {
        const auto coarse = lineAt(Swing::kTriplet, Swing::kSixteenths, step);
        char label[64];
        snprintf(label, sizeof(label), "%lld ticks a block: sixteen notes", static_cast<long long>(step));
        ok(label, coarse.size() == fine.size(), std::string("got ") + std::to_string(coarse.size()));
    }
}

} // namespace

int main() {
    printf("swing\n");
    straightIsTheIdentity();
    itIsMonotonic();
    itStaysInsideItsOwnPair();
    theOffbeatIsLate();
    theWayBack();
    aPartPlayedInStaysWhereItWasPlayed();
    thePlayerSwingsTheLine();
    theBlockSizeDoesNotMatter();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
