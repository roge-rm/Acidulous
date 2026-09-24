#pragma once
#include <cstdint>
#include <vector>
#include <engine/core/Take.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/dsp/Wsola.h>
#include <engine/machine/Machine.h>

// Dice - a loop, cut up, and rolled.
//
// Forage plays one-shots; nothing here took a breakbeat and let you play the
// pieces. Dice does: a loop is sliced at its own transients (the detector
// built for Pollen finds them) or on a grid, and each slice lands on a pad.
//
// The twist is in the name. Every trigger rolls against a handful of
// probabilities - swap this slice for another, reverse it, stutter it, drop
// it, throw it up an octave - so a loop reshuffles as it plays and a fill is
// never the same twice. Hold the dice and the same rolls come up every pass,
// which is the difference between a machine that surprises you and one you
// cannot record.
namespace acidulous::machine {

class Dice final : public Machine {
  public:
    static constexpr int kSlices = 16;
    // One per pad at the machine's maximum, so a sixteen-slice loop can have
    // every slice sounding before anything has to be stolen. Stealing a slice
    // is a cut in the middle of audio, and the cheapest fix for a cut is not
    // having to make it.
    static constexpr int kVoices = 16;
    static constexpr uint8_t kBaseNote = 36;

    enum SliceP : int32_t { Level = 0, Pan, Pitch, Decay, Direction, SliceParamCount };
    enum P : int32_t {
        SliceBase = 0,
        CutMode = SliceBase + kSlices * SliceParamCount, // onsets or grid
        SliceCount, Gate, Rate, RootPitch, Fine,
        Swap, Reverse, Stutter, StutterDiv, Drop, Jump, JumpRange, Hold, Seed,
        Cutoff, Resonance, FilterType, Drive, Volume, MasterPan, Accent,
        /**
         * Whether the loop plays at the song's tempo rather than its own.
         *
         * Off, a slice plays at the speed it was cut at, so a 90 bpm break in
         * a 126 bpm song leaves a gap after every slice. On, each slice goes
         * through the same stretcher a frozen clip and a Bias take use, by the
         * song's tempo over the loop's - so it lasts its share of the bar and
         * keeps its pitch.
         */
        Follow,
        /**
         * How many bars the loop is, which is what its tempo is worked out
         * from. Auto takes `Take::bars`, the guess made when it was loaded;
         * the rest say a half, 1, 2, 4, 8 or 16 outright.
         */
        Bars,
        Count
    };
    static_assert(Count <= kMaxParams, "Dice declares more parameters than a unit can hold");

    Dice();
    const char *typeName() const override { return "Dice"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    bool render(float *L, float *R, int32_t frames) override;
    void *swapObject(int32_t slot, void *object) override;
    /** The song's tempo, which is half of what a follow ratio is made of. */
    void onBlock(int64_t, int64_t, float bpm) override { songBpm = bpm; }

    /** The loop's tempo as it stands, or nought with no loop. For the panel. */
    float loopBpm() const;

    /** Where the cuts fall, for the panel to draw. Not audio-thread state. */
    int32_t sliceCount() const { return slices; }
    int32_t sliceStart(int32_t i) const { return i >= 0 && i < slices ? bounds[i] : 0; }

  private:
    struct Voice {
        bool used = false;
        int32_t slice = 0;
        double pos = 0.0, inc = 1.0;
        int32_t left = 0;          // frames until this slice runs out
        int32_t repeats = 0;       // stutter passes still owed
        int32_t repeatLen = 0;
        int32_t start = 0, end = 0;
        float gainL = 0.5f, gainR = 0.5f;
        float env = 1.0f, envCoeff = 0.0f;
        // Frames since this slice (or this stutter repeat) started, for the
        // ramp in. A slice ends where the next one begins, which on an
        // onset cut is a transient - so both ends need a ramp or every
        // slice boundary is a step.
        int32_t age = 0;
        uint8_t note = 0;
        // Following the song: the slice comes out of `stretch` at [stretchRate]
        // into `held`, which is read at [pitch] - so the stretch sets how long
        // the slice lasts and the pitch knob only its pitch.
        bool follows = false;
        float stretchRate = 1.0f, pitch = 1.0f, timeRate = 1.0f;
        dsp::StereoStretch stretch;
        std::vector<float> held[2];
        int32_t heldHave = 0;
        double heldPos = 0.0;
        // One per channel. A single filter processed into L only, which left
        // the right channel unfiltered: on Dust, with its cutoff at 2.2 kHz,
        // the right side measured seventeen decibels more treble than the
        // left. The same fault Mosaic had, found the same way.
        dsp::MultiFilter filter, filterR;
    };

    float sliceParam(int32_t slice, int32_t which) const {
        return params_.get(SliceBase + slice * SliceParamCount + which);
    }
    float paramOf(int32_t p) const { return params_.get(p); }
    int32_t steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }
    void recut();
    void startStretch(Voice &v) const;
    bool pullStretch(Voice &v, const float *left, const float *right) const;
    Voice *allocate();
    uint32_t rollFor(int32_t slice);

    float sampleRate = 48000.0f;
    float songBpm = 120.0f;
    const audio::Take *take = nullptr;
    int32_t bounds[kSlices + 1] = {};
    int32_t slices = 0;
    int32_t builtMode = -1, builtCount = -1, builtFrames = -1;
    Voice voices[kVoices];
    static constexpr uint32_t kRngSeed = 0x5bd1e995u;
    uint32_t rng = kRngSeed;
    int64_t triggers = 0;
};

} // namespace acidulous::machine
