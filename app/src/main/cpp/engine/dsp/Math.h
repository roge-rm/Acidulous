#pragma once
#include <cmath>

namespace acidulous::dsp {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 6.28318530717959f;

inline float mtof(float note) { return 440.0f * std::exp2((note - 69.0f) / 12.0f); }
inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

// Pade-style tanh: cheap, monotonic, good to ~1e-3 in the range that matters.
inline float fastTanh(float x) {
    if (x < -4.0f) return -1.0f;
    if (x > 4.0f) return 1.0f;
    const float x2 = x * x;
    return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Per-sample one-pole coefficient for a time constant in seconds.
inline float onePoleCoeff(float seconds, float sampleRate) {
    if (seconds <= 0.0f) return 1.0f;
    return 1.0f - std::exp(-1.0f / (seconds * sampleRate));
}

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

} // namespace acidulous::dsp
