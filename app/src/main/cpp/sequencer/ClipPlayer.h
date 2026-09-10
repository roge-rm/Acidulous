#pragma once
#include "Clip.h"
#include <atomic>
#include <cstdint>

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
                schedule(note.pitch, t + (note.length > 0 ? note.length : 1), sink);
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
    void schedule(uint8_t pitch, int64_t offTick, Sink &sink) {
        for (PendingOff &p : pending) {
            if (!p.active) {
                p.tick = offTick;
                p.pitch = pitch;
                p.active = true;
                return;
            }
        }
        // Table full - more than kMaxPending notes sounding at once. Degrade to
        // a zero-length note rather than a stuck one.
        sink(0x80, pitch, 0);
        offCount.fetch_add(1, std::memory_order_relaxed);
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
