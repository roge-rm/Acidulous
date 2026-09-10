#include "Effects.h"
#include <cmath>

namespace acidulous::effect {

using dsp::clampf;
using dsp::fastTanh;
using dsp::Lfo;

namespace {

constexpr float kMaxDelaySeconds = 3.0f;

// Delay note values in quarter notes.
constexpr int kDelayTimes = 8;
constexpr float kDelayBeats[kDelayTimes] = {0.125f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 4.0f};

inline float peakEnvelope(float &env, float in, float attack, float release) {
    const float a = std::fabs(in);
    env += (a - env) * (a > env ? attack : release);
    return env;
}

inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }

inline float xorshift01(uint32_t &s) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return static_cast<float>(s & 0xffffff) / 16777216.0f;
}

} // namespace

// --- Delay ------------------------------------------------------------------------

const ParamDef *Delay::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"time", 0.0f, 7.0f, 3.0f, Curve::Stepped, kDelayTimes, ""},   // 1/32 .. 1 bar
        {"feedback", 0.0f, 0.95f, 0.4f, Curve::Linear, 0, ""},
        {"tone", 300.0f, 18000.0f, 6000.0f, Curve::Exponential, 0, "Hz"},
        {"pingpong", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
        {"mix", 0.0f, 1.0f, 0.35f, Curve::Linear, 0, ""},
        {"duck", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"wobble", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Delay::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &l : line) l.prepare(static_cast<int32_t>(sr * kMaxDelaySeconds) + 64);
    reset();
}

void Delay::reset() {
    for (auto &l : line) l.clear();
    lp[0] = lp[1] = 0.0f;
    duckEnv = 0.0f;
    readSamples = clampf(kDelayBeats[3] * 60.0f / bpm * sr, 1.0f, sr * kMaxDelaySeconds);
}

bool Delay::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const int timeIdx = static_cast<int>(p.get(Time) + 0.5f);
    const float target = clampf(kDelayBeats[timeIdx] * 60.0f / bpm * sr, 1.0f, sr * kMaxDelaySeconds);
    const float fb = p.get(Feedback), mix = p.get(Mix), duck = p.get(Duck), wobble = p.get(Wobble);
    const bool pingPong = p.get(PingPong) >= 0.5f;
    const float toneCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * p.get(Tone)), sr);
    const float duckAtk = dsp::onePoleCoeff(0.002f, sr), duckRel = dsp::onePoleCoeff(0.25f, sr);
    // The read position glides to a new note value: a pitch swoop, not a splice.
    const float glide = dsp::onePoleCoeff(0.05f, sr);
    const float wobbleInc = 0.9f / sr, wobbleDepth = wobble * 70.0f;

    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        readSamples += (target - readSamples) * glide;
        wobblePhase += wobbleInc;
        if (wobblePhase >= 1.0f) wobblePhase -= 1.0f;
        // Two incommensurate sines: tape flutter rather than a vibrato.
        const float drift = wobbleDepth * (0.6f * std::sin(wobblePhase * dsp::kTwoPi) +
                                           0.4f * std::sin(wobblePhase * dsp::kTwoPi * 2.71f));
        const float rd = readSamples + drift;
        const float tapL = line[0].read(rd), tapR = line[1].read(rd);
        lp[0] += (tapL - lp[0]) * toneCoeff;
        lp[1] += (tapR - lp[1]) * toneCoeff;
        if (pingPong) {
            line[0].write(inL * 0.5f + inR * 0.5f + lp[1] * fb);
            line[1].write(lp[0] * fb);
        } else {
            line[0].write(inL + lp[0] * fb);
            line[1].write(inR + lp[1] * fb);
        }
        // Duck: echoes sit under the dry signal and swell up in the gaps.
        const float env = peakEnvelope(duckEnv, (std::fabs(inL) + std::fabs(inR)) * 1.5f, duckAtk, duckRel);
        const float wet = mix * (1.0f - duck * clampf(env, 0.0f, 1.0f));
        L[i] = inL + tapL * wet;
        R[i] = inR + tapR * wet;
    }
    return true;
}

// --- Reverb -----------------------------------------------------------------------

