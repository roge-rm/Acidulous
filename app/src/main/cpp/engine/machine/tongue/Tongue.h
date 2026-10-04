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
 *
 * Ten kinds of harp share the model, each with its own reed, slot, ring and
 * frame (kKindVoices); the knobs move each kind's sound from where it sits.
 * A harp can have up to five reeds, tuned as a chord.
 */
class Tongue final : public Machine {
  public:
    enum P : int32_t {
        Model = 0, Tune, Set, Edge, Ring, Pluck, Overtones,
        Mouth, Focus, Depth, Glide,
        Breath, Air, Sustain, Stop,
        Voices, VelocityAmount, BendRange, Octave, Volume,
        Reeds, Chord, Strum, Order,
        Count
    };
    static_assert(Count <= kMaxParams, "too many parameters");

    /** The kinds of jaw harp, for `model`. */
    enum Kind : int32_t {
        Steel = 0, Munnharpe, Khomus, Morsing, TemirKomuz, Brass, Bamboo, Mukkuri, Genggong, Kouxian, kKinds
    };
    /** How the reeds of a harp are tuned, for `chord`; Auto is the kind's own. */
    enum ChordKind : int32_t { AutoChord = 0, Unison, Octaves, Fifths, Major, Minor, Pentatonic, kChords };
    /** The order a harp's reeds are plucked in, for `order`. */
    enum OrderKind : int32_t { Up = 0, Down, InTurn, Scatter, kOrders };

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

    /**
     * What makes each kind itself. set, edge and pluck are where the kind
     * sits when those knobs are at their defaults; ring scales the ring knob.
     */
    struct KindVoice {
        float set, edge, pluck;
        float ring;
        /** The overtones' ring against the note's. */
        float overRing;
        /** The reed's second and third modes against its first. */
        float ratio2, ratio3;
        /** A first-order high-pass on the sound, Hz; 0 for none. */
        float lowCut;
        /** The frame's knock: Hz, ring in seconds, level against the pluck. */
        float frameHz, frameRing, frameLevel;
        /** A string pull's draw in ms; 0 for a finger. */
        float pullMs;
        /** The breath's hiss against steel's. */
        float air;
        /** Level, so every kind sits at the same loudness. */
        float level;
        /** Reeds and tuning when those knobs are on auto. */
        int reeds;
        int chord;
    };
    static const KindVoice kKindVoices[kKinds];

  private:
    static constexpr int kVoices = 4;
    static constexpr int kReeds = 5;
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

    /** One reed of a harp and what its slot does with it. */
    struct Tine {
        tongue::Reed reed;
        bool retuned = false;
        /** Semitones from the harp's note. */
        float offset = 0.0f;
        /** Samples a radian of the note: a change a sample, taken back to one a radian. */
        float perRadian = 40.0f;
        float q = 0.0f, air = 0.0f;
        /** The slot's half width this block, and the level that goes with it. */
        float width = kDefaultWidth, fit = kDefaultWidth;
        float level = 0.0f;
        /** How far the reed swings, followed over about a millisecond. */
        float swing = 0.0f;
        float gain = 0.0f;
        /** A pluck waiting its turn in a strum: samples to go, or -1. */
        int wait = -1;
        float waitSwing = 0.0f, waitContact = 0.0f, waitSnap = 0.0f, waitGain = 0.0f;
    };

    struct Voice {
        Tine tines[kReeds];
        int count = 1;
        /** The kind this harp was plucked as. */
        int kind = Steel;
        /** The next reed to pluck, played in turn. */
        int next = 0;
        tongue::Knock knock;
        bool used = false, held = false;
        uint8_t note = 0;
        float baseNote = 60.0f;
        int quietBlocks = 0;
        uint32_t age = 0;
    };

    float paramOf(int32_t i) const { return params_.get(i); }
    Voice *voiceFor(uint8_t note);
    void retune(Voice &v);
    /** Sets a reed going now: by finger or string, as its kind is played. */
    void pluckTine(const Voice &v, Tine &t, float swing, float contact, float snap, float gain);
    /** The mouth: formants from the vowel control, narrowed by focus, and the gain that keeps the level. */
    void moveMouth(bool jump);

    Voice voices[kVoices];
    /** The mouth's three formants on the sound, and on the breath (twice as wide). */
    diction::Bandpass mouth[3], mouthAir[3];
    float sampleRate = 48000.0f;
    float formants[3] = {730, 1090, 2440};
    float mouthGain = 1.0f, mouthGainTarget = 1.0f;
    /** What the mouth's gain was last worked out for: formants, focus, lift and pitch. */
    float lastMouth[6] = {};
    float bend = 0.0f, wheel = 0.0f, pressure = 0.0f;
    /** The breath this sample, on its way to the block's. */
    float blown = 0.0f;
    float dcIn = 0.0f, dcOut = 0.0f;
    /** The kind's low cut: last input and output. */
    float cutIn = 0.0f, cutOut = 0.0f;
    uint32_t noise = 0x9e3779b9u, clock = 0;
    int controlLeft = 0, quietSamples = 0;
    bool asleep = true;
};

} // namespace acidulous::machine
