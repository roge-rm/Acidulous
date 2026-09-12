#pragma once
#include <cstdint>
#include <engine/core/Params.h>

// An insert effect in a rack slot. Same lifecycle rules as Machine.
namespace acidulous {

class Effect {
  public:
    virtual ~Effect() = default;
    virtual const char *typeName() const = 0;
    virtual const ParamDef *paramDefs(int32_t &count) const = 0;
    virtual void prepare(int32_t sampleRate) = 0;
    virtual void reset() = 0;
    // In place. Return true if the output is stereo.
    virtual bool process(float *L, float *R, int32_t frames, bool stereoIn) = 0;
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
