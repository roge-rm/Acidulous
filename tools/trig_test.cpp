// What a trig is allowed to decide, and whether it decides the same twice.
//
// Three properties ride on one note now - a chance, a condition and a ratchet
// - and every one of them is asked the same question more than once: a
// ratchet's later hits land in a different block from its first, and a note
// the block has already walked past still owes an answer to the `Prev` after
// it. So the gate has to be a *pure function* of the dice, the pass and which
// note it is, never a consumed random draw. Most of what is below exists to
// catch the moment that stops being true - which is why so many checks run the
// same clip at several block sizes and demand identical output.
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <set>
#include <string>
#include <vector>

#include <sequencer/ClipPlayer.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int failures = 0;
int checks = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    if (cond) {
        printf("  ok   %-58s %s\n", what, detail.c_str());
    } else {
        ++failures;
        printf("  FAIL %-58s %s\n", what, detail.c_str());
    }
}

constexpr int32_t kBar = 4 * kPPQN; // 960

/** Every note-on and note-off, with the tick it landed on. */
struct Heard {
    struct Hit { int64_t tick; uint8_t pitch; };
    std::vector<Hit> ons, offs;

    std::vector<int64_t> onTicks(uint8_t pitch) const {
        std::vector<int64_t> out;
        for (const Hit &h : ons) if (h.pitch == pitch) out.push_back(h.tick);
        return out;
    }
    size_t countOn(uint8_t pitch) const { return onTicks(pitch).size(); }
    bool balanced() const { return ons.size() == offs.size(); }
};

/**
 * Drive the player the way the scheduler does, in blocks of [step].
 *
 * The block size is a parameter because it is the thing most likely to break
 * a wrong implementation: a gate that consumes a draw, or that reads state
 * left by the previous call, gives a different answer at 1 tick a block than
 * at a whole bar, and only one of those is ever tested by hand.
 */
void run(ClipPlayer &p, Heard &h, int64_t from, int64_t until, int64_t origin, int64_t step) {
    for (int64_t t = from; t < until; t += step) {
        const int64_t to = std::min(t + step, until);
        // The sink is handed the block's start, which is as precise as the
        // scheduler is: events are block-quantised everywhere in this engine.
        p.process(t, to, origin, [&h, t](uint8_t c, uint8_t a, uint8_t) {
            if ((c & 0xf0) == 0x90) h.ons.push_back({t, a}); else h.offs.push_back({t, a});
        });
    }
}

/** A clip of plain notes, with the trig word set per note. */
struct Build {
    Clip clip;
    explicit Build(int bars = 1) {
        clip.rev = 7;
        clip.bars = bars;
        clip.ticksPerBar = kBar;
    }
    void note(int32_t tick, int32_t len, uint8_t pitch,
              int32_t chance = 100, int32_t cond = 0, int32_t ratchet = 1) {
        ClipNote n{};
        n.tick = tick;
        n.length = len;
        n.pitch = pitch;
        n.velocity = 100;
        n.trig = packTrig(chance, cond, ratchet);
        if (cond == static_cast<int32_t>(TrigCond::Prev) || cond == static_cast<int32_t>(TrigCond::NotPrev)) {
            clip.hasPrevCond = true;
        }
        clip.notes.push_back(n);
        std::stable_sort(clip.notes.begin(), clip.notes.end(),
                         [](const ClipNote &a, const ClipNote &b) { return a.tick < b.tick; });
    }
};

/**
 * Which passes a pitch sounded on, over [0, passes) of a one-bar clip.
 *
 * The block size must **divide the bar**, and that is a statement about this
 * function rather than about the player. The sink is handed the block's start,
 * because that is the resolution the engine actually works at - so with a
 * block of 97 ticks a note at the top of pass 2 is reported at tick 1880,
 * 1880/960 is 1, and the check reads a correct alternation as a wrong one.
 * Anything that wants an awkward block size compares two runs against each
 * other instead of attributing hits to bars.
 */
std::set<int> passesHeard(const Clip &clip, uint8_t pitch, int passes, int64_t step = 96) {
    ClipPlayer p;
    p.setClip(&clip);
    Heard h;
    run(p, h, 0, static_cast<int64_t>(passes) * kBar, 0, step);
    std::set<int> out;
    for (int64_t t : h.onTicks(pitch)) out.insert(static_cast<int>(t / kBar));
    return out;
}

std::string listOf(const std::set<int> &s) {
    std::string out;
    for (int v : s) { out += std::to_string(v); out += " "; }
    return out.empty() ? "(none)" : out;
}

