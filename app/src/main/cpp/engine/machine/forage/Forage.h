#pragma once
#include <cstdint>
#include <engine/core/Sample.h>
#include <engine/dsp/Filter.h>
#include <engine/machine/Machine.h>
#include <string>
#include <vector>

// Forage - the sample drum machine, taken past a plain sampler the same way
// Subvert is taken past a plain bass. Thirteen pads on C2..C3, each with its
// own sample and, on top
// of the expected start / end / pitch / decay / level / pan / reverse / choke,
// a resonant filter, a crusher and a pitch envelope. Samples are decoded
// elsewhere and mounted as objects; nothing is shipped - users bring their own.
namespace acidulous::machine {

class Forage final : public Machine {
  public:
    static constexpr int32_t kPads = 13;
    static constexpr uint8_t kBaseNote = 36;
    /**
     * The slot the *shared* sample is mounted in, above the thirteen pads.
     *
     * Slicing one file across the pads is start and end points and nothing
     * else - the machine has had those from the beginning - but mounting the
     * same file on thirteen pads would decode it thirteen times, and a
     * thirty-second stereo file is eleven megabytes a copy. So one copy is
     * mounted here and a pad with no sample of its own reads it instead.
     * Pads keep their own where they have one, so half a kit can be sliced
     * from a break and the other half loaded pad by pad.
     */
    static constexpr int32_t kSharedSlot = kPads;
    // Per-pad parameter order; the table is generated pad-major with names
    // like "p03_cutoff". Globals follow the last pad.
    // `Play` is appended rather than filed next to `Decay` where it belongs,
    // because the table is pad-major and inserting anywhere else would move
    // every parameter after it. Patches and automation lanes are keyed by
    // name so they would survive, but nothing is gained by churning them.
    enum PadParam : int32_t { Start, End, Pitch, Decay, Level, Pan, Reverse, Choke, Cutoff, Reso, Mode, Crush, PitchEnv, PitchDecay, Play, PadParamCount };
    enum Global : int32_t { Accent, Volume, GlobalCount };
    static int32_t index(int32_t pad, PadParam p) { return pad * PadParamCount + p; }
    static int32_t globalIndex(Global g) { return kPads * PadParamCount + g; }

    Forage();
    const char *typeName() const override { return "Forage"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    bool render(float *L, float *R, int32_t frames) override;
    void *swapObject(int32_t slot, void *object) override;

    // UI thread reads, for the panel's sample name and the proof. This is
    // what the pad will actually play, which is its own sample or the shared
    // one behind it.
    const SampleData *sampleAt(int32_t pad) const {
        if (pad < 0 || pad >= kPads) return nullptr;
        return pads[pad].sample != nullptr ? pads[pad].sample : shared;
    }

  private:
    struct Pad {
        const SampleData *sample = nullptr;
        double pos = 0.0;      // in frames
        bool playing = false;
        float amp = 0.0f, ampCoeff = 0.0f;
        float penv = 0.0f, penvCoeff = 0.0f;
        float gain = 1.0f;
        dsp::Svf filter;
        float holdL = 0.0f, holdR = 0.0f; // crusher sample-and-hold
        float holdPhase = 0.0f;
        int32_t age = 0; // frames since the trigger, for the edge ramp
        bool held = false; // a while-held pad, waiting for its note off
    };

    void trigger(int32_t pad, float velocity01, bool accent);

    float sr = 48000.0f;
    Pad pads[kPads];
    const SampleData *shared = nullptr;
};

} // namespace acidulous::machine
