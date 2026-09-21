#pragma once
#include <cstdint>
#include <engine/core/Constants.h>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/Lfo.h>
#include <engine/dsp/Math.h>
#include <engine/effect/Effect.h>

// The first wave of insert effects. Each is the classic thing plus the one
// extra that takes it somewhere.
namespace acidulous::effect {

#define ACIDULOUS_EFFECT_COMMON(Name)                                         \
    const char *typeName() const override { return #Name; }                  \
    const ParamDef *paramDefs(int32_t &count) const override;                 \
    void prepare(int32_t sampleRate) override;                                \
    void reset() override;                                                    \
    bool process(float *L, float *R, int32_t frames, bool stereoIn) override;

class Delay final : public Effect {
  public:
    enum P { Time, Feedback, Tone, PingPong, Mix, Duck, Wobble, Gain, Count };
    Delay() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Delay)
    void onBlock(int64_t, int64_t, float bpm) override { this->bpm = bpm; }
  private:
    dsp::DelayLine line[2];
    float readSamples = 24000.0f, lp[2]{}, duckEnv = 0.0f, wobblePhase = 0.0f, sr = 48000.0f, bpm = 120.0f;
};

/**
 * Reverb - a room, and four things a room cannot do.
 *
 * The classic half is Freeverb's: eight combs and four allpasses a side, with
 * `size`, `damp` and `predelay` doing what they always do. Everything after
 * that is here because a rack with one reverb in it should not only be able to
 * make rooms.
 *
 *   - **Shimmer.** The tail is fed back an octave up, so each pass climbs and
 *     the room turns into a slowly rising chord. The pitch shift is the cheap
 *     honest one - a delay line read at the wrong speed, two taps crossfaded -
 *     and it lives *inside* the feedback, which is what makes it build rather
 *     than just add a high part.
 *   - **Bits** and **crush.** The tail, and only the tail, goes through a bit
 *     quantiser and a sample-and-hold. A reverb made of eight-bit memory:
 *     the dry stays clean and the room behind it is a cheap sampler, which is
 *     a thing hardware did by accident for years and nobody offers on purpose.
 *   - **Wobble.** The comb lengths drift, slowly and out of step, so a long
 *     tail is never quite still. Tape does this; concrete does not.
 *
 * Freeze and gate were always here: one holds the tail forever, the other cuts
 * it off flat.
 */
class Reverb final : public Effect {
  public:
    enum P { Size, Damp, Tone, PreDelay, Mix, Freeze, Gate, Shimmer, Bits, Crush, Wobble, Gain, Count };
    Reverb() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Reverb)
  private:
    struct Comb { dsp::DelayLine line; float store = 0.0f; int32_t len = 1; };
    struct Allpass { dsp::DelayLine line; int32_t len = 1; };
    /** Eight combs and four allpasses a side at full quality, half at lean. */
    static constexpr int32_t kCombs = 8;
    static constexpr int32_t kAps = 4;
    Comb combs[2][kCombs];
    Allpass aps[2][kAps];
    dsp::DelayLine pre[2];
    /** The shimmer's own line: the tail, re-read an octave up. */
    dsp::DelayLine shimmerLine[2];
    float shimmerPhase[2]{}, shimmerFb[2]{};
    /** The shimmer path's own DC blocker; see the note where it is used. */
    float shimDcX[2]{}, shimDcY[2]{}, shimLp[2]{};
    float crushAcc[2]{}, crushHeld[2]{};
    float wobblePhase = 0.0f;
    float lp[2]{}, gateEnv = 0.0f, inputEnv = 0.0f, sr = 48000.0f;
    int32_t gateHold = 0;
};

class Eq final : public Effect {
  public:
    enum P { LowGain, LowFreq, MidGain, MidFreq, MidQ, HighGain, HighFreq, Tilt, Gain, Count };
    Eq() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Eq)
  private:
    dsp::Biquad low[2], mid[2], high[2], tiltLo[2], tiltHi[2];
    float sr = 48000.0f;
};

class Distortion final : public Effect {
  public:
    enum P { Drive, Tone, Mix, Mode, Bias, Gain, Count };
    Distortion() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Distortion)
  private:
    dsp::Biquad tone[2];
    // The downsampling filter runs at twice the engine rate, which is why it
    // is not the tone filter reused.
    dsp::Biquad halfband[2];
    float dcIn[2]{}, dcOut[2]{}, prevIn[2]{}, sr = 48000.0f;
};

class Compressor final : public Effect {
  public:
    enum P { Threshold, Ratio, Attack, Release, Makeup, Pump, PumpRate, Gain, Count };
    Compressor() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Compressor)
    void onBlock(int64_t tickStart, int64_t, float bpm) override { tick = tickStart; this->bpm = bpm; }
  private:
    float env = 0.0f, gain = 1.0f, sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

class Filter final : public Effect {
  public:
    enum P { Cutoff, Reso, Mode, LfoRate, LfoDepth, EnvDepth, Gain, Count };
    Filter() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Filter)
    void onBlock(int64_t tickStart, int64_t, float bpm) override { tick = tickStart; this->bpm = bpm; }
  private:
    dsp::Svf svf[2];
    float follower = 0.0f, sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

