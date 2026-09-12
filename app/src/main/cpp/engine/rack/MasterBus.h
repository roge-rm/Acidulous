#pragma once
#include "Rack.h"
#include <atomic>
#include <cmath>

// Sums the racks. Master reverb/delay sends and the limiter arrive in M5.
namespace acidulous {

class MasterBus {
  public:
    void mix(Rack *racks, int32_t rackCount, float *outInterleaved, int32_t frames) {
        for (int32_t i = 0; i < frames * 2; ++i) outInterleaved[i] = 0.0f;
        for (int32_t r = 0; r < rackCount; ++r) {
            if (!racks[r].isActive()) continue;
            const float *L = racks[r].bufL;
            const float *R = racks[r].bufR;
            for (int32_t i = 0; i < frames; ++i) {
                outInterleaved[i * 2] += L[i];
                outInterleaved[i * 2 + 1] += R[i];
            }
        }
        float peak = 0.0f;
        for (int32_t i = 0; i < frames * 2; ++i) {
            float v = outInterleaved[i] * gain;
            if (v > 1.0f) v = 1.0f; else if (v < -1.0f) v = -1.0f; // hard ceiling until the limiter exists
            outInterleaved[i] = v;
            const float a = std::fabs(v);
            if (a > peak) peak = a;
        }
        if (peak > peakHold.load(std::memory_order_relaxed)) peakHold.store(peak, std::memory_order_relaxed);
    }

    void setGain(float g) { gain = g; }
    float readPeak() { return peakHold.exchange(0.0f, std::memory_order_relaxed); }

  private:
    float gain = 0.8f;
    std::atomic<float> peakHold{0.0f};
};

} // namespace acidulous