namespace {
// Freeverb's tunings at 44.1 kHz, scaled in prepare(); right channel offset by
// 23 samples for width.
constexpr int kCombBase[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassBase[4] = {556, 441, 341, 225};
constexpr float kSizeStretchMax = 1.9f;
} // namespace

const ParamDef *Reverb::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"size", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"damp", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"tone", 500.0f, 18000.0f, 9000.0f, Curve::Exponential, 0, "Hz"},
        {"predelay", 0.0f, 200.0f, 10.0f, Curve::Linear, 0, "ms"},
        {"mix", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"freeze", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"gate", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Reverb::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    const float scale = sr / 44100.0f;
    for (int c = 0; c < 2; ++c) {
        for (int i = 0; i < 8; ++i) {
            combs[c][i].len = static_cast<int32_t>(static_cast<float>(kCombBase[i]) * scale) + (c == 1 ? 23 : 0);
            combs[c][i].line.prepare(static_cast<int32_t>(static_cast<float>(combs[c][i].len) * kSizeStretchMax) + 8);
        }
        for (int i = 0; i < 4; ++i) {
            aps[c][i].len = static_cast<int32_t>(static_cast<float>(kAllpassBase[i]) * scale) + (c == 1 ? 23 : 0);
            aps[c][i].line.prepare(aps[c][i].len + 8);
        }
        pre[c].prepare(static_cast<int32_t>(sr * 0.21f));
    }
    reset();
}

void Reverb::reset() {
    for (auto &ch : combs) for (auto &c : ch) { c.line.clear(); c.store = 0.0f; }
    for (auto &ch : aps) for (auto &a : ch) a.line.clear();
    for (auto &p : pre) p.clear();
    lp[0] = lp[1] = 0.0f;
    gateEnv = 0.0f;
    gateHold = 0;
    inputEnv = 0.0f;
}

bool Reverb::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const bool freeze = p.get(Freeze) >= 0.5f;
    const float size = p.get(Size);
    const float stretch = 1.0f + (kSizeStretchMax - 1.0f) * size;
    const float fb = freeze ? 1.0f : 0.72f + 0.26f * size;
    const float damp = freeze ? 0.0f : p.get(Damp) * 0.6f;
    const float inGain = freeze ? 0.0f : 0.015f;
    const float mix = p.get(Mix), gate = p.get(Gate);
    const float preSamples = clampf(p.get(PreDelay) * 0.001f * sr, 1.0f, sr * 0.2f);
    const float toneCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * p.get(Tone)), sr);
    const float envAtk = dsp::onePoleCoeff(0.001f, sr), envRel = dsp::onePoleCoeff(0.05f, sr);
    const float gateRel = dsp::onePoleCoeff(0.006f, sr);
    const int32_t holdSamples = static_cast<int32_t>((0.04f + gate * 0.5f) * sr);

    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        pre[0].write(inL);
        pre[1].write(inR);
        const float dry[2] = {pre[0].read(preSamples), pre[1].read(preSamples)};
        float out[2];
        for (int c = 0; c < 2; ++c) {
            const float x = dry[c] * inGain;
            float acc = 0.0f;
            for (auto &comb : combs[c]) {
                const float y = comb.line.read(static_cast<float>(comb.len) * stretch);
                comb.store = y * (1.0f - damp) + comb.store * damp;
                comb.line.write(x + comb.store * fb);
                acc += y;
            }
            for (auto &ap : aps[c]) {
                const float y = ap.line.read(static_cast<float>(ap.len));
                ap.line.write(acc + y * 0.5f);
                acc = y - acc;
            }
            lp[c] += (acc - lp[c]) * toneCoeff;
            out[c] = lp[c];
        }
        // Gate: the tail is let through for a hold after each hit, then cut -
        // the drum-room trick, without needing a second effect.
        float g = 1.0f;
        if (gate > 0.0f) {
            const float env = peakEnvelope(inputEnv, (std::fabs(inL) + std::fabs(inR)), envAtk, envRel);
            if (env > 0.05f) { gateHold = holdSamples; gateEnv = 1.0f; }
            if (gateHold > 0) --gateHold; else gateEnv += (0.0f - gateEnv) * gateRel;
            g = gateEnv;
        }
        L[i] = inL + out[0] * mix * g;
        R[i] = inR + out[1] * mix * g;
    }
    return true;
}

// --- Eq ---------------------------------------------------------------------------