// --- the checks -------------------------------------------------------------

void plainNotesAreUntouched() {
    printf("- a clip that says nothing plays as it always did\n");
    Build b;
    b.note(0, 120, 36);
    b.note(kPPQN, 120, 38);
    b.note(kPPQN * 2, 120, 36);
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar * 4, 0, 64);
    // Four passes of three notes. A zeroed trig word would read as chance
    // nought and this would be silence, which is the whole reason
    // kTrigDefault is a default member initialiser.
    ok("twelve hits over four passes", h.ons.size() == 12, std::to_string(h.ons.size()));
    ok("and every one is paired", h.balanced());
}

void theEndsOfTheChance() {
    printf("- chance at nought and at a hundred\n");
    Build b;
    b.note(0, 120, 36, 0);
    b.note(kPPQN, 120, 38, 100);
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar * 200, 0, 97);
    ok("chance 0 never fires", h.countOn(36) == 0, std::to_string(h.countOn(36)));
    ok("chance 100 always fires", h.countOn(38) == 200, std::to_string(h.countOn(38)));
    ok("and the offs balance", h.balanced());
}

void aHalfChanceIsAHalfChance() {
    printf("- chance 50 over a thousand passes\n");
    Build b;
    b.note(0, 120, 36, 50);
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar * 1000, 0, 240);
    const size_t n = h.countOn(36);
    // A hash that is correlated with the pass parity gives 0, 500 or 1000 on
    // the nose; a real one lands somewhere in the middle and not exactly.
    ok("lands between 400 and 600", n > 400 && n < 600, std::to_string(n));
    ok("and is not suspiciously exact", n != 500, std::to_string(n));
}

void theSameSeedPlaysTheSameBar() {
    printf("- the same clip, twice, and at four block sizes\n");
    Build b;
    b.note(0, 100, 36, 40);
    b.note(kPPQN, 100, 38, 60);
    b.note(kPPQN * 2, 100, 40, 25);
    b.clip.seed = 12345;

    std::vector<int64_t> reference;
    bool first = true;
    bool same = true;
    for (int64_t step : {1, 7, 97, kBar}) {
        ClipPlayer p;
        p.setClip(&b.clip);
        Heard h;
        run(p, h, 0, kBar * 40, 0, step);
        std::vector<int64_t> got;
        for (const Heard::Hit &x : h.ons) got.push_back(x.pitch);
        if (first) { reference = got; first = false; }
        else if (got != reference) same = false;
    }
    ok("identical at 1, 7, 97 and 960 ticks a block", same,
       std::to_string(reference.size()) + " hits");
}

void aDifferentSeedPlaysADifferentBar() {
    printf("- the seed is actually mixed in\n");
    Build a;
    a.note(0, 100, 36, 50);
    a.clip.seed = 1;
    Build b;
    b.note(0, 100, 36, 50);
    b.clip.seed = 2;
    const std::set<int> one = passesHeard(a.clip, 36, 60);
    const std::set<int> two = passesHeard(b.clip, 36, 60);
    ok("two seeds, two patterns", one != two,
       std::to_string(one.size()) + " vs " + std::to_string(two.size()) + " hits");
}

void identityIsWhereTheNoteIs() {
    printf("- inserting a note does not reroll the others\n");
    Build a;
    a.note(kPPQN, 100, 38, 50);
    a.note(kPPQN * 2, 100, 40, 50);
    Build b;
    b.note(0, 100, 36, 50); // a new note at the top of the list
    b.note(kPPQN, 100, 38, 50);
    b.note(kPPQN * 2, 100, 40, 50);
    // Hashing the note's index would shift every note after the insertion.
    ok("pitch 38 keeps its pattern", passesHeard(a.clip, 38, 60) == passesHeard(b.clip, 38, 60));
    ok("pitch 40 keeps its pattern", passesHeard(a.clip, 40, 60) == passesHeard(b.clip, 40, 60));
}

