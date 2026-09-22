#pragma once
#include <engine/core/Constants.h>
#include <engine/machine/Machine.h>

// Bus - a group track. It plays no notes: what comes out of it is the sum of
// the tracks routed into it, handed over by the engine each block, and then
// the rack does what it does to any machine's output - its two inserts, its
// fader, its sends, its sidechain tap. So a group is a track like any other,
// and everything a track already has is a group's without being written twice.
namespace acidulous::machine {

class Bus final : public Machine {
  public:
    Bus() { initParams(); }

    const char *typeName() const override { return "Bus"; }
    const ParamDef *paramDefs(int32_t &count) const override {
        count = 0;
        return nullptr;
    }
    void prepare(int32_t) override {}
    void reset() override {}
    void noteOn(uint8_t, uint8_t) override {}
    void noteOff(uint8_t) override {}
    void allNotesOff() override {}

    /**
     * This block's members, summed: set by the engine before the rack
     * renders, valid for that call only. Null means nothing is routed here.
     */
    void setInput(const float *l, const float *r) { inL = l; inR = r; }

    bool render(float *L, float *R, int32_t frames) override {
        if (inL == nullptr) {
            for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
        } else {
            for (int32_t i = 0; i < frames; ++i) {
                L[i] = inL[i];
                R[i] = inR[i];
            }
        }
        return true;
    }

  private:
    const float *inL = nullptr;
    const float *inR = nullptr;
};

} // namespace acidulous::machine
