#pragma once
#include "Math.h"

// Delay / attack / decay / sustain / release, with an optional repeat that
// loops back to the delay instead of holding. Per sample; exponential
// segments with a small overshoot, which is what makes them sound analogue
// rather than mathematical.
namespace acidulous::dsp {

class Adsr {
  public:
    void setSampleRate(float sr) { sampleRate = sr; }
    void set(float delaySec, float attackSec, float decaySec, float sustain01, float releaseSec, bool repeat) {
        delaySamples = static_cast<int32_t>(delaySec * sampleRate);
        aCoeff = onePoleCoeff(attackSec * 0.4f, sampleRate);
        dCoeff = onePoleCoeff(decaySec * 0.35f, sampleRate);
        rCoeff = onePoleCoeff(releaseSec * 0.35f, sampleRate);
        sustain = clampf(sustain01, 0.0f, 1.0f);
        loop = repeat;
    }

    void trigger() { stage = delaySamples > 0 ? Stage::Delay : Stage::Attack; delayLeft = delaySamples; }
    void retrigger() { trigger(); level = 0.0f; }
    void release() { if (stage != Stage::Idle) stage = Stage::Release; }
    void kill() { stage = Stage::Idle; level = 0.0f; }
    /**
     * Finish a release that is already under way, over [seconds].
     *
     * The curve carries on from wherever it is, only faster, so a tail that
     * has to go is faded rather than cut - `kill()` would be a click. Only the
     * release coefficient moves; the next `set()` puts it back.
     */
    void hasten(float seconds) {
        if (stage == Stage::Release) rCoeff = onePoleCoeff(seconds * 0.35f, sampleRate);
    }
    /**
     * Back to new, for a panic.
     *
     * kill() silences the envelope but leaves its coefficients, its sustain
     * and its delay counter where the last patch put them, which is right
     * for a note-off and wrong for a reset: a render would start with the
     * shape of whatever was playing before it. The sample rate survives
     * because prepare() set it, not a patch.
     */
    void reset() {
        const float sr = sampleRate;
        *this = Adsr();
        sampleRate = sr;
    }
    bool active() const { return stage != Stage::Idle; }
    float value() const { return level; }

    float next() {
        switch (stage) {
        case Stage::Delay:
            if (--delayLeft <= 0) stage = Stage::Attack;
            break;
        case Stage::Attack:
            level += (1.2f - level) * aCoeff;
            if (level >= 1.0f) { level = 1.0f; stage = Stage::Decay; }
            break;
        case Stage::Decay:
            level += (sustain - 0.02f - level) * dCoeff;
            if (level <= sustain + 0.001f) {
                level = sustain;
                if (loop) { stage = delaySamples > 0 ? Stage::Delay : Stage::Attack; delayLeft = delaySamples; }
                else stage = Stage::Sustain;
            }
            break;
        case Stage::Sustain:
            level += (sustain - level) * 0.001f;
            break;
        case Stage::Release:
            level += (-0.02f - level) * rCoeff;
            if (level <= 0.0005f) { level = 0.0f; stage = Stage::Idle; }
            break;
        case Stage::Idle:
            level = 0.0f;
            break;
        }
        return level;
    }

  private:
    enum class Stage { Idle, Delay, Attack, Decay, Sustain, Release };
    float sampleRate = 48000.0f;
    float aCoeff = 0.01f, dCoeff = 0.001f, rCoeff = 0.001f, sustain = 1.0f, level = 0.0f;
    int32_t delaySamples = 0, delayLeft = 0;
    bool loop = false;
    Stage stage = Stage::Idle;
};

} // namespace acidulous::dsp