void everyConditionHasItsOwnPeriod() {
    printf("- the Nth of every M, as an exact set of passes\n");
    struct Case { int n, m; std::set<int> want; };
    const std::vector<Case> cases = {
        {1, 2, {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22}},
        {2, 2, {1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23}},
        {1, 3, {0, 3, 6, 9, 12, 15, 18, 21}},
        {3, 3, {2, 5, 8, 11, 14, 17, 20, 23}},
        {1, 4, {0, 4, 8, 12, 16, 20}},
        {3, 4, {2, 6, 10, 14, 18, 22}},
        {4, 4, {3, 7, 11, 15, 19, 23}},
        {5, 8, {4, 12, 20}},
    };
    for (const Case &c : cases) {
        Build b;
        b.note(0, 100, 36, 100, nthCond(c.n, c.m));
        const std::set<int> got = passesHeard(b.clip, 36, 24);
        char name[64];
        std::snprintf(name, sizeof(name), "%d:%d fires on exactly the right passes", c.n, c.m);
        ok(name, got == c.want, listOf(got));
    }
    // And the codes themselves: 35 of them, none colliding with the named ones.
    std::set<int32_t> codes;
    for (int m = 2; m <= 8; ++m) for (int n = 1; n <= m; ++n) codes.insert(nthCond(n, m));
    ok("the Nth family is 35 distinct codes", codes.size() == 35, std::to_string(codes.size()));
    ok("and none of them is a named condition",
       *codes.begin() >= static_cast<int32_t>(TrigCond::NthBase));
    // Round trip through the packing, which is where a bit budget goes wrong.
    bool roundTrips = true;
    for (int m = 2; m <= 8; ++m) {
        for (int n = 1; n <= m; ++n) {
            ClipNote note{};
            note.trig = packTrig(73, nthCond(n, m), 5);
            int32_t gn = 0, gm = 0;
            nthOf(note.condition(), gn, gm);
            if (note.chance() != 73 || note.ratchet() != 5 || gn != n || gm != m) roundTrips = false;
        }
    }
    ok("chance, condition and ratchet survive the packing", roundTrips);
}

void aOneShotCountsItsOwnPasses() {
    printf("- a one-bar one-shot inside a four-bar iteration\n");
    // The scheduler advances the origin by the *scene* iteration, and a
    // OneShot never leaves k = 0. `base / len` would step the pass index by
    // four each time and 1:2 would be true always or never.
    Build b;
    b.clip.playMode = PlayMode::OneShot;
    b.note(0, 100, 36, 100, nthCond(1, 2));
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    const int64_t iter = kBar * 4;
    for (int i = 0; i < 8; ++i) run(p, h, i * iter, (i + 1) * iter, i * iter, 97);
    ok("sounds on four of eight iterations", h.countOn(36) == 4, std::to_string(h.countOn(36)));
    std::set<int> which;
    for (int64_t t : h.onTicks(36)) which.insert(static_cast<int>(t / iter));
    ok("and alternates", which == std::set<int>({0, 2, 4, 6}), listOf(which));
}

void aClipThatDoesNotDivideItsScene() {
    printf("- a three-bar clip in a four-bar scene\n");
    // base/len returns the same index twice here, so a 1:2 note fires on two
    // consecutive passes and then skips one.
    Build b(3);
    b.note(0, 100, 36, 100, nthCond(1, 2));
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    const int64_t iter = kBar * 4;
    for (int i = 0; i < 12; ++i) run(p, h, i * iter, (i + 1) * iter, i * iter, 97);
    // Whatever the passes work out to, no two consecutive hits may be one
    // clip-length apart: that is what firing on the same index twice means.
    const std::vector<int64_t> ticks = h.onTicks(36);
    bool consecutive = false;
    for (size_t i = 1; i < ticks.size(); ++i) {
        if (ticks[i] - ticks[i - 1] <= b.clip.lengthTicks()) consecutive = true;
    }
    ok("never fires on two passes in a row", !consecutive, std::to_string(ticks.size()) + " hits");
}

void aPassSplitAcrossBlocksCountsOnce() {
    printf("- one pass, many blocks\n");
    Build b;
    b.note(0, 100, 36, 100, nthCond(1, 2));
    // Six passes at a tick a block is the finest cut there is.
    ok("still alternates at one tick a block",
       passesHeard(b.clip, 36, 6, 1) == std::set<int>({0, 2, 4}));
    ok("and at a whole bar a block",
       passesHeard(b.clip, 36, 6, kBar) == std::set<int>({0, 2, 4}));
}

