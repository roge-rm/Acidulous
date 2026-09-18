#pragma once
#include <cstdint>
#include <engine/core/Constants.h>
#include <engine/core/Expression.h>
#include <engine/core/Messages.h>
#include <vector>

// One track's data for one scene.
//
// Built on a non-audio thread, handed to the audio thread through the
// constructor queue, and never mutated afterwards - an edit produces a new Clip
// and the old one goes to the destructor queue. That convention is what lets the
// audio thread read `notes` without a lock.

namespace acidulous::seq {

/**
 * What a trig is allowed to decide: a chance, a condition and a ratchet.
 *
 * Sixty-four conditions is more than the vocabulary needs today and exactly
 * what it needs to grow: `Always`, `Prev`, `NotPrev`, `Fill`, `NotFill` and
 * the whole *Nth of every M* family up to eight is forty, which five bits
 * could not have held.
 */
enum class TrigCond : uint8_t {
    Always = 0,
    Prev = 1,    // only if the previous conditional trig in this pass played
    NotPrev = 2, // only if it did not
    Fill = 3,    // only while the fill control is held
    NotFill = 4, // only while it is not
    NthBase = 5, // and upwards: see nthCond
};

/**
 * The code for "the Nth of every M passes", packed triangularly.
 *
 * M = 2 takes **two** codes, M = 3 takes three, and so on, so everything
 * before M is `2 + 3 + ... + (M-1)`, which is `(M-1)M/2 - 1`. The family is
 * contiguous and there is no table.
 *
 * The minus one is the whole of it, and getting it wrong is silent: with
 * `(m-2)(m-1)/2` the M = 3 codes land on top of the M = 2 codes and `1:3`
 * plays as `2:2` - which sounds like a pattern, just not the one asked for.
 */
inline int32_t nthCond(int32_t n, int32_t m) {
    return static_cast<int32_t>(TrigCond::NthBase) + (m - 1) * m / 2 - 1 + (n - 1);
}

/** Undo nthCond: the M it belongs to, and which of that M it is. */
inline void nthOf(int32_t cond, int32_t &n, int32_t &m) {
    int32_t off = cond - static_cast<int32_t>(TrigCond::NthBase);
    m = 2;
    while (off >= m) { off -= m; ++m; }
    n = off + 1;
}

/**
 * Chance, condition and ratchet in one word.
 *
 * 7 | 6 | 3 is sixteen bits exactly, and sixteen bits is what ClipNote has
 * going spare: `velocity` leaves two bytes of padding behind and this lands in
 * them, so the note table is the size it always was. Three separate bytes
 * would have pushed `exprFirst` out and grown every note by a fifth.
 */
inline uint16_t packTrig(int32_t chance, int32_t cond, int32_t ratchet) {
    const auto c = static_cast<uint32_t>(chance < 0 ? 0 : (chance > 100 ? 100 : chance));
    const auto d = static_cast<uint32_t>(cond) & 0x3Fu;
    const auto r = static_cast<uint32_t>((ratchet < 1 ? 1 : (ratchet > 8 ? 8 : ratchet)) - 1);
    return static_cast<uint16_t>(c | (d << 7) | (r << 13));
}

struct ClipNote {
    int32_t tick;     // offset from clip start
    int32_t length;   // in ticks; ≥ 1
    uint8_t pitch;    // MIDI note number
    uint8_t velocity; // 1..127
    /**
     * The default is an ordinary note: certain, unconditional, struck once.
     *
     * Worth stating because it is the one value that must never arrive by
     * accident - a zeroed `trig` reads as *chance nought*, which is silence.
     * Aggregate initialisation (`ClipNote n{}`, as the harnesses write) picks
     * this up; a memset would not, and nothing does one today.
     */
    static constexpr uint16_t kTrigDefault = 100; // 100%, Always, one hit
    uint16_t trig = kTrigDefault;

    int32_t chance() const { return trig & 0x7F; }               // 0..100
    int32_t condition() const { return (trig >> 7) & 0x3F; }     // a TrigCond
    int32_t ratchet() const { return ((trig >> 13) & 7) + 1; }   // 1..8
    /** Is this note in the Prev chain - that is, did it make a decision? */
    bool conditional() const { return condition() != 0 || chance() < 100; }

