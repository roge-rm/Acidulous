#pragma once
#include <cstdint>
#include <vector>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Filter.h>
#include <engine/machine/Machine.h>

namespace acidulous::machine {

/**
 * Divisi: the string ensemble.
 *
 * Twelve top oscillators, one per pitch class, divided down in octaves as the
 * old boxes did, so a note and its octaves are locked in phase. A key gates
 * its note into six registers, each with a level: violin and viola at the
 * note, cello an octave down, contrabass two, a choir (a pulse and saw mix
 * through three formants, the vowel knob moving them, each key levelled by how
 * much of its harmonic series the formants pass) and brass. Their filters are
 * fixed voicings that the tone knob moves together.
 *
 * Brass is a saw of its own per key, driven softly into a low-pass whose
 * cutoff follows the note's loudness and velocity: it opens a little after the
 * level, overshoots and settles, then falls slowly while the note is held, and
 * the pitch scoops up into the note. In poly mode each key has its own filter;
 * in paraphonic the keys share one, opened on the first key.
 *
 * In paraphonic mode one envelope swells the whole sound: a key joins the
 * sound as it is, and the release starts only when every key is up. In poly
 * mode each note has its own. After that an ensemble chorus (one delay line
 * read at two places a side, each moved by a slow and a fast LFO a third of a
 * turn apart, the right side moved the opposite way to the left) and a
 * phaser. The dry sound comes from the line's middle, 6 ms late.
 *
 * Tunings and a finger's own bend: a key follows its pitch class's divider
 * while it sounds the octave of it. A key whose tuning isn't an octave of its
 * pitch class, or that a finger bends, gets a divider chain of its own,
 * started from where the shared one was so it doesn't jump. The channel bend
 * moves the top oscillators, so every key bends together.
 *
 * Players: each register is a section of 1 to 16 players. A player is a tap
 * on the registers' mix, its delay moved by a slow random walk (its pitch
 * drifts by the delay's speed), a vibrato of its own phase and rate, and a
 * seat delay that spreads the players' entries (scatter, re-dealt on a note
 * from silence). Player n moves the same way in every section, so the
 * registers stay locked to each other as the dividers made them and a patch
 * keeps its level, but sits at its own place across each section's width:
 * the mix is kept twice, as is and weighted by each section's width, and a
 * player's place is how much of the second it adds on one side and takes
 * off the other. One player with no drift or vibrato reads the newest
 * sample, so it sounds as the machine did before players. The paraphonic
 * envelope and the volume come after the players.
 */
class Divisi final : public Machine {
  public:
    enum P : int32_t {
        Violin = 0, Viola, Cello, Contrabass, Choir, Vowel, Brass, Sweep, BrassDecay, Blat,
        Tone, Mode, Attack, Release,
        Ensemble, Speed, Phaser, Rate, Feedback,
        Tune, Octave, BendRange, VelocityAmount, Volume,
        Players, Drift, Vibrato, Vibrate, Scatter,
        // Eight macro knobs, each with four routes: a destination (a
        // MacroDest) and a depth. A macro's knob times a route's depth is
        // added to the destination, in its own 0..1 range. Block rate. Pitch
        // is 24 semitones per unit.
        MacroBase,
        MacroParams = 9, // knob, 4 destinations, 4 depths
        Count = MacroBase + 8 * MacroParams
    };
    // Where a macro route goes. Continuous params only: the mode, tune,
    // octave and bend range are left out.
    enum MacroDest : int32_t {
        McOff, McViolin, McViola, McCello, McContrabass, McChoir, McVowel, McBrass, McSweep, McBrassDecay, McBlat,
        McTone, McAttack, McRelease, McEnsemble, McSpeed, McPhaser, McRate, McFeedback,
        McVelocity, McVolume, McPitch,
        MacroDestCount
    };
    static constexpr int kMacros = 8;
    static_assert(Count <= kMaxParams, "too many parameters");

    enum EnvelopeMode : int32_t { Paraphonic = 0, Poly, ModeCount };
    static constexpr int kKeys = 24;
    static constexpr int kMaxPlayers = 16;

    Divisi();

