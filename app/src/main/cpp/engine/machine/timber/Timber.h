#pragma once
#include <engine/dsp/Adsr.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>
#include <engine/machine/timber/Pipe.h>

// Timber - woodwinds, modelled.
//
// Three ways of starting a column of air and two shapes to start it in: a
// single reed, a double reed or a ribbon of air, in a cylinder or a cone.
// That grid is the whole family - a clarinet is a cylinder with a reed and
// therefore hollow and overblowing a twelfth, a saxophone is the same reed
// on a cone and therefore has every partial, a flute is no reed at all.
//
// The twist is **the tube below your fingers**. Every other modelled
// woodwind is one delay line set to the pitch, as though the instrument
// stopped where the note does. Here the note is a hole part way along, and
// the rest of the instrument is still there: still reflecting under its own
// cutoff, still letting the top of the sound past into the bore below, and
// still radiating out of it. Nobody sets that second tube - it falls out of
// where the note is and how big the instrument is, which is why this
// machine sounds different at the bottom of its range than at the top
// without a single crossfade, why the same pitch fingered two ways is two
// sounds, and why the cutoff those holes make is a control rather than an
// EQ afterwards.
namespace acidulous::machine {

class Timber final : public Machine {
  public:
    static constexpr int kVoices = 8;

    enum Register : int32_t { Natural = 0, Octave, Twelfth, RegisterCount };

    enum P : int32_t {
        Family = 0, Bore, Body, Lattice, Holes, Fingering, Below, Answer, Reg,
        Reed, Embouchure, Pressure, Breath, Jet, Aim,
        Bell, Loss,
        Tongue, TongueTime, Flutter, FlutterRate, Keys,
        Attack, Decay, Sustain, Release,
        Vibrato, VibratoRate, VibratoDelay,
        Cutoff, Resonance, FilterType,
        Mono, Glide, BendRange, Octave_, Transpose, Fine, VelocityAmount,
        Drive, Volume, Pan,
        // Appended: parameters are addressed by name, so a patch that has
        // never heard of this one simply takes its default.
        MpeTimbre,
        Count
    };
    static_assert(Count <= kMaxParams, "Timber declares more parameters than a unit can hold");

    Timber();
    const char *typeName() const override { return "Timber"; }
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

    /** Where the lattice is actually cutting, for the harness. */
    float latticeHz() const { return lastLattice; }

  private:
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 60;
        float velocity = 1.0f;
        float outGain = 1.0f; // velocity as level, ramped; see velocityGain
        float freq = 261.63f, glideFrom = 261.63f, glidePos = 1.0f;
        timber::Pipe pipe;
        dsp::Adsr amp;
        dsp::MultiFilter filter;
        float vibratoPhase = 0.0f, vibratoLeft = 0.0f;
        bool lift = false;         // acted on after the next tune, which needs the note
        // A note struck on a voice that is still sounding fades it out for
        // two milliseconds first, then starts. See noteOn.
        int32_t fadeLeft = 0;
        int pendingNote = -1;
        uint8_t pendingVel = 0;
        bool pendingOff = false;
        float breathScale = 0.0f;  // the breath itself, which the envelope does not touch
        float tongueLeft = 0.0f;   // the tongue is still on the reed
        float keyLeft = 0.0f;      // a pad is still closing
        float keyState = 0.0f;
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
    void startVoice(Voice &v, uint8_t note, uint8_t velocity, bool slurred);

    float sampleRate = 48000.0f;
    Voice voices[kVoices];
    int64_t ageCounter = 0;
    float bend = 0.0f, modWheel = 0.0f, aftertouch = 0.0f;
    float flutterPhase = 0.0f;
    static constexpr uint32_t kRngSeed = 0x2f6e2b1u;
    uint32_t rng = kRngSeed;
    float lastLattice = 1500.0f;
};

} // namespace acidulous::machine
