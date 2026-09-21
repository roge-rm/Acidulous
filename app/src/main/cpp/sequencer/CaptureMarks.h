#pragma once
#include <atomic>
#include <cstdint>

// Where the song was when each part of a recording was made.
//
// `Capture` streams the input to a file and has no idea where the song is; the
// scheduler knows where the song is and has no idea a recording is happening.
// This is the join: **the audio thread stamps a boundary as it crosses it**,
// carrying the frame it has written so far and the cell it has just entered.
//
// That is the whole of splitting a take. Kotlin does no scene-length or repeat
// arithmetic, so a per-scene tempo override, a scene played twice, a clip
// shorter than its scene and a punch-in half way through all come out right
// without anybody thinking about them: consecutive marks pair into segments,
// and each segment is one cell's region of one file.
//
// Two limits, stated rather than engineered away:
//
//   - a boundary lands to the block, which at 64 frames is 1.3 ms;
//   - if the capture ring overflowed, every frame index after the drop names
//     the wrong moment, so the marks are poisoned and the split refuses. A
//     recording with a hole in it can still be kept; it cannot be cut up.
//
// Header-only so `tools/` can drive it without the engine archive.
namespace acidulous::seq {

/** One boundary: where in the file, and where in the song. */
struct CaptureMark {
    int64_t frame = 0;       // frames written to the capture file at this moment
    int64_t sceneId = 0;     // the cell being entered
    int32_t tick = 0;        // how far into that cell's cycle it starts
    int32_t cycleTicks = 0;  // the cycle itself - bars x repeat
    float bpm = 120.0f;      // the tempo it was recorded at
};

class CaptureMarks {
  public:
    /**
     * How many boundaries one take may cross.
     *
     * Five minutes of two-second cells is a hundred and fifty, so this is
     * generous; it exists so that a pathological song poisons the split rather
     * than silently losing the end of it.
     */
    static constexpr int32_t kMax = 512;

    /** Mount thread, when a recording is armed. */
    void reset() {
        n.store(0, std::memory_order_relaxed);
        bad.store(false, std::memory_order_relaxed);
        lastScene = kNoScene;
        lastTick = -1;
    }

    /**
     * Audio thread, once a block while a recording is armed.
     *
     * A mark is written when the cell changes, when the cycle starts again -
     * which the tick going backwards is the only honest sign of - and once at
     * the start. Nothing else: a block that carries on where the last one left
     * off has nothing to say.
     */
    void observe(int64_t frame, int64_t sceneId, int64_t cycleTick, int32_t cycleTicks, float bpm) {
        // **No cycle, no cell.** In clip mode a rack with nothing launched is
        // not anywhere, and the seconds recorded before a clip is tapped belong
        // to no cell at all - so they are left out rather than stamped onto
        // whatever the scheduler happened to answer. The same skip covers the
        // blocks before the transport has started.
        if (cycleTicks <= 0) return;
        const bool fresh = lastScene == kNoScene;
        const bool moved = sceneId != lastScene;
        const bool wrapped = cycleTick < lastTick;
        lastScene = sceneId;
        lastTick = cycleTick;
        if (!fresh && !moved && !wrapped) return;

        const int32_t at = n.load(std::memory_order_relaxed);
        if (at >= kMax) {
            bad.store(true, std::memory_order_relaxed);
            return;
        }
        marks[at].frame = frame;
        marks[at].sceneId = sceneId;
        marks[at].tick = static_cast<int32_t>(cycleTick);
        marks[at].cycleTicks = cycleTicks;
        marks[at].bpm = bpm;
        n.store(at + 1, std::memory_order_release);
    }

    /** The ring dropped frames: every index after it names the wrong moment. */
    void poison() { bad.store(true, std::memory_order_relaxed); }

    bool poisoned() const { return bad.load(std::memory_order_relaxed); }
    int32_t count() const { return n.load(std::memory_order_acquire); }
    const CaptureMark &at(int32_t i) const { return marks[i]; }

  private:
    static constexpr int64_t kNoScene = INT64_MIN;

    CaptureMark marks[kMax];
    std::atomic<int32_t> n{0};
    std::atomic<bool> bad{false};
    // Audio thread only: never read from anywhere else.
    int64_t lastScene = kNoScene;
    int64_t lastTick = -1;
};

} // namespace acidulous::seq
