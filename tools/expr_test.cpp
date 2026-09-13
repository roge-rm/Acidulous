// Does a note's expression play back as the note's, and only while it sounds?
//
// The half that matters is ownership, as it was for M38: a curve belongs to
// one note, so a second note sounding at the same time must be untouched by
// it, and the moment a note ends its curve must stop being read.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <sequencer/ClipPlayer.h>

using namespace acidulous;
using namespace acidulous::seq;
namespace { int failures = 0; int checks = 0; }

static void ok(const char *what, bool cond, const char *detail = "") {
    ++checks;
    if (cond) { printf("  ok   %-56s %s\n", what, detail); }
    else { ++failures; printf("  FAIL %-56s %s\n", what, detail); }
}

static void near(const char *what, float got, float want, float tol = 1e-4f) {
    ++checks;
    if (std::fabs(got - want) <= tol) printf("  ok   %-56s %.5f\n", what, got);
    else { ++failures; printf("  FAIL %-56s got %.5f want %.5f\n", what, got, want); }
}

constexpr int32_t kBar = 4 * kPPQN; // 960

/** What the rack would have been told, in the order it was told. */
struct Heard {
    struct Item { uint8_t note; int32_t kind; float value; };
    std::vector<Item> items;
    std::vector<int> ons, offs;

    float last(uint8_t note, int32_t kind, float fallback = -1.0f) const {
        float v = fallback;
        for (const Item &i : items) if (i.note == note && i.kind == kind) v = i.value;
        return v;
    }
    int count(uint8_t note, int32_t kind) const {
        int n = 0;
        for (const Item &i : items) if (i.note == note && i.kind == kind) ++n;
        return n;
    }
    void clear() { items.clear(); ons.clear(); offs.clear(); }
};

/** Run the player over [0, until) in blocks, as the scheduler does. */
static void run(ClipPlayer &p, Heard &h, int64_t from, int64_t until, int64_t origin, int64_t step = 2) {
    for (int64_t t = from; t < until; t += step) {
        const int64_t to = t + step;
        p.process(t, to, origin, [&h](uint8_t c, uint8_t a, uint8_t) {
            if ((c & 0xf0) == 0x90) h.ons.push_back(a); else h.offs.push_back(a);
        });
        p.processExpression(to, [&h](uint8_t n, int32_t k, float v) { h.items.push_back({n, k, v}); });
    }
}

/** A clip of one note per entry, each with its own points. */
struct Build {
    Clip clip;
    Build(int bars = 1) { clip.rev = 7; clip.bars = bars; clip.ticksPerBar = kBar; }
    void note(int32_t tick, int32_t len, uint8_t pitch,
              std::vector<ExprPoint> points = {}) {
        ClipNote n{};
        n.tick = tick; n.length = len; n.pitch = pitch; n.velocity = 100;
        n.exprFirst = static_cast<int32_t>(clip.expr.size());
        n.exprCount = static_cast<int32_t>(points.size());
        for (const ExprPoint &p : points) clip.expr.push_back(p);
        std::stable_sort(clip.expr.begin() + n.exprFirst, clip.expr.end(),
                         [](const ExprPoint &a, const ExprPoint &b) {
                             return a.kind != b.kind ? a.kind < b.kind : a.tick < b.tick;
                         });
        clip.notes.push_back(n);
    }
};

static void oneNoteGlides() {
    printf("- one note, a bend from centre to a tone up over a beat\n");
    Build b;
    // 0.5 is centred; +2 semitones of 48 full-scale is 0.5 + 2/96.
    b.note(0, kBar, 60, {{0, 0, 0.5f}, {kPPQN, 0, exprBendTo01(2.0f)}});
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN / 2, 0);
    near("halfway through the glide", exprBendFrom01(h.last(60, 0)), 1.0f, 0.05f);
    run(p, h, kPPQN / 2, kPPQN + 8, 0);
    near("at the end of the glide", exprBendFrom01(h.last(60, 0)), 2.0f, 0.02f);
    run(p, h, kPPQN + 8, kPPQN * 2, 0);
    near("held flat past the last point", exprBendFrom01(h.last(60, 0)), 2.0f, 0.02f);
}

