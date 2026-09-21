#pragma once
#include "Cabinet.h"
#include "Stages.h"
#include <engine/core/Constants.h>
#include <engine/dsp/Oversampler.h>
#include <engine/effect/Effect.h>

// A guitar amplifier: a preamp that clips asymmetrically, a tone stack whose
// three controls fight each other, a power stage that sags under load, and a
// speaker in a box.
//
// **It is a chain, and the order is the point.** `fx.Distortion` already
// exists and is good; what it is not is an amp, because an amp is those four
// things in that sequence. The tone stack between the two nonlinearities is
// what makes the second one distort something shaped; presence inside the
// feedback loop is what makes it a presence control rather than a treble one.
//
// **One oversampled region, not three.** Everything from the bright cap to the
// output transformer runs at twice the rate; the cabinet runs at the base
// rate, because it is linear and cannot alias. Wrapping each nonlinearity
// separately would cost three round trips *and be wrong*: decimating between
// the preamp stages throws away exactly the harmonics the oversampling was
// protecting.
namespace acidulous::effect {

class Amp final : public Effect {
  public:
    enum P {
        Drive, Bias, Bass, Mid, Treble, Stack, Presence, Master, Sag,
        Cab, Size, Cone, Mic, Edge, Room, Mix, Gain, Count
    };
    Amp() { initParams(); }

    // Written out rather than through `ACIDULOUS_EFFECT_COMMON`, which lives
    // in `Effects.h` - a header that declares all fourteen of the others. Five
    // lines here against a dependency on every effect in the app.
    const char *typeName() const override { return "Amp"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    bool process(float *L, float *R, int32_t frames, bool stereoIn) override;

  private:
    /** Room for the latency and a block, rounded up so the wrap is cheap. */
    static constexpr int32_t kDry = 128;

    struct Channel {
        dsp::Oversampler os;
        dsp::Biquad inShelf, bright;
        dsp::Svf inHp;
        amp::ToneStack tone;
        dsp::Biquad presence;
        amp::PowerStage power;
        amp::Cabinet cab;
        float interZ = 0.0f;
        float dry[kDry] = {0.0f};
        int32_t dryAt = 0;
    };

    /** Stage A: a diode curve with the player's own bias on it. */
    static float stageA(float x, float g, float b) {
        const float off = dsp::fastTanh(b);
        const float at = dsp::fastTanh(dsp::kDriveNominal * g + b) - off;
        const float norm = at > 1e-6f ? dsp::kDriveNominal / at : 1.0f;
        return (dsp::fastTanh(x * g + b) - off) * norm;
    }
    /** Stage B: the valve curve, verbatim from MultiFilter's. */
    static float stageB(float x, float g) {
        return dsp::fastTanh(x * g) * (dsp::kDriveNominal / dsp::fastTanh(dsp::kDriveNominal * g));
    }

    Channel ch[2];
    float sr = 48000.0f;
    float g1 = 1.0f, g2 = 1.0f, biasOff = 0.0f, interCoeff = 0.1f;
    bool stageBOn = false, cabOn = true;
    float mixNow = 1.0f;
    float up[kBlockFrames * 2] = {0.0f};
};

} // namespace acidulous::effect
