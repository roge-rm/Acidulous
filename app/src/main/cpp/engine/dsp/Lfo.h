#pragma once
#include "Math.h"
#include <cstdint>

// A tempo-aware LFO. Rates are note values in quarter notes; phase can be
// derived from the transport tick so the sweep stays locked to the bar.
namespace acidulous::dsp {

class Lfo {
  public:
    static constexpr int kRates = 8;
    static constexpr float kBeats[kRates] = {0.25f, 0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f, 32.0f}; // 1/16 .. 8 bars
    static const char *rateName(int i) {
        static const char *n[kRates] = {"1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8"};
        return n[i < 0 ? 0 : (i >= kRates ? kRates - 1 : i)];
    }
    // Phase 0..1 for a note-value rate from a tick position (240 PPQN).
    static float phaseAt(int64_t tick, int rateIndex) {
        const float period = kBeats[rateIndex < 0 ? 0 : (rateIndex >= kRates ? kRates - 1 : rateIndex)] * 240.0f;
        const float t = static_cast<float>(tick % static_cast<int64_t>(period));
        return t / period;
    }
    // Phase advance per sample for a note-value rate at a tempo.
    static float phaseInc(int rateIndex, float bpm, float sr) {
        const float beats = kBeats[rateIndex < 0 ? 0 : (rateIndex >= kRates ? kRates - 1 : rateIndex)];
        return bpm / (beats * 60.0f * sr);
    }
    static float sine(float phase) { return std::sin(phase * kTwoPi); }
    static float triangle(float phase) { return phase < 0.5f ? 4.0f * phase - 1.0f : 3.0f - 4.0f * phase; }
};

} // namespace acidulous::dsp