static void curvesAreTheNotesOwn() {
    printf("- two notes at once, one of them moved\n");
    Build b;
    b.note(0, kBar, 60, {{0, 1, 0.0f}, {kPPQN * 2, 1, 1.0f}});
    b.note(0, kBar, 64); // no expression at all
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN * 2 + 8, 0);
    near("the pressed note arrives at full pressure", h.last(60, 1), 1.0f, 0.02f);
    ok("the note beside it is never addressed", h.count(64, 0) == 0 && h.count(64, 1) == 0 &&
                                                    h.count(64, 2) == 0);
    ok("both notes did sound", h.ons.size() == 2, "so the silence above is not silence of the whole clip");
}

static void stopsWithTheNote() {
    printf("- a curve that outlives its note\n");
    Build b;
    // The note is a beat long; the curve goes on for two.
    b.note(0, kPPQN, 60, {{0, 2, 0.0f}, {kPPQN * 2, 2, 1.0f}});
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN, 0);
    const float atEnd = h.last(60, 2);
    ok("it was being read while the note sounded", atEnd > 0.4f && atEnd < 0.6f);
    const int before = h.count(60, 2);
    run(p, h, kPPQN, kPPQN * 2, 0);
    ok("and not one point after the note ended", h.count(60, 2) == before);
}

static void everyPassStartsAgain() {
    printf("- the clip loops\n");
    Build b;
    b.note(0, kPPQN / 2, 60, {{0, 1, 0.2f}, {kPPQN / 2, 1, 0.9f}});
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kBar, 0);
    near("the first pass ends high", h.last(60, 1), 0.9f, 0.05f);
    h.clear();
    run(p, h, kBar, kBar + 8, 0); // second pass, just after the note restarts
    near("the second pass starts low again", h.last(60, 1), 0.2f, 0.05f);
}

static void threeAtOnce() {
    printf("- bend, pressure and slide on one note\n");
    Build b;
    b.note(0, kBar, 60, {{0, 2, 0.25f}, {0, 0, exprBendTo01(-12.0f)}, {0, 1, 0.75f}});
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, 16, 0);
    near("bend, an octave down", exprBendFrom01(h.last(60, 0)), -12.0f, 0.02f);
    near("pressure", h.last(60, 1), 0.75f);
    near("slide", h.last(60, 2), 0.25f);
    ok("each was sent once, not once a block", h.count(60, 0) == 1 && h.count(60, 1) == 1 &&
                                                   h.count(60, 2) == 1,
       "a flat curve costs one call");
}

static void aSwappedClipLetsGo() {
    printf("- the clip is swapped while a note sounds\n");
    Build b;
    b.note(0, kBar, 60, {{0, 1, 0.0f}, {kBar, 1, 1.0f}});
    Build other;
    other.clip.rev = 8;
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN, 0);
    const int before = h.count(60, 1);
    ok("it was reading the curve", before > 0);
    p.setClip(&other.clip);
    run(p, h, kPPQN, kPPQN * 2, 0);
    ok("and stops the moment the clip under it changes", h.count(60, 1) == before,
       "the points belonged to the clip on its way to the retirer");
    run(p, h, kPPQN * 2, kBar + 8, 0);
    ok("but the note-off it owed still arrives", h.offs.size() == 1 && h.offs[0] == 60,
       "a swap takes the curves and leaves the note");
}

static void malformedRangesAreIgnored() {
    printf("- a range that points outside the array\n");
    Build b;
    b.note(0, kBar, 60);
    b.clip.notes[0].exprFirst = 5; // nothing there
    b.clip.notes[0].exprCount = 3;
    ClipPlayer p;
    p.setClip(&b.clip);
    Heard h;
    run(p, h, 0, kPPQN, 0);
    ok("nothing is read and nothing crashes", h.items.empty());
}

int main() {
    oneNoteGlides();
    curvesAreTheNotesOwn();
    stopsWithTheNote();
    everyPassStartsAgain();
    threeAtOnce();
    aSwappedClipLetsGo();
    malformedRangesAreIgnored();
    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
