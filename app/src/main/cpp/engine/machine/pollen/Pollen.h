#pragma once
#include <engine/dsp/Adsr.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>
#include <engine/machine/pollen/Source.h>
#include <vector>

// Pollen - granular, both ways round.
//
// A cloud per note over a buffer, after the GR-1; and that buffer can be
// what is coming into the phone, after the Texture Lab. Mosaic has a grain
// *mode* - eight readers a voice, a metronomic birth timer, one window, mono
// reads. This is the instrument: ninety-six grains across eight voices,
// stereo, four window shapes, births that jitter, pan per grain - and four
// things that are ours:
//
//   - **Pollination.** A dying grain can seed a child near where it was,
//     with its position, pitch, size and pan mutated, and that child can
//     seed another, to a depth you set. A cloud grows from a seed rather
//     than being a fixed statistical spray, and each generation is quieter,
//     so it settles instead of piling up.
//   - **Harmonic scatter.** The per-grain pitch random is quantised - to
//     octaves, fifths, a triad, or any of the thirty-three scales the
//     eventors already know. A spray becomes a chord.
//   - **Onset snap.** The buffer's transients are found (on a worker for a
//     file, as it records for the live ring) and grains land on them by an
//     amount. A slicer feeding the cloud.
//   - **Self-seeding feedback.** The machine's own output goes back into the
//     live ring, so the texture eats itself.
namespace acidulous::machine {

class Pollen final : public Machine {
  public:
    static constexpr int kVoices = 8;
    static constexpr int kGrains = 96;
    static constexpr int kWindowSize = 1024;
    static constexpr int kLiveSeconds = 8;
    static constexpr int kLiveOnsets = 64;

    enum SourceKind : int32_t { FromSample, FromLive, SourceCount };
    enum Scatter : int32_t { Free, Octaves, Fifths, Triad, InScale, ScatterCount };
    enum Window : int32_t { Hann, Tukey, Percussive, Reverse, WindowCount };

    enum P : int32_t {
        SourceMode = 0, InGain, BufferSeconds, Freeze, Capture, Feedback,
        Position, Scan, Spray, Snap, ReverseProb,
        Size, SizeSpread, Density, Jitter, WindowShape, Skew,
        Pitch, Fine, KeyTrack, Spread, ScatterMode, ScaleIndex, Key,
        Bloom, Drift, Mutate, Generations,
        PanSpread, Width,
        Bits, Crush, Wobble, WobbleRate,
        Cutoff, Resonance, FilterType,
        AmpAttack, AmpDecay, AmpSustain, AmpRelease,
        Mono, Glide, BendRange, Octave, Transpose, VelocityAmount,
        Drive, Dry, Volume, Pan,
        Count
    };

    Pollen();

    const char *typeName() const override { return "Pollen"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void pitchBend(int16_t value14) override;
    bool render(float *L, float *R, int32_t frames) override;
    void *swapObject(int32_t slot, void *object) override;

    // What the cloud is doing, for the harness and for the panel's readout.
    int64_t grainsBorn() const { return births; }
    int32_t grainsAlive() const {
        int32_t n = 0;
        for (const auto &g : grains) if (g.active) ++n;
        return n;
    }
    int32_t onsetsFound() const { return liveOnsetCount; }

  private:
    struct Grain {
        bool active = false;
        int32_t voice = -1;
        double pos = 0.0, inc = 1.0;
        int32_t age = 0, length = 1;
        float gainL = 0.5f, gainR = 0.5f;
        float skew = 0.0f;
        int32_t generation = 0;
    };
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 60;
        float velocity = 1.0f;
        float freq = 261.63f, glideFrom = 261.63f, glidePos = 1.0f;
        double playhead = 0.0;   // where this voice is reading, in frames
        double scanOffset = 0.0; // how far the scan has carried it from the base
        float timer = 0.0f;    // frames until the next grain
        int32_t living = 0;    // grains currently belonging to this voice
        dsp::Adsr amp;
        int64_t age = 0;
    };

    float paramOf(int32_t p) const { return params_.get(p); }
    /** The target, not the smoothed value: for momentary and switch controls. */
    float rawOf(int32_t p) const;
    int32_t steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

    Voice *allocate();
    int32_t takeGrain();
    void spawn(Voice &v, int32_t voiceIndex, const pollen::View &view, float env);
    void pollinate(const Grain &parent, const pollen::View &view);
    float windowAt(int32_t shape, float phase, float skew) const;
    float scatterSemis(float amount, uint32_t &state) const;
    pollen::View resolveView(int32_t mode, int32_t liveLen) const;

    float nextRandom() {
        rng = rng * 1664525u + 1013904223u;
        return static_cast<float>(rng >> 8) * (1.0f / 16777216.0f);
    }

    float sampleRate = 48000.0f;
    const pollen::Source *source = nullptr;

    // The live ring, owned here and written on the audio thread.
    std::vector<float> ringL, ringR;
    int32_t ringCapacity = 0, writePos = 0, liveLength = 0;
    int32_t captureLeft = 0;
    bool lastCapture = false;
    pollen::OnsetFinder finder;
    int32_t liveOnsets[kLiveOnsets] = {};
    int32_t liveOnsetCount = 0, liveOnsetNext = 0;

    std::vector<float> window[WindowCount];
    Grain grains[kGrains];
    Voice voices[kVoices];
    int32_t grainCursor = 0;
    int64_t births = 0;
    int64_t ageCounter = 0;
    float bend = 0.0f, modWheel = 0.0f;
    float wobblePhase = 0.0f;
    float feedbackL = 0.0f, feedbackR = 0.0f;
    dsp::MultiFilter filterL, filterR;
    float crushAcc = 0.0f, heldL = 0.0f, heldR = 0.0f;
    // The feedback path's own state: a loop needs somewhere for DC to go
    // other than into the ring, where it would sit until a reset.
    float dcInL = 0.0f, dcOutL = 0.0f, dcInR = 0.0f, dcOutR = 0.0f;
    uint32_t rng = 0x51ed270bu;
};

} // namespace acidulous::machine
