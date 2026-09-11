#pragma once
#include "Rack.h"
#include <atomic>
#include <engine/core/Params.h>
#include <engine/dsp/Click.h>
#include <engine/dsp/Delay.h>
#include <engine/dsp/Limiter.h>
#include <engine/dsp/Reverb.h>

// The master section: sums the racks, feeds the reverb and delay send buses,
// then master fader -> limiter -> scene fade -> metronome -> meter.
namespace acidulous {

class MasterBus {
  public:
    enum P : int32_t {
        Volume, ReverbOn, ReverbSize, ReverbDamp, ReverbTone,
        DelayOn, DelayTime, DelayFeedback, DelayTone, DelayPingPong,
        LimiterOn, LimiterDrive, ClickOn, ClickVolume, Count
    };

    MasterBus();
    void prepare(int32_t sampleRate); // not the audio thread

    ParamSet &params() { return params_; }

    // Per block. `fade` is the scene fade multiplier (1 = none); a click may
    // be pending from the metronome.
    void process(Rack *racks, int32_t rackCount, float *outInterleaved, int32_t frames, float bpm, float fade);

    void clickAt(bool downbeat, int32_t offsetSamples) { click.trigger(downbeat, offsetSamples); }
    bool clickEnabled() const { return params_.get(ClickOn) >= 0.5f; }

    float readPeak() { return peakHold.exchange(0.0f, std::memory_order_relaxed); }
    float currentFade() const { return fadeNow.load(std::memory_order_relaxed); }

    /** Empty every tail and come back from silence. Audio thread. */
    void panic();

  private:
    ParamSet params_;
    dsp::Reverb reverb;
    dsp::Delay delay;
    dsp::Limiter<kBlockFrames> limiter;
    float sampleRate = 48000.0f;
    float panicRamp = 1.0f;
    dsp::Click click;
    Smoothed fadeSmooth;
    float sumL[kBlockFrames]{}, sumR[kBlockFrames]{};
    float sendR[kBlockFrames]{}, sendD[kBlockFrames]{};
    std::atomic<float> peakHold{0.0f};
    std::atomic<float> fadeNow{1.0f};
};

} // namespace acidulous
