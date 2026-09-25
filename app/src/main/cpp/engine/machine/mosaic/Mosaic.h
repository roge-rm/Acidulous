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
        // **Nothing may be appended to this.** `m##_src` is a stepped
        // parameter normalised against `SourceCount`, so growing the list
        // re-points every saved patch's matrix rows - and `Patch` carries no
        // version to migrate on. Slide is therefore not a source here; it is
        // a depth knob, as it is on the five machines that had it first.
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
        // Appended: parameters are addressed by name, so a patch that has
        // never heard of these takes their defaults. A finger's slide opens
        // the filter; its pressure opens it too and leans on the level.
        MpeTimbre = MatrixBase + kMatrixSlots * MatrixParams,
        MpePressure,
        Count
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
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    void noteTimbre(uint8_t note, uint8_t value) override;
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
        /**
         * The playback rate's parts and the pan's two gains, worked out once
         * a block by `cacheLayer` instead of on every sample.
         *
         * The rate was two `exp2` and a double divide per layer per sample,
         * and the pan a `cos` and a `sin`, for numbers that only move when a
         * knob, a modulator or the zone does. The rate is still recomputed
         * whenever the note's frequency has moved - which is a glide - using
         * the same expression in the same order, so nothing comes out
         * different by so much as a bit.
         */
        float rootHz = 440.0f, tuneMul = 1.0f, incFreq = -1.0f;
        double rateRatio = 1.0, incNow = 1.0;
        float panC = 0.70710678f, panS = 0.70710678f;
        /**
         * A short ramp to nothing when the sample runs off its end.
         *
         * A non-looping sample stopped dead wherever the playhead happened to
         * be, with the amplitude envelope still wide open: Dan heard it as
         * "pops between some of the notes... and at the end", and a held note
         * went from 91% of full scale to silence in one sample. A player does
         * not hear two milliseconds of fade, and does hear that step.
         */
        float fade = 1.0f;
        /**
         * And the same ramp at the *start* of a note.
         *
         * A sample whose `start` is anywhere but the very beginning opens
         * part way through a waveform, at whatever value that sample happens
         * to hold, and the amplitude envelope is the only thing hiding the
         * jump. Broken Loop starts 55% in behind a six millisecond attack -
         * less than two cycles at these pitches - and Dan heard the result as
         * "quiet pops between some of the notes". Two milliseconds of ramp is
         * shorter than any attack anybody sets and longer than any edge.
         */
        float fadeIn = 1.0f;
        /**
         * Where the playhead is going, once the old content has faded out.
         *
         * Retriggering a voice that is still sounding used to move `pos`
         * immediately: the new note ramps in over `fadeIn`, but the old one
         * stops on whatever sample it was on. In mono and legato that is
         * every note, because they all land on voice zero - Dan on Reed: "a
         * small popping noise at the start of some notes".
         *
         * The obvious fix - hold the last output and decay it - is wrong, and
         * measurably so: the signal being replaced is mid-oscillation, so a
         * held sample decayed to nothing is a DC thump, and it made the steps
         * worse (35% of full scale to 61%). What is needed is to keep playing
         * the old content while it fades, and only then jump. Two
         * milliseconds out, two back in, and nothing in between to hear.
         */
        double pendingPos = -1.0;
        /**
         * And the zone it is going to, because a note can change zone.
         *
         * Deferring the playhead but not the sample under it is worse than
         * not deferring at all: for two milliseconds the layer reads the
         * *new* sample at the *old* position. The tune steps to +12 at seven
         * seconds, which is exactly where the mid zone ends and the high one
         * begins, and every mono lead cracked there - Dan reported it on
         * Grind, Bright Lead, Glide Lead and Mono Lead in turn, "same spot".
         */
        const MapZone *pendingZone = nullptr;
        const SampleData *pendingSample = nullptr;
        float pendingGain = 0.0f, pendingPan = 0.0f;
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
        /**
         * This note's own pressure and slide, or -1 for "never told".
         *
         * The sentinel is what keeps an ordinary keyboard working: one that
         * sends a single channel aftertouch never calls `notePressure`, so
         * every voice stays at -1 and the matrix falls through to the
         * channel value. A controller that speaks per finger sets it, and
         * from then on that voice answers to its own. Trinity has done it
         * this way since M38; this is the same sentinel, not a new idea.
         */
        float pressure = -1.0f, timbre = -1.0f;
        float prsGlide = 0.0f; // see glidePressure
        Layer layer[kZonesPerVoice];
        int32_t layerCount = 0;
        Grain grain[kGrains];
        double grainOffset = 0.0;
        float grainTimer = 0.0f;
        dsp::Adsr amp, filterEg, modEg[kModEgs];
        dsp::LfoGen lfo[kLfos];
        /**
         * One filter per channel, because a filter has state.
         *
         * There used to be one, processing left and then right through the
         * same instance: the right channel came out filtered through history
         * left behind by the left, the two blended, and the stereo image
         * collapsed. Panning a patch hard left measured 0.0 dB between the
         * channels - Dan, on stereo earbuds: "I cannot hear any panning in
         * autopan, it sounds right in the middle to me." It was not the
         * modulation, or the rate, or the patch. The machine was mono.
         */
        dsp::MultiFilter filter, filterR;
        float mod[DestCount]{};
        float modCutoffCents = 0.0f; // from the file's modulators, not the matrix
        bool fileDrivesLevel = false;
        static constexpr uint32_t kSeed = 0x31415926u;
        uint32_t rng = kSeed;
        // Per-note expression (MPE). `bend` is in semitones and adds to
        // whatever the channel is bending.
        float bend = 0.0f;
    };

    Voice *allocate();
    void updateVoiceMod(Voice &v, float blockSeconds);
    float sourceValue(const Voice &v, int src) const;
    void cacheLayer(Layer &L, float panBase);
    /** Every matrix row for one voice, into `v.mod`: once a block, and at note-on. */
    void evalMatrix(Voice &v);
    /** Which of the mod envelopes a matrix row reads, once a block. See Ratio's. */
    int32_t egUsed = (1 << kModEgs) - 1;
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
