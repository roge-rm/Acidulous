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
        LimiterOn, LimiterDrive, ClickOn, ClickVolume, ClickVoice, ClickDiv, ClickWhen, Count
    };

    MasterBus();
    void prepare(int32_t sampleRate); // not the audio thread

    ParamSet &params() { return params_; }

    // Per block. `fade` is the scene fade multiplier (1 = none); a click may
    // be pending from the metronome.
    void process(Rack *racks, int32_t rackCount, float *outInterleaved, int32_t frames, float bpm, float fade);

    /** [accent] is dsp::Click::Bar, Beat or Division. */
    void clickAt(int32_t accent, int32_t offsetSamples) { click.trigger(accent, offsetSamples); }
    void setClickVoice(int32_t voice) { click.setVoice(voice); }
    bool clickEnabled() const { return params_.get(ClickOn) >= 0.5f; }

    /**
     * Whether the click should sound while the transport runs.
     *
     * "Recording only" is the setting most people end up on: a metronome
     * is a thing you need while playing something in and a thing you stop
     * hearing the moment you are listening back.
     */
    bool clickAllowed(bool recordArmed) const {
        const int32_t when = static_cast<int32_t>(params_.normalized(ClickWhen) * 2.0f + 0.5f);
        if (when == 1) return recordArmed;
        if (when == 2) return false; // the count-in is not gated by this
        return true;
    }

    /**
     * How often it ticks, in ticks: a bar, or a division of the beat.
     *
     * Read off the *target* rather than the smoothed value, as every
     * stepped control must be - a smoothed one slides through the values
     * in between on its way, and here that would mean the metronome
     * briefly ticking sixteenths on its way from eighths to a bar.
     */
    int64_t clickStepTicks() const {
        const int32_t index = static_cast<int32_t>(params_.normalized(ClickDiv) * 4.0f + 0.5f);
        switch (index) {
        case 0: return 0;             // the bar, whatever the signature says it is
        case 2: return kPPQN / 2;     // eighths
        case 3: return kPPQN / 4;     // sixteenths
        case 4: return kPPQN / 3;     // eighth triplets
        default: return kPPQN;        // the beat
        }
    }

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
