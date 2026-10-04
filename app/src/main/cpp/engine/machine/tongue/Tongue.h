#pragma once
#include <cstdint>
#include <engine/machine/Machine.h>
#include <engine/machine/diction/Throat.h>
#include <engine/machine/tongue/Reed.h>

namespace acidulous::machine {

/**
 * Tongue: a jaw harp.
 *
 * A reed rings through a slot in its frame. While it's in the slot it pushes
 * air like a piston; out of it, the air goes round. That switch, twice a
 * cycle, chops the air into sharp pulses: a drone with every harmonic at much
 * the same level (as measured, PLAN §4.25). Breath adds air through the gap
 * and, hard enough, keeps the reed going. The mouth (Diction's vowels, as
 * peaks added to the sound) brings single harmonics forward: that's the
 * melody, over a drone that doesn't move.
 */
class Tongue final : public Machine {
  public:
    enum P : int32_t {
        Model = 0, Tune, Set, Edge, Ring, Pluck, Overtones,
        Mouth, Focus, Depth, Glide,
        Breath, Air, Sustain, Stop,
        Voices, VelocityAmount, BendRange, Octave, Volume,
        Count
    };
    static_assert(Count <= kMaxParams, "too many parameters");

    /** The kinds of jaw harp, for `model`. */
    enum Kind : int32_t {
        Steel = 0, Munnharpe, Khomus, Morsing, TemirKomuz, Brass, Bamboo, Mukkuri, Genggong, Kouxian, kKinds
    };

    Tongue();

    const char *typeName() const override { return "Tongue"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void pitchBend(int16_t value14) override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void channelPressure(uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

    /** Voices sounding, for tests. */
    int activeVoices() const;

  private:
    static constexpr int kVoices = 4;
    /** Samples between moves of the mouth. */
    static constexpr int kControl = 32;
    /** A formant's peak over the sound at full depth (about 18 dB), and each formant's share. */
    static constexpr float kMouthBoost = 8.0f;
    /** The furthest off the slot's middle the reed rests, as a share of its swing. */
    static constexpr float kReach = 0.8f;
    /** The slot's half width at the default fit (edge 0.55), in swings. */
    static constexpr float kDefaultWidth = 0.25f - 0.23f * 0.55f;
    static constexpr float kFormantShare[3] = {1.00f, 0.70f, 0.40f};
    /**
     * The mouth's formants (Hz) along the vowel control, oo oh ah eh ee. A
     * player's mouth round a harp sits higher than speech: F1 0.5 to 1.6 kHz
     * and F2 1.4 to 3.2 kHz in the recordings (PLAN 4.25).
     */
    static constexpr float kMouths[5][3] = {
        {350, 1700, 2700}, {900, 1900, 2800}, {1650, 1800, 3000}, {950, 2400, 3200}, {800, 2750, 3500},
    };

    struct Voice {
        tongue::Reed reed;
        bool used = false, held = false, retuned = false;
        uint8_t note = 0;
        float baseNote = 60.0f;
        float gain = 0.0f;
        /** Samples a radian of the note: a change a sample, taken back to one a radian. */
        float perRadian = 40.0f;
        float q = 0.0f, air = 0.0f;
        /** The slot's half width this block, and the level that goes with it. */
        float width = kDefaultWidth, fit = kDefaultWidth;
        float level = 0.0f;
        /** How far the reed swings, followed over about a millisecond. */
        float swing = 0.0f;
        int quietBlocks = 0;
        uint32_t age = 0;
    };

    float paramOf(int32_t i) const { return params_.get(i); }
    Voice *voiceFor(uint8_t note);
    void retune(Voice &v);
    /** The mouth: formants from the vowel control, narrowed by focus, and the gain that keeps the level. */
    void moveMouth(bool jump);

    Voice voices[kVoices];
    /** The mouth's three formants on the sound, and on the breath (twice as wide). */
    diction::Bandpass mouth[3], mouthAir[3];
    float sampleRate = 48000.0f;
    float formants[3] = {730, 1090, 2440};
    float mouthGain = 1.0f, mouthGainTarget = 1.0f;
    /** The mouth's power in and out, followed over 30 ms. */
    float powerIn = 0.0f, powerOut = 0.0f;
    float bend = 0.0f, wheel = 0.0f, pressure = 0.0f;
    /** The breath this sample, on its way to the block's. */
    float blown = 0.0f;
    float dcIn = 0.0f, dcOut = 0.0f;
    uint32_t noise = 0x9e3779b9u, clock = 0;
    int controlLeft = 0, quietSamples = 0;
    bool asleep = true;
};

} // namespace acidulous::machine
