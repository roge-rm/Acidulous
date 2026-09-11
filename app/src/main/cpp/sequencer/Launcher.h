#pragma once
#include <algorithm>
#include <cstdint>
#include <engine/core/Constants.h>

// Clip mode: what each rack is playing, what it has been asked to play next,
// and exactly when the swap happens.
//
// The arranger walks one scene column and points all sixteen racks at it, so
// it needs one position: (scene, repeat, origin). A launcher needs sixteen of
// everything, because the whole point is that the Verse bass can run under the
// Chorus drums. This holds those sixteen, and nothing else - no racks, no
// snapshot, no clock. It is pure arithmetic over ticks, which is why it can be
// proven by a standalone harness before a note of it is wired in.
//
// Two rules decide every launch:
//
//   - a rack that is playing swaps at the end of the cycle it is in;
//   - a rack that is silent starts on the next multiple of the incoming
//     clip's *own* cycle, counted from the start of the transport, so an
//     eight-bar clip can only ever begin on an eight-bar line and a stack of
//     clips stays in phase however it was assembled.
//
// A fixed launch quantise overrides both with a plain grid.
//
// A clip's "cycle" is bars x repeat: the clip still loops every `bars`, but
// the cycle is what a swap waits for and what re-arms a OneShot. That is the
// "x2 1b" already written on the scene chip.

namespace acidulous::seq {

class Launcher {
  public:
    // Scene ids are FNV-1a hashes of the document's scene id, so zero is free
    // to mean "nothing" and a negative value is free to mean "stop".
    static constexpr int64_t kNone = 0;
    static constexpr int64_t kStopId = -1;
    /**
     * "Whatever is queued on this rack, forget it" - and nothing else.
     *
     * A second tap could be left to mean cancel by itself, since tapping a
     * queued clip toggles it off. But if the first tap has already landed in
     * the intervening quarter second, that same toggle reads as "stop the
     * clip that is now playing", and a double tap meant to open the editor
     * would leave a stop queued behind it. An explicit cancel cannot be
     * misread whatever happened in between.
     */
    static constexpr int64_t kCancelId = -2;

    void reset() {
        for (auto &s : slots) {
            s = Slot{};
        }
        changed = 0;
    }

    /** 0 launches at the end of the playing clip's cycle; otherwise a grid. */
    void setQuantise(int32_t ticks) { quantise = ticks < 0 ? 0 : ticks; }
    int32_t quantiseTicks() const { return quantise; }

    /**
     * A cell was tapped. The rule is here rather than in the UI so that it is
     * decided against what the audio thread is actually playing, and so that
     * one tap cannot be interpreted against a stale readback.
     *
     * Tapping a queued clip cancels the queue; tapping the clip that is
     * playing queues it to stop; tapping anything else queues it to start.
     * [cycle] is the tapped clip's own cycle in ticks.
     */
    void request(int32_t rack, int64_t sceneId, int64_t cycle, int64_t now) {
        if (!valid(rack) || sceneId == kNone) {
            return;
        }
        Slot &s = slots[rack];
        if (s.pendingId == sceneId || (s.pendingId == kStopId && s.sceneId == sceneId)) {
            s.pendingId = kNone; // tapped twice: never mind
            s.pendingCycle = 0;
            return;
        }
        if (s.sceneId == sceneId) {
            queueAt(s, kStopId, 0, boundary(s, 0, now));
            return;
        }
        queueAt(s, sceneId, cycle, boundary(s, cycle, now));
    }

    /** Forget what this rack had queued; leave what it is playing alone. */
    void cancel(int32_t rack) {
        if (valid(rack)) {
            slots[rack].pendingId = kNone;
            slots[rack].pendingCycle = 0;
        }
    }

    /** Every rack that is sounding is queued to stop at its own boundary. */
    void requestStopAll(int64_t now) {
        for (auto &s : slots) {
            if (s.sceneId != kNone) {
                queueAt(s, kStopId, 0, boundary(s, 0, now));
            } else {
                s.pendingId = kNone;
                s.pendingCycle = 0;
            }
        }
    }

    /** Nothing is queued and nothing sounds. Used by a hard stop. */
    void clearAll() {
        for (auto &s : slots) {
            s.sceneId = kNone;
            s.pendingId = kNone;
            s.cycle = 0;
            s.pendingCycle = 0;
        }
        changed = 0xffffffffu;
    }

