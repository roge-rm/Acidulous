#pragma once
#include <cstdint>
#include <cmath>
#include <cstring>
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
        const bool stereo = process(L, R, frames, stereoIn);
        applyGain(L, R, frames, stereo || stereoIn);
        return stereo;
    }

    // Bypass keeps state (a delay's tail survives being switched back on).
    void setBypass(bool on) { bypass_ = on; }
    bool bypassed() const { return bypass_; }

  protected:
    void initParams() {
        int32_t n = 0;
        const ParamDef *defs = paramDefs(n);
        params_.init(defs, n);
        // Every effect ends with an output trim, and it is applied here rather
        // than fourteen times over. Found by name so an effect that has not
        // got one simply does not get the multiply.
        gainIndex_ = -1;
        for (int32_t i = 0; i < n; ++i) {
            if (std::strcmp(defs[i].name, "gain") == 0) { gainIndex_ = i; break; }
        }
    }

  private:
    /**
     * The output trim, in decibels, applied after the effect has run.
     *
     * A wet/dry crossfade does not preserve level: mixing half of a chorus in
     * takes half the dry away and what replaces it is spread across several
     * detuned voices, so the sum is quieter than what went in. Dan, hearing
     * exactly that: "a lot of the effects (eg chorus) seem to make the sound
     * quieter". Every insert now ends with a knob that puts it back, which is
     * also the knob you want when a distortion has made something louder.
     */
    void applyGain(float *L, float *R, int32_t frames, bool stereo) {
        if (gainIndex_ < 0) return;
        const float db = params_.get(gainIndex_);
        if (db > -0.01f && db < 0.01f) return; // the default costs nothing
        const float g = std::pow(10.0f, db * 0.05f);
        for (int32_t i = 0; i < frames; ++i) L[i] *= g;
        if (stereo) for (int32_t i = 0; i < frames; ++i) R[i] *= g;
    }
    int32_t gainIndex_ = -1;

  protected:
    ParamSet params_;
    bool bypass_ = false;
};

} // namespace acidulous
