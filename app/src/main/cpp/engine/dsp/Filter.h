#pragma once
#include "Math.h"

// Topology-preserving state-variable filter (Simper). Stable under fast
// modulation, which is the whole point for an acid line: the cutoff moves
// every sample and must not blow up or zipper.
namespace acidulous::dsp {

class Svf {
  public:
    void setSampleRate(float sr) { sampleRate = sr; }

    // resonance 0..1 -> k from 2 (no resonance) down to a self-oscillating edge.
    void set(float cutoffHz, float resonance) {
        const float fc = clampf(cutoffHz, 20.0f, sampleRate * 0.45f);
        g = std::tan(kPi * fc / sampleRate);
        k = 2.0f - 1.96f * clampf(resonance, 0.0f, 1.0f);
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
    }

    void reset() { ic1eq = ic2eq = 0.0f; }

    /**
     * What to multiply the band-pass output by for a peak gain of one.
     *
     * `lp` and `hp` peak at unity; `bp` peaks at 1/k, so at no resonance it
     * arrives six decibels under its neighbours for no reason anybody asked
     * for. Cascade two for a 12 dB slope and it is twelve. That is a filter
     * *type* costing level rather than changing character, and it is why
     * Mosaic's band-passed patches measured twenty decibels under the rest
     * of the bank. The narrowness is real and stays; the scaling was not.
     */
    float bandNorm() const { return k; }

    // All three outputs of one step, for filters that mix them.
    struct Out { float lp, bp, hp; };
    Out step(float v0) {
        const float v3 = v0 - ic2eq;
        const float v1 = a1 * ic1eq + a2 * v3;
        const float v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = undenormal(2.0f * v1 - ic1eq);
        ic2eq = undenormal(2.0f * v2 - ic2eq);
        return {v2, v1, v0 - k * v1 - v2};
    }
    float resonanceK() const { return k; }

    // Band output of the same step; call instead of lowpass(), not as well.
    float bandpass(float v0) {
        const float v3 = v0 - ic2eq;
        const float v1 = a1 * ic1eq + a2 * v3;
        const float v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = undenormal(2.0f * v1 - ic1eq);
        ic2eq = undenormal(2.0f * v2 - ic2eq);
        return v1;
    }

    float highpass(float v0) {
        const float v3 = v0 - ic2eq;
        const float v1 = a1 * ic1eq + a2 * v3;
        const float v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = undenormal(2.0f * v1 - ic1eq);
        ic2eq = undenormal(2.0f * v2 - ic2eq);
        return v0 - k * v1 - v2;
    }

    float lowpass(float v0) {
        const float v3 = v0 - ic2eq;
        const float v1 = a1 * ic1eq + a2 * v3;
        const float v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = undenormal(2.0f * v1 - ic1eq);
        ic2eq = undenormal(2.0f * v2 - ic2eq);
        return v2;
    }

  private:
    float sampleRate = 48000.0f;
    float g = 0.1f, k = 2.0f, a1 = 1.0f, a2 = 0.0f, a3 = 0.0f;
    float ic1eq = 0.0f, ic2eq = 0.0f;
};

} // namespace acidulous::dsp