const ParamDef *Eq::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"lowgain", -15.0f, 15.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"lowfreq", 40.0f, 500.0f, 120.0f, Curve::Exponential, 0, "Hz"},
        {"midgain", -15.0f, 15.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"midfreq", 200.0f, 8000.0f, 1000.0f, Curve::Exponential, 0, "Hz"},
        {"midq", 0.3f, 6.0f, 0.8f, Curve::Exponential, 0, ""},
        {"highgain", -15.0f, 15.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"highfreq", 1500.0f, 16000.0f, 6000.0f, Curve::Exponential, 0, "Hz"},
        {"tilt", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Eq::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Eq::reset() {
    for (int c = 0; c < 2; ++c) { low[c].reset(); mid[c].reset(); high[c].reset(); tiltLo[c].reset(); tiltHi[c].reset(); }
}

bool Eq::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float tilt = p.get(Tilt) * 6.0f; // +-6 dB across the spectrum
    for (int c = 0; c < 2; ++c) {
        low[c].lowShelf(p.get(LowFreq), p.get(LowGain), sr);
        mid[c].peak(p.get(MidFreq), p.get(MidGain), p.get(MidQ), sr);
        high[c].highShelf(p.get(HighFreq), p.get(HighGain), sr);
        tiltLo[c].lowShelf(650.0f, -tilt, sr);
        tiltHi[c].highShelf(650.0f, tilt, sr);
    }
    const int chans = stereoIn ? 2 : 1;
    for (int c = 0; c < chans; ++c) {
        float *buf = c == 0 ? L : R;
        for (int32_t i = 0; i < frames; ++i) {
            float x = buf[i];
            x = low[c].process(x);
            x = mid[c].process(x);
            x = high[c].process(x);
            x = tiltLo[c].process(x);
            buf[i] = tiltHi[c].process(x);
        }
    }
    return stereoIn;
}

// --- Distortion -------------------------------------------------------------------

const ParamDef *Distortion::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"drive", 1.0f, 40.0f, 4.0f, Curve::Exponential, 0, ""},
        {"tone", 400.0f, 20000.0f, 8000.0f, Curve::Exponential, 0, "Hz"},
        {"mix", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"mode", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""}, // soft, hard, fold, tube
        {"bias", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Distortion::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Distortion::reset() { for (int c = 0; c < 2; ++c) { tone[c].reset(); dcIn[c] = dcOut[c] = 0.0f; } }

bool Distortion::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float drive = p.get(Drive), mix = p.get(Mix), bias = p.get(Bias) * 0.6f;
    const int mode = static_cast<int>(p.get(Mode) + 0.5f);
    for (int c = 0; c < 2; ++c) tone[c].lowpass(p.get(Tone), 0.707f, sr);
    // Level roughly follows the input: a loud drive is a texture change, not a jump.
    const float comp = 1.0f / std::sqrt(drive);
    const int chans = stereoIn ? 2 : 1;
    for (int c = 0; c < chans; ++c) {
        float *buf = c == 0 ? L : R;
        for (int32_t i = 0; i < frames; ++i) {
            const float in = buf[i];
            float x = in * drive + bias; // bias: asymmetry -> even harmonics
            float y;
            switch (mode) {
            case 1: y = clampf(x, -1.0f, 1.0f); break;
            case 2: { // wavefold: triangle-wrap x back into -1..1
                const float t = x * 0.25f + 0.25f;
                y = 4.0f * std::fabs(t - std::floor(t + 0.5f)) - 1.0f;
                break;
            }
            case 3: y = fastTanh(x) - 0.3f * fastTanh(x * 0.5f) * fastTanh(x * 0.5f); break; // tube: soft with a sag
            default: y = fastTanh(x); break;
            }
            y *= comp * 2.0f;
            // Block the DC that bias introduces (one-pole highpass at ~10 Hz).
            const float hp = y - dcIn[c] + 0.9987f * dcOut[c];
            dcIn[c] = y;
            dcOut[c] = hp;
            const float wet = tone[c].process(hp);
            buf[i] = in + (wet - in) * mix;
        }
    }
    return stereoIn;
}

// --- Compressor -------------------------------------------------------------------

const ParamDef *Compressor::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"threshold", -60.0f, 0.0f, -18.0f, Curve::Linear, 0, "dB"},
        {"ratio", 1.0f, 20.0f, 4.0f, Curve::Exponential, 0, ""},
        {"attack", 0.1f, 100.0f, 5.0f, Curve::Exponential, 0, "ms"},
        {"release", 10.0f, 1000.0f, 120.0f, Curve::Exponential, 0, "ms"},
        {"makeup", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"pump", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"pumprate", 0.0f, 3.0f, 2.0f, Curve::Stepped, 4, ""}, // 1/16 1/8 1/4 1/2
    };
    count = Count;
    return defs;
}

void Compressor::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Compressor::reset() { env = 0.0f; gain = 1.0f; }

