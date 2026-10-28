#pragma once
#include <cstdint>
#include <vector>
#include <engine/dsp/DelayLine.h>
#include <engine/effect/Effect.h>

namespace acidulous::effect {

/**
 * Reverb, in several models picked by `model`:
 *
 *   - room: eight combs and four allpasses a side, the original reverb.
 *   - plate: a figure-eight tank. The input goes through four diffusing
 *     allpasses into two loops that feed each other, each with a slowly
 *     modulated allpass, a damping filter and a second allpass. Bright and
 *     dense.
 *   - hall: eight delay lines mixed through a Householder matrix, with lows
 *     lasting longer than mids and highs, slow modulation on every line and
 *     early reflections in front.
 *   - spring, early, space: reserved, they play the room for now.
 *
 * Shared by every model:
 *
 *   - Shimmer: the tail is fed back an octave up, so each pass climbs into a
 *     rising chord. The pitch shift is a delay line read at double speed with
 *     two crossfaded taps, and it sits inside the feedback so it builds up.
 *   - Bits and crush: only the tail goes through a bit quantiser and a
 *     sample-and-hold, so the dry stays clean.
 *   - Wobble: the tank's delay lengths drift slowly and out of step, like tape.
 *   - Freeze holds the tail forever and gate cuts it off.
 *   - Duck: the tail drops while the track plays and comes back over `duckrel`.
 *   - Distance: near to far. Longer pre-delay, quieter and later early
 *     reflections, more wet against the dry, air loss on the tail, a narrower
 *     dry and a wider tail.
 *   - Bloom: the tail starts thin and fills in over up to two seconds of
 *     input, and falls back when the input stops.
 */
class Reverb final : public Effect {
  public:
    // Appended after Gain, so old songs, patches and lanes keep their indices.
    enum P { Size, Damp, Tone, PreDelay, Mix, Freeze, Gate, Shimmer, Bits, Crush, Wobble, Gain,
             Model, Duck, DuckRel, Distance, Bloom, Count };
    enum ModelKind { Room, Plate, Hall, Spring, Early, Space, kModels };
    Reverb() { initParams(); }
    const char *typeName() const override { return "Reverb"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    bool process(float *L, float *R, int32_t frames, bool stereoIn) override;

    /** A plain circular buffer: whole-sample reads and wrapped fractional reads. */
    struct Line {
        std::vector<float> buf;
        int32_t size = 4, wr = 0;
        void prepare(int32_t n);
        void clear();
        void write(float v) { buf[static_cast<size_t>(wr)] = v; if (++wr >= size) wr = 0; }
        /** `d` samples back, 1 <= d <= size. */
        float at(int32_t d) const {
            int32_t i = wr - d;
            if (i < 0) i += size;
            return buf[static_cast<size_t>(i)];
        }
        float frac(float d) const;
    };

  private:
    /** Per block: everything the per-sample code reads. */
    struct Block;
    template <typename Tank> void run(float *L, float *R, int32_t frames, bool stereoIn, const Block &b, Tank &&tank);
    void roomSample(const Block &b, const float dry[2], float out[2]);
    void plateSample(const Block &b, const float dry[2], float out[2]);
    void hallSample(const Block &b, const float dry[2], float out[2]);
    float lofi(int c, float t, const Block &b);
    void shimmerStep(int c, float t, const Block &b);
    void clearRoom();
    void clearPlate();
    void clearHall();
    void updatePlate(float size, float damp, bool freeze);
    void updateHall(float size, float damp, bool freeze);

    float sr = 48000.0f;

    // --- room ---
    struct Comb { dsp::DelayLine line; float store = 0.0f; int32_t len = 1; };
    struct Allpass { dsp::DelayLine line; int32_t len = 1; };
    /** Eight combs and four allpasses a side at full quality, half at lean. */
    static constexpr int32_t kCombs = 8;
    static constexpr int32_t kAps = 4;
    Comb combs[2][kCombs];
    Allpass aps[2][kAps];
    float wobblePhase = 0.0f;

    // --- shared ---
    dsp::DelayLine pre[2], preLong[2];
    /** The shimmer's line: the tail, read back an octave up. */
    dsp::DelayLine shimmerLine[2];
    float shimmerPhase[2]{}, shimmerFb[2]{};
    /** The shimmer path's DC blocker. See the note where it's used. */
    float shimDcX[2]{}, shimDcY[2]{}, shimLp[2]{};
    float crushAcc[2]{}, crushHeld[2]{};
    float lp[2]{}, gateEnv = 0.0f, inputEnv = 0.0f;
    int32_t gateHold = 0;
    float duckEnv = 0.0f, bloomDet = 0.0f, bloomEnv = 0.0f, airLp[2]{};
    int32_t lastModel = -1;
    bool lastFull = true;

    // --- plate ---
    static constexpr int32_t kPlateDiff = 4;
    Line plDiff[kPlateDiff], plAp1[2], plD1[2], plAp2[2], plD2[2];
    float plDamp[2]{}, plCross[2]{};
    float plOsc[2][2]{};
    float plRot[2][2]{};
    // Lengths and gains for the current size, damp and freeze.
    float plSizeWas = -1.0f, plDampWas = -1.0f;
    bool plFreezeWas = false;
    int32_t plLenDiff[kPlateDiff]{}, plLenD1[2]{}, plLenAp2[2]{}, plLenD2[2]{};
    float plLenAp1[2]{}, plGain = 0.0f, plDampCoeff = 1.0f, plScale = 1.0f;
    int32_t plTapL[7]{}, plTapR[7]{};

    // --- hall ---
    static constexpr int32_t kHallLines = 8;
    static constexpr int32_t kHallEr = 8;
    Line haLine[kHallLines], haDiff[2][2];
    float haLow[kHallLines]{}, haHigh[kHallLines]{};
    float haOsc[kHallLines][2]{};
    float haRot[kHallLines][2]{};
    float haSizeWas = -1.0f, haDampWas = -1.0f;
    bool haFreezeWas = false;
    float haLen[kHallLines]{}, haGLow[kHallLines]{}, haGMid[kHallLines]{}, haGHigh[kHallLines]{};
    int32_t haLenDiff[2][2]{};
    float haScale = 1.0f, haLowCoeff = 0.0f, haHighCoeff = 0.0f;
};

} // namespace acidulous::effect
