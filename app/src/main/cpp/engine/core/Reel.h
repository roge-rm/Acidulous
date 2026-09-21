#pragma once
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <vector>

// What an audio track is holding: recordings, and where in the song each of
// them plays.
//
// **The shape is FrozenSet's**, one level along. Frozen audio is a rendered
// clip keyed by scene, per rack, mounted whole and swapped at a block
// boundary; this is a recording keyed by scene, per rack, mounted whole and
// swapped at a block boundary. The differences are the two that matter for a
// performance rather than a render:
//
//   - **A region is a window into a file, not the whole of one.** A take sung
//     across four scenes is one file and four regions at four offsets, which
//     is what lets the split at the scene lines cost no audio.
//   - **Four of them sound at once.** A four-track holds four tracks along one
//     length of tape, so a cell has four lanes and they are summed.
//
// Built on a worker and never mutated: the audio thread only ever reads, and a
// new one arrives by being swapped in.
namespace acidulous::audio {

/** How long one take may be. See the note in Reel::Source. */
constexpr int32_t kMaxReelSeconds = 300;

/** How many lanes a tape has. Four, because that is what a four-track is. */
constexpr int32_t kReelLanes = 4;

/**
 * Float to int16, clamped, with the rounding a converter owes its input.
 *
 * A take can legitimately sit above full scale - the channel strip is what
 * brings it down, which is the same argument the freeze render makes for
 * writing float - so this clamps rather than wrapping, because wrapping is a
 * click and clamping is a loud moment.
 */
inline int16_t toI16(float v) {
    const float x = v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
    return static_cast<int16_t>(x * 32767.0f + (x >= 0.0f ? 0.5f : -0.5f));
}

struct Reel {
    /**
     * One decoded file, shared by every region that reads it.
     *
     * **int16, and mono stays mono**, which is what makes this fit a phone. A
     * five-minute take is 115 MB as the stereo float everything else here
     * keeps, and 29 MB this way; three audio tracks over a five-minute song
     * are 345 MB against 86. A phone microphone's noise floor is far above
     * sixteen bits and everything downstream of this is float, so what is
     * given up is nothing anybody can hear and what is bought is the feature
     * existing on hardware people own.
     *
     * Shared by `shared_ptr` because a take that runs the length of the song
     * is one file and a dozen regions, and decoding it a dozen times is the
     * difference between 29 MB and 350.
     */
    struct Source {
        std::vector<int16_t> left, right; // right empty means mono
        int32_t frames = 0;
        bool stereo = false;
    };

    /** One lane of one cell: which file, which part of it, and when. */
    struct Region {
        std::shared_ptr<const Source> source;
        int32_t offset = 0;    // frames into the source where this cell starts
        int32_t frames = 0;    // how many of them are this cell's
        int32_t startTick = 0; // where in the cycle it begins; a punch-in is not nought
        int32_t ticks = 0;     // the cycle it was recorded against
        float bpm = 120.0f;    // the tempo it was recorded at
        bool loop = false;     // wrap within the region rather than falling silent
    };

    /** One cell: the lanes that have anything on them. */
    struct Cell {
        int64_t sceneId = 0;
        Region lanes[kReelLanes];
        bool has(int32_t lane) const {
            return lane >= 0 && lane < kReelLanes && lanes[lane].source != nullptr &&
                   lanes[lane].frames > 0;
        }
    };

    std::vector<Cell> cells;

    /** Audio thread: a handful of cells, so a scan beats anything cleverer. */
    const Cell *find(int64_t sceneId) const {
        if (sceneId == 0) return nullptr;
        for (const Cell &c : cells) {
            if (c.sceneId == sceneId) return &c;
        }
        return nullptr;
    }
};

/**
 * A read position that runs free between blocks and is pulled back only when
 * it has drifted audibly.
 *
 * Lifted from `Rack::syncFrozen`, whose note is the reason it is not simply
 * recomputed every block: *"recomputing it from the tick every block would
 * step the read position by a sample or two each time, which clicks."*
 */
struct FrameCursor {
    int64_t at = -1;

    void invalidate() { at = -1; }

    void anchor(int64_t target) {
        if (at < 0 || std::llabs(target - at) > 256) at = target;
    }
};

} // namespace acidulous::audio
