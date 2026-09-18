#pragma once
#include "Clip.h"
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <limits>

// Plays one rack's clip. Lives inside the Rack and emits into
// Rack::handleMidi(), ahead of the eventors, so sequenced and live notes are
// treated identically.
//
// Positions are absolute transport ticks. The clip loops by arithmetic from the
// scene iteration's origin (pass k covers [origin + k·len, origin + (k+1)·len)),
// and pending note-offs are stored as absolute ticks, so a note that runs past a
// loop point or a scene boundary simply ends when it ends.
//
// Audio thread only, apart from the diagnostic counters.

namespace acidulous::seq {

class ClipPlayer {
  public:
    static constexpr int kMaxPending = 64;
    static constexpr int kExprKinds = static_cast<int>(acidulous::Expr::Count);

    // Audio thread, at a block boundary (ObjectManager does this). Pending
    // note-offs are deliberately kept: they belong to notes already sounding.
    void setClip(const Clip *newClip) {
        clip_ = newClip;
        for (float &v : lastLane) v = -1.0f;
        lastOrigin = -1;
        // A different clip is a different pattern. `pointLaunched` calls this
        // on every launch, so a clip you fire starts its conditions from that
        // bar rather than inheriting the phase of whatever was there.
        passIndex_ = 0;
        lastBase_ = kNoBase;
    }

    /**
     * Back to the beginning, for a render.
     *
     * The three things below are the only state the player carries that a
     * performance can move, and nothing rewound them until there was
     * something to rewind: `allNotesOff` clears the pending table and that was
     * all there was. This is the lesson `Arp::reset()` wrote down - an arp
     * carried its RNG from one playing to the next and made an offline render
     * of the same song come out differently. A reset means from the beginning.
     *
     * Deliberately *not* part of `allNotesOff`, which runs on every ordinary
     * stop: re-seeding there would make a free-rolling clip repeat, which is
     * the one thing free is for.
     */
    void reset() {
        freeSeed_ = kFreeSeed;
        lastFreePass_ = kNoBase;
        passIndex_ = 0;
        lastBase_ = kNoBase;
    }

    /** Whether Fill trigs may sound. The transport owns it; the gate reads it. */
    void setFill(bool on) { fill_ = on; }
    const Clip *clip() const { return clip_; }

    // Fire everything due in absolute tick range [start, end). `origin` is the
    // absolute tick at which the current scene iteration began: the clip's
    // tick 0 sits there, and it loops from there. Because the origin moves on
    // every iteration, a OneShot clip (which fires only in its first pass)
    // re-arms on each scene repeat without any extra state.
    // Sink signature: void(uint8_t cmd, uint8_t p1, uint8_t p2).
    template <class Sink>
    void process(int64_t start, int64_t end, int64_t origin, Sink &&sink) {
        // Note-offs first, so a note ending where another begins retriggers
        // cleanly. Anything overdue (tick < start) fires now rather than never.
        for (PendingOff &p : pending) {
            if (p.active && p.tick < end) {
                sink(0x80, p.pitch, 0);
                p.active = false;
                offCount.fetch_add(1, std::memory_order_relaxed);
            }
        }

        if (clip_ == nullptr || clip_->mute || clip_->notes.empty()) {
            return;
        }
        const int64_t len = clip_->lengthTicks();
        if (len <= 0 || start < origin) {
            return;
        }

        for (int64_t k = (start - origin) / len; origin + k * len < end; ++k) {
            if (clip_->playMode == PlayMode::OneShot && k > 0) {
                break;
            }
            const int64_t base = origin + k * len;
            // Which pass of this clip this is.
            //
            // Counted rather than derived. `base / len` looks free and is
            // wrong twice: a three-bar clip in a four-bar scene returns the
            // same index for two consecutive passes, because the scene's
            // iteration length is not a multiple of the clip's; and a OneShot
            // never leaves k = 0, so its index steps by the scene's length
            // instead of its own. Counting distinct origins gets both right -
            // a pass split over two blocks counts once, a block covering
            // three counts three - at the price of one field, which the reset
            // above pays for anyway.
            if (base != lastBase_) {
                if (lastBase_ != kNoBase) ++passIndex_;
                lastBase_ = base;
            }
            const int64_t pass = passIndex_;
            // Free mode re-draws the seed word once per pass. Not per note and
            // not per block: within a pass the roll has to stay a pure
            // function of who and when, or a ratchet whose sub-hits land in
            // the next block, and a Prev chain cut by a block boundary, would
            // both get a different answer the second time they asked.
            if (clip_->freeRoll && pass != lastFreePass_) {
                lastFreePass_ = pass;
                freeSeed_ = freeSeed_ * 1664525u + 1013904223u;
            }

            bool prevPlayed = false; // the chain starts again every pass
            for (const ClipNote &note : clip_->notes) {
                const int64_t t = base + note.tick;
                if (t >= end) {
                    break; // notes are sorted, and no sub-hit precedes its note
                }
                const int32_t rat = note.ratchet();
                // A note this block will not fire still had a verdict, and the
                // note after it may be asking what that verdict was - so the
                // gate runs above the skip, not below it. Only clips that
                // actually contain a Prev pay for the replay.
                if (!clip_->hasPrevCond && rat <= 1 && t < start) {
                    continue;
                }
                const bool play = gate(note, pass, prevPlayed);
                if (note.conditional()) {
                    prevPlayed = play;
                }
                if (!play || (rat <= 1 && t < start)) {
                    continue;
                }
                // A ratchet subdivides a note; it does not spill into the next
                // bar. Without the clamp, a note near the end of the clip asks
                // for sub-hits in a pass the loop above will never walk again,
                // and they vanish silently.
                const int32_t full = note.length > 0 ? note.length : 1;
                const int32_t span = std::min<int32_t>(full, static_cast<int32_t>(len) - note.tick);
                const int32_t step = rat > 1 ? std::max(1, span / rat) : span;
                for (int32_t j = 0; j < rat; ++j) {
                    const int64_t h = t + static_cast<int64_t>(j) * step;
                    if (h >= end) break;
                    if (h < start) continue; // an earlier block fired it
                    const int64_t off = rat > 1
                                            ? std::max<int64_t>(h + 1, std::min<int64_t>(h + step, t + span))
                                            : t + span;
                    releaseIfSounding(note.pitch, sink);
                    sink(0x90, note.pitch, note.velocity);
                    onCount.fetch_add(1, std::memory_order_relaxed);
                    // `t`, not `h`: a curve belongs to the note, so it runs
                    // across the whole ratchet instead of restarting on each.
                    schedule(note, t, off, sink);
                }
            }
        }
    }

