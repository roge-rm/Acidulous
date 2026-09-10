#pragma once
#include <cstdint>
#include <engine/core/Params.h>

// An insert effect in a rack slot. Same lifecycle rules as Machine.
namespace acidulous {

// Bypass is addressed like a parameter (unit effect1/2, this index) so the UI,
// automation lanes and recording treat it as one, without it living in every
// effect's table.
constexpr int32_t kEffectBypassIndex = -2;

class Effect {
  public:
    virtual ~Effect() = default;
    virtual const char *typeName() const = 0;
    virtual const ParamDef *paramDefs(int32_t &count) const = 0;
    virtual void prepare(int32_t sampleRate) = 0;
    virtual void reset() = 0;
    // Once per block, before process(): tempo and the block's tick range, for
    // anything synced to the transport.
    virtual void onBlock(int64_t /*tickStart*/, int64_t /*tickEnd*/, float /*bpm*/) {}
    // In place. Return true if the output is stereo.
    virtual bool process(float *L, float *R, int32_t frames, bool stereoIn) = 0;
    ParamSet &params() { return params_; }

    // What the rack calls: bypass short-circuits, params are ticked here.
    bool run(float *L, float *R, int32_t frames, bool stereoIn) {
        if (bypass_) return stereoIn;
        params_.tick();
        return process(L, R, frames, stereoIn);
    }

    // Bypass keeps state (a delay's tail survives being switched back on).
    void setBypass(bool on) { bypass_ = on; }
    bool bypassed() const { return bypass_; }

  protected:
    void initParams() {
        int32_t n = 0;
        const ParamDef *defs = paramDefs(n);
        params_.init(defs, n);
    }
    ParamSet params_;
    bool bypass_ = false;
};

} // namespace acidulous