bool Compressor::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float thr = p.get(Threshold), ratio = p.get(Ratio), makeup = dbToGain(p.get(Makeup));
    const float atk = dsp::onePoleCoeff(p.get(Attack) * 0.001f, sr), rel = dsp::onePoleCoeff(p.get(Release) * 0.001f, sr);
    const float pump = p.get(Pump);
    const int rateIdx = static_cast<int>(p.get(PumpRate) + 0.5f);
    float phase = Lfo::phaseAt(tick, rateIdx);
    const float inc = Lfo::phaseInc(rateIdx, bpm, sr);
    const float slope = 1.0f - 1.0f / ratio;
    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        const float a = std::fabs(inL) > std::fabs(inR) ? std::fabs(inL) : std::fabs(inR);
        env += (a - env) * (a > env ? atk : rel);
        float g = 1.0f;
        if (env > 1e-5f) {
            const float over = 20.0f * std::log10(env) - thr;
            if (over > 0.0f) g = dbToGain(-over * slope);
        }
        // Pump: a sidechain-shaped dip on every beat of the chosen note value,
        // no routing needed. Exponential recovery like a kick would give.
        if (pump > 0.0f) {
            g *= 1.0f - pump * std::exp(-phase * 6.0f);
            phase += inc;
            if (phase >= 1.0f) phase -= 1.0f;
        }
        gain = g;
        L[i] = inL * g * makeup;
        if (stereoIn) R[i] = inR * g * makeup;
    }
    return stereoIn;
}

// --- Filter -----------------------------------------------------------------------

const ParamDef *Filter::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"cutoff", 20.0f, 20000.0f, 1500.0f, Curve::Exponential, 0, "Hz"},
        {"reso", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"mode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""}, // LP BP HP
        {"lforate", 0.0f, 7.0f, 4.0f, Curve::Stepped, Lfo::kRates, ""},
        {"lfodepth", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"envdepth", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Filter::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &s : svf) s.setSampleRate(sr);
    reset();
}
void Filter::reset() { for (auto &s : svf) s.reset(); follower = 0.0f; }

bool Filter::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float cutoff = p.get(Cutoff), reso = p.get(Reso), lfoDepth = p.get(LfoDepth), envDepth = p.get(EnvDepth);
    const int mode = static_cast<int>(p.get(Mode) + 0.5f);
    const int rateIdx = static_cast<int>(p.get(LfoRate) + 0.5f);
    float phase = Lfo::phaseAt(tick, rateIdx);
    const float inc = Lfo::phaseInc(rateIdx, bpm, sr);
    const float atk = dsp::onePoleCoeff(0.004f, sr), rel = dsp::onePoleCoeff(0.12f, sr);
    const int chans = stereoIn ? 2 : 1;
    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        const float a = (std::fabs(inL) + std::fabs(inR)) * 1.2f;
        follower += (a - follower) * (a > follower ? atk : rel);
        // Both modulators move the cutoff in octaves: the LFO up to +-3, the
        // follower up to 4 (an auto-wah when positive, a duck when negative).
        const float octaves = lfoDepth * 3.0f * Lfo::triangle(phase) + envDepth * 4.0f * clampf(follower, 0.0f, 1.0f);
        const float fc = cutoff * std::exp2(octaves);
        phase += inc;
        if (phase >= 1.0f) phase -= 1.0f;
        for (int c = 0; c < chans; ++c) {
            float *buf = c == 0 ? L : R;
            svf[c].set(fc, reso);
            const float x = buf[i];
            buf[i] = mode == 1 ? svf[c].bandpass(x) : (mode == 2 ? svf[c].highpass(x) : svf[c].lowpass(x));
        }
    }
    return stereoIn;
}

// --- Bitcrusher -------------------------------------------------------------------

const ParamDef *Bitcrusher::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"bits", 1.0f, 16.0f, 8.0f, Curve::Linear, 0, ""},
        {"rate", 500.0f, 48000.0f, 12000.0f, Curve::Exponential, 0, "Hz"},
        {"jitter", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"tone", 300.0f, 20000.0f, 20000.0f, Curve::Exponential, 0, "Hz"},
        {"mix", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Bitcrusher::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Bitcrusher::reset() { hold[0] = hold[1] = 0.0f; phase = 0.0f; period = 1.0f; for (auto &t : tone) t.reset(); }

bool Bitcrusher::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float bits = p.get(Bits), jitter = p.get(Jitter), mix = p.get(Mix);
    const float inc = p.get(Rate) / sr;
    const float levels = std::exp2(bits - 1.0f);
    for (auto &t : tone) t.lowpass(p.get(Tone), 0.707f, sr);
    const int chans = stereoIn ? 2 : 1;
    for (int32_t i = 0; i < frames; ++i) {
        phase += inc;
        if (phase >= period) {
            phase -= period;
            // Jitter: each hold lasts a random length, so the aliasing smears
            // into noise instead of ringing at one pitch.
            period = 1.0f + jitter * (xorshift01(rng) * 1.8f - 0.9f);
            for (int c = 0; c < chans; ++c) {
                const float x = (c == 0 ? L : R)[i];
                hold[c] = std::round(x * levels) / levels;
            }
        }
        for (int c = 0; c < chans; ++c) {
            float *buf = c == 0 ? L : R;
            const float wet = tone[c].process(hold[c]);
            buf[i] = buf[i] + (wet - buf[i]) * mix;
        }
    }
    return stereoIn;
}

