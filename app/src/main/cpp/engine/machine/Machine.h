#pragma once
#include <cstdint>
#include <engine/core/Params.h>

// A sound source in a rack. Built and prepare()d on a normal thread, then
// mounted; everything after that runs on the audio thread and must not
// allocate, lock, or block.
namespace acidulous {

class Machine {
  public:
    virtual ~Machine() = default;

    virtual const char *typeName() const = 0;
    virtual const ParamDef *paramDefs(int32_t &count) const = 0;

    // Mount thread, before hand-over.
    virtual void prepare(int32_t sampleRate) = 0;

    // --- Audio thread ---------------------------------------------------------
    // Once per block, before render(): the block's tick range and the tempo,
    // for anything a machine syncs to the transport (Trinity's LFOs).
    virtual void onBlock(int64_t /*tickStart*/, int64_t /*tickEnd*/, float /*bpm*/) {}
    /**
     * Where the rack is in the arrangement, for a machine that plays the song
     * rather than notes from it.
     *
     * Every machine here is told *when* a block is and nothing about *where*;
     * Bias has to know which cell it is in and how far through that cell's
     * own cycle, and only the scheduler can say - it is the one place that
     * unifies the arranger's single position with the launcher's sixteen.
     * `cycleTick` counts the repeats, unlike the tick a note is fired against;
     * see SceneScheduler::rackCycleTick.
     *
     * Defaulted, so the nineteen machines that answer notes ignore it.
     */
    virtual void onScene(int64_t /*sceneId*/, int64_t /*cycleTick*/, bool /*playing*/,
                         bool /*clipMuted*/) {}
    virtual void reset() = 0; // silence, forget held notes
    virtual void noteOn(uint8_t note, uint8_t velocity) = 0;
    virtual void noteOff(uint8_t note) = 0;
    virtual void allNotesOff() = 0;
    virtual void controlChange(uint8_t /*cc*/, uint8_t /*value*/) {}
    virtual void channelPressure(uint8_t /*value*/) {}
    virtual void pitchBend(int16_t /*value14*/) {}

    // --- Per-note expression (MPE) ---------------------------------------
    //
    // The same three gestures, but belonging to one note rather than to the
    // channel. A controller that gives every finger its own channel can
    // bend, press and slide them independently; the rack works out which
    // note a member channel is holding and calls these.
    //
    // They default to the channel-wide versions, which is what makes this
    // safe to add: a machine that has not been taught about voices still
    // answers a bend by bending, as it always did. Only the sense of "which
    // note" is lost, and it had none to begin with.
    //
    // [semitones] is signed and already scaled by the zone's bend range, so
    // a machine adds it to the voice's pitch and asks nothing further.
    virtual void noteBend(uint8_t /*note*/, float semitones) {
        pitchBend(static_cast<int16_t>(semitones / 2.0f * 8192.0f));
    }
    virtual void notePressure(uint8_t /*note*/, uint8_t value) { channelPressure(value); }
    /** Slide, CC 74. Nothing read it before MPE, so there is nothing to fall back to. */
    virtual void noteTimbre(uint8_t /*note*/, uint8_t /*value*/) {}

    // Render `frames` samples. Return true if R was written (stereo), false if
    // the output is mono in L and the rack should copy it.
    virtual bool render(float *L, float *R, int32_t frames) = 0;

    ParamSet &params() { return params_; }
    const ParamSet &params() const { return params_; }

    // Audio thread. An object built elsewhere (a decoded sample) for `slot`.
    // Return what it displaces for the caller to retire; a machine that has no
    // use for it returns `object` itself, and it is retired unused.
    virtual void *swapObject(int32_t /*slot*/, void *object) { return object; }

    void handleMidi(uint8_t status, uint8_t d1, uint8_t d2) {
        switch (status & 0xf0) {
        case 0x90:
            if (d2 == 0) noteOff(d1); else noteOn(d1, d2);
            break;
        case 0x80: noteOff(d1); break;
        case 0xb0: controlChange(d1, d2); break;
        case 0xd0: channelPressure(d1); break;
        case 0xe0: pitchBend(static_cast<int16_t>((d2 << 7) | d1) - 8192); break;
        default: break;
        }
    }

  protected:
    void initParams() {
        int32_t n = 0;
        const ParamDef *defs = paramDefs(n);
        params_.init(defs, n);
    }
    ParamSet params_;

    /**
     * The three ways to read a parameter, and which to use when.
     *
     * `paramOf` is the smoothed value and is what audio should be made from:
     * a cutoff that jumped to its new value on the block a knob moved would
     * click, which is what the smoother is for.
     *
     * `targetOf` and `steppedTargetOf` are where it is *going*, and are what
     * anything read **once, at note-on, to seed per-note state** must use -
     * a glide time, an envelope stage, a voice count, a spread. Seeded from
     * the smoothed value, a note sounds different depending on how long ago
     * the knob moved, so the same song exported twice can differ: once from
     * a panic, once carrying on from whatever was played before it.
     *
     * Found 2026-09-13 while working on Brazen and left open until now
     * because `tools/reset_test.sh` cannot see it - its performance never
     * moves a parameter, so the smoothed and target values are equal
     * throughout and both its passes agree. `tools/noteon_check.py` is what
     * watches this instead.
     */
    float paramOfIndex(int32_t p) const { return params_.get(p); }
    float targetOf(int32_t p) const { return params_.target(p); }
    int32_t steppedTargetOf(int32_t p) const {
        return static_cast<int32_t>(params_.target(p) + 0.5f);
    }
};

} // namespace acidulous
