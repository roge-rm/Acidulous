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
 * The hit detector's high way: energy above kHighFrom Hz over kHighAvg
 * seconds, a hit when it's kHighRise times its recent peak (5 dB). The peak
 * hears it kHighLate seconds late and falls in kHighFall seconds, so a saw's
 * click every cycle isn't a hit down to about 40 Hz, and a 32nd roll's hits
 * at 180 bpm, 42 ms apart, are.
 */
constexpr float kHighFrom = 400.0f, kHighAvg = 0.001f, kHighRise = 3.2f;
constexpr float kHighLate = 0.002f, kHighFall = 0.022f;
/**
 * Energy under kHighFloor (-80 dB), or under kHighShare of the level's
 * (-30 dB), is nothing to the high way: what's left of a bass note's
 * ripple after the filter stays under it.
 */
constexpr float kHighFloor = 1.0e-8f, kHighShare = 1.0e-3f;
/**
 * The low way: the envelope falls in kEnvRelease seconds, slower than a 20 Hz
 * half wave, and a hit is when it's kEnvRise times its recent low (6 dB),
 * which climbs in kEnvUp seconds.
 */
constexpr float kEnvRelease = 0.04f, kEnvRise = 2.0f, kEnvUp = 0.03f;
/** After a hit starts, no other can for this long (seconds): a flam is 20 ms. */
constexpr float kWait = 0.008f;
/**
 * The accent comes in as the attack followers part from kAccentFrom to 1
 * octave, so it leaves the ring between hits alone.
 */
constexpr float kAccentFrom = 0.3f;
/**
 * The step holds at least kHitHold seconds and until the hit's envelope is
 * kHitEnd of its peak or its attack is over, or kHitMost seconds at most (all
 * at time 1), then goes back to flat, so a roll's next hit doesn't start on
 * the last one's step.
 */
constexpr float kHitHold = 0.015f, kHitEnd = 0.5f, kHitMost = 0.035f;
/**
 * The most the accent may move the gain from one sample to the next, in dB.
 * When the level rises faster than kRiseFrom dB a sample, as at the very
 * start of a hit, it may move by the rest of the rise too. The level is the
 * envelope smoothed over kLevel seconds, so noise's peaks don't count.
 */
constexpr float kAccentSlew = 0.05f, kRiseFrom = 0.1f, kLevel = 0.0005f;
/**
 * A hit with high energy at least kQuickShare of its level's (-20 dB) starts
 * with a click that covers a quicker change: up to kQuickSlew dB a sample
 * for its first kQuick seconds.
 */
constexpr float kQuickShare = 0.01f, kQuickSlew = 0.75f, kQuick = 0.00075f;
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
    hpCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * kHighFrom), sr);
    hiECoeff = dsp::onePoleCoeff(kHighAvg, sr);
    hiFall = 1.0f - dsp::onePoleCoeff(kHighFall, sr);
    pastLength = std::clamp(static_cast<int32_t>(kHighLate * sr), 1, kPastMost);
    envRelease = dsp::onePoleCoeff(kEnvRelease, sr);
    envUp = dsp::onePoleCoeff(kEnvUp, sr);
    accentStep = kAccentSlew / 6.0206f;
    quickStep = kQuickSlew / 6.0206f;
    riseFrom = kRiseFrom / 6.0206f;
    riseRatio = std::exp2(riseFrom);
    levelCoeff = dsp::onePoleCoeff(kLevel, sr);
    reset();
}

void Transient::reset() {
    low[0] = low[1] = 0.0f;
    fastA = slowA = holdS = fallS = 0.0f;
    for (auto &side : hpLow) for (float &lp : side) lp = 0.0f;
    hiE = hiPeak = 0.0f;
    std::fill(hiPast, hiPast + kPastMost, 0.0f);
    pastAt = 0;
    env = envLow = hitPeak = level = 0.0f;
    hitFrames = wait = quick = 0;
    inHit = false;
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
    const auto hold = static_cast<int32_t>(kHitHold * time * sr), most = static_cast<int32_t>(kHitMost * time * sr);
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
            // The high way, on the part that's shaped.
            float e = 0.0f;
            for (int c = 0; c < chans; ++c) {
                float h = hi[c];
                for (float &lp : hpLow[c]) {
                    lp = dsp::guardDenormal(lp + (h - lp) * hpCoeff);
                    h -= lp;
                }
                e = std::max(e, h * h);
            }
            hiE = dsp::guardDenormal(hiE + (e - hiE) * hiECoeff);
            const float late = hiPast[pastAt];
            hiPast[pastAt] = hiE;
            if (++pastAt >= pastLength) pastAt = 0;
            hiPeak = std::max(dsp::guardDenormal(hiPeak * hiFall), late);
            // The low way.
            env = dsp::guardDenormal(x > env ? x : env + (x - env) * envRelease);
            envLow = env < envLow ? env : dsp::guardDenormal(envLow + (env - envLow) * envUp);
            if (wait > 0) {
                // Waiting: both ways follow the hit up, so it can't start again on its own rise.
                --wait;
                hiPeak = std::max(hiPeak, hiE);
                envLow = std::max(envLow, env);
            } else if (const bool high = hiE > kHighRise * hiPeak + kHighFloor && hiE > kHighShare * env * env; high || env > kEnvRise * envLow + kFloor) {
                wait = static_cast<int32_t>(kWait * sr);
                quick = static_cast<int32_t>(kQuick * sr);
                hiPeak = hiE;
                envLow = env;
                inHit = true;
                hitPeak = env;
                hitFrames = 0;
                const double tick = songStart + static_cast<double>(i + subOffset_) * ticksPerFrame;
                accent = grid && rolling ? stepAccent(static_cast<int64_t>(std::floor(tick))) : 0.0f;
            }
            if (inHit) {
                hitPeak = std::max(hitPeak, env);
                if (++hitFrames > hold && (env < kHitEnd * hitPeak || apart < kAccentFrom || hitFrames > most)) {
                    // The hit is over: back to flat.
                    inHit = false;
                    accent = 0.0f;
                }
            }
            // Turned off, it eases out and then stops, back to the plain sum above.
            const float to = grid ? accent * depth * std::clamp((apart - kAccentFrom) / (1.0f - kAccentFrom), 0.0f, 1.0f) : 0.0f;
            // A hit's own fast rise or click covers a quicker change.
            const float levelWas = level;
            level = dsp::guardDenormal(level + (env - level) * levelCoeff);
            float slew = accentStep;
            // The log only when it's rising that fast, which is seldom.
            if (level + kFloor > riseRatio * (levelWas + kFloor))
                slew += std::log2((level + kFloor) / (levelWas + kFloor)) - riseFrom;
            if (quick > 0) {
                --quick;
                if (hiE > kQuickShare * env * env) slew = std::max(slew, quickStep);
            }
            accentNow += std::clamp(to - accentNow, -slew, slew);
            shape += accentNow;
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
