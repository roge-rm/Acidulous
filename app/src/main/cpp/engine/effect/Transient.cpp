#include "Transient.h"
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>
#include <sequencer/Swing.h>

namespace acidulous::effect {

namespace {
/** The followers at time 1, in seconds. */
constexpr float kFastAttack = 0.0003f, kSlowAttack = 0.015f, kRelease = 0.05f;
constexpr float kHoldRelease = 0.4f, kFallRelease = 0.04f;
/** Added to every follower, so silence and hiss read as no change (-80 dB). */
constexpr float kFloor = 1.0e-4f;
/**
 * How far the followers may part, in octaves of level (6 dB each), and how
 * much of that `attack` or `sustain` at 1 turns into gain: up to 12 dB.
 */
constexpr float kMostApart = 4.0f, kDepth = 0.5f;
/** The gain's range, in octaves: -24 to +12 dB. */
constexpr float kLeast = -4.0f, kMost = 2.0f;
/** At the bottom of `split` the whole sound is shaped. */
constexpr float kSplitOff = 21.0f;
/** The grid's step lengths in ticks: 1/16 triplet, 1/16, 1/8. */
constexpr int64_t kStepTicks[3] = {kPPQN / 6, kPPQN / 4, kPPQN / 2};
/**
 * The hit detector: a 1 ms average of the level against a 30 ms one. A hit
 * starts when the short one is kOnset times the long one (6 dB) and the next
 * can start once it's back under kRearm times.
 */
constexpr float kShortAvg = 0.001f, kLongAvg = 0.03f;
constexpr float kOnset = 2.0f, kRearm = 1.2f;
/**
 * The accent comes in as the attack followers part from kAccentFrom to 1
 * octave, so it leaves the ring between hits alone.
 */
constexpr float kAccentFrom = 0.3f;
/** The hit's envelope falls this fast (seconds): slower than a 20 Hz half wave. */
constexpr float kEnvRelease = 0.04f;
/** A hit lasts at least this long, in seconds at time 1, and ends once its envelope is half its peak. */
constexpr float kHitHold = 0.015f, kHitEnd = 0.5f;
/**
 * The accent's glide in at a hit's start and back to flat after it, in
 * seconds: in under the first wave, out slowly enough not to step.
 */
constexpr float kAccentIn = 0.0005f, kAccentOut = 0.005f;
/**
 * A hit up to a quarter step early counts as the step it's early for, so
 * a played-in or rounded note isn't put on the step before.
 */
constexpr int64_t kLeadDivisor = 4;

inline float follow(float &env, float x, float attack, float release) {
    env = dsp::guardDenormal(env + (x - env) * (x > env ? attack : release));
    return env;
}
} // namespace

const ParamDef *Transient::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"attack", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"sustain", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"time", 0.25f, 4.0f, 1.0f, Curve::Exponential, 0, ""},
        {"split", 20.0f, 8000.0f, 20.0f, Curve::Exponential, 0, "Hz"},
        {"gain", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"grid", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"depth", 0.0f, 12.0f, 6.0f, Curve::Linear, 0, "dB"},
        {"rate", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""}, // 1/16T 1/16 1/8
        {"length", 1.0f, 16.0f, 16.0f, Curve::Stepped, 16, ""},
        // The steps: 0 down, 1 flat, 2 up.
        {"a01", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a02", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a03", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a04", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a05", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a06", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a07", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a08", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a09", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a10", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a11", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a12", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a13", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a14", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a15", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
        {"a16", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""},
    };
    count = Count;
    return defs;
}

void Transient::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    timeWas = -1.0f;
    accentIn = dsp::onePoleCoeff(kAccentIn, sr);
    accentOut = dsp::onePoleCoeff(kAccentOut, sr);
    envRelease = dsp::onePoleCoeff(kEnvRelease, sr);
    shortCoeff = dsp::onePoleCoeff(kShortAvg, sr);
    longCoeff = dsp::onePoleCoeff(kLongAvg, sr);
    reset();
}

void Transient::reset() {
    low[0] = low[1] = 0.0f;
    fastA = slowA = holdS = fallS = 0.0f;
    shortAvg = longAvg = 0.0f;
    env = hitPeak = 0.0f;
    hitFrames = 0;
    armed = true;
    accent = accentNow = 0.0f;
}

float Transient::stepAccent(int64_t songTick) const {
    const auto &p = params_;
    const int64_t step = kStepTicks[std::clamp(static_cast<int>(p.get(Rate) + 0.5f), 0, 2)];
    const int64_t length = std::clamp(static_cast<int64_t>(p.get(Length) + 0.5f), int64_t{1}, int64_t{16});
    // Back to where the hit's note was written, swing counted from its clip's start, then onto the grid.
    const ModTransport &t = modTransport_;
    const int64_t inClip = songTick - t.swingFrom;
    const int64_t straight = inClip >= 0 ? seq::Swing::from(inClip, t.swing, t.swingPair) + t.swingFrom : songTick;
    const int64_t at = ((straight + step / kLeadDivisor) / step) % length;
    return std::round(p.get(A01 + static_cast<int32_t>(at))) - 1.0f;
}

