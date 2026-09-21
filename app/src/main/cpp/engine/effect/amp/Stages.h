#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/Math.h>
#include <engine/dsp/MultiFilter.h>

// What is between the guitar and the speaker: two preamp stages, a tone stack
// that fights with itself, and a power stage whose supply sags.
//
// The order is the amp's order, and every part of it is load-bearing:
//
//   input HP -> low shelf -> bright cap -> NL A -> interstage HP -> NL B
//   -> tone stack -> presence -> [sag detector] -> NL C -> output transformer
//
// **`presence` is before the power stage, not after.** In a real amp it is a
// tilt inside the negative feedback loop - it makes the output stage work
// harder in the upper mids. After the power stage it is a treble knob and the
// entire point is lost. It is the most commonly botched placement there is.
//
// **The interstage highpass is why a high-gain cascade sounds tight.** Two
// cascaded tanhs with nothing between them are mush; a real amp has a coupling
// capacitor between every stage, so stage B is fed a bass-shy stage A. It also
// does DC duty: stage A's bias makes a real offset, stage B amplifies it and
// then clips around it, and what comes out is an asymmetry nobody asked for.
//
// **Nothing here is a hard clamp.** A clamp has infinite bandwidth and 2x
// oversampling will not save it. The power stage is a tanh inside a tanh,
// which gives a harder knee than one with no discontinuity anywhere - and that
// is what buys the right to stay at 2x rather than needing 4x.
namespace acidulous::effect::amp {

/** Which amp this is. It changes far more than the tone stack. */
enum Stack : int32_t { Us = 0, Uk = 1, Modern = 2, StackCount = 3 };

/** Everything the voicing decides, looked up once a block. */
struct Voicing {
    float bassHz, bassLo, bassHi;
    float midRef, scoopBase, scoopSpan, midQ;
    float trebRef, trebLo, trebHi;
    float inShelfDb, inShelfHz;
    float brightDb, brightHz;
    float interHz;
    float stageBFrom;
    float sagScale;
};

inline Voicing voicingOf(int32_t stack) {
    switch (stack) {
    // **A passive stack can only attenuate**, which is why every one of these
    // shelves runs from a large cut up to about nothing rather than from a cut
    // to a boost. An amp's make-up gain is the stage after it, not the stack;
    // a stack that boosts is an equaliser wearing its name.
    case Uk:
        return {90.0f, -15.0f, 2.0f, 650.0f, 3.0f, 12.0f, 0.70f, 3000.0f, -14.0f, 2.0f,
                -4.0f, 150.0f, 5.0f, 3000.0f, 90.0f, 0.30f, 0.8f};
    case Modern:
        // The least scooped of the three on purpose: a modern high-gain amp
        // does its scooping with gain structure, and its stack is flatter.
        return {70.0f, -12.0f, 3.0f, 800.0f, 2.0f, 5.0f, 1.00f, 4500.0f, -10.0f, 3.0f,
                -8.0f, 220.0f, 0.0f, 3000.0f, 160.0f, 0.15f, 0.5f};
    default:
        return {120.0f, -14.0f, 2.0f, 500.0f, 3.0f, 9.0f, 0.55f, 2200.0f, -16.0f, 2.0f,
                -3.0f, 120.0f, 3.0f, 2200.0f, 72.0f, 0.35f, 1.0f};
    }
}

/**
 * The tone stack: three sections whose coefficients are cross-coupled.
 *
 * **Not a solved RC network.** The real Fender/Marshall stack is third order
 * and `Biquad` is second, so it would want a cubic factorisation per block on
 * the audio thread - which is where the NaN at an extreme knob setting comes
 * from. Worse, at all-pots-zero the network is genuinely near-degenerate and
 * its poles crawl towards the unit circle; a cascade of cookbook sections
 * **cannot** go unstable, and that guarantee is worth a great deal in
 * something anybody can automate.
 *
 * What a player hears from a real stack is three facts, and all three are
 * cheap:
 *
 *   - mid is not a boost and cut at a fixed frequency; it sets the floor of a
 *     scoop whose corner slides;
 *   - **bass and treble up deepens that scoop**, because a passive stack can
 *     only attenuate - a Marshall at all-ten is about ten decibels down at a
 *     kilohertz;
 *   - treble's corner moves with mid, because they share a node.
 *
 * The scoop depth runs on the **product** of bass and treble, which is the
 * whole trick: turn either one down and the mid fills back in. Independent
 * shelves never do that, and it is the single thing that tells a tone stack
 * from an equaliser.
 */
class ToneStack {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        reset();
    }
    void reset() {
        low.reset();
        mid.reset();
        high.reset();
    }

