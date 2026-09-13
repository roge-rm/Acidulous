#pragma once
#include <cmath>

namespace acidulous::dsp {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 6.28318530717959f;

inline float mtof(float note) { return 440.0f * std::exp2((note - 69.0f) / 12.0f); }
inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }

// Pade-style tanh: cheap, monotonic, good to ~1e-3 in the range that matters.
//
// The seam is at three and not at four, and that is arithmetic rather than
// taste. Differentiating the rational part gives 9(x^2-9)^2 over the square
// of its denominator, so it is stationary at x = 3 - and there it evaluates
// to 3(27+9)/(27+81), which is exactly one. Three is therefore the one place
// the curve and the clamp agree in value *and* in slope.
//
// At four they do not: the rational part has climbed to 1.005848 by then, so
// the old seam stepped back down by 5.8e-3 on the way out and the function
// was neither monotonic nor a tanh - it returned more than one for every
// |x| between 3 and 4. Reachable wherever a drive knob multiplies before
// this is called.
inline float fastTanh(float x) {
    if (x < -3.0f) return -1.0f;
    if (x > 3.0f) return 1.0f;
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
