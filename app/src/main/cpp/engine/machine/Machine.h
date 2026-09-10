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
    virtual void reset() = 0; // silence, forget held notes
    virtual void noteOn(uint8_t note, uint8_t velocity) = 0;
    virtual void noteOff(uint8_t note) = 0;
    virtual void allNotesOff() = 0;
    virtual void controlChange(uint8_t /*cc*/, uint8_t /*value*/) {}
    virtual void pitchBend(int16_t /*value14*/) {}

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
};

} // namespace acidulous