    void set(const Voicing &v, float b, float m, float t) {
        // **Make-up, so the stack does not also set the level.** Everything in
        // a passive network is a cut, so halfway on every control is a long
        // way down; the amp after it makes that back. Measured from the
        // network's own response at the middle setting rather than typed in as
        // a number somebody tuned once - and only when the voicing moves,
        // because it costs three sections' worth of coefficients.
        if (v.midRef != builtFor) {
            builtFor = v.midRef;
            dsp::Biquad rl, rm, rh;
            rl.lowShelf(v.bassHz, (v.bassLo + v.bassHi) * 0.5f, sr);
            rm.peak(v.midRef, -(v.scoopBase + v.scoopSpan * 0.25f) * 0.5f, v.midQ + 0.175f, sr);
            rh.highShelf(v.trebRef * 0.7071f, (v.trebLo + v.trebHi) * 0.5f, sr);
            const float at = rl.magnitudeAt(1000.0f, sr) * rm.magnitudeAt(1000.0f, sr) *
                             rh.magnitudeAt(1000.0f, sr);
            trim = at > 1e-6f ? 1.0f / at : 1.0f;
        }
        low.lowShelf(v.bassHz, v.bassLo + (v.bassHi - v.bassLo) * b, sr);
        const float midHz = dsp::clampf(v.midRef * std::pow(2.0f, -1.0f * t + 0.35f * b), 60.0f, 4000.0f);
        // Never below 0.5: `Biquad::peak` clamps Q at 0.1, and a fifteen
        // decibel cut at Q 0.1 is a three-octave hole rather than a scoop -
        // the control dies quietly, which is worse than it blowing up.
        const float q = dsp::clampf(v.midQ + 0.7f * b * t, 0.5f, 4.0f);
        mid.peak(midHz, -(v.scoopBase + v.scoopSpan * b * t) * (1.0f - m), q, sr);
        high.highShelf(dsp::clampf(v.trebRef * std::pow(2.0f, -0.5f * m), 300.0f, sr * 0.4f),
                       v.trebLo + (v.trebHi - v.trebLo) * t, sr);
    }

    float process(float x) { return high.process(mid.process(low.process(x))) * trim; }

    /** The chain's magnitude at [hz], for the harness. */
    float magnitudeAt(float hz) const {
        return low.magnitudeAt(hz, sr) * mid.magnitudeAt(hz, sr) * high.magnitudeAt(hz, sr) * trim;
    }

  private:
    dsp::Biquad low, mid, high;
    float sr = 48000.0f, trim = 1.0f, builtFor = -1.0f;
};

/**
 * The power stage, and the supply that droops under it.
 *
 * **Feedforward, from the stage's input.** Physically right - a preamp draws a
 * milliamp and an output stage a hundred, so it is the output stage that pulls
 * the rail down - and it is also what keeps this implementable. Detect on the
 * stage's *output* for accuracy and you have a limiter loop with a twelve
 * millisecond time constant and a loop gain above one, which **motorboats at
 * thirty to eighty hertz** at high sag and high master, and gets blamed on the
 * cab.
 *
 * Twelve milliseconds down and two hundred and twenty back: the eighteen-to-one
 * asymmetry *is* the sound, because a rectifier only conducts on peaks, so a
 * rail refills far more slowly than it empties.
 */
class PowerStage {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        atk = dsp::onePoleCoeff(0.012f, sr);
        rel = dsp::onePoleCoeff(0.220f, sr);
        reset();
    }
    void reset() {
        demand = 0.0f;
        railNow = 1.0f;
        ot.reset();
        otLow.reset();
        block.reset();
    }

    void set(float master, float sagAmt, float sagScale) {
        gm = 1.0f + master * 14.0f;
        sag = sagAmt * sagScale;
        // The normaliser once a block from the block's opening rail: per
        // sample it is a divide and a tanh at twice the rate for a level error
        // under half a decibel across sixty-four frames.
        const float atNom = std::tanh(1.2f * std::tanh(dsp::kDriveNominal * gm / railNow));
        norm = atNom > 1e-6f ? dsp::kDriveNominal / atNom : 1.0f;
        // The output transformer: a peak whose depth tracks the rail, which is
        // why a sagging amp sounds *loose* rather than merely compressed.
        ot.peak(85.0f, 2.5f * railNow, 0.8f, sr);
        otLow.lowpass(11000.0f, 0.707f, sr);
        block.setSampleRate(sr);
        block.set(22.0f, 0.299f); // Butterworth: k = 1/Q, so Q 0.707 is res 0.299
    }

    float process(float x) {
        const float a = std::fabs(x);
        demand += (a - demand) * (a > demand ? atk : rel);
        demand = dsp::undenormal(demand);
        const float load = dsp::clampf(demand * gm / 3.0f, 0.0f, 1.0f);
        railNow = 1.0f - sag * 0.45f * load;
        // Drive harder *and* clip lower, which is where the compression comes
        // from without a compressor being anywhere.
        const float y = std::tanh(1.2f * std::tanh(x * gm / railNow)) * norm * railNow;
        // The transformer cannot pass DC, and this is after the sag because a
        // moving offset is a thump rather than an offset.
        return block.highpass(otLow.process(ot.process(y)));
    }

    float rail() const { return railNow; }

  private:
    dsp::Biquad ot, otLow;
    dsp::Svf block;
    float sr = 48000.0f, atk = 0.001f, rel = 1e-4f;
    float demand = 0.0f, railNow = 1.0f, gm = 1.0f, sag = 0.0f, norm = 1.0f;
};

} // namespace acidulous::effect::amp