void freeRollsVaryAndResetRewinds() {
    printf("- free mode\n");
    Build b;
    b.note(0, 100, 36, 50);
    b.clip.freeRoll = true;
    ClipPlayer one, two;
    one.setClip(&b.clip);
    two.setClip(&b.clip);
    Heard a, c;
    run(one, a, 0, kBar * 200, 0, 97);
    run(two, c, 0, kBar * 200, 0, 97);
    // Two players started together walk the same LCG, so they agree; what
    // free buys is that a *second playing* differs, which is the next check.
    ok("two players in step agree", a.onTicks(36) == c.onTicks(36));
    Heard again;
    run(one, again, kBar * 200, kBar * 400, 0, 97);
    std::vector<int64_t> shifted;
    for (int64_t t : again.onTicks(36)) shifted.push_back(t - kBar * 200);
    ok("a second playing differs", shifted != a.onTicks(36),
       std::to_string(shifted.size()) + " vs " + std::to_string(a.onTicks(36).size()));
    one.reset();
    Heard afterReset;
    run(one, afterReset, 0, kBar * 200, 0, 97);
    ok("and after a reset it plays the first one again", afterReset.onTicks(36) == a.onTicks(36));
}

void aFreeRatchetIsAllOrNothing() {
    printf("- a free, half-chance, four-way ratchet at a tick a block\n");
    Build b;
    b.note(0, 240, 36, 50, 0, 4);
    b.clip.freeRoll = true;
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar * 60, 0, 1);
    // The check that fails only in free mode, and only if the seed word is
    // re-drawn per note or per block instead of once per pass: a note whose
    // gate is asked four times in four different blocks would answer
    // differently and produce one, two or three of its four hits.
    int perPass[64] = {};
    for (int64_t t : h.onTicks(36)) ++perPass[t / kBar];
    bool clean = true;
    for (int i = 0; i < 60; ++i) if (perPass[i] != 0 && perPass[i] != 4) clean = false;
    ok("every pass gives four hits or none", clean);
    ok("and some of each happened", h.countOn(36) > 0 && h.countOn(36) < 240u,
       std::to_string(h.countOn(36)));
}

void aGatedNoteDoesNotCutTheNoteBeforeIt() {
    printf("- the gate sits above releaseIfSounding, not below it\n");
    Build b;
    b.note(0, kBar, 60);                 // a long note, certain
    b.note(kPPQN * 2, 100, 60, 0);       // same pitch, never plays
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN * 3, 0, 32);
    // Gated below releaseIfSounding, the second note would emit an off for
    // the first at tick 480 - and leave onCount and offCount unequal for the
    // rest of the session, which is the stuck-note diagnostic.
    ok("the long note is still sounding", h.offs.empty(), std::to_string(h.offs.size()) + " offs");
    ok("and nothing was booked for the note that did not play", h.ons.size() == 1);
}

void ratchetsSubdivideTheirOwnNote() {
    printf("- ratchets\n");
    Build b;
    b.note(0, 240, 36, 100, 0, 4);
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar, 0, 1);
    const std::vector<int64_t> ticks = h.onTicks(36);
    ok("four hits", ticks.size() == 4, std::to_string(ticks.size()));
    ok("at 0, 60, 120 and 180",
       ticks == std::vector<int64_t>({0, 60, 120, 180}), listOf({}));

    // The same under every block size, which is what the sub-hit walk is for.
    bool stable = true;
    for (int64_t step : {1, 7, 64, 961}) {
        ClipPlayer q;
        q.setClip(&b.clip);
        Heard g;
        run(q, g, 0, kBar * 4, 0, step);
        if (g.countOn(36) != 16) stable = false;
    }
    ok("sixteen hits over four passes at every block size", stable);

    // A note near the end of the clip: the sub-hits must stay in their pass.
    Build late;
    late.note(kBar - 10, 120, 38, 100, 0, 4);
    ClipPlayer r;
    r.setClip(&late.clip);
    Heard lh;
    // Past the end of the fourth pass: the last sub-hit's off is due a few
    // ticks after the note, and a run that stops on the bar line simply never
    // reaches the block that would have flushed it.
    run(r, lh, 0, kBar * 4 + 100, 0, 7);
    ok("a ratchet at the end of the clip stays inside it",
       lh.countOn(38) == 4 * 4, std::to_string(lh.countOn(38)));
    ok("and every hit is paired", lh.balanced(),
       std::to_string(lh.ons.size()) + " on, " + std::to_string(lh.offs.size()) + " off");

    // Eight ratchets of a four-tick note: the step clamps to one.
    Build tiny;
    tiny.note(0, 4, 40, 100, 0, 8);
    ClipPlayer t;
    t.setClip(&tiny.clip);
    Heard th;
    run(t, th, 0, kBar, 0, 1);
    ok("eight hits out of a four-tick note", th.countOn(40) == 8, std::to_string(th.countOn(40)));
    ok("and they balance", th.balanced());
}