bool Transient::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float time = p.get(Time);
    if (time != timeWas) {
        timeWas = time;
        atkFast = dsp::onePoleCoeff(kFastAttack, sr);
        atkSlow = dsp::onePoleCoeff(kSlowAttack * time, sr);
        relA = dsp::onePoleCoeff(kRelease * time, sr);
        relHold = dsp::onePoleCoeff(kHoldRelease * time, sr);
        relFall = dsp::onePoleCoeff(kFallRelease * time, sr);
    }
    const float attack = p.get(Attack) * kDepth, sustain = p.get(Sustain) * kDepth;
    const float split = p.get(Split);
    const bool splitting = split > kSplitOff;
    const float lc = splitting ? dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * split), sr) : 0.0f;
    const int chans = stereoIn ? 2 : 1;
    // The low filter only runs while split is on; it starts from silence each time.
    if (!splitting) low[0] = low[1] = 0.0f;
    // The grid: only while the song is moving.
    const bool grid = p.get(Grid) >= 0.5f;
    const bool rolling = modTransport_.rolling;
    const auto hold = static_cast<int32_t>(kHitHold * time * sr);
    const double ticksPerFrame = frames > 0 ? static_cast<double>(blockEnd - blockStart) / blockFramesOf(frames) : 0.0;
    const double songStart = static_cast<double>(blockStart + modTransport_.songOffset);
    const float depth = p.get(Depth) / 6.0206f; // in octaves, as the gain is worked out
    for (int32_t i = 0; i < frames; ++i) {
        // The part that's shaped: above the split, or all of it.
        float hi[2] = {L[i], stereoIn ? R[i] : 0.0f};
        if (splitting) {
            for (int c = 0; c < chans; ++c) {
                low[c] = dsp::guardDenormal(low[c] + (hi[c] - low[c]) * lc);
                hi[c] -= low[c];
            }
        }
        const float x = std::max(std::fabs(hi[0]), std::fabs(hi[1]));
        follow(fastA, x, atkFast, relA);
        follow(slowA, x, atkSlow, relA);
        follow(holdS, x, atkFast, relHold);
        follow(fallS, x, atkFast, relFall);
        const float apart = std::clamp(std::log2((fastA + kFloor) / (slowA + kFloor)), 0.0f, kMostApart);
        const float ring = std::clamp(std::log2((holdS + kFloor) / (fallS + kFloor)), 0.0f, kMostApart);
        float shape = attack * apart + sustain * ring;
        if (grid || accentNow != 0.0f) {
            shortAvg = dsp::guardDenormal(shortAvg + (x - shortAvg) * shortCoeff);
            longAvg = dsp::guardDenormal(longAvg + (x - longAvg) * longCoeff);
            env = dsp::guardDenormal(x > env ? x : env + (x - env) * envRelease);
            if (armed && shortAvg + kFloor > kOnset * (longAvg + kFloor)) {
                armed = false;
                hitPeak = env;
                hitFrames = 0;
                const double tick = songStart + static_cast<double>(i + subOffset_) * ticksPerFrame;
                accent = grid && rolling ? stepAccent(static_cast<int64_t>(std::floor(tick))) : 0.0f;
            } else if (!armed) {
                hitPeak = std::max(hitPeak, env);
                if (++hitFrames > hold && env < kHitEnd * hitPeak) {
                    // The hit is over: back to flat, so the next starts from there.
                    armed = true;
                    accent = 0.0f;
                }
            }
            // Turned off, it eases out and then stops, back to the plain sum above.
            const float to = grid ? accent : 0.0f;
            accentNow += (to - accentNow) * (armed ? accentOut : accentIn);
            // Landed, exactly, so it can't sink into denormals.
            if (std::fabs(to - accentNow) < 1.0e-5f) accentNow = to;
            shape += accentNow * depth * std::clamp((apart - kAccentFrom) / (1.0f - kAccentFrom), 0.0f, 1.0f);
        }
        const float g = std::exp2(std::clamp(shape, kLeast, kMost));
        if (splitting) {
            L[i] = low[0] + hi[0] * g;
            if (stereoIn) R[i] = low[1] + hi[1] * g;
        } else {
            L[i] *= g;
            if (stereoIn) R[i] *= g;
        }
    }
    return stereoIn;
}

} // namespace acidulous::effect
