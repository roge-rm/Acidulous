#pragma once
#include "Math.h"
#include <cstdint>
#include <vector>

// A send/return reverb in the Schroeder topology: eight parallel damped combs
// feeding four series allpasses, per channel, with the right channel's delay
// lines offset a few samples for width. The classic public-domain layout,
// implemented here from scratch. Size sets comb feedback, damp rolls the highs
// off inside the loop, tone is a one-pole lowpass on the return.
namespace acidulous::dsp {

class Reverb {
  public:
    void prepare(int32_t sampleRate) {
        const float scale = static_cast<float>(sampleRate) / 44100.0f;
        static const int kComb[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int kAll[kAllpasses] = {556, 441, 341, 225};
        for (int c = 0; c < 2; ++c) {
            const int spread = c == 0 ? 0 : 23;
            for (int i = 0; i < kCombs; ++i) combs[c][i].init(static_cast<int>(kComb[i] * scale) + spread);
            for (int i = 0; i < kAllpasses; ++i) allpasses[c][i].init(static_cast<int>(kAll[i] * scale) + spread);
        }
        this->sampleRate = static_cast<float>(sampleRate);
        reset();
    }

    void reset() {
        for (int c = 0; c < 2; ++c) {
            for (auto &x : combs[c]) x.clear();
            for (auto &x : allpasses[c]) x.clear();
            lp[c] = 0.0f;
        }
    }

    // size, damp, tone in 0..1.
    void set(float size, float damp, float tone) {
        feedback = 0.70f + clampf(size, 0.0f, 1.0f) * 0.28f;
        damping = clampf(damp, 0.0f, 1.0f) * 0.4f + 0.05f;
        const float hz = 1000.0f * std::exp2(clampf(tone, 0.0f, 1.0f) * 4.0f); // 1 kHz .. 16 kHz
        toneCoeff = 1.0f - std::exp(-kTwoPi * hz / sampleRate);
    }

    // In: a mono send. Out: added to L/R (100% wet).
    void process(const float *in, float *outL, float *outR, int32_t frames) {
        for (int32_t i = 0; i < frames; ++i) {
            const float x = in[i] * 0.015f;
            for (int c = 0; c < 2; ++c) {
                float acc = 0.0f;
                for (auto &comb : combs[c]) acc += comb.process(x, feedback, damping);
                for (auto &ap : allpasses[c]) acc = ap.process(acc);
                lp[c] += (acc - lp[c]) * toneCoeff;
                if (c == 0) outL[i] += lp[c]; else outR[i] += lp[c];
            }
        }
    }

  private:
    static constexpr int kCombs = 8;
    static constexpr int kAllpasses = 4;

    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float store = 0.0f;
        void init(int len) { buf.assign(static_cast<size_t>(len), 0.0f); idx = 0; store = 0.0f; }
        void clear() { for (auto &v : buf) v = 0.0f; store = 0.0f; }
        float process(float x, float fb, float damp) {
            const float y = buf[static_cast<size_t>(idx)];
            store = y * (1.0f - damp) + store * damp;
            buf[static_cast<size_t>(idx)] = x + store * fb;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return y;
        }
    };
    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
        void init(int len) { buf.assign(static_cast<size_t>(len), 0.0f); idx = 0; }
        void clear() { for (auto &v : buf) v = 0.0f; }
        float process(float x) {
            const float y = buf[static_cast<size_t>(idx)];
            buf[static_cast<size_t>(idx)] = x + y * 0.5f;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return y - x;
        }
    };

    Comb combs[2][kCombs];
    Allpass allpasses[2][kAllpasses];
    float lp[2]{};
    float feedback = 0.84f, damping = 0.25f, toneCoeff = 0.5f;
    float sampleRate = 48000.0f;
};

} // namespace acidulous::dsp
