#pragma once
#include <cstdint>
#include <engine/core/Params.h>

// A MIDI processor between the clip player and the machine - where scale
// lock, chord trigger and the arpeggiator will live.
namespace acidulous {

struct MidiSink {
    virtual ~MidiSink() = default;
    virtual void send(uint8_t status, uint8_t d1, uint8_t d2) = 0;
};

class Eventor {
  public:
    virtual ~Eventor() = default;
    virtual const char *typeName() const = 0;
    virtual const ParamDef *paramDefs(int32_t &count) const = 0;
    virtual void reset() = 0;
    virtual void handleMidi(uint8_t status, uint8_t d1, uint8_t d2, MidiSink &out) = 0;
    // Once per block, with the block's tick range, for anything clocked.
    virtual void onBlock(int64_t /*tickStart*/, int64_t /*tickEnd*/, MidiSink & /*out*/) {}
    ParamSet &params() { return params_; }

  protected:
    void initParams() {
        int32_t n = 0;
        const ParamDef *defs = paramDefs(n);
        params_.init(defs, n);
    }
    ParamSet params_;
};

} // namespace acidulous
