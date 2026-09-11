#pragma once
#include <cstddef>
#include <cstdint>

// What is coming in from outside, for whatever wants it.
//
// The engine has always been a closed system: oscillators in, speakers out.
// A vocoder cannot be, and neither can sampling something you play into the
// phone. So one block of input audio is published here each time the engine
// renders, and anything on the audio thread can read it - the capture
// recorder, a monitor path, or a machine like Cipher.
//
// Audio thread only. The pointer is valid for the duration of one block and
// no longer; nobody may keep it.
namespace acidulous {

class InputBus {
  public:
    static InputBus &get() {
        static InputBus bus;
        return bus;
    }

    void publish(const float *interleavedStereo, int32_t frames) {
        data = interleavedStereo;
        count = interleavedStereo != nullptr ? frames : 0;
    }

    /** Interleaved stereo, [frames] long, or null when nothing is open. */
    const float *block() const { return data; }
    int32_t frames() const { return count; }
    bool live() const { return data != nullptr && count > 0; }

    /** Mono sum of one frame, which is what a modulator usually wants. */
    float mono(int32_t frame) const {
        if (!live() || frame < 0 || frame >= count) return 0.0f;
        return 0.5f * (data[static_cast<size_t>(frame) * 2] + data[static_cast<size_t>(frame) * 2 + 1]);
    }

  private:
    const float *data = nullptr;
    int32_t count = 0;
};

} // namespace acidulous