    const char *typeName() const override { return "Divisi"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void pitchBend(int16_t value14) override;
    void channelPressure(uint8_t value) override;
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

    /** Keys sounding, for tests. */
    int activeKeys() const;
    /** Keys sounding on a divider chain of their own, for tests. */
    int ownChains() const;

  private:
    /** A rising-and-falling level: a straight attack to full and a release that falls 100 dB in its time. */
    struct Env {
        float level = 0.0f;
        bool open = false;
        void step(float rise, float fall) {
            if (open) {
                level += rise;
                if (level > 1.0f) level = 1.0f;
            } else if (level > 0.0f) {
                level *= fall;
                if (level < 1e-5f) level = 0.0f;
            }
        }
        bool idle() const { return !open && level == 0.0f; }
    };

    /** The brass filter's brightness: loudness followed a little late, the overshoot, and the slow fall. */
    struct Bright {
        /** Loudness followed; the overshoot's fast and slow halves; how much of the fall is still to come, 1 to 0. */
        float y = 0.0f, fast = 0.0f, slow = 0.0f, settle = 0.0f;
        void start() { fast = slow = settle = 1.0f; }
    };
    /** Per-block coefficients for Bright, stepped every kBrassEvery samples. */
    struct BrightCoefs {
        float up = 0.0f, down = 0.0f, fast = 0.0f, slow = 0.0f, settle = 0.0f, over = 0.0f;
    };
    /** Moves [b] one coefficient step toward [target] and returns its brightness, 0 to about 1.3. */
    static float stepBright(Bright &b, float target, float level, const BrightCoefs &c);
    /** A low-pass for one key's brass, the Svf's low-pass with the coefficients set from outside. */
    struct BrassLp {
        float ic1 = 0.0f, ic2 = 0.0f, a1 = 0.0f, a2 = 0.0f, a3 = 0.0f;
        void set(float g, float k) {
            a1 = 1.0f / (1.0f + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;
        }
        float run(float v0) {
            const float v3 = v0 - ic2;
            const float v1 = a1 * ic1 + a2 * v3;
            const float v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = dsp::undenormal(2.0f * v1 - ic1);
            ic2 = dsp::undenormal(2.0f * v2 - ic2);
            return v2;
        }
    };

    struct Key {
        bool used = false, held = false;
        uint8_t note = 0;
        /** The note it sounds after the octave knob, 0 to 127; its pitch class and octave. */
        int sounding = 60, pc = 0, octave = 5;
        /** Its level from velocity, and the velocity, 0 to 1. */
        float gain = 0.0f, vel = 0.0f;
        /** The key's own switch into the registers (paraphonic): rises in a few ms, falls in a few tens. */
        float gate = 0.0f;
        bool gateOn = false;
        /** Its own envelope (poly). */
        Env env;
        /** A divider chain of its own: the 32' phase and step, a fraction of 2^32. */
        bool own = false;
        uint32_t ownPhase = 0, ownStep = 0;
        /** The finger's own bend, semitones, and its pressure (-1 for the channel's), and that glided. */
        float noteBend = 0.0f, pressure = -1.0f, pressed = 0.0f;
        /** The pressure's swell this block and last, for a ramp across the block. */
        float swell = 1.0f;
        float level = 0.0f;
        /** Its choir level against the formants, this block; 0 before its first. */
        float choirComp = 0.0f;
        uint32_t age = 0;
        /** Its brass saw's 16' phase, free of the divider so it can scoop; the scoop, semitones below the note. */
        bool brassOn = false;
        uint32_t brassPhase = 0;
        float scoop = 0.0f;
        /** Its brightness, filter, drive and the samples to its next coefficient step (poly). */
        Bright bright;
        BrassLp brassLp;
        float drive = 1.0f, invDrive = 1.0f;
        int brassCountdown = 0;
    };

    /** One player of a section: a tap on the section's line. */
    struct Player {
        /** Its random walk: its own generator, the target, two smoothing stages (-1 to 1) and the chunks to the next target. */
        uint32_t rng = 1;
        float target = 0.0f, y1 = 0.0f, y2 = 0.0f;
        int32_t nextPick = 0;
        /** The drift's delay excursion, seconds, either side of the middle of its reach. */
        float x = 0.0f;
        /** Its vibrato's phase, turns, and its rate against the knob. */
        float vibPhase = 0.0f, vibRate = 1.0f;
        /** Its place in the scatter (0 to 1, the first player 0) and in the seating order (0 to 1). */
        float seat = 0.0f, order = 0.0f;
        /** The scatter delay now, samples, which glides to its place; the whole delay at the end of the last chunk. */
        float seatDelay = 0.0f, delay = 0.0f;
        /** Its gains on the mix and on the width-weighted mix now, and its place across a section, -1 to 1. */
        float mid = 0.0f, side = 0.0f, pos = 0.0f;
    };
    /** Deals every player's walk, vibrato and seat from fixed seeds. */
    void seatPlayers();
    /** Runs the players over [n] samples of the registers' mix [mid] and its width-weighted twin [wide], into [outL] and [outR]. */
    void runPlayers(const float *mid, const float *wide, float *outL, float *outR, int32_t n);

    /** Param [p], smoothed, with the macros' routes to it added. */
    float paramOf(int32_t p) const { return val(p, false); }
    /** Param [p]'s target with the macros' targets added, for note-on seeds. */
    float goalOf(int32_t p) const { return val(p, true); }
    float val(int32_t p, bool goal) const;
    void updateMacros();
    void updateMacroGoals();
    float read(int32_t p, bool goal) const { return goal ? params_.target(p) : params_.get(p); }

    Key *keyFor(uint8_t note);
    /** Semitones every key is moved by now: tune, the channel bend and the pitch macro. */
    float offset() const;
    /** Sets the top oscillators' steps, and each key's chain, for this block. */
    void tuneChains();
    /** Puts every filter, line and phase back as a reset leaves them. */
    void clearState();
    int heldCount() const;
    /** The formants' centres, Hz, their filters' k (one over Q) and levels, at [vowel], 0 to 1. */
    static void formantsAt(float vowel, float hz[3], float k[3], float level[3]);
    /** The choir's power for [note] at vowel row [row], through the formants as heard. */
    float choirPower(int row, int note) const;
    /** A key's choir level, so every note comes out of the formants about as loud as C4's aah. */
    float compFor(float vowel, int note);

    Key keys[kKeys];
    float sampleRate = 48000.0f;
    float bend = 0.0f, channelPressure_ = 0.0f;
    /** The twelve dividers' lowest stage: a phase and a step per pitch class, a fraction of 2^32. */
    uint32_t topPhase[12] = {}, topStep[12] = {};
    /** That stage's frequency, Hz. */
    float topHz[12] = {};
    /** The shared envelope, and the shared brass brightness and drive (paraphonic). */
    Env shared;
    Bright sharedBright;
    float sharedDrive = 1.0f;
    /** The registers' voicings. */
    dsp::Svf violinHigh, violinLow, violaHigh, violaLow, celloHigh, celloLow, bassLow;
    dsp::Svf choirHigh, choirLow, formant[3];
    dsp::Svf brassLow;
    int brassCountdown = 0;
    /**
     * The choir's per-note levels, worked out the first time a vowel row and
     * note are played and kept, since they depend only on the sample rate.
     * 0 means not worked out yet.
     */
    static constexpr int kVowelRows = 17;
    float compCache[kVowelRows][128] = {};
    float compRef = 0.0f;
    /**
     * The players: their state, their two lines (one buffer, the mix's
     * power-of-two line then the wide one's, written together), the write
     * place, the samples the lines have heard only silence, and this
     * block's player settings.
     */
    Player players_[kMaxPlayers];
    std::vector<float> playerLines;
    int32_t lineSize = 0, lineMask = 0, lineWrite = 0, lineQuiet = 0;
    uint32_t dealRng = 1;
    bool snapPlayers = true;
    int playerCount = 1, seatedFor = 0;
    float driftRatio = 0.0f, vibRatio = 0.0f, vibHz = 5.0f, scatterSamples = 0.0f, playerNorm = 1.0f;
    /** The ensemble: a line a side read at its middle and two places, and its two LFOs' phases, turns. */
    dsp::DelayLine line, lineR;
    float slowPhase = 0.0f, fastPhase = 0.0f;
    /** The phaser: four all-pass stages a side, the fed-back sample, and its LFO's phase. */
    float apState[2][4] = {};
    float apBack[2] = {};
    float apCoef[2] = {};
    float phaserPhase = 0.0f;
    int phaserCountdown = 0;
    uint32_t clock = 0;
    int32_t quietSamples = 0;
    bool asleep = true;
    float mac_[MacroDestCount]{}, macGoal_[MacroDestCount]{};
};

} // namespace acidulous::machine
