#pragma once
#include <cstdint>
#include <engine/core/Constants.h>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/Lfo.h>
#include <engine/dsp/Math.h>
#include <engine/effect/Effect.h>

// The first wave of insert effects. Each is the classic thing plus the extra
// that takes it somewhere.
namespace acidulous::effect {

#define ACIDULOUS_EFFECT_COMMON(Name)                                         \
    const char *typeName() const override { return #Name; }                  \
    const ParamDef *paramDefs(int32_t &count) const override;                 \
    void prepare(int32_t sampleRate) override;                                \
    void reset() override;                                                    \
    bool process(float *L, float *R, int32_t frames, bool stereoIn) override;

class Delay final : public Effect {
  public:
    enum P { Time, Feedback, Tone, PingPong, Mix, Duck, Wobble, Count };
    Delay() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Delay)
    void onBlock(int64_t, int64_t, float bpm) override { this->bpm = bpm; }
  private:
    dsp::DelayLine line[2];
    float readSamples = 24000.0f, lp[2]{}, duckEnv = 0.0f, wobblePhase = 0.0f, sr = 48000.0f, bpm = 120.0f;
};

class Reverb final : public Effect {
  public:
    enum P { Size, Damp, Tone, PreDelay, Mix, Freeze, Gate, Count };
    Reverb() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Reverb)
  private:
    struct Comb { dsp::DelayLine line; float store = 0.0f; int32_t len = 1; };
    struct Allpass { dsp::DelayLine line; int32_t len = 1; };
    Comb combs[2][8];
    Allpass aps[2][4];
    dsp::DelayLine pre[2];
    float lp[2]{}, gateEnv = 0.0f, inputEnv = 0.0f, sr = 48000.0f;
    int32_t gateHold = 0;
};

class Eq final : public Effect {
  public:
    enum P { LowGain, LowFreq, MidGain, MidFreq, MidQ, HighGain, HighFreq, Tilt, Count };
    Eq() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Eq)
  private:
    dsp::Biquad low[2], mid[2], high[2], tiltLo[2], tiltHi[2];
    float sr = 48000.0f;
};

class Distortion final : public Effect {
  public:
    enum P { Drive, Tone, Mix, Mode, Bias, Count };
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
    enum P { Threshold, Ratio, Attack, Release, Makeup, Pump, PumpRate, Count };
    Compressor() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Compressor)
    void onBlock(int64_t tickStart, int64_t, float bpm) override { tick = tickStart; this->bpm = bpm; }
  private:
    float env = 0.0f, gain = 1.0f, sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

class Filter final : public Effect {
  public:
    enum P { Cutoff, Reso, Mode, LfoRate, LfoDepth, EnvDepth, Count };
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
    enum P { Bits, Rate, Jitter, Tone, Mix, Count };
    Bitcrusher() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Bitcrusher)
  private:
    float hold[2]{}, phase = 0.0f, period = 1.0f, sr = 48000.0f;
    uint32_t rng = 0x2545F491u;
    dsp::Biquad tone[2];
};

class Phaser final : public Effect {
  public:
    enum P { Rate, Depth, Feedback, Stages, Spread, Mix, Count };
    Phaser() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Phaser)
    void onBlock(int64_t tickStart, int64_t, float) override { tick = tickStart; }
  private:
    dsp::Biquad ap[2][8];
    float fb[2]{}, sr = 48000.0f;
    int64_t tick = 0;
};

class Flanger final : public Effect {
  public:
    enum P { Rate, Depth, Feedback, Negative, Spread, Mix, Count };
    Flanger() { initParams(); }
    ACIDULOUS_EFFECT_COMMON(Flanger)
    void onBlock(int64_t tickStart, int64_t, float bpm) override { tick = tickStart; this->bpm = bpm; }
  private:
    dsp::DelayLine line[2];
    float sr = 48000.0f, bpm = 120.0f;
    int64_t tick = 0;
};

#undef ACIDULOUS_EFFECT_COMMON

} // namespace acidulous::effect
