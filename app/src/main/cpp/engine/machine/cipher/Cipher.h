#pragma once
#include <engine/core/InputBus.h>
#include <engine/dsp/Adsr.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/LfoGen.h>
#include <engine/machine/Machine.h>

// Cipher - the vocoder.
//
// A vocoder listens to one sound through a bank of filters, measures how
// loud each band is, and imposes that shape on another. Everything
// interesting about one is what you are allowed to do between the measuring
// and the imposing, and most vocoders allow nothing: band one drives band
// one, and that is the instrument.
//
// Here the two banks are wired through a map, and the map is the machine:
//
//   - **Remap.** Band order can be reversed, mirrored, folded, spread odd
//     against even, or shuffled from a seed. Speech through a reversed bank
//     is still speech-shaped and completely unintelligible, which is a sound
//     nothing else makes.
//   - **Shift and stretch.** The synthesis bank can be read at an offset or
//     with its spacing warped, which moves formants without moving pitch.
//   - **Freeze.** The measured shape can be held, so a vowel sustains for as
//     long as you play, and morphed back and forth against the live one.
//   - **Smear.** Each band's release is scaled across the bank, so the top
//     falls away before the bottom and the spectrum leaves a trail.
//   - **Swap.** What is coming in can be the carrier rather than the
//     modulator, with the internal oscillators doing the talking.
//   - **Track.** The modulator's own pitch can drive the carrier, so a voice
//     plays the synth rather than the keyboard.
//   - **Feedback.** The output can be fed back into the analysis, which is a
//     vocoder listening to itself.
namespace acidulous::machine {

class Cipher final : public Machine {
  public:
    static constexpr int kMaxBands = 40;
    static constexpr int kVoices = 8;
    static constexpr int kMatrixSlots = 8;
    static constexpr int kMatrixParams = 3;

    enum Remap : int32_t { MapDirect, MapReverse, MapMirror, MapOddEven, MapShuffle, MapFold, RemapCount };
    enum Role : int32_t { InputIsModulator, InputIsCarrier, RoleCount };
    enum CarrierWave : int32_t { WaveSaw, WavePulse, WaveSuper, WaveNoise, WaveRing, CarrierWaveCount };

    enum ModSource : int32_t {
        SrcOff, SrcOn, SrcModWheel, SrcPressure, SrcVelocity, SrcKeyTrack,
        SrcEg1, SrcEg2, SrcLfo1, SrcLfo2, SrcLoudness, SrcBrightness, SrcPitchTrack, SourceCount
    };
    enum ModDest : int32_t {
        DstOff, DstShift, DstStretch, DstRemapAmount, DstFreezeMorph, DstSmear, DstQ,
        DstCarrierPitch, DstCarrierMix, DstNoise, DstFeedback, DstDrive, DstVolume, DstPan,
        DstBandLow, DstBandHigh, DstGate, DestCount
    };

    enum P : int32_t {
        Bands = 0, LowHz, HighHz, BandQ, Slope,
        RoleMode, Attack, Release, Smear, Gate, GateDepth,
        Shift, Stretch, RemapMode, RemapAmount, Seed,
        Freeze, FreezeMorph, FreezeDecay,
        Sibilance, SibilanceHz, SibilanceLevel,
        PitchTrack, TrackGlide, TrackAmount,
        Feedback, FeedbackTone,
        CarrierWaveA, CarrierWaveB, CarrierMix, Detune, PulseWidth, SubLevel, NoiseLevel, CarrierDrive,
        Unvoiced, Dry, Wet, Drive, Volume, Pan,
        AmpAttack, AmpDecay, AmpSustain, AmpRelease,
        Eg1A, Eg1D, Eg1S, Eg1R, Eg2A, Eg2D, Eg2S, Eg2R,
        Lfo1Wave, Lfo1Rate, Lfo1Sync, Lfo1Depth,
        Lfo2Wave, Lfo2Rate, Lfo2Sync, Lfo2Depth,
        MatrixBase,
        VoiceBase = MatrixBase + kMatrixSlots * kMatrixParams,
        VoiceMode = VoiceBase, Glide, BendRange, Octave, Transpose, Fine, VelocityAmount,
        Count
    };
    enum MatP { XSrc = 0, XDest, XDepth };

    Cipher();

    const char *typeName() const override { return "Cipher"; }
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
    bool render(float *L, float *R, int32_t frames) override;

  private:
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0;
        float velocity = 1.0f, key01 = 0.5f;
        float phaseA = 0.0f, phaseB = 0.0f, phaseSub = 0.0f;
        float freq = 220.0f, target = 220.0f;
        dsp::Adsr amp;
    };
    struct Band {
        dsp::Svf analysis1, analysis2;
        dsp::Svf synthesis1, synthesis2;
        float envelope = 0.0f;
        float held = 0.0f;
        float attackCoeff = 0.01f, releaseCoeff = 0.001f;
        float centre = 100.0f;
    };

    float paramOf(int32_t p) const { return params_.get(p); }
    int32_t steppedOf(int32_t p) const;
    void rebuildBands();
    float bandResonance(float q) const;
    int32_t mappedBand(int32_t band) const;
    float sourceValue(int32_t src) const;
    void applyMatrix();
    float carrierSample(Voice &v, float dt, float pitchScale, int32_t waveA, int32_t waveB, float mix,
                        float detune, float pw, float sub);

    float sampleRate = 48000.0f;
    Band bands[kMaxBands];
    int32_t bandCount = 16;
    float lastLow = -1.0f, lastHigh = -1.0f, lastQ = -1.0f;
    int32_t lastCount = -1;

    Voice voices[kVoices];
    dsp::Adsr eg[2];
    dsp::LfoGen lfo[2];
    float lfoValue[2] = {0.0f, 0.0f};
    float mod[DestCount] = {};

    // What the modulator is doing, published for the matrix: how loud it is,
    // where its energy sits, and what note it is nearest.
    float loudness = 0.0f, brightness = 0.0f, trackedHz = 0.0f, trackedNote = 0.0f;
    float zeroPrev = 0.0f;
    int32_t zeroCount = 0, zeroWindow = 0;
    float sibilanceEnv = 0.0f;
    dsp::Svf sibilanceFilter;
    float feedbackSample = 0.0f;
    float feedbackLp = 0.0f;

    float bendSemis = 0.0f, modWheel = 0.0f, pressure = 0.0f;
    float bpm = 120.0f;
    uint32_t rngState = 0x2f6e1cu;
    int32_t shuffleMap[kMaxBands] = {};
    int32_t shuffleSeed = -1;
};

} // namespace acidulous::machine
