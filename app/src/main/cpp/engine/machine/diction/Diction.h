#pragma once
#include <cstdint>
#include <engine/dsp/Adsr.h>
#include <engine/machine/Machine.h>
#include <engine/machine/diction/VoiceBank.h>
#include <vector>

// Diction sings.
//
// One singer, legato, from a bank of a voice's sounds. Like Molt it lays the
// voice's own glottal pulses down at the note's spacing, so the pitch moves
// and the throat stays where it was, and it reads each pulse faster or slower
// to move the throat on its own: the voice control, which makes the one bank a
// man, a woman or neither.
//
// For now it sings vowels, chosen and swept by the vowel control. Words come
// with consonants, and later lyrics on the notes.
namespace acidulous::machine {

class Diction final : public Machine {
  public:
    /** The overlap-add ring, as Molt's: two periods at 70 Hz with the voice an octave down fits. */
    static constexpr int kAccum = 4096;
    /** Held keys remembered for legato, so letting go of one returns to the last still held. */
    static constexpr int kHeld = 16;

    enum P : int32_t {
        Vowel = 0, Formant, Breath,
        Vibrato, VibratoRate, VibratoDelay, Drift,
        Glide, Attack, Release, VelocityAmount,
        BendRange, Octave, Transpose,
        Volume, Pan,
        Count
    };

    Diction();

    const char *typeName() const override { return "Diction"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void pitchBend(int16_t value14) override;
    void channelPressure(uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    /** Lay one grain from each vowel the knob is between, and return the spacing to the next. */
    float layGrains(float vowel, float formantRatio, float breath);
    /** Lay one grain of [unit] at the read head: its low band, high band and breath, weighted by [weight]. */
    void layGrain(const diction::Unit &unit, float weight, float targetPeriod, float formantRatio, float breath);
    /** One layer of a grain: [data] around mark [e], read at [ratio], added in at [gain]. */
    void addGrain(const float *data, int32_t frames, const audio::Epoch &e, float gain, float targetPeriod, float ratio);
    /** Where the pitch is going: the note, the bends, octave and transpose, in semitones. */
    float targetNote() const;
    void startNote(uint8_t note, uint8_t velocity, bool legato);

    float paramOf(int32_t i) const { return params_.get(i); }
    int32_t steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

    float sampleRate = 48000.0f;
    const diction::VoiceBank *bank = nullptr;

    // The one singer.
    uint8_t held[kHeld]{};
    int32_t heldCount = 0;
    uint8_t note = 0;
    bool gate = false;
    bool sounding = false;
    float velocity = 1.0f;
    /** The sung pitch in semitones, gliding to the target. */
    float pitch = 0.0f;
    /** A glide goes from here to the note and arrives in the glide time. [glideDone] runs 0 to 1. */
    float glideFrom = 0.0f, glideDone = 1.0f;
    /** A singer arrives at a note from a little below. Semitones, decaying to nothing. */
    float scoop = 0.0f;
    float bend = 0.0f;
    float pressure = 0.0f;
    float modWheel = 0.0f;
    /** Seconds since the note began, for the vibrato's delay. */
    float sinceOnset = 0.0f;
    float vibratoPhase = 0.0f;
    /** A slow wander in the pitch, as no voice holds a note perfectly still. */
    float wander = 0.0f, wanderTarget = 0.0f;
    int32_t wanderCountdown = 0;
    uint32_t seed = 1;

    /** Where the voice is read, in frames into each vowel's held part. Shared, as the vowels line up. */
    double head = 0.0;
    /** Where the breath is read, moved on a sung period per grain so no stretch of air is used twice. */
    double airHead = 0.0;
    float untilGrain = 0.0f;
    int32_t accHead = 0;
    std::vector<float> acc;
    /** The shortest held part among the vowels, so one head fits them all. */
    double holdLength = 1.0;

    dsp::Adsr amp;
};

} // namespace acidulous::machine
