#pragma once
#include "Rack.h"
#include <atomic>
#include <engine/core/Params.h>
#include <engine/dsp/Click.h>
#include <engine/dsp/Limiter.h>
#include <engine/effect/Effect.h>

// The master section: sums the racks, feeds two send buses, then master
// fader -> limiter -> scene fade -> metronome -> meter.
namespace acidulous {

/** How many send buses the master carries. */
constexpr int32_t kSendSlots = 2;
/** How many insert slots the master carries, before its fader and limiter. */
constexpr int32_t kMasterInsertSlots = 2;

class MasterBus {
  public:
    /**
     * What is left of the master's own parameters.
     *
     * **The sends are not here any more.** They were nine entries - reverb on,
     * size, damp, tone, delay on, time, feedback, tone, ping-pong - describing
     * two fixed boxes, and the boxes are now slots holding any of the
     * fourteen effects, each with its own parameter table. A send's parameters
     * belong to whatever is in it, addressed as `send1` and `send2`, the same
     * way an insert's belong to the effect in the slot.
     */
    enum P : int32_t {
        Volume, LimiterOn, LimiterDrive,
        ClickOn, ClickVolume, ClickVoice, ClickDiv, ClickWhen, Count
    };

    MasterBus();
    void prepare(int32_t sampleRate); // not the audio thread

    ParamSet &params() { return params_; }

    // Per block. `fade` is the scene fade multiplier (1 = none); a click may
    // be pending from the metronome. The tick range is passed on to the sends,
    // which may hold anything an insert slot can hold - and four of those sync
    // their LFOs to the transport rather than to the wall.
    void process(Rack *racks, int32_t rackCount, float *outInterleaved, int32_t frames, float bpm, float fade,
                 int64_t tickStart = 0, int64_t tickEnd = 0);

    /**
     * Put [next] on send [slot] and hand back what was there, for retiring
     * off this thread. The audio thread's half of a mount; see Rack::swapEffect,
     * which this is the master's copy of.
     */
    Effect *swapSend(int32_t slot, Effect *next) {
        if (slot < 0 || slot >= kSendSlots) return next; // the caller retires it
        Effect *old = sends[slot];
        sends[slot] = next;
        return old;
    }

    /** What is on send [slot], or null. Its own ParamSet is its parameters. */
    Effect *send(int32_t slot) { return (slot >= 0 && slot < kSendSlots) ? sends[slot] : nullptr; }

    /** Put [next] on master insert [slot]; returns what was there, for the caller to retire. */
    Effect *swapInsert(int32_t slot, Effect *next) {
        if (slot < 0 || slot >= kMasterInsertSlots) return next;
        Effect *old = inserts[slot];
        inserts[slot] = next;
        return old;
    }
    Effect *insert(int32_t slot) { return (slot >= 0 && slot < kMasterInsertSlots) ? inserts[slot] : nullptr; }

    /** [accent] is dsp::Click::Bar, Beat or Division. */
    void clickAt(int32_t accent, int32_t offsetSamples) { click.trigger(accent, offsetSamples); }
    void setClickVoice(int32_t voice) { click.setVoice(voice); }
    bool clickEnabled() const { return params_.get(ClickOn) >= 0.5f; }
    /**
     * A count-in is running, so the click sounds whatever the metronome says.
     *
     * The engine already queues count-in clicks without asking whether the
     * metronome is on - "a count-in always clicks, that is the whole of what
     * it is" - but this bus only *rendered* them when it was. With the
     * metronome off you got the wait and no count, which is the worst of both
     * and is what Dan reported. Worse, `Click::trigger` queues and
     * `Click::process` is what empties the queue: never rendering left four
     * stale clicks sitting in it with stale sample offsets, to go off later at
     * the wrong moment.
     */
    void setCountingIn(bool on) { countingIn = on; }
    bool clickAudible() const { return clickEnabled() || countingIn; }

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
    Effect *sends[kSendSlots]{};
    Effect *inserts[kMasterInsertSlots]{};
    dsp::Limiter<kBlockFrames> limiter;
    float sampleRate = 48000.0f;
    float panicRamp = 1.0f;
    dsp::Click click;
    bool countingIn = false;
    Smoothed fadeSmooth;
    float sumL[kBlockFrames]{}, sumR[kBlockFrames]{};
    /** What each send is fed, summed mono across the racks. */
    float sendSum[kSendSlots][kBlockFrames]{};
    /**
     * The pair a send is *processed* in.
     *
     * An insert works in place on a stereo pair; a send bus is a mono sum that
     * gets added to the mix. So the sum is fanned into this, the effect runs on
     * it, and the result is added in - which is also what lets a send be
     * something with a stereo image of its own, like the chorus or the width.
     */
    float wetL[kBlockFrames]{}, wetR[kBlockFrames]{};
    std::atomic<float> peakHold{0.0f};
    std::atomic<float> fadeNow{1.0f};
};

} // namespace acidulous
