#pragma once
#include <engine/dsp/Envelope.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/Osc.h>
#include <engine/machine/Machine.h>

// Subvert - the signature machine, in its first form. Not a 303: the brief is
// to do to the 303 what the TB-3 did. This is the classic layer only - saw or
// pulse, a resonant lowpass with envelope-modulated cutoff, decay, accent and
// slide - voiced by ear, tuned later.
//
// Monophonic with last-note priority. Slide is legato: a note-on while one is
// held glides the pitch and leaves the envelopes alone. Accent is velocity at
// or above the threshold: more envelope, more level.
namespace acidulous::machine {

class Subvert final : public Machine {
  public:
    enum P : int32_t { Wave, Tune, Cutoff, Resonance, EnvMod, Decay, Accent, Slide, Drive, Volume,
                       // the open layer
                       PulseWidth, Sub, Mode, Count };

    Subvert();

    const char *typeName() const override { return "Subvert"; }
    const ParamDef *paramDefs(int32_t &count) const override;

    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    static constexpr int kStack = 16;
    static constexpr uint8_t kAccentVelocity = 100;

    void startNote(uint8_t note, bool legato, bool accent);

    float sampleRate = 48000.0f;
    dsp::Osc osc;
    dsp::Osc sub;
    dsp::Svf svf1, svf2;
    dsp::DecayEnv filterEnv;
    dsp::DecayEnv accentEnv;
    dsp::AsrEnv ampEnv;

    uint8_t stack[kStack]{};
    int32_t stackSize = 0;
    float targetPitch = 48.0f;
    float pitch = 48.0f;      // glides toward targetPitch
    float glideCoeff = 1.0f;  // per-sample
    bool gliding = false;
    bool accented = false;
    int32_t coeffCountdown = 0;
};

} // namespace acidulous::machine
