#pragma once
#include "Clip.h"
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
    }
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
            for (const ClipNote &note : clip_->notes) {
                const int64_t t = base + note.tick;
                if (t >= end) {
                    break; // notes are sorted
                }
                if (t < start) {
                    continue;
                }
                releaseIfSounding(note.pitch, sink);
                sink(0x90, note.pitch, note.velocity);
                onCount.fetch_add(1, std::memory_order_relaxed);
                schedule(note, t, t + (note.length > 0 ? note.length : 1), sink);
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

    static constexpr size_t kMaxLanes = 32;
    const Clip *clip_ = nullptr;
    float lastLane[kMaxLanes]{};
    int64_t lastOrigin = -1;
    PendingOff pending[kMaxPending];
    std::atomic<uint32_t> onCount{0};
    std::atomic<uint32_t> offCount{0};
};

} // namespace acidulous::seq