// --- Phaser -----------------------------------------------------------------------

const ParamDef *Phaser::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"rate", 0.0f, 7.0f, 5.0f, Curve::Stepped, Lfo::kRates, ""},
        {"depth", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        {"feedback", 0.0f, 0.9f, 0.3f, Curve::Linear, 0, ""},
        {"stages", 0.0f, 3.0f, 1.0f, Curve::Stepped, 4, ""}, // 2 4 6 8
        {"spread", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Phaser::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Phaser::reset() { for (auto &ch : ap) for (auto &a : ch) a.reset(); fb[0] = fb[1] = 0.0f; }

bool Phaser::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float depth = p.get(Depth), feedback = p.get(Feedback), spread = p.get(Spread), mix = p.get(Mix);
    const int stages = 2 * (static_cast<int>(p.get(Stages) + 0.5f) + 1);
    const int rateIdx = static_cast<int>(p.get(Rate) + 0.5f);
    const float phase = Lfo::phaseAt(tick, rateIdx);
    // Sweep 120 Hz .. 5 kHz, the right channel offset by up to half a cycle.
    for (int c = 0; c < 2; ++c) {
        float ph = phase + (c == 1 ? spread * 0.5f : 0.0f);
        if (ph >= 1.0f) ph -= 1.0f;
        const float pos = 0.5f + 0.5f * depth * Lfo::sine(ph);
        const float fc = 120.0f * std::pow(5000.0f / 120.0f, pos);
        for (int s = 0; s < stages; ++s) ap[c][s].allpass(fc * (1.0f + 0.15f * static_cast<float>(s)), 0.6f, sr);
    }
    for (int32_t i = 0; i < frames; ++i) {
        const float in[2] = {L[i], stereoIn ? R[i] : L[i]};
        for (int c = 0; c < 2; ++c) {
            float x = in[c] + fb[c] * feedback;
            for (int s = 0; s < stages; ++s) x = ap[c][s].process(x);
            fb[c] = x;
            (c == 0 ? L : R)[i] = in[c] + (x - in[c]) * mix;
        }
    }
    return true;
}

// --- Flanger ----------------------------------------------------------------------

const ParamDef *Flanger::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"rate", 0.0f, 7.0f, 5.0f, Curve::Stepped, Lfo::kRates, ""},
        {"depth", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"feedback", 0.0f, 0.95f, 0.5f, Curve::Linear, 0, ""},
        {"negative", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"spread", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Flanger::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &l : line) l.prepare(static_cast<int32_t>(sr * 0.02f) + 64);
    reset();
}
void Flanger::reset() { for (auto &l : line) l.clear(); }

bool Flanger::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float depth = p.get(Depth), spread = p.get(Spread), mix = p.get(Mix);
    const float feedback = p.get(Feedback) * (p.get(Negative) >= 0.5f ? -1.0f : 1.0f);
    const int rateIdx = static_cast<int>(p.get(Rate) + 0.5f);
    float phase = Lfo::phaseAt(tick, rateIdx);
    const float inc = Lfo::phaseInc(rateIdx, bpm, sr);
    const float base = 0.0006f * sr, range = depth * 0.008f * sr; // 0.6 ms .. 8.6 ms
    for (int32_t i = 0; i < frames; ++i) {
        const float in[2] = {L[i], stereoIn ? R[i] : L[i]};
        for (int c = 0; c < 2; ++c) {
            float ph = phase + (c == 1 ? spread * 0.5f : 0.0f);
            if (ph >= 1.0f) ph -= 1.0f;
            const float d = base + range * (0.5f + 0.5f * Lfo::triangle(ph));
            const float tap = line[c].read(d);
            line[c].write(in[c] + tap * feedback);
            // Negative feedback with the wet inverted: the hollow, through-zero flavour.
            const float wet = feedback < 0.0f ? -tap : tap;
            (c == 0 ? L : R)[i] = in[c] + wet * mix;
        }
        phase += inc;
        if (phase >= 1.0f) phase -= 1.0f;
    }
    return true;
}

} // namespace acidulous::effect
