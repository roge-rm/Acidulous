#pragma once
#include <cstdint>
#include <engine/core/InputBus.h>
#include <engine/dsp/Adsr.h>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/LfoGen.h>
#include <engine/machine/Machine.h>
#include <engine/machine/filament/Waveguide.h>
#include <vector>

// Filament, a modelled string instrument.
//
// It excites a string and lets it ring, so the same string sounds different
// plucked, struck, bowed or blown. The basic model is simple. On top of it:
//
//   - Six exciters: pluck, pick, hammer, bow, breath and the audio input, so
//     you can excite a string with your voice or whatever is plugged in.
//   - Sympathetic strings: six more, undamped, tuned to a chord, a scale or
//     the harmonic series, ringing from what the played string feeds them.
//   - Preparation: a damper anywhere along the string and a rattle that
//     buzzes when driven hard, like a prepared piano, movable while a note
//     sustains.
//   - Stiffness: dispersion makes the partials run sharp, turning a guitar
//     into a piano.
//   - Tension: hit it hard and it goes sharp, settling as it dies.
//   - Two strings per voice, slightly detuned and coupled, like a course.
namespace acidulous::machine {

class Filament final : public Machine {
  public:
    static constexpr int kVoices = 8;
    static constexpr int kSympathetic = 6;
    static constexpr int kBodyModes = 4;
    static constexpr int kMatrixSlots = 8;
    static constexpr int kMatrixParams = 3;

    enum Exciter : int32_t { Pluck, Pick, Hammer, Bow, Breath, External, ExciterCount };
    enum SympatheticTuning : int32_t { SymOctaves, SymFifths, SymMajor, SymMinor, SymHarmonic, SymUnison, SymCount };

    enum ModSource : int32_t {
        SrcOff, SrcOn, SrcModWheel, SrcPressure, SrcVelocity, SrcKeyTrack, SrcRandom,
        SrcEg1, SrcEg2, SrcLfo1, SrcLfo2, SrcStringLevel, SourceCount
    };
    enum ModDest : int32_t {
        DstOff, DstPitch, DstDamping, DstTone, DstBrightness, DstPosition, DstPressure,
        DstDamperPos, DstDamperPressure, DstRattle, DstDispersion, DstTension,
        DstSympathetic, DstDetune, DstBody, DstDrive, DstVolume, DstPan, DestCount
    };

    enum P : int32_t {
        ExciterMode = 0, Position, Hardness, Pressure, Speed, Noise, ExcitLength, ExternalGain,
        Damping, DampingKey, Tone, ToneKey, Dispersion, DispersionStages, Tension,
        DamperPos, DamperPressure, Rattle, RattleThreshold,
        Detune, Spread, Couple,
        SympatheticOn, SympatheticTune, SympatheticLevel, SympatheticDamping, SympatheticSpread,
        BodyOn, BodySize, BodyMix, BodyDamp,
        Drive, Volume, Pan, Dry,
        AmpAttack, AmpRelease,
        Eg1A, Eg1D, Eg1S, Eg1R, Eg2A, Eg2D, Eg2S, Eg2R,
        Lfo1Wave, Lfo1Rate, Lfo1Sync, Lfo1Depth,
        Lfo2Wave, Lfo2Rate, Lfo2Sync, Lfo2Depth,
        MatrixBase,
        VoiceBase = MatrixBase + kMatrixSlots * kMatrixParams,
        VoiceMode = VoiceBase, Glide, BendRange, Octave, Transpose, Fine, VelocityAmount, Release,
        // Added later. Parameters are saved by name, so older patches just
        // get the default.
        MpeTimbre,
        // What MPE pressure does when the matrix doesn't use it: pushes the
        // bow or breath, brightens and raises the level.
        MpePressure,
        // A vibrato per voice: rate in Hz, depth in cents (0 is off) and the
        // time it takes to fade in after the note starts. Not a matrix
        // destination; the macros can reach it.
        VibRate, VibDepth, VibDelay,
        // Eight macro knobs, each with four routes: a destination (a
        // MacroDest) and a depth. A macro's knob times a route's depth is
        // added to the destination, in its own 0..1 range. Block rate. Pitch
        // is 24 semitones per unit.
        MacroBase,
        MacroParams = 9, // knob, 4 destinations, 4 depths
        Count = MacroBase + 8 * MacroParams
    };
    // Where a macro route goes. Continuous params only: the exciter, stages,
    // sympathy, symtune, body and voice mode switches are left out.
    enum MacroDest : int32_t {
        McOff, McPosition, McHardness, McPressure, McSpeed, McGrit, McLength, McInGain,
        McSustain, McSustainKey, McTone, McToneKey, McStiffness, McTension,
        McDamperAt, McDamper, McRattle, McRattleAt,
        McDetune, McSpread, McCouple,
        McSymLevel, McSymSustain,
        McBodySize, McBodyMix, McBodyDamp,
        McDrive, McVolume, McPan, McExciterOut, McDampTime,
        McEg1A, McEg1D, McEg1S, McEg1R, McEg2A, McEg2D, McEg2S, McEg2R,
        McLfo1Rate, McLfo1Depth, McLfo2Rate, McLfo2Depth,
        McVelocity, McMpeTimbre, McMpePressure,
        McVibRate, McVibDepth, McVibDelay,
        McPitch,
        MacroDestCount
    };
    static constexpr int kMacros = 8;
    static_assert(Count <= kMaxParams, "Filament declares more parameters than a unit can hold");
    enum MatP { XSrc = 0, XDest, XDepth };