    /**
     * Everything due exactly at [tick]: cycles that have run out, and launches
     * whose boundary has come. Call it before rendering any span that starts
     * at `tick`, so a clip that begins here begins with its own origin here.
     */
    void applyDue(int64_t tick) {
        for (int32_t r = 0; r < kRackCount; ++r) {
            Slot &s = slots[r];
            if (s.sceneId != kNone && s.cycle > 0) {
                // A cycle that has run out moves its origin up. This is what
                // re-arms OneShot clips and re-anchors automation lanes.
                const int64_t past = tick - s.origin;
                if (past >= s.cycle) {
                    s.origin += (past / s.cycle) * s.cycle;
                }
            }
            if (s.pendingId != kNone && s.pendingAt <= tick) {
                if (s.pendingId == kStopId) {
                    s.sceneId = kNone;
                    s.cycle = 0;
                    s.origin = s.pendingAt;
                } else {
                    s.sceneId = s.pendingId;
                    s.cycle = std::max<int64_t>(1, s.pendingCycle);
                    // The boundary, not `tick`: if a block ever starts past a
                    // boundary the clip still keeps the phase it was promised.
                    s.origin = s.pendingAt;
                }
                s.pendingId = kNone;
                s.pendingCycle = 0;
                changed |= (1u << r);
            }
        }
    }

    /**
     * The next tick at which anything changes, strictly after [now]: a cycle
     * ending or a launch landing. The scheduler renders up to here and no
     * further, which is what makes a swap sample-accurate.
     */
    int64_t nextEvent(int64_t now) const {
        int64_t best = INT64_MAX;
        for (const Slot &s : slots) {
            if (s.sceneId != kNone && s.cycle > 0) {
                const int64_t end = s.origin + s.cycle;
                if (end > now && end < best) {
                    best = end;
                }
            }
            if (s.pendingId != kNone && s.pendingAt > now && s.pendingAt < best) {
                best = s.pendingAt;
            }
        }
        return best;
    }

    // --- what the scheduler and the UI need to read ---------------------------

    bool playing(int32_t rack) const { return valid(rack) && slots[rack].sceneId != kNone; }
    int64_t sceneId(int32_t rack) const { return valid(rack) ? slots[rack].sceneId : kNone; }
    int64_t pendingId(int32_t rack) const { return valid(rack) ? slots[rack].pendingId : kNone; }
    int64_t origin(int32_t rack) const { return valid(rack) ? slots[rack].origin : 0; }
    int64_t cycle(int32_t rack) const { return valid(rack) ? slots[rack].cycle : 0; }

    bool anyPlaying() const {
        for (const Slot &s : slots) {
            if (s.sceneId != kNone) {
                return true;
            }
        }
        return false;
    }
    bool anyPending() const {
        for (const Slot &s : slots) {
            if (s.pendingId != kNone) {
                return true;
            }
        }
        return false;
    }

    /** Which racks changed clip since this was last asked. One bit per rack. */
    uint32_t takeChanged() {
        const uint32_t c = changed;
        changed = 0;
        return c;
    }

    /** A snapshot arrived and this rack's scene went away. */
    void dropRack(int32_t rack) {
        if (!valid(rack)) {
            return;
        }
        slots[rack].sceneId = kNone;
        slots[rack].cycle = 0;
        slots[rack].pendingId = kNone;
        changed |= (1u << rack);
    }
    /** A snapshot arrived and this rack's clip changed length. */
    void reshape(int32_t rack, int64_t cycle) {
        if (valid(rack) && slots[rack].sceneId != kNone) {
            slots[rack].cycle = std::max<int64_t>(1, cycle);
        }
    }

  private:
    struct Slot {
        int64_t sceneId = kNone;   // what is sounding, by stable scene id
        int64_t pendingId = kNone; // what is queued, or kStopId
        int64_t pendingAt = 0;     // the tick it lands on, decided when queued
        int64_t pendingCycle = 0;
        int64_t origin = 0; // absolute tick this rack's cycle began
        int64_t cycle = 0;  // bars x repeat x ticksPerBar
    };

    static bool valid(int32_t rack) { return rack >= 0 && rack < kRackCount; }

    static int64_t nextMultiple(int64_t now, int64_t m) {
        if (m <= 0) {
            return now;
        }
        if (now <= 0) {
            return 0;
        }
        return ((now + m - 1) / m) * m;
    }

    /** When a change asked for at [now] should land on this slot. */
    int64_t boundary(const Slot &s, int64_t incoming, int64_t now) const {
        if (quantise > 0) {
            return nextMultiple(now, quantise);
        }
        if (s.sceneId != kNone && s.cycle > 0) {
            // The end of the cycle this rack is in - never this instant, or a
            // tap landing exactly on a boundary would swallow a whole cycle.
            const int64_t past = std::max<int64_t>(0, now - s.origin);
            return s.origin + (past / s.cycle + 1) * s.cycle;
        }
        // Silent: the next line this clip's own length falls on.
        return incoming > 0 ? nextMultiple(now, incoming) : now;
    }

    static void queueAt(Slot &s, int64_t id, int64_t cycle, int64_t at) {
        s.pendingId = id;
        s.pendingCycle = cycle;
        s.pendingAt = at;
    }

    Slot slots[kRackCount];
    int32_t quantise = 0;
    uint32_t changed = 0;
};

} // namespace acidulous::seq