    // Automation: after the notes, set every lane's value at the block's end
    // position. Setter signature: void(Unit, int32_t index, float value).
    // Skips lanes the live UI has touched during this pass while recording.
    template <class Setter, class Touched>
    void processLanes(int64_t end, int64_t origin, Setter &&set, Touched &&touched) {
        if (clip_ == nullptr || clip_->lanes.empty()) return;
        const int64_t len = clip_->lengthTicks();
        if (len <= 0 || end < origin) return;
        if (origin != lastOrigin) {
            lastOrigin = origin;
            for (float &v : lastLane) v = -1.0f; // a new pass re-sends from the top
        }
        const auto t = static_cast<int32_t>((end - origin) % len);
        const size_t n = clip_->lanes.size() < kMaxLanes ? clip_->lanes.size() : kMaxLanes;
        for (size_t i = 0; i < n; ++i) {
            const Lane &lane = clip_->lanes[i];
            if (touched(lane.unit, lane.index)) continue;
            const float v = lane.valueAt(t);
            if (v != lastLane[i]) {
                lastLane[i] = v;
                set(lane.unit, lane.index, v);
            }
        }
    }
    bool originChanged(int64_t origin) const { return origin != lastOrigin; }

    /**
     * Per-note expression: for every note still sounding, where its curves
     * have got to by the end of this block.
     *
     * Read at the block's end position like a lane, and sent only when the
     * value has moved, so a curve that is flat between two distant points
     * costs one call and a glide costs one call a block - which at 64 frames
     * is finer than any controller sends in the first place.
     *
     * Sink signature: void(uint8_t note, int32_t kind, float value01).
     */
    template <class Sink>
    void processExpression(int64_t end, Sink &&sink) {
        if (clip_ == nullptr) return;
        for (PendingOff &p : pending) {
            // A clip swapped underneath a sounding note takes its curves with
            // it - the old Clip is on its way to the destructor queue, and the
            // points were never ours. The note keeps sounding and holds the
            // last value it was given, which is the quiet answer.
            if (!p.active || !p.hasExpr || p.exprRev != clip_->rev) continue;
            const auto rel = static_cast<int32_t>(end > p.startTick ? end - p.startTick : 0);
            for (int32_t k = 0; k < kExprKinds; ++k) {
                if (p.exprCount[k] == 0) continue;
                const float v = exprValueAt(clip_->expr.data() + p.exprFirst[k], p.exprCount[k], rel);
                if (v == p.exprLast[k]) continue;
                p.exprLast[k] = v;
                sink(p.pitch, k, v);
            }
        }
    }