    Filament();

    const char *typeName() const override { return "Filament"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void setDampers(bool lifted) override { dampersUp = lifted; }
    void channelPressure(uint8_t value) override;
    void pitchBend(int16_t value14) override;
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    void noteTimbre(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0;
        float velocity = 1.0f, key01 = 0.5f;
        float freq = 220.0f, target = 220.0f;
        /** What the string was last tuned to, kept for the pick comb below. */
        float freqNow = 220.0f;
        Waveguide a, b;          // a course: two strings, slightly apart
        int32_t exciteLeft = 0;  // samples of excitation remaining
        float exciteGain = 0.0f;
        float exciteDc = 0.0f;   // the slow part of the drive, kept out of the string
        float lastPick = 0.0f;   // a pick differentiates the excitation
        // Recent excitation, for the pick-position comb.
        std::vector<float> pick;
        int32_t pickWrite = 0;
        float bowPhase = 0.0f;
        float pan = 0.0f;
        float damp = 0.0f;       // release damping, 0 while held
        /**
         * This voice's matrix values, refreshed every 16 samples. Each voice
         * needs its own copy since they hold per-note sources like key and
         * velocity.
         */
        float mod[DestCount] = {};
        /** This voice's pan gains, worked out with the matrix. */
        float panL = 1.0f, panR = 1.0f;
        // Per-note expression (MPE). `bend` is in semitones and adds to the
        // channel bend. `pressure` and `timbre` are -1 until this note sends
        // them, and until then the channel's values are used.
        float bend = 0.0f, pressure = -1.0f, timbre = -1.0f;
        float prsGlide = 0.0f; // see glidePressure
        // The vibrato's phase (0..1), and the sample clock at the note-on and
        // at the last string update.
        float vibPhase = 0.0f;
        int64_t vibStart = 0, vibAt = 0;
    };

    /** Param [p], smoothed, with the macros' routes to it added. */
    float paramOf(int32_t p) const { return val(p, false); }
    /** Param [p]'s target with the macros' targets added, for note-on seeds. */
    float goalOf(int32_t p) const { return val(p, true); }
    float val(int32_t p, bool goal) const;
    /** Fills [mac_] from the macro knobs, smoothed or (when [goal]) their targets. */
    void updateMacros(bool goal = false);
    float read(int32_t p, bool goal) const { return goal ? params_.target(p) : params_.get(p); }
    int32_t steppedOf(int32_t p) const;
    float sourceValue(int32_t src, const Voice &v) const;
    void applyMatrix(const Voice &v, float *dest);
    void retuneSympathetic(float rootHz);

    float sampleRate = 48000.0f;
    Voice voices[kVoices];
    Waveguide sympathetic[kSympathetic];
    float sympatheticHz[kSympathetic] = {};
    float lastRoot = 0.0f;
    int32_t lastTuning = -1;

    dsp::Biquad body[kBodyModes];
    dsp::Adsr eg[2];
    dsp::LfoGen lfo[2];
    float lfoValue[2] = {0.0f, 0.0f};
    float stringLevel = 0.0f;
    /** Blocks in a row with no voice and nothing over -120 dB, see `render`. */
    int32_t quietBlocks = 0;
    bool asleep = false;
    /**
     * The sustain pedal lifts the dampers, so the sympathetic bank rings
     * even with "sympathy" off, like a piano with the pedal down. [damperMix]
     * follows it over 30 ms so the bank fades in and out.
     */
    bool dampersUp = false;
    float damperMix = 0.0f;

    float bendSemis = 0.0f, modWheel = 0.0f, pressure = 0.0f;
    float bpm = 120.0f;
    static constexpr uint32_t kRngSeed = 0x51f3aa1u;
    uint32_t rngState = kRngSeed;

    float mac_[MacroDestCount]{};
    /** Samples rendered since the last reset, for the vibrato's timing. */
    int64_t clock_ = 0;
};

} // namespace acidulous::machine