void prevFollowsTheTrigBeforeIt() {
    printf("- prev and not-prev\n");
    Build never;
    never.note(0, 100, 36, 0);
    never.note(kPPQN, 100, 38, 100, static_cast<int32_t>(TrigCond::Prev));
    never.note(kPPQN * 2, 100, 40, 100, static_cast<int32_t>(TrigCond::NotPrev));
    ClipPlayer p;
    p.setClip(&never.clip);
    Heard h;
    run(p, h, 0, kBar * 20, 0, 97);
    ok("after a trig that did not play, prev is silent", h.countOn(38) == 0);
    ok("and not-prev sounds every pass", h.countOn(40) == 20, std::to_string(h.countOn(40)));

    Build always;
    always.note(0, 100, 36, 100, static_cast<int32_t>(TrigCond::Always));
    always.note(kPPQN, 100, 38, 100, static_cast<int32_t>(TrigCond::Prev));
    // An Always note is not in the chain at all, so prev sees the pass's
    // opening false rather than "the note before me played".
    ClipPlayer q;
    q.setClip(&always.clip);
    Heard qh;
    run(q, qh, 0, kBar * 8, 0, 97);
    ok("an unconditional note is not in the chain", qh.countOn(38) == 0);

    // The interesting one: a half-chance note, then its two followers.
    Build half;
    half.note(0, 100, 36, 50);
    half.note(kPPQN, 100, 38, 100, static_cast<int32_t>(TrigCond::Prev));
    half.note(kPPQN * 2, 100, 40, 100, static_cast<int32_t>(TrigCond::NotPrev));
    for (int64_t step : {1, 97, 240, kBar}) {
        ClipPlayer r;
        r.setClip(&half.clip);
        Heard rh;
        run(r, rh, 0, kBar * 200, 0, step);
        const size_t fired = rh.countOn(36), yes = rh.countOn(38), no = rh.countOn(40);
        char name[80];
        std::snprintf(name, sizeof(name), "at %lld ticks a block, prev == the roll", (long long)step);
        ok(name, yes == fired, std::to_string(yes) + " vs " + std::to_string(fired));
        std::snprintf(name, sizeof(name), "and not-prev is its exact complement");
        ok(name, yes + no == 200, std::to_string(yes + no));
    }
}

void fillIsOffUntilSomebodyHoldsIt() {
    printf("- fill\n");
    Build b;
    b.note(0, 100, 36, 100, static_cast<int32_t>(TrigCond::Fill));
    b.note(kPPQN, 100, 38, 100, static_cast<int32_t>(TrigCond::NotFill));
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard off;
    run(p, off, 0, kBar * 10, 0, 97);
    // The render case: nobody is holding anything, so a fill trig is silent
    // and a not-fill trig is ordinary. Deterministic, which is the point.
    ok("with fill up, a fill trig never sounds", off.countOn(36) == 0);
    ok("and a not-fill trig always does", off.countOn(38) == 10, std::to_string(off.countOn(38)));

    p.setFill(true);
    Heard on;
    run(p, on, kBar * 10, kBar * 20, 0, 97);
    ok("held, the fill trig sounds", on.countOn(36) == 10, std::to_string(on.countOn(36)));
    ok("and the not-fill trig stops", on.countOn(38) == 0, std::to_string(on.countOn(38)));
}

void nothingIsLeftHanging() {
    printf("- the whole matrix, and the note-off balance\n");
    // Every condition against three chances against eight ratchets, cut at an
    // awkward block size. A stray off from a gated note shows up here as an
    // imbalance, and nothing else in the suite would catch it.
    int cases = 0;
    bool balanced = true;
    std::vector<int32_t> conds = {0,
                                  static_cast<int32_t>(TrigCond::Prev),
                                  static_cast<int32_t>(TrigCond::NotPrev),
                                  static_cast<int32_t>(TrigCond::Fill),
                                  static_cast<int32_t>(TrigCond::NotFill)};
    for (int m = 2; m <= 8; ++m) for (int n = 1; n <= m; ++n) conds.push_back(nthCond(n, m));
    for (int32_t cond : conds) {
        for (int32_t chance : {0, 50, 100}) {
            for (int32_t rat = 1; rat <= 8; ++rat) {
                Build b;
                b.note(0, 200, 36, chance, cond, rat);
                b.note(kPPQN, 200, 38, 100); // an ordinary neighbour
                ClipPlayer p;
                p.setClip(&b.clip);
                Heard h;
                run(p, h, 0, kBar * 12, 0, 53);
                p.allNotesOff([&h](uint8_t c, uint8_t a, uint8_t) {
                    if ((c & 0xf0) == 0x90) h.ons.push_back({0, a}); else h.offs.push_back({0, a});
                });
                if (!h.balanced()) balanced = false;
                ++cases;
            }
        }
    }
    ok("every on has an off, over the whole matrix", balanced, std::to_string(cases) + " cases");
}

