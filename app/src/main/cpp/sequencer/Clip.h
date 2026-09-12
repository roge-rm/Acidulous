#pragma once
#include <cstdint>
#include <engine/core/Constants.h>
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

    int32_t lengthTicks() const { return bars * ticksPerBar; }
};

} // namespace acidulous::seq
