#pragma once
#include <cstdint>
#include <engine/dsp/Filter.h>
#include <engine/machine/Machine.h>

// Resonance - percussion by what a thing is, not by what it sounded like.
//
// Hexbeat is a drum machine's circuits; Forage is recordings of drums. This
// is neither: eight objects, each a bank of resonators tuned to the modes of
// a shape - a membrane, a bar, a plate, a tube, a bowl, a lump of metal -
// hit somewhere, with something, and left to ring. Congas, woodblocks, steel
// pans, bottles, anvils and a thousand things with no name, all arithmetic.
//
// The twist is the thing every hardware drum machine leaves out: **the pads
// hear each other**. Real kits do this constantly - hit the kick and the
// snare's shell buzzes, the ride shimmers - and here each pad feeds the
// others by an amount you set, so a kit rings as one object rather than
// eight islands.
namespace acidulous::machine {

class Resonance final : public Machine {
  public:
    static constexpr int kPads = 8;
    static constexpr int kMaxModes = 24;
    static constexpr uint8_t kBaseNote = 36;

    enum Shape : int32_t { Membrane, Bar, Plate, Tube, Bowl, Metal, ShapeCount };

    // Fourteen per pad, then the globals. Names are generated as
    // "p00_tune" and so on, the way Forage's are.
    enum PadP : int32_t {
        Kind = 0, Tune, Decay, Damp, Inharm, Hit, Hard, Noise, Bend, BendTime,
        Drive, Level, Pan, Couple, PadParamCount
    };
    enum P : int32_t {
        PadBase = 0,
        Modes = PadBase + kPads * PadParamCount,
        Coupling, Humanise, Accent, Volume, MasterPan,
        Count
    };
    static_assert(Count <= kMaxParams, "Resonance declares more parameters than a unit can hold");

    Resonance();
    const char *typeName() const override { return "Resonance"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t) override {} // a struck thing rings until it stops
    void allNotesOff() override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    /** One mode: a two-pole resonator, which is all a mode is. */
    struct Mode {
        float a1 = 0.0f, a2 = 0.0f, b0 = 0.0f;
        float y1 = 0.0f, y2 = 0.0f;
        float step(float x) {
            const float y = b0 * x + a1 * y1 + a2 * y2;
            y2 = y1;
            y1 = y;
            return y;
        }
        void clear() { y1 = y2 = 0.0f; }
    };
    struct Pad {
        Mode modes[kMaxModes];
        int32_t modeCount = 8;
        float exciteLeft = 0.0f, exciteStep = 0.0f, exciteGain = 0.0f;
        float bendLeft = 0.0f, bendCoeff = 0.0f, bendDepth = 0.0f;
        float velocity = 1.0f;
        // Where the last strike landed, humanise included. -1 until struck.
        float hit = -1.0f;
        float last = 0.0f;   // what this pad put out, for the coupling bus
        // Two samples of the excitation, so every mode can be fed the
        // *difference* rather than the signal. See the note in render().
        float x1 = 0.0f, x2 = 0.0f;
        // The frame's losses, per pad - see the note in render() for why this
        // cannot be one filter on the shared bus.
        float coupleLp = 0.0f;
        // How much of the frame this object accepts. A resonator's gain runs
        // as 1/sqrt(1-r), so a long decay has a thousand times the gain of a
        // short one and no fixed coupling constant can be safe for both.
        float couplingTrim = 1.0f;
        bool ringing = false;
        // What the modes were built from, so they are only rebuilt when the
        // object actually changes.
        float builtTune = -1.0f, builtDecay = -1.0f, builtDamp = -1.0f, builtInharm = -1.0f, builtHit = -1.0f;
        int32_t builtKind = -1, builtModes = -1;
    };

    float padParam(int32_t pad, int32_t which) const { return params_.get(PadBase + pad * PadParamCount + which); }
    int32_t padStep(int32_t pad, int32_t which) const { return static_cast<int32_t>(padParam(pad, which) + 0.5f); }
    void buildPad(int32_t pad);
    /**
     * Where a pad is struck, humanise included.
     *
     * Humanise used to be written back into the Hit parameter itself, which
     * made it a random walk with nothing pulling it home: the position you
     * set drifted away over a session and eventually pinned at one end, and
     * two renders of the same song could not agree because the drift
     * survived a reset. Where a stick landed is a property of the hit, so
     * it lives on the pad and a reset takes it back.
     */
    float hitOf(int32_t pad) const {
        return pads[pad].hit >= 0.0f ? pads[pad].hit : padParam(pad, Hit);
    }
    float noise() {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        return static_cast<float>(rng) * (2.0f / 4294967296.0f) - 1.0f;
    }

    float sr = 48000.0f;
    Pad pads[kPads];
    // **Two buses, because they are two different things.** The knock is a
    // one-shot: it is generated by a key, never by a resonator, so it cannot
    // feed back and needs no limiting - and it is broadband, which is what
    // actually excites a neighbouring object. The ring is a handful of lines
    // and *is* a loop, so it travels at a fraction and is trimmed by how
    // resonant the object receiving it is.
    float knockBus = 0.0f, ringBus = 0.0f;
    static constexpr uint32_t kRngSeed = 0x2545f491u;
    uint32_t rng = kRngSeed;
};

} // namespace acidulous::machine
