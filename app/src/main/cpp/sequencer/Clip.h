#pragma once
#include <cstdint>
#include <engine/core/Constants.h>
#include <engine/core/Messages.h>
#include <vector>

// One track's data for one scene.
//
// Built on a non-audio thread, handed to the audio thread through the
// constructor queue, and never mutated afterwards - an edit produces a new Clip
// and the old one goes to the destructor queue. That convention is what lets the
// audio thread read `notes` without a lock.

namespace acidulous::seq {

struct ClipNote {
    int32_t tick;     // offset from clip start
    int32_t length;   // in ticks; ≥ 1
    uint8_t pitch;    // MIDI note number
    uint8_t velocity; // 1..127
};

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
    std::vector<ClipNote> notes; // MUST be sorted by tick before hand-over
    std::vector<Lane> lanes;

    int32_t lengthTicks() const { return bars * ticksPerBar; }
};

} // namespace acidulous::seq