class Bitcrusher final : public Effect {
  public:
    enum P { Bits, Rate, Jitter, Tone, Mix, Gain, Count };
    Bitcrusher() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Bitcrusher)
  private:
    float hold[2]{}, phase = 0.0f, period = 1.0f, sr = 48000.0f;
    uint32_t rng = 0x2545F491u;
    dsp::Biquad tone[2];
};

class Phaser final : public Effect {
  public:
    enum P { Rate, Depth, Feedback, Stages, Spread, Mix, Gain, Count };
    Phaser() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Phaser)
    void onBlock(int64_t tickStart, int64_t, float) override { tick = tickStart; }
  private:
    dsp::Biquad ap[2][8];
    float fb[2]{}, sr = 48000.0f;
    int64_t tick = 0;
};

/**
 * Chorus - several detuned copies, and one that will not sit still.
 *
 * The gap the first nine left: a flanger at low feedback is not a chorus, it is
 * a flanger somebody turned down. A chorus is *voices* - two, three or four
 * taps at different delays, each swept by its own phase of the LFO, so what
 * comes back is a section rather than a doubling.
 *
 * The extra is `drift`: a slow random walk added to each voice's delay, which
 * is what a bucket-brigade line does when its clock is not quite steady. At
 * nothing it is a clean digital chorus; turned up, no two voices agree about
 * the tuning for very long.
 */
class Chorus final : public Effect {
  public:
    enum P { Rate, Depth, Voices, Spread, Drift, Mix, Gain, Count };
    Chorus() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Chorus)
    void onBlock(int64_t tickStart, int64_t, float) override { tick = tickStart; }
  private:
    dsp::DelayLine line[2];
    float drift[4]{}, sr = 48000.0f;
    uint32_t rng = 0x9e3779b9u;
    int64_t tick = 0;
};

/**
 * Tremolo, and the same lever turned into an auto-pan.
 *
 * Nothing in the rack modulated amplitude at all, which is a strange gap - it
 * is the oldest effect there is. The classic part is rate, depth and a shape
 * to sweep with.
 *
 * The extra is `pan`, which is not a second effect but the same LFO arriving at
 * the two channels further and further out of step: at nothing both channels
 * duck together and it is a tremolo, at full they are opposite and it is an
 * auto-pan, and everywhere between is the wobble that neither has a name for.
 * `skew` bends the waveform's duty so the dip can be a stab or a swell.
 */
class Tremolo final : public Effect {
  public:
    enum P { Rate, Depth, Shape, Pan, Skew, Mix, Gain, Count };
    Tremolo() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Tremolo)
    void onBlock(int64_t tickStart, int64_t, float b) override { tick = tickStart; bpm = b; }
  private:
    float sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

/**
 * Width - the stereo image, which nothing else here could touch.
 *
 * Mid and side, with the side scaled: under one it collapses toward mono, over
 * one it opens past where it was recorded. That is the classic part, and it is
 * also a diagnostic - a machine that has quietly gone mono is obvious the
 * moment you can widen it and nothing happens.
 *
 * Two extras, both of which are what mastering actually does with this.
 * `below` returns everything under a frequency to the centre, because width in
 * the bass is what makes a mix fall apart on a club system and on a phone
 * speaker alike. `haas` delays one side by up to twenty milliseconds, which is
 * width the side channel cannot give you - it is the ear's own arrival-time
 * cue rather than a level trick, and it survives a mono fold as a comb rather
 * than as silence.
 */
class Width final : public Effect {
  public:
    enum P { Amount, Below, Haas, Rotate, Gain, Count };
    Width() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Width)
  private:
    dsp::DelayLine haasLine;
    dsp::Biquad lowL, lowR;
    float sr = 48000.0f;
};

/**
 * Frequency shifter - the one effect here that nothing else can imitate.
 *
 * Not a pitch shifter. A pitch shifter multiplies every partial by the same
 * ratio and the sound keeps its harmonic series; this *adds* the same number of
 * hertz to every partial, so 100, 200, 300 becomes 180, 280, 380 and the series
 * is no longer harmonic at all. A few hertz and it is a slow phasing that never
 * repeats, because the two sides beat against each other forever; a few hundred
 * and everything turns to struck metal.
 *
 * The classic part is a Hilbert pair - two allpass chains whose outputs stay 90
 * degrees apart across the band - and a quadrature oscillator to turn the
 * spectrum by. The extra is `spread`: the two channels are shifted in opposite
 * directions, which nothing acoustic can do and which makes a mono source into
 * something that will not sit still.
 *
 * `feedback` sends the shifted output back in, so each pass is shifted again
 * and the partials walk away in a ladder.
 */