    // This note's slice of Clip::expr, or an empty one. A side array and not
    // three vectors per note, because the note table is walked every block
    // by every playing rack and almost no note has expression: three empty
    // vectors would cost seventy-two bytes and six cache lines a note to say
    // nothing. Eight bytes says the same thing.
    int32_t exprFirst = 0;
    int32_t exprCount = 0;
};

// The whole point of packing three properties into one word. If this ever
// fails, the note table grew and somebody should have to say why.
static_assert(sizeof(ClipNote) == 20, "ClipNote must stay twenty bytes");

// One point of one of a note's three curves. Ticks are relative to the
// note's own start, which is what lets a note be moved, quantised or copied
// without its expression coming loose.
struct ExprPoint {
    int32_t tick;
    int32_t kind; // an acidulous::Expr
    float value;  // 0..1; see engine/core/Expression.h
};

// One curve's value at `tick`, held flat before the first point and after the
// last and interpolated between. The same shape as Lane::valueAt - a curve is
// a lane whose clock is its own note - but over a plain range, because a
// note's three curves live inside one array and own no storage of their own.
inline float exprValueAt(const ExprPoint *points, int32_t count, int32_t tick) {
    if (count <= 0) return 0.0f;
    if (tick <= points[0].tick) return points[0].value;
    if (tick >= points[count - 1].tick) return points[count - 1].value;
    int32_t lo = 0, hi = count - 1;
    while (hi - lo > 1) {
        const int32_t mid = (lo + hi) / 2;
        if (points[mid].tick <= tick) lo = mid; else hi = mid;
    }
    const ExprPoint &a = points[lo], &b = points[hi];
    if (b.tick == a.tick) return a.value;
    const float f = static_cast<float>(tick - a.tick) / static_cast<float>(b.tick - a.tick);
    return a.value + (b.value - a.value) * f;
}

enum class PlayMode : uint8_t { Loop, OneShot };

struct LanePoint {
    int32_t tick;
    float value; // normalised 0..1, the engine's parameter domain
};

// One parameter's movement over the clip. Names are resolved to a unit and a
// table index when the snapshot is built, so playback is a lookup, not a hash.
struct Lane {
    Unit unit = Unit::Machine;
    int32_t index = 0;
    bool linear = true;
    std::vector<LanePoint> points; // sorted by tick

    float valueAt(int32_t tick) const {
        if (points.empty()) return 0.0f;
        if (tick <= points.front().tick) return points.front().value;
        if (tick >= points.back().tick) return points.back().value;
        // binary search for the last point at or before tick
        size_t lo = 0, hi = points.size() - 1;
        while (hi - lo > 1) {
            const size_t mid = (lo + hi) / 2;
            if (points[mid].tick <= tick) lo = mid; else hi = mid;
        }
        const LanePoint &a = points[lo], &b = points[hi];
        if (!linear || b.tick == a.tick) return a.value;
        const float f = static_cast<float>(tick - a.tick) / static_cast<float>(b.tick - a.tick);
        return a.value + (b.value - a.value) * f;
    }
};

struct Clip {
    // Identity of the document-side instance this was built from. The builder
    // reuses a Clip across snapshots when the rev matches, so an edit to one
    // clip re-marshals one clip, not the whole song.
    int64_t rev = 0;
    int32_t bars = 1;
    int32_t ticksPerBar = 4 * kPPQN; // from the scene's signature, set when the snapshot is built
    PlayMode playMode = PlayMode::Loop;
    bool mute = false;
    /**
     * What the dice are made of.
     *
     * [seed] is the player's own: the same clip with the same seed plays the
     * same bar every time, which is what makes an export repeatable and a
     * take recordable. Changing it is how you ask for a different variation.
     * It is a *document* value and not `rev` - `rev` is fresh on every edit,
     * so a pattern built on it would reroll itself under your hand.
     *
     * [freeRoll] sets the dice loose: the seed word is re-drawn once per pass
     * instead, so the clip varies while you play it. Within a pass the roll is
     * still a pure function, which is what lets a ratchet and a Prev chain
     * survive a block boundary in either mode.
     */
    int32_t seed = 0;
    bool freeRoll = false;
    /**
     * Some note in here asks what the trig before it did.
     *
     * Set when the clip is built. Without it the player would have to compute
     * a verdict for notes it is about to skip, on every clip, to keep the
     * chain intact across a block cut; with it only the clips that need that
     * pay for it.
     */
    bool hasPrevCond = false;
    std::vector<ClipNote> notes; // MUST be sorted by tick before hand-over
    std::vector<Lane> lanes;
    // Every note's curves, end to end. Each note owns [exprFirst, +exprCount)
    // and that slice is sorted by kind and then by tick, so a note-on resolves
    // its three ranges with one walk of its own points and never touches
    // anybody else's.
    std::vector<ExprPoint> expr;

    int32_t lengthTicks() const { return bars * ticksPerBar; }
};

} // namespace acidulous::seq
