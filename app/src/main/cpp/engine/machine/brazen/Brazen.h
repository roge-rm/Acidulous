#pragma once
#include <engine/dsp/Adsr.h>
#include <engine/dsp/LfoGen.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>
#include <engine/machine/brazen/Bore.h>

// Brazen - brass, one player or a section.
//
// A tube, a bell and a pair of lips, from a tuba's pedal to a trumpet's
// scream, modelled rather than sampled: the lips are a valve whose opening
// depends on the pressure behind them *and* the pressure already in the
// tube, which is why brass locks to its resonances, why it opens out as you
// lean on it, and why it screams instead of merely getting louder.
//
// The twist is the section. Every other ensemble patch is one player,
// detuned and delayed a few times - a chorus pretending to be people. Here
// each player is a whole instrument of their own, and **they listen to each
// other**: a coupling knob pulls their pitches toward the section's centre,
// so at zero they are a shambles of individuals and at one they lock into a
// single enormous horn. Everything in between is what a real section does on
// the way into tune - and it is the only control here that has no equivalent
// on a sampled brass library, because samples cannot listen.
namespace acidulous::machine {

class Brazen final : public Machine {
  public:
    static constexpr int kVoices = 6;
    static constexpr int kPlayers = 4;

    enum Mute : int32_t { Open, Straight, Cup, Harmon, MuteCount };

    enum P : int32_t {
        Size = 0, Bell, Loss, MuteKind, MuteTone,
        Tension, LipDamp, Pressure, Breath, Bite, Brassiness,
        Growl, GrowlRate,
        Players, Spread, Scatter, Lock, Drift, Width,
        Attack, Decay, Sustain, Release,
        Vibrato, VibratoRate, VibratoDelay,
        Cutoff, Resonance, FilterType,
        Mono, Glide, BendRange, Octave, Transpose, Fine, VelocityAmount,
        Drive, Volume, Pan,
        // Appended: parameters are addressed by name, so a patch that has
        // never heard of this one simply takes its default.
        MpeTimbre,
        Count
    };
    static_assert(Count <= kMaxParams, "Brazen declares more parameters than a unit can hold");

    Brazen();
    const char *typeName() const override { return "Brazen"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void channelPressure(uint8_t value) override;
    void pitchBend(int16_t value14) override;
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    void noteTimbre(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

    /** How far apart the section is, in cents. For the harness, and honest. */
    float sectionSpreadCents() const;

  private:
    struct Player {
        brazen::Bore bore;
        float offsetCents = 0.0f;  // where this player is, against the note
        float home = 0.0f;         // where they think the note is
        float walk = 0.0f;         // and how far they have wandered from it
        float delayLeft = 0.0f;    // they do not all come in together
        float breath = 1.0f;       // nor blow equally hard
        float pan = 0.0f;
        float push = 0.0f;      // what they are blowing, this block
        static constexpr uint32_t kSeed = 1u;
        uint32_t rng = kSeed;
    };
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 60;
        float velocity = 1.0f;
        float freq = 261.63f, glideFrom = 261.63f, glidePos = 1.0f;
        Player players[kPlayers];
        dsp::Adsr amp;
        dsp::MultiFilter filterL, filterR;
        float vibratoPhase = 0.0f, vibratoLeft = 0.0f;
        float muteLpL = 0.0f, muteLpR = 0.0f, muteHpL = 0.0f, muteHpR = 0.0f;
        int64_t age = 0;
        // Per-note expression (MPE). `bend` is in semitones and adds to
        // whatever the channel is bending.
        // `pressure` and `timbre` are -1 until this finger sends them, so
        // a voice with none of its own falls back to the channel and a
        // keyboard plays exactly as it did.
        float bend = 0.0f, pressure = -1.0f, timbre = -1.0f;
    };

    float paramOf(int32_t p) const { return params_.get(p); }
    int32_t steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }
    Voice *allocate();
    void startVoice(Voice &v, uint8_t note, uint8_t velocity);

    float nextRandom() {
        rng = rng * 1664525u + 1013904223u;
        return static_cast<float>(rng >> 8) * (1.0f / 16777216.0f);
    }

    float sampleRate = 48000.0f;
    Voice voices[kVoices];
    int64_t ageCounter = 0;
    float bend = 0.0f, modWheel = 0.0f, pressure = 0.0f;
    float growlPhase = 0.0f;
    static constexpr uint32_t kRngSeed = 0x6d2b79f5u;
    uint32_t rng = kRngSeed;
    float lastSpreadCents = 0.0f;
};

} // namespace acidulous::machine
