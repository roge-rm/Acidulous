#pragma once
#include <engine/core/SampleMap.h>
#include <engine/dsp/Adsr.h>
#include <engine/dsp/LfoGen.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>

// Mosaic - the multisample player: an instrument tiled out of many pieces.
// Key and velocity zones, loops, root keys and tuning, loaded from a set of
// WAVs or from one preset of a SoundFont.
//
// Three things take it past a sampler, and the name covers all three:
//   - Zone edges *crossfade* in both axes, by a width the panel sets, so the
//     seams between tiles never click.
//   - *Layer scan* drives the velocity axis from a modulator instead of from
//     how hard you played, so which tile sounds comes off the keyboard.
//   - Any zone can be played as a *grain cloud* rather than a one-shot:
//     position, rate, size, density, spray and pitch spread. The tile is
//     broken into far smaller pieces and reassembled as a texture.
//
// The whole instrument arrives as a single object mount, built on a worker,
// so the audio thread never touches a file or an allocator.
namespace acidulous::machine {

class Mosaic final : public Machine {
  public:
    static constexpr int kVoices = 16;
    static constexpr int kZonesPerVoice = 4;
    static constexpr int kGrains = 8;
    static constexpr int kLfos = 2;
    static constexpr int kModEgs = 2;
    static constexpr int kMatrixSlots = 8;

    enum LoopMode : int32_t { LoopFromFile, LoopOff, LoopForward, LoopModeCount };
    enum EnvSource : int32_t { EnvPanel, EnvFile, EnvSourceCount };

    enum ModSource : int32_t {
        SrcOff, SrcOn, SrcModWheel, SrcPressure, SrcVelocity, SrcKeyTrack, SrcRandom,
        SrcAmpEg, SrcFilterEg, SrcEg1, SrcEg2, SrcLfo1, SrcLfo2, SourceCount
    };
    enum ModDest : int32_t {
        DstOff, DstPitch, DstScan, DstStart,
        DstGrainPos, DstGrainRate, DstGrainSize, DstGrainDensity, DstGrainSpray, DstGrainPitch,
        DstFilterFreq, DstFilterRes, DstAmp, DstPan, DstLfo1Rate, DstLfo2Rate, DestCount
    };

    enum P : int32_t {
        LayerScan = 0, ScanAmount, KeyFade, VelFade,
        Start, LoopModeIndex, Reverse, EnvSourceIndex, FileMods,
        GrainMode, GrainPos, GrainRate, GrainSize, GrainDensity, GrainSpray, GrainPitch,
        AmpAttack, AmpDecay, AmpSustain, AmpRelease,
        FilterType, FilterFreq, FilterRes, FilterEnv, FilterKey,
        FilterAttack, FilterDecay, FilterSustain, FilterRelease,
        Coarse, Fine, Glide, GlideMode, BendRange, Octave, Transpose,
        VoiceMode, Volume, Pan, VelocityAmount, VelToFilter,
        LfoBase,
        LfoParams = 6,
        EgBase = LfoBase + kLfos * LfoParams,
        EgParams = 4,
        MatrixBase = EgBase + kModEgs * EgParams,
        MatrixParams = 4,
        Count = MatrixBase + kMatrixSlots * MatrixParams
    };
    enum LfoP { LWave = 0, LRate, LSync, LDelay, LPhase, LKeySync };
    enum EgP { EAttack = 0, EDecay, ESustain, ERelease };
    enum MatP { XSrc = 0, XSrc2, XDest, XDepth };

    Mosaic();

    const char *typeName() const override { return "Mosaic"; }
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
    void *swapObject(int32_t slot, void *object) override;

    /** What the UI shows about the loaded instrument. Audio thread safe to read. */
    const SampleMap *currentMap() const { return map; }

  private:
    struct Layer {
        const SampleData *sample = nullptr;
        const MapZone *zone = nullptr;
        double pos = 0.0;      // in source frames
        double inc = 1.0;      // source frames per engine frame, before modulation
        float gain = 0.0f;
        float pan = 0.0f;
        // What the file's own modulators ask of this layer, recomputed per
        // block because some of their sources are continuous controllers.
        float modGain = 1.0f;
        float modPan = 0.0f;
        float modTuneCents = 0.0f;
        bool finished = true;
    };

    struct Grain {
        bool active = false;
        double pos = 0.0;
        double inc = 1.0;
        int32_t age = 0, length = 1;
        float gain = 1.0f;
    };

    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0, velocity = 0;
        uint32_t age = 0;
        float freq = 440.0f, glideFrom = 440.0f, glidePos = 1.0f;
        float random = 0.0f;
        Layer layer[kZonesPerVoice];
        int32_t layerCount = 0;
        Grain grain[kGrains];
        double grainOffset = 0.0;
        float grainTimer = 0.0f;
        dsp::Adsr amp, filterEg, modEg[kModEgs];
        dsp::LfoGen lfo[kLfos];
        dsp::MultiFilter filter;
        float mod[DestCount]{};
        float modCutoffCents = 0.0f; // from the file's modulators, not the matrix
        bool fileDrivesLevel = false;
        static constexpr uint32_t kSeed = 0x31415926u;
        uint32_t rng = kSeed;
    };

    Voice *allocate();
    void updateVoiceMod(Voice &v, float blockSeconds);
    float sourceValue(const Voice &v, int src) const;
    void renderVoice(Voice &v, int32_t frames, float *outL, float *outR);
    float paramOf(int32_t index) const { return params_.get(index); }
    int stepOf(int32_t index) const { return static_cast<int>(params_.get(index) + 0.5f); }
    static float readSample(const SampleData &s, double pos);

    Voice voices[kVoices];
    const SampleMap *map = nullptr;
    float sampleRate = 48000.0f;
    uint32_t ageCounter = 1;
    void applyFileMods(Voice &v);

    float modWheel = 0.0f, pressure = 0.0f, bend = 0.0f, bpm = 120.0f;
    /** Continuous controllers the file's modulators can name. */
    float cc[128]{};
    float voiceL[64]{}, voiceR[64]{};
};

} // namespace acidulous::machine