class Shifter final : public Effect {
  public:
    enum P { Shift, Fine, Spread, Feedback, Mix, Gain, Count };
    Shifter() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Shifter)
  private:
    // A second-order allpass, the section a Hilbert chain is built from.
    struct Ap2 {
        float a = 0.0f, x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
        float process(float x) {
            const float y = a * a * (x + y2) - x2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
        void clear() { x1 = x2 = y1 = y2 = 0.0f; }
    };
    Ap2 apI[2][4], apQ[2][4];
    float delayed[2]{}, fb[2]{}, phase = 0.0f, sr = 48000.0f;
};

/**
 * Harmonizer - intervals that belong to the key, not to the knob.
 *
 * Every pitch shifter in a box like this shifts by a fixed number of
 * semitones, which means a "third" is a major third over every note you play
 * and half of them are wrong. This one shifts by a number of *scale degrees*:
 * it listens for what note is arriving, finds that note's place in the scale,
 * counts up the degrees you asked for, and shifts by whatever that turns out
 * to be - a major third here, a minor third there, the way a second singer
 * would. The thirty-three scales are the ones the modifiers already know, so a
 * part harmonised here agrees with a part quantised there.
 *
 * Two voices, because two is what a harmony part is and four is a chorus.
 * Below the tracker's confidence - on a drum, on noise, on silence between
 * phrases - it falls back to the plain chromatic interval rather than guessing
 * a key, since a wrong note is worse than an unmusical one.
 *
 * The shifter itself is the honest cheap one: a delay line read at the wrong
 * speed, with two taps half a window apart crossfaded so the wrap never lands
 * in the middle of a note. `window` is that length, and it is the trade -
 * short is tight and burbles, long is smooth and smears.
 */
class Harmonizer final : public Effect {
  public:
    enum P { Interval, Interval2, Scale, Key, Window, Feedback, Mix, Gain, Count };
    Harmonizer() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Harmonizer)
  private:
    /** One shifted voice: a read point sliding through the line. */
    struct Voice {
        float phase = 0.0f;
        float ratio = 1.0f;   // smoothed, so an interval change is a glide
    };
    dsp::DelayLine line[2];
    Voice voice[2][2];        // [channel][voice]
    // The tracker: zero crossings over a window, the same cheap thing Cipher
    // steers its carrier with. A thousandth of the cost of autocorrelation and
    // accurate enough to name a note.
    float zeroPrev = 0.0f, trackedHz = 0.0f;
    int32_t zeroCount = 0, zeroWindow = 0;
    float confidence = 0.0f;
    float fb[2]{}, sr = 48000.0f;
};

class Flanger final : public Effect {
  public:
    enum P { Rate, Depth, Feedback, Negative, Spread, Mix, Gain, Count };
    Flanger() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Flanger)
    void onBlock(int64_t tickStart, int64_t, float bpm) override { tick = tickStart; this->bpm = bpm; }
  private:
    dsp::DelayLine line[2];
    float sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

/**
 * Gate - the one an amp asks for, and the sixteenth insert.
 *
 * A gate is four knobs everybody knows: shut below a level, open fast, stay
 * open a while, and fall away. Two things here are not on a pedal.
 *
 * **`key`** filters the *detector* and not the audio. A gate in front of a
 * guitar amp is listening to a pickup that hears mains hum, a room and a
 * player's hand as well as the string, and all of those are low. Sliding the
 * detector's high-pass up means the gate opens for a pick and not for a
 * building, while the note it lets through keeps its bottom end - which is
 * the difference between a gate and a high-pass filter with attitude.
 *
 * **`duck`** is how far down closed is. All the way is what a gate does;
 * 12 dB down is what you want on drums, where silence between hits is a
 * hole and the room going quiet is a tightening.
 *
 * There is deliberately **no `mix`**. Every other insert has one and it
 * would be an anti-control here: half a gate is the noise at half level,
 * which is the thing the gate was added to remove. Without one the base
 * class leaves it alone on a send bus, where gating the send is exactly what
 * the knob would have been asked for anyway.
 *
 * **Not a parameter: a threshold that learns the hiss.** It was designed and
 * dropped. Making `threshold` mean dBFS with the learning off and dB above a
 * measured floor with it on is one knob with two units, and a knob that
 * needs a sentence under it to say which one it is in is the fault the house
 * rule about help text exists to catch.
 */
class Gate final : public Effect {
  public:
    enum P { Threshold, Hyst, Attack, Hold, Release, Duck, Key, Gain, Count };
    Gate() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Gate)
  private:
    /**
     * One detector for the pair, always.
     *
     * Two independent gates on a stereo signal open at slightly different
     * moments, and what that sounds like is the image stepping sideways at
     * every note onset. Nobody has ever wanted that, so the detector takes
     * the louder of the two and both channels get the same gain.
     */
    float env = 0.0f, gain = 0.0f, sr = 48000.0f;
    float holdLeft = 0.0f; // seconds still to run before the release starts
    bool open = false;
    dsp::Svf key[2];
    float keyHz = -1.0f; // what the key filters are set to, so they are not rebuilt per block
};

#undef ACIDULOUS_EFFECT_COMMON

} // namespace acidulous::effect
