#pragma once
#include <cstdint>
#include <engine/effect/Effect.h>

namespace acidulous::effect {

/**
 * Transient shaper: `attack` brings the start of each hit up or down and
 * `sustain` the ring after it, whatever the level, so there's no threshold.
 *
 * Attack is a fast follower against a slow one: they part at an onset and
 * meet again once the hit settles. Sustain is a follower that holds against
 * one that lets go quickly, which part in the tail. `time` scales the
 * followers together.
 *
 * The extra is `split`: only what's above it is shaped (and listened to), so
 * a drum loop's hats and snare can snap while its kick stays as it was. At
 * the bottom of its range the whole sound is shaped.
 *
 * The second extra is the accent grid: with `grid` on, each hit is shaped by
 * where it lands in the bar. A row of steps (`a01`..`a16`, `length` of them,
 * each `rate` long, counted from the song's start and swung with the track)
 * says down, flat or up, and an up step gives the hit's attack `depth` dB
 * more, a down step that much less. The step is chosen at the hit's onset
 * and held for the whole hit. With grid off it plays exactly as without it.
 */
class Transient final : public Effect {
  public:
    enum P {
        Attack, Sustain, Time, Split, Gain, Grid, Depth, Rate, Length,
        A01, A02, A03, A04, A05, A06, A07, A08, A09, A10, A11, A12, A13, A14, A15, A16, Count
    };
    Transient() { initParams(); }
    const char *typeName() const override { return "Transient"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    bool process(float *L, float *R, int32_t frames, bool stereoIn) override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float) override {
        blockStart = tickStart;
        blockEnd = tickEnd;
    }
    void onTransport(bool rolling, float percent, int64_t pair) override {
        told = true;
        moving = rolling;
        swing = percent;
        swingPair = pair;
    }

  private:
    float sr = 48000.0f;
    float low[2] = {}; // the one-pole under the split, a side
    float fastA = 0.0f, slowA = 0.0f, holdS = 0.0f, fallS = 0.0f;
    float timeWas = -1.0f;
    float atkFast = 0.0f, atkSlow = 0.0f, relA = 0.0f, relHold = 0.0f, relFall = 0.0f;

    // The accent grid. The block's ticks from onBlock; on a track's insert,
    // whether the song moves and its swing from onTransport. Elsewhere it
    // counts as moving while the ticks do (stillFrames since they last did).
    int64_t blockStart = 0, blockEnd = 0, tickWas = 0;
    int32_t stillFrames = 0;
    bool told = false, moving = false, tickKnown = false;
    float swing = 50.0f;
    int64_t swingPair = 120;
    // A hit starts when a short average of the level jumps over a long one
    // (armed), and can start again once it has fallen back under it. The
    // step is picked then and held for the hit: accent is its -1, 0 or +1,
    // and accentNow glides to it a frame at a time.
    float shortAvg = 0.0f, longAvg = 0.0f, shortCoeff = 0.0f, longCoeff = 0.0f;
    bool armed = true;
    float accent = 0.0f, accentNow = 0.0f;
    float accentGlide = 0.0f;
    float stepAccent(int64_t songTick) const;
};

} // namespace acidulous::effect