/**
 * The decision table, for the Kotlin side to be checked against.
 *
 * The rule exists twice - here and in `model/Trig.kt` - because a MIDI export
 * is Kotlin walking the passes itself and has to write the file the app
 * plays. Two copies of a rule drift, so this prints what this one decides and
 * a unit test asserts the other agrees. A drift then fails a test rather than
 * a listen.
 */
void printTable() {
    printf("# chance cond pass tick pitch seed prev -> plays\n");
    const int32_t conds[] = {0, 1, 2, 5, 6, 7, 8, 9, 10, 11, 12, 20, 39};
    for (int32_t cond : conds) {
        for (int32_t chance : {0, 17, 50, 83, 100}) {
            for (int32_t pass = 0; pass < 6; ++pass) {
                for (int32_t seed : {0, 7}) {
                    ClipNote n{};
                    n.tick = 120;
                    n.length = 100;
                    n.pitch = 60;
                    n.velocity = 100;
                    n.trig = packTrig(chance, cond, 1);
                    Clip clip;
                    clip.bars = 1;
                    clip.ticksPerBar = kBar;
                    clip.seed = seed;
                    clip.notes.push_back(n);
                    // `Prev` and `NotPrev` are asked both ways, since their
                    // answer is about the note before them and not about them.
                    for (int prev = 0; prev < 2; ++prev) {
                        // Driven through the player rather than by reaching
                        // into the gate: what the table records is what a clip
                        // actually does. The note at tick 0 is the one the
                        // Prev conditions are asking about.
                        Clip two;
                        two.bars = 1;
                        two.ticksPerBar = kBar;
                        two.seed = seed;
                        two.hasPrevCond = true;
                        ClipNote first{};
                        first.tick = 0;
                        first.length = 10;
                        first.pitch = 40;
                        first.velocity = 100;
                        // The leader has to be *in the chain*, and a certain
                        // unconditional note is not - `conditional()` is
                        // false for it, so it decides nothing and the Prev
                        // after it sees the pass's opening `false` whatever
                        // the leader did. `NotPrev` at the top of a pass
                        // always plays and `Prev` there never does, and both
                        // are conditional, so the pair sets prevPlayed
                        // exactly.
                        first.trig = packTrig(100,
                                              static_cast<int32_t>(prev != 0 ? TrigCond::NotPrev : TrigCond::Prev), 1);
                        two.notes.push_back(first);
                        two.notes.push_back(n);
                        ClipPlayer q;
                        q.setClip(&two);
                        // From the top, not from this pass: the player counts
                        // its own passes, so a run that begins at pass four
                        // has a player that thinks it is on pass nought. That
                        // is the counter doing its job, and it made the first
                        // version of this table a record of pass 0 six times.
                        Heard g;
                        run(q, g, 0, static_cast<int64_t>(pass + 1) * kBar, 0, 1);
                        int fired = 0;
                        for (int64_t t : g.onTicks(60)) if (t / kBar == pass) ++fired;
                        printf("%d %d %d %d %d %d %d %d\n", chance, cond, pass, n.tick, n.pitch, seed, prev,
                               fired > 0 ? 1 : 0);
                    }
                }
            }
        }
    }
}

} // namespace

int main(int argc, char **argv) {
    if (argc > 1 && std::string(argv[1]) == "--table") {
        printTable();
        return 0;
    }
    plainNotesAreUntouched();
    theEndsOfTheChance();
    aHalfChanceIsAHalfChance();
    theSameSeedPlaysTheSameBar();
    aDifferentSeedPlaysADifferentBar();
    identityIsWhereTheNoteIs();
    everyConditionHasItsOwnPeriod();
    aOneShotCountsItsOwnPasses();
    aClipThatDoesNotDivideItsScene();
    aPassSplitAcrossBlocksCountsOnce();
    freeRollsVaryAndResetRewinds();
    aFreeRatchetIsAllOrNothing();
    aGatedNoteDoesNotCutTheNoteBeforeIt();
    ratchetsSubdivideTheirOwnNote();
    prevFollowsTheTrigBeforeIt();
    fillIsOffUntilSomebodyHoldsIt();
    nothingIsLeftHanging();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
