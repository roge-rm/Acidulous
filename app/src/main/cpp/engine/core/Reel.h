#pragma once
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <engine/core/Mapping.h>
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

/**
 * How long a take may be **and be held in memory**: two minutes.
 *
 * Under this it is decoded into vectors as it always was, which is the simple
 * path and covers a verse, a chorus and nearly every overdub anybody makes.
 */
constexpr int32_t kResidentSeconds = 120;

/**
 * How long a take may be at all: half an hour, and it is mapped rather than
 * held.
 *
 * The point of an audio track is a voice that runs the length of a song, and a
 * song is longer than five minutes - which is what this used to say. Above
 * [kResidentSeconds] a take is converted once to the engine's own flat format
 * and memory-mapped, so what it costs in RAM is what the song is actually
 * playing rather than the whole of it. See `audio::Mapping`.
 */
constexpr int32_t kMaxReelSeconds = 1800;

/** How many lanes Bias has. Four, because that is what a four-track is. */
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
        /**
         * **Read through the pointers, never through the vectors.**
         *
         * A source is held one of two ways - decoded into `own` under the
         * resident ceiling, or mapped from a converted cache file above it -
         * and the render must not care which. So the two pointers are the
         * interface and the storage below them is an implementation detail;
         * `lp` and `rp` are set by whichever of the two filled it.
         *
         * Planar, and mono stays mono, which is why the mapped file is planar
         * too: one layout means one access path, and a mono take is half the
         * file rather than a duplicated channel.
         */
        const int16_t *lp = nullptr;
        const int16_t *rp = nullptr;
        int32_t frames = 0;
        bool stereo = false;

        std::vector<int16_t> own;   // resident: the samples themselves
        std::shared_ptr<Mapping> map; // mapped: the file they live in

        /** Resident, from planar channels already in hand. */
        void hold(std::vector<int16_t> &&planes, int32_t n, bool isStereo) {
            own = std::move(planes);
            frames = n;
            stereo = isStereo;
            lp = own.data();
            rp = isStereo ? own.data() + n : own.data();
        }

        /** Mapped, from a converted cache file: left plane then right. */
        bool point(std::shared_ptr<Mapping> m, int32_t n, bool isStereo) {
            const size_t want = static_cast<size_t>(n) * (isStereo ? 2 : 1) * sizeof(int16_t);
            if (!m || !m->valid() || m->size() < want) return false;
            map = std::move(m);
            frames = n;
            stereo = isStereo;
            lp = reinterpret_cast<const int16_t *>(map->data());
            rp = isStereo ? lp + n : lp;
            return true;
        }

        /** Ask the kernel for the frames a cell is about to play. A hint. */
        void willNeed(int32_t from, int32_t count) const {
            if (!map) return;
            const size_t unit = sizeof(int16_t);
            map->willNeed(static_cast<size_t>(from) * unit, static_cast<size_t>(count) * unit);
            if (stereo) {
                map->willNeed(static_cast<size_t>(frames + from) * unit,
                              static_cast<size_t>(count) * unit);
            }
        }
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
        /**
         * How long this take takes to arrive and to go, in frames.
         *
         * Equal-power, so **one lane fading out under another fading in holds
         * a steady level** - which is what makes a crossfade between two takes
         * nothing more than two of these overlapping. A linear pair would dip
         * three decibels in the middle, and that dip is the sound of an edit.
         */
        int32_t fadeIn = 0;
        int32_t fadeOut = 0;

        /** The envelope at [at] frames into the region: 0..1. */
        float fadeAt(int64_t at) const {
            float g = 1.0f;
            if (fadeIn > 0 && at < fadeIn) g = static_cast<float>(at) / static_cast<float>(fadeIn);
            if (fadeOut > 0 && at > frames - fadeOut) {
                const float k = static_cast<float>(frames - at) / static_cast<float>(fadeOut);
                g = g < k ? g : k;
            }
            if (g <= 0.0f) return 0.0f;
            if (g >= 1.0f) return 1.0f;
            // Equal power: sin of a quarter turn, whose square sums to one
            // against its mirror.
            return std::sin(g * 1.5707963f);
        }
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
