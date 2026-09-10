#pragma once
#include "Math.h"
#include <cstdint>

// The metronome's voice: a short decaying sine, higher on the downbeat.
namespace acidulous::dsp {

class Click {
  public:
    void prepare(int32_t sampleRate) {
        this->sampleRate = static_cast<float>(sampleRate);
        decayCoeff = onePoleCoeff(0.025f, this->sampleRate);
    }

    // Start a click `offset` samples into the next process() call.
    void trigger(bool downbeat, int32_t offset) {
        pendingOffset = offset;
        pendingDownbeat = downbeat;
        pending = true;
    }

    void process(float *L, float *R, int32_t frames, float volume) {
        for (int32_t i = 0; i < frames; ++i) {
            if (pending && i >= pendingOffset) {
                pending = false;
                env = 1.0f;
                phase = 0.0f;
                inc = (pendingDownbeat ? 2000.0f : 1400.0f) / sampleRate;
            }
            if (env > 1e-4f) {
                const float s = std::sin(phase * kTwoPi) * env * volume * 0.6f;
                phase += inc;
                if (phase >= 1.0f) phase -= 1.0f;
                env -= env * decayCoeff;
                L[i] += s;
                R[i] += s;
            }
        }
        pending = false; // an offset past the block is dropped rather than carried
    }

  private:
    float sampleRate = 48000.0f;
    float decayCoeff = 0.001f;
    float env = 0.0f, phase = 0.0f, inc = 0.0f;
    bool pending = false, pendingDownbeat = false;
    int32_t pendingOffset = 0;
};

} // namespace acidulous::dsp
