#pragma once
#include <engine/dsp/Adsr.h>
#include <engine/dsp/LfoGen.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>
#include <engine/machine/cumulus/Cloud.h>

// Cumulus - pads by spectrum.
//
// Spectral pads are the thing nothing else sounds quite like: harmonics
// smeared into bands, inverse transformed into one enormous table, and the
// result is a chorus of hundreds of oscillators for the price of reading an
// array. This is our own implementation of that idea, and then the part
// that makes it an instrument rather than a tribute:
//
//   - **Four spectra, morphed.** The table is built four times along a path
//     from one spectrum to another - brighter, wider, stretched, hollower -
//     and the morph knob walks it. Every frame is built from the same random
//     phases, which is the trick that lets two of them be crossfaded without
//     cancelling.
//   - **Stretch.** Partial n sits at n^(1+stretch), so the same patch goes
//     from choir to piano to bell to gong by moving one knob off zero.
//   - **A vowel in the profile.** Three formants, five vowels, interpolated:
//     a pad that says something.
//   - **Scatter.** The table is a second and a half long, and every voice
//     starts somewhere different in it, so a chord is never the same cloud
//     three times.
//   - **Drift.** A slow random walk on each copy's rate: the one thing a
//     static table cannot do on its own is change, and this is what keeps it
//     alive without an LFO on anything.
//   - **Shimmer.** A second reader an octave (or a fifth, or two octaves) up,
//     from the same table, for nothing but the read.
//   - **Width by distance.** The right channel reads a quarter of a table
//     away from the left. Two uncorrelated parts of the same cloud, which is
//     as wide as stereo gets without an effect.
namespace acidulous::machine {

class Cumulus final : public Machine {
  public:
    static constexpr int kVoices = 12;
    static constexpr int kUnison = 3;

    enum P : int32_t {
        // --- The spectrum. These build the tables, off the audio thread. ---
        Partials = 0, Tilt, Odd, Comb, CombPeriod, Vowel, VowelAmount,
        Bandwidth, BandwidthScale, Stretch, Seed,
        // The far end of the morph, as differences from the above.
        BTilt, BBandwidth, BStretch, BComb, BVowel, BOdd,
        // --- Everything below is live. ---
        Morph, MorphKey,
        Shimmer, ShimmerInterval,
        Width, Scatter, Drift, DriftRate,
        Spread, Detune, SpreadWidth,
        Cutoff, Resonance, FilterType, FilterEnv, FilterKey, FilterDrive,
        AmpAttack, AmpDecay, AmpSustain, AmpRelease,
        FiltAttack, FiltDecay, FiltSustain, FiltRelease,
        Lfo1Wave, Lfo1Rate, Lfo1Sync, Lfo1Morph, Lfo1Pitch,
        Lfo2Wave, Lfo2Rate, Lfo2Sync, Lfo2Cutoff, Lfo2Pan,
        Drive, Volume, Pan,
        Glide, BendRange, Octave, Transpose, Fine, VelocityAmount,
        // A finger's own: slide walks the morph, pressure opens the filter
        // and lifts the level. Appended, so a patch without them takes the
        // defaults.
        MpeTimbre, MpePressure,
        Count
    };

    /** Where the spectrum parameters stop and the live ones start. */
    static constexpr int32_t kFirstLiveParam = Morph;

    Cumulus();

    const char *typeName() const override { return "Cumulus"; }
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
    void *swapObject(int32_t slot, void *object) override;

    /**
     * The recipe the current parameters describe. Mount thread.
     *
     * [norm01] is the UI's own idea of the spectrum parameters, in table
     * order, 0..1 - passed in rather than read from here because parameters
     * reach a machine through a queue the audio thread drains, and a build
     * started in the same breath as the knob would otherwise render the
     * value before it. NaN means "whatever this machine already has", and
     * a null pointer means all of them.
     */
    cumulus::CloudSpec spec(const float *norm01 = nullptr, int32_t count = 0) const;

  private:
    struct Reader {
        float pos = 0.0f;     // in samples, inside the table
        float rateMul = 1.0f; // detune
        float drift = 0.0f;   // slow walk, in cents
        float driftTarget = 0.0f;
        /** What that drift works out to, refreshed on a stride rather than per sample. */
        float rate = 1.0f;
        float pan = 0.0f;
    };
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0;
        float velocity = 1.0f, key01 = 0.5f;
        int zone = 1;
        float freq = 261.63f, glideFrom = 261.63f, glidePos = 1.0f;
        Reader readers[kUnison];
        float shimmerPos = 0.0f;
        dsp::Adsr amp, fenv;
        dsp::MultiFilter filterL, filterR;
        int64_t age = 0;
        // Per-note expression (MPE). `bend` is in semitones and adds to
        // whatever the channel is bending; `pressure` and `timbre` are -1
        // until this finger sends any.
        float bend = 0.0f, pressure = -1.0f, timbre = -1.0f;
        float prsGlide = 0.0f; // see glidePressure
    };

    float paramOf(int32_t p) const { return params_.get(p); }
    int32_t steppedOf(int32_t p) const;
    Voice *allocate();
    void startVoice(Voice &v, uint8_t note, uint8_t velocity);
    float readTable(const cumulus::CloudTable &t, float pos) const;

    float sampleRate = 48000.0f;
    const cumulus::CloudSet *cloud = nullptr;
    Voice voices[kVoices];
    int64_t ageCounter = 0;
    dsp::LfoGen lfo[2];
    float lfoValue[2] = {0.0f, 0.0f};
    float bpm = 120.0f;
    float bend = 0.0f, modWheel = 0.0f, pressure = 0.0f;
    static constexpr uint32_t kRngSeed = 0x9e3779b9u;
    uint32_t rng = kRngSeed;

    float nextRandom() {
        rng = rng * 1664525u + 1013904223u;
        return static_cast<float>(rng >> 8) * (1.0f / 16777216.0f);
    }
};

} // namespace acidulous::machine