    // Stop: release everything that is sounding.
    template <class Sink>
    void allNotesOff(Sink &&sink) {
        for (PendingOff &p : pending) {
            if (p.active) {
                sink(0x80, p.pitch, 0);
                p.active = false;
                offCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    // Diagnostics, any thread. Equal after a stop means no stuck notes.
    uint32_t notesOn() const { return onCount.load(std::memory_order_relaxed); }
    uint32_t notesOff() const { return offCount.load(std::memory_order_relaxed); }

  private:
    struct PendingOff {
        int64_t tick = 0;
        uint8_t pitch = 0;
        bool active = false;
        // The note's expression, resolved once when it fires: where it
        // started, which clip it came from, and each curve's own range
        // within that clip's expr array. Resolved here and not per block
        // because the ranges never change while the note sounds, and this
        // is the one moment we are already looking at the ClipNote.
        int64_t startTick = 0;
        int64_t exprRev = 0;
        int32_t exprFirst[kExprKinds] = {};
        int32_t exprCount[kExprKinds] = {};
        float exprLast[kExprKinds] = {};
        bool hasExpr = false;
    };

    // Same pitch already sounding: end it before restarting it, so a voice is
    // never left with an off it will never receive.
    template <class Sink>
    void releaseIfSounding(uint8_t pitch, Sink &sink) {
        for (PendingOff &p : pending) {
            if (p.active && p.pitch == pitch) {
                sink(0x80, pitch, 0);
                p.active = false;
                offCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    template <class Sink>
    void schedule(const ClipNote &note, int64_t onTick, int64_t offTick, Sink &sink) {
        for (PendingOff &p : pending) {
            if (!p.active) {
                p.tick = offTick;
                p.pitch = note.pitch;
                p.active = true;
                bindExpression(p, note, onTick);
                return;
            }
        }
        // Table full - more than kMaxPending notes sounding at once. Degrade to
        // a zero-length note rather than a stuck one.
        sink(0x80, note.pitch, 0);
        offCount.fetch_add(1, std::memory_order_relaxed);
    }

    // Split the note's slice of the clip's expr array into one range per
    // kind. The slice is sorted by kind and then by tick, so this is one walk
    // of the note's own points - typically none, and never many.
    void bindExpression(PendingOff &p, const ClipNote &note, int64_t onTick) {
        p.startTick = onTick;
        p.exprRev = clip_ != nullptr ? clip_->rev : 0;
        p.hasExpr = false;
        for (int32_t k = 0; k < kExprKinds; ++k) {
            p.exprFirst[k] = 0;
            p.exprCount[k] = 0;
            // Nothing has been sent for this note yet, and a NaN differs from
            // every value there is, so the first read always reaches the
            // machine even when it happens to be the neutral one.
            p.exprLast[k] = std::numeric_limits<float>::quiet_NaN();
        }
        if (clip_ == nullptr || note.exprCount <= 0) return;
        const auto total = static_cast<int32_t>(clip_->expr.size());
        if (note.exprFirst < 0 || note.exprFirst + note.exprCount > total) return; // malformed; ignore it
        for (int32_t i = 0; i < note.exprCount; ++i) {
            const int32_t kind = clip_->expr[static_cast<size_t>(note.exprFirst + i)].kind;
            if (kind < 0 || kind >= kExprKinds) continue;
            if (p.exprCount[kind] == 0) p.exprFirst[kind] = note.exprFirst + i;
            ++p.exprCount[kind];
            p.hasExpr = true;
        }
    }

    /**
     * The roll: a pure function of the dice, the pass and which note this is.
     *
     * Identity is (tick, pitch) rather than the note's index in the list,
     * because inserting a note at the top of a clip must not reroll every
     * note after it. The mixing is Dice's, which is the house's answer to
     * "random, but the same twice".
     */
    uint32_t roll(const ClipNote &note, int64_t pass) const {
        uint32_t h = clip_->freeRoll ? freeSeed_ : static_cast<uint32_t>(clip_->seed);
        h = h * 2654435761u + static_cast<uint32_t>(pass) * 40503u + 1u;
        h += static_cast<uint32_t>(note.tick) * 2246822519u + note.pitch * 668265263u;
        h ^= h >> 13;
        h *= 0x5bd1e995u;
        h ^= h >> 15;
        return h;
    }

    /** Does this trig play? Condition first, then the dice. */
    bool gate(const ClipNote &note, int64_t pass, bool prevPlayed) const {
        const int32_t c = note.condition();
        if (c == static_cast<int32_t>(TrigCond::Prev) && !prevPlayed) return false;
        if (c == static_cast<int32_t>(TrigCond::NotPrev) && prevPlayed) return false;
        if (c == static_cast<int32_t>(TrigCond::Fill) && !fill_) return false;
        if (c == static_cast<int32_t>(TrigCond::NotFill) && fill_) return false;
        if (c >= static_cast<int32_t>(TrigCond::NthBase)) {
            int32_t n = 0, m = 2;
            nthOf(c, n, m);
            if (pass % m != n - 1) return false; // pass is >= 0 by construction
        }
        const int32_t chance = note.chance();
        if (chance >= 100) return true;
        if (chance <= 0) return false;
        return static_cast<int32_t>(roll(note, pass) % 100u) < chance;
    }

    static constexpr size_t kMaxLanes = 32;
    static constexpr int64_t kNoBase = std::numeric_limits<int64_t>::min();
    static constexpr uint32_t kFreeSeed = 0x9E3779B9u; // as Arp's, and for the same reason
    const Clip *clip_ = nullptr;
    float lastLane[kMaxLanes]{};
    int64_t lastOrigin = -1;
    int64_t passIndex_ = 0;
    int64_t lastBase_ = kNoBase;
    int64_t lastFreePass_ = kNoBase;
    uint32_t freeSeed_ = kFreeSeed;
    bool fill_ = false;
    PendingOff pending[kMaxPending];
    std::atomic<uint32_t> onCount{0};
    std::atomic<uint32_t> offCount{0};
};

} // namespace acidulous::seq
