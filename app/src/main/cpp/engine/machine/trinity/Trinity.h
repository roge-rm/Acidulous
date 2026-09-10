#pragma once
#include <engine/dsp/Adsr.h>
#include <engine/dsp/LfoGen.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/dsp/Osc.h>
#include <engine/dsp/Wavetable.h>
#include <engine/machine/Machine.h>

// Trinity - the polyphonic machine, three oscillators deep. Modelled on the
// reference poly's architecture (Dan's favourite poly): three equal
// oscillators that each carry analogue waves or wavetables, density stacking,
// virtual sync, two filters with drive, six envelopes, three LFOs and a
// modulation matrix.
//
// What it adds beyond that model, in the TB-3 spirit: FM between the
// oscillators (the reference poly rings and syncs but never phase-modulates), and
// per-voice drift so held chords breathe.
namespace acidulous::machine {

class Trinity final : public Machine {
  public:
    static constexpr int kOscs = 3;
    static constexpr int kDensity = 8;
    static constexpr int kEnvs = 6;
    static constexpr int kLfos = 3;
    static constexpr int kMatrixSlots = 12;
    static constexpr int kVoices = 16;

    // Waves 0..3 are the analogue ones; 4.. index the wavetable bank.
    enum WaveKind : int32_t { WSaw, WSquare, WTriangle, WSine, WFirstTable, WaveCount = WFirstTable + dsp::WavetableBank::kTables };

    enum ModSource : int32_t {
        SrcOff, SrcOn, SrcModWheel, SrcAftertouch, SrcVelocity, SrcKeyTrack, SrcRandom,
        SrcEnvAmp, SrcEnvFilter, SrcEnv3, SrcEnv4, SrcEnv5, SrcEnv6,
        SrcLfo1, SrcLfo2, SrcLfo3, SourceCount
    };
    enum ModDest : int32_t {
        DstOff, DstPitch, DstPitch1, DstPitch2, DstPitch3,
        DstPos1, DstPos2, DstPos3, DstLevel1, DstLevel2, DstLevel3,
        DstPw1, DstPw2, DstPw3, DstSync1, DstSync2, DstSync3,
        DstDetune, DstNoise, DstRing12, DstRing23, DstFm21, DstFm32,
        DstF1Freq, DstF2Freq, DstF1Res, DstF2Res, DstBalance, DstDrive,
        DstAmp, DstPan, DstLfo1Rate, DstLfo2Rate, DstLfo3Rate, DestCount
    };

    // Parameter indices. Blocks are regular so the table can be generated.
    enum P : int32_t {
        OscBase = 0,                                   // 3 x OscParams
        OscParams = 12,
        MixBase = OscBase + kOscs * OscParams,         // 6
        MixParams = 6,
        FilterBase = MixBase + MixParams,              // 2 x 7 plus route, balance
        FilterParams = 7,
        RouteIndex = FilterBase + 2 * FilterParams,
        BalanceIndex,
        EnvBase,                                       // 6 x 6
        EnvParams = 6,
        LfoBase = EnvBase + kEnvs * EnvParams,         // 3 x 8
        LfoParams = 8,
        MatrixBase = LfoBase + kLfos * LfoParams,      // 12 x 4
        MatrixParams = 4,
        VoiceBase = MatrixBase + kMatrixSlots * MatrixParams,
        VoiceMode = VoiceBase, UnisonCount, UnisonDetune, UnisonSpread,
        Glide, GlideMode, BendRange, Octave, Transpose, Volume, Pan, VelocityAmount,
        Count
    };
    // Offsets inside a block.
    enum OscP { OWave, OPos, OWarp, OCoarse, OFine, OLevel, ODensity, ODetune, OSync, OHard, OPw, ODrift };
    enum MixP { MRing12 = 0, MRing23, MFm21, MFm32, MNoise, MNoiseColour };
    enum FiltP { FType = 0, FFreq, FRes, FDriveType, FDrive, FEnv, FKey };
    enum EnvP { EDelay = 0, EAttack, EDecay, ESustain, ERelease, ERepeat };
    enum LfoP { LWave = 0, LRate, LSync, LDelay, LPhase, LSlew, LKeySync, LOneShot };
    enum MatP { XSrc = 0, XSrc2, XDest, XDepth };

    Trinity();

    const char *typeName() const override { return "Trinity"; }
    const ParamDef *paramDefs(int32_t &count) const override;

    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm) override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void channelPressure(uint8_t value) override;
    void pitchBend(int16_t value14) override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    struct OscState {
        float phase[kDensity]{};
        float syncPhase = 0.0f;
        float hardZ = 0.0f;
        float driftTarget = 0.0f, drift = 0.0f;
    };

    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0, velocity = 0;
        uint32_t age = 0;
        float freq = 440.0f, glideFrom = 440.0f, glidePos = 1.0f;
        float random = 0.0f, detuneCents = 0.0f, panOffset = 0.0f;
        OscState osc[kOscs];
        dsp::Adsr env[kEnvs];
        dsp::LfoGen lfo[kLfos];
        dsp::MultiFilter filter[2];
        float mod[DestCount]{};
        uint32_t rng = 0x2f6e2b1u;
    };

    void startVoice(Voice &v, uint8_t note, uint8_t velocity, bool retrigger);
    Voice *allocate();
    void updateVoiceMod(Voice &v, float blockSeconds);
    float sourceValue(const Voice &v, int src) const;
    float renderVoice(Voice &v, int32_t frames, float *out);
    float paramOf(int32_t index) const { return params_.get(index); }
    int stepOf(int32_t index) const { return static_cast<int>(params_.get(index) + 0.5f); }

    Voice voices[kVoices];
    const dsp::WavetableBank *bank = nullptr;
    float sampleRate = 48000.0f;
    uint32_t ageCounter = 1;
    float modWheel = 0.0f, aftertouch = 0.0f, bend = 0.0f, bpm = 120.0f;
    float noiseZ = 0.0f;
    uint32_t noiseRng = 0x13579bdfu;
    float voiceBuf[64]{};
};

} // namespace acidulous::machine
