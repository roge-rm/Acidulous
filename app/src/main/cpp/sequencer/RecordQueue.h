#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>

// Live MIDI, stamped with where the transport was when it arrived, on its way
// from the audio thread to the document.
//
// Single producer (the audio thread, in PlayerEngine::pollMidiIn), single
// consumer (the UI's recorder draining through JNI). Wait-free on both sides.
// The audio thread never waits; if the UI falls 256 events behind, events drop
// and the recorder will simply miss them - preferable to a stall.

namespace acidulous::seq {

// Two of the `cmd` values are not MIDI status bytes at all. They sit above
// 0xf0, where a status byte would be a system message and never reaches the
// recorder, so there is nothing for them to collide with.
constexpr uint8_t kRecParam = 0xf0;         // a knob: p1 is the Unit
constexpr uint8_t kRecNoteExpression = 0xf1; // a finger: p1 is the note, p2 the Expr

struct RecordedEvent {
    int64_t absTick;         // clock position: lets the recorder measure lengths across loop points
    int64_t sceneId;         // which scene was playing
    int64_t tickInIteration; // offset into that scene's current pass (the clip loops within it)
    int32_t rack;
    uint8_t cmd;             // 0x80 / 0x90 / 0xb0 with channel bits cleared, or one of the kRec* above
    uint8_t p1;              // parameter: the Unit. expression: the note it belongs to
    uint8_t p2;              // expression: which of the note's three curves
    int32_t paramIndex = 0;  // parameter events only
    float value = 0.0f;      // parameter and expression events, normalised
};

class RecordQueue {
  public:
    // Power of two. A thousand and not the original two hundred and fifty
    // six because per-note expression arrives per finger rather than per
    // gesture: five fingers bending, pressing and sliding at a controller's
    // own rate is thousands of events a second, and the UI drains twelve
    // times a second.
    static constexpr size_t kSize = 1024;

    // Audio thread.
    bool push(const RecordedEvent &e) {
        const size_t w = wrIdx.load(std::memory_order_relaxed);
        const size_t next = (w + 1) & (kSize - 1);
        if (next == rdIdx.load(std::memory_order_acquire)) {
            dropped.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        buffer[w] = e;
        wrIdx.store(next, std::memory_order_release);
        return true;
    }

    // UI thread.
    bool pop(RecordedEvent &out) {
        const size_t r = rdIdx.load(std::memory_order_relaxed);
        if (r == wrIdx.load(std::memory_order_acquire)) {
            return false;
        }
        out = buffer[r];
        rdIdx.store((r + 1) & (kSize - 1), std::memory_order_release);
        return true;
    }

    uint32_t droppedCount() const { return dropped.load(std::memory_order_relaxed); }

  private:
    RecordedEvent buffer[kSize];
    std::atomic<size_t> wrIdx{0};
    std::atomic<size_t> rdIdx{0};
    std::atomic<uint32_t> dropped{0};
};

} // namespace acidulous::seq
