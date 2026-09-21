#include "Effects.h"
#include <algorithm>
#include <cmath>
#include <engine/core/Settings.h>
#include <engine/eventor/Scales.h>

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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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

/**
 * Keep a decaying feedback path out of denormal numbers.
 *
 * A comb filter's store falls toward zero for as long as the tail lasts, and
 * somewhere down there it crosses into denormals - where arithmetic is orders
 * of magnitude slower on some processors, and where the *result* depends on
 * the FPU's flush-to-zero flag rather than only on the input. That made this
 * reverb render differently the second time in `bank_test`, but not under the
 * sanitiser at -O1 and not on its own: the failing patches changed depending
 * on what had run before it, which is what a flag rather than a bug looks
 * like. Freeverb has had this exact line since 2000.
 *
 * Adding a tiny constant and taking it away again leaves the number where it
 * was to every bit a listener could hear, and never lets it get small enough
 * to become a denormal.
 */
/** What the tail reaches, so the bit crusher has something true to quantise
 *  against rather than a full scale it never sees. */
constexpr float kReverbNominal = 0.25f;
/**
 * How much of the shifted tail goes back into each comb.
 *
 * Found by sweeping rather than derived: the shimmer is injected into all
 * eight combs and each has its own resonance, so the loop gain is not
 * something a single line of arithmetic gets right. Undivided the output
 * reached six times full scale; at an eighth it was stable and inaudible,
 * moving the tail's centroid from 270 Hz to 317.
 *
 * The worst case is not the largest room, which is the trap this fell into
 * twice: `fb` is `0.72 + 0.26 * size`, so a *small* room has more headroom
 * under unity and therefore gets injected harder. Swept against every size
 * with the lo-fi and the wobble on, a half peaks at 13.7 and a quarter at
 * 3.9; 0.15 peaks at 0.72 and still lifts the tail's centroid from 337 Hz to
 * 878, which is a shimmer anyone can hear.
 */
constexpr float kCombInject = 0.15f;

inline float undenormal(float v) {
    static constexpr float kTiny = 1.0e-20f;
    return v + kTiny - kTiny;
}
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
        {"shimmer", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bits", 1.0f, 16.0f, 16.0f, Curve::Stepped, 16, ""},
        {"crush", 1.0f, 32.0f, 1.0f, Curve::Exponential, 0, ""},
        {"wobble", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        // A tenth of a second is far more window than the shimmer needs and
        // leaves room for the read point to slide without catching the head.
        shimmerLine[c].prepare(static_cast<int32_t>(sr * 0.12f));
    }
    reset();
}

void Reverb::reset() {
    for (auto &ch : combs) for (auto &c : ch) { c.line.clear(); c.store = 0.0f; }
    for (auto &ch : aps) for (auto &a : ch) a.line.clear();
    for (auto &p : pre) p.clear();
    for (auto &l : shimmerLine) l.clear();
    for (int c = 0; c < 2; ++c) {
        shimmerPhase[c] = 0.0f;
        shimmerFb[c] = 0.0f;
        shimDcX[c] = 0.0f;
        shimDcY[c] = 0.0f;
        shimLp[c] = 0.0f;
        crushAcc[c] = 0.0f;
        crushHeld[c] = 0.0f;
    }
    wobblePhase = 0.0f;
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
    const float shimmer = p.get(Shimmer);
    const int32_t bits = static_cast<int32_t>(p.get(Bits) + 0.5f);
    const float crush = p.get(Crush);
    const float wobble = p.get(Wobble);
    // The shimmer window, and how far the read point slides per sample to come
    // out an octave up. Half a window either side keeps the crossfade honest.
    const float shimWin = sr * 0.045f;
    // The shimmer's own damping: brighter rooms let the ladder climb further.
    const float shimDamp = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * (1200.0f + 2400.0f * (1.0f - damp))), sr);
    // Combs drift by up to a couple of milliseconds, each at its own speed, so
    // the tail never settles into a fixed comb pattern.
    const float wobbleInc = 0.23f / sr;
    const float wobbleDepth = wobble * sr * 0.0015f;

    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        pre[0].write(inL);
        pre[1].write(inR);
        const float dry[2] = {pre[0].read(preSamples), pre[1].read(preSamples)};
        float out[2];
        for (int c = 0; c < 2; ++c) {
            const float x = dry[c] * inGain;
            float acc = 0.0f;
            int combIndex = 0;
            for (auto &comb : combs[c]) {
                // Each comb wobbles on its own phase; without the offset they
                // would all lengthen together, which is a pitch bend rather
                // than a room that will not sit still.
                const float ph = wobblePhase + static_cast<float>(combIndex) * 0.125f +
                                 (c == 1 ? 0.5f : 0.0f);
                const float drift = wobbleDepth * std::sin((ph - std::floor(ph)) * dsp::kTwoPi);
                const float y = comb.line.read(static_cast<float>(comb.len) * stretch + drift);
                comb.store = undenormal(y * (1.0f - damp) + comb.store * damp);
                // The shimmer is fed back out of the room's own gain budget,
                // not on top of it. The combs already run at `fb`, which is
                // close to unity for a long tail; adding a second path that
                // also derives from the combs makes the loop gain exceed one
                // and the whole thing reaches infinity in about a second. It
                // did, the first time. Scaling by what is left under unity
                // keeps the room stable at every size - and means a frozen
                // reverb, where fb *is* one, cannot shimmer, which is correct:
                // there is no headroom left to climb with.
                // Divided by the comb count, because it goes into every one
                // of them: the shimmer should add the same energy to the room
                // whether the room is built from eight combs or eighty, and
                // forgetting that made the injected gain eight times what the
                // arithmetic above said it was.
                comb.line.write(x + comb.store * fb +
                                shimmerFb[c] * shimmer * (1.0f - fb) * kCombInject);
                acc += y;
                ++combIndex;
            }
            for (auto &ap : aps[c]) {
                const float y = ap.line.read(static_cast<float>(ap.len));
                ap.line.write(acc + y * 0.5f);
                acc = y - acc;
            }
            lp[c] += (acc - lp[c]) * toneCoeff;
            float t = lp[c];

            // The tail as cheap memory. Quantised and sample-held *after* the
            // room and before the mix, so the dry signal never sees it.
            if (bits < 16) {
                const float step = kReverbNominal / static_cast<float>(1 << bits);
                t = std::round(t / step) * step;
            }
            if (crush > 1.001f) {
                crushAcc[c] += 1.0f / crush;
                if (crushAcc[c] >= 1.0f) { crushAcc[c] -= 1.0f; crushHeld[c] = t; }
                t = crushHeld[c];
            }

            // Shimmer: the tail read back an octave up, into the combs.
            if (shimmer > 0.0f) {
                shimmerLine[c].write(t);
                // *Minus* one sample of delay per sample, so the read point
                // catches the write head at twice the rate and the tail comes
                // back an octave up. Plus, which is what this said first, makes
                // the read point stand still - the line stops being a pitch
                // shifter and becomes a hold, and a hold inside a feedback
                // path accumulates DC until it is the only thing left. The
                // tail's centroid read zero hertz, which is what that looks
                // like from outside.
                shimmerPhase[c] -= 1.0f / shimWin;
                while (shimmerPhase[c] < 0.0f) shimmerPhase[c] += 1.0f;
                float ph2 = shimmerPhase[c] + 0.5f;
                if (ph2 >= 1.0f) ph2 -= 1.0f;
                const float g1 = std::sin(shimmerPhase[c] * 3.14159265f);
                const float g2 = std::sin(ph2 * 3.14159265f);
                const float shifted = shimmerLine[c].read(shimmerPhase[c] * shimWin + 2.0f) * g1 +
                                      shimmerLine[c].read(ph2 * shimWin + 2.0f) * g2;
                // A feedback path that can carry a NaN carries it forever.
                // DC out, before it goes round again.
                //
                // A shifter that doubles every frequency also doubles nothing:
                // whatever direct current it is handed comes back as direct
                // current, is added to the combs, and is handed back bigger
                // next time. Left in, the tail's centroid fell to one hertz
                // and the output reached twenty-three times full scale - the
                // room had become a battery. A one-pole blocker at about five
                // hertz costs nothing a listener can hear and breaks the only
                // frequency the loop can run away at.
                const float dc = shifted - shimDcX[c] + 0.9995f * shimDcY[c];
                shimDcX[c] = shifted;
                shimDcY[c] = dc;
                // And the top off, every time round.
                //
                // Each pass moves the tail up an octave; with nothing taken
                // away it climbs forever and the output reached eight times
                // full scale. A real shimmer converges because the shifted
                // signal goes back through the room's own damping - so this
                // one does too, and the ladder runs out of rungs where the
                // room stops being bright.
                const float bounded = std::isfinite(dc) ? clampf(dc, -2.0f, 2.0f) : 0.0f;
                shimLp[c] += (bounded - shimLp[c]) * shimDamp;
                // Soft-limited, which is the only thing that actually holds.
                //
                // Scaling the injection by what is left under unity makes
                // *small* rooms shimmer hardest, because they have the most
                // headroom; scaling by a constant instead makes large ones
                // worst, because their combs resonate longest. Neither end can
                // be fixed by choosing a better number, so the regeneration
                // path is limited instead - which is what a shimmer pedal
                // does, and it bounds the loop whatever the room is doing.
                shimmerFb[c] = undenormal(fastTanh(shimLp[c] * 1.6f) * 0.55f);
            } else {
                shimmerFb[c] = 0.0f;
            }
            out[c] = t;
        }
        wobblePhase += wobbleInc;
        if (wobblePhase >= 1.0f) wobblePhase -= 1.0f;
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Distortion::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Distortion::reset() {
    for (int c = 0; c < 2; ++c) {
        tone[c].reset();
        halfband[c].reset();
        dcIn[c] = dcOut[c] = prevIn[c] = 0.0f;
    }
}

bool Distortion::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float drive = p.get(Drive), mix = p.get(Mix), bias = p.get(Bias) * 0.6f;
    const int mode = static_cast<int>(p.get(Mode) + 0.5f);
    for (int c = 0; c < 2; ++c) tone[c].lowpass(p.get(Tone), 0.707f, sr);
    // Level roughly follows the input: a loud drive is a texture change, not a jump.
    const float comp = 1.0f / std::sqrt(drive);
    const int chans = stereoIn ? 2 : 1;
    // A waveshaper makes harmonics above the ones it is fed, and anything
    // past half the sample rate folds back down as something inharmonic -
    // which is what makes a hard clip sound like a cheap plugin. At full
    // quality the shaper runs at twice the rate and the extra octave is
    // filtered off before the result is thinned back out; at lean quality
    // it runs once, as it always did.
    const bool oversample = fullQuality();
    for (int c = 0; c < 2; ++c) halfband[c].lowpass(std::min(19000.0f, sr * 0.45f), 0.707f, sr * 2.0f);
    auto shape = [&](float x) {
        switch (mode) {
        case 1: return clampf(x, -1.0f, 1.0f);
        case 2: { // wavefold: triangle-wrap x back into -1..1
            const float t = x * 0.25f + 0.25f;
            return 4.0f * std::fabs(t - std::floor(t + 0.5f)) - 1.0f;
        }
        case 3: return fastTanh(x) - 0.3f * fastTanh(x * 0.5f) * fastTanh(x * 0.5f); // tube: soft with a sag
        default: return fastTanh(x);
        }
    };
    for (int c = 0; c < chans; ++c) {
        float *buf = c == 0 ? L : R;
        for (int32_t i = 0; i < frames; ++i) {
            const float in = buf[i];
            float y;
            if (oversample) {
                // Two sub-samples: the midpoint of the last input and this
                // one, then this one. Both are filtered; the second is kept.
                const float mid = (prevIn[c] + in) * 0.5f;
                halfband[c].process(shape(mid * drive + bias));
                y = halfband[c].process(shape(in * drive + bias));
                prevIn[c] = in;
            } else {
                y = shape(in * drive + bias);
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
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



// --- Chorus -----------------------------------------------------------------------

const ParamDef *Chorus::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"rate", 0.0f, 7.0f, 4.0f, Curve::Stepped, Lfo::kRates, ""},
        {"depth", 0.0f, 1.0f, 0.45f, Curve::Linear, 0, ""},
        {"voices", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""}, // 2 3 4
        {"spread", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"drift", 0.0f, 1.0f, 0.15f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Chorus::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    // Fifty milliseconds is more than any chorus needs and leaves room for the
    // drift to wander without running off the end of the line.
    for (auto &l : line) l.prepare(static_cast<int32_t>(sr * 0.05f));
    reset();
}

void Chorus::reset() {
    for (auto &l : line) l.clear();
    for (float &d : drift) d = 0.0f;
    rng = 0x9e3779b9u;
}

bool Chorus::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float depth = p.get(Depth), spread = p.get(Spread), mix = p.get(Mix);
    const float driftAmt = p.get(Drift);
    const int voices = static_cast<int>(p.get(Voices) + 0.5f) + 2;
    const float phase = Lfo::phaseAt(tick, static_cast<int>(p.get(Rate) + 0.5f));

    // A chorus is short delays, not long ones: 8 ms at the centre, swept by up
    // to 5 either way. Past about 15 ms it stops doubling and starts flanging.
    const float centre = sr * 0.008f;
    const float swing = sr * 0.005f * depth;
    // One step of the random walk per block, which at 64 samples is about
    // 750 Hz - far faster than anything audible as pitch, and slow enough that
    // it reads as tuning rather than as noise.
    for (int v = 0; v < 4; ++v) {
        rng = rng * 1664525u + 1013904223u;
        const float step = (static_cast<float>(rng >> 9) * (1.0f / 4194304.0f) - 1.0f) * 0.02f;
        drift[v] = clampf(drift[v] * 0.995f + step, -1.0f, 1.0f);
    }

    for (int32_t i = 0; i < frames; ++i) {
        const float in[2] = {L[i], stereoIn ? R[i] : L[i]};
        const float mono = (in[0] + in[1]) * 0.5f;
        line[0].write(mono);
        line[1].write(mono);
        float wet[2] = {0.0f, 0.0f};
        for (int v = 0; v < voices; ++v) {
            // Each voice sits at its own point in the cycle, and the pair of
            // them lands at opposite ends of the image.
            const float vp = phase + static_cast<float>(v) / static_cast<float>(voices);
            const float ph = vp - std::floor(vp);
            const float d = centre + swing * Lfo::sine(ph) + drift[v] * driftAmt * sr * 0.002f;
            const float s = line[0].read(clampf(d, 2.0f, sr * 0.045f));
            // Voices alternate sides, by `spread`; at nothing they all arrive
            // in the middle and it is a mono chorus in stereo.
            const float side = (v % 2 == 0 ? -1.0f : 1.0f) * spread;
            wet[0] += s * (1.0f - 0.5f * (1.0f + side));
            wet[1] += s * (0.5f * (1.0f + side));
        }
        const float norm = 1.0f / std::sqrt(static_cast<float>(voices));
        L[i] = in[0] + (wet[0] * norm - in[0]) * mix;
        R[i] = in[1] + (wet[1] * norm - in[1]) * mix;
    }
    return true;
}

// --- Tremolo ----------------------------------------------------------------------

const ParamDef *Tremolo::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"rate", 0.0f, 7.0f, 2.0f, Curve::Stepped, Lfo::kRates, ""},
        {"depth", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"shape", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""}, // sine, triangle, square
        {"pan", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"skew", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Tremolo::prepare(int32_t sampleRate) { sr = static_cast<float>(sampleRate); reset(); }
void Tremolo::reset() {}

bool Tremolo::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float depth = p.get(Depth), pan = p.get(Pan), skew = p.get(Skew), mix = p.get(Mix);
    const int shape = static_cast<int>(p.get(Shape) + 0.5f);
    const int rateIdx = static_cast<int>(p.get(Rate) + 0.5f);
    const float base = Lfo::phaseAt(tick, rateIdx);
    // The phase walks on per sample between blocks, from the same table the
    // block phase came from, so the two agree and the depth does not step at a
    // block boundary. Derived from the tempo rather than assumed: `kBeats` is
    // in quarter notes, so a cycle is `beats * 60 / bpm` seconds, and writing
    // it as a constant would have pinned every sync rate to 120 bpm.
    const float hz = bpm / (60.0f * Lfo::kBeats[rateIdx]);
    const float inc = hz / sr;

    for (int32_t i = 0; i < frames; ++i) {
        float ph = base + inc * static_cast<float>(i);
        ph -= std::floor(ph);
        // Skew bends the duty: at a half it is symmetrical, away from it the
        // dip is either a stab or a swell.
        const float k = clampf(skew, 0.05f, 0.95f);
        const float warped = ph < k ? 0.5f * ph / k : 0.5f + 0.5f * (ph - k) / (1.0f - k);
        float w;
        switch (shape) {
        case 1: w = Lfo::triangle(warped); break;
        case 2: w = warped < 0.5f ? 1.0f : -1.0f; break;
        default: w = Lfo::sine(warped); break;
        }
        // Pan slides the two channels apart in phase: together is a tremolo,
        // opposite is an auto-pan, and the space between belongs to neither.
        float wR;
        {
            float ph2 = ph + 0.5f * pan;
            ph2 -= std::floor(ph2);
            const float warped2 = ph2 < k ? 0.5f * ph2 / k : 0.5f + 0.5f * (ph2 - k) / (1.0f - k);
            switch (shape) {
            case 1: wR = Lfo::triangle(warped2); break;
            case 2: wR = warped2 < 0.5f ? 1.0f : -1.0f; break;
            default: wR = Lfo::sine(warped2); break;
            }
        }
        const float gL = 1.0f - depth * 0.5f * (1.0f - w);
        const float gR = 1.0f - depth * 0.5f * (1.0f - wR);
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        L[i] = inL + (inL * gL - inL) * mix;
        R[i] = inR + (inR * gR - inR) * mix;
    }
    return true;
}

// --- Width ------------------------------------------------------------------------

const ParamDef *Width::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"width", 0.0f, 2.0f, 1.0f, Curve::Linear, 0, ""},
        {"below", 20.0f, 500.0f, 20.0f, Curve::Exponential, 0, "Hz"},
        {"haas", 0.0f, 20.0f, 0.0f, Curve::Linear, 0, "ms"},
        {"rotate", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Width::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    haasLine.prepare(static_cast<int32_t>(sr * 0.025f));
    reset();
}

void Width::reset() {
    haasLine.clear();
    lowL.reset();
    lowR.reset();
}

bool Width::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float amount = p.get(Amount), below = p.get(Below), rotate = p.get(Rotate);
    const float haas = p.get(Haas) * 0.001f * sr;
    // The crossover the bass is folded back through. At the bottom of its
    // range there is nothing under it and the control is off.
    const bool folding = below > 21.0f;
    if (folding) {
        lowL.lowpass(below, 0.707f, sr);
        lowR.lowpass(below, 0.707f, sr);
    }
    const float ang = (rotate + 1.0f) * 0.25f * 3.14159265f;
    const float rl = std::cos(ang) * 1.4142f, rr = std::sin(ang) * 1.4142f;

    for (int32_t i = 0; i < frames; ++i) {
        float l = L[i], r = stereoIn ? R[i] : L[i];
        if (haas > 1.0f) {
            haasLine.write(r);
            r = haasLine.read(haas);
        }
        float lowSum = 0.0f;
        if (folding) {
            // Take the bottom out of both, sum it, and put it back in the
            // middle. What is left above the crossover is what gets widened.
            const float ll = lowL.process(l), lr = lowR.process(r);
            l -= ll;
            r -= lr;
            lowSum = (ll + lr) * 0.5f;
        }
        const float mid = (l + r) * 0.5f;
        const float side = (l - r) * 0.5f * amount;
        l = mid + side + lowSum;
        r = mid - side + lowSum;
        L[i] = l * rl;
        R[i] = r * rr;
    }
    return true;
}


// --- Shifter ----------------------------------------------------------------------

namespace {
// A Hilbert pair: two four-section allpass chains whose phase responses stay
// about ninety degrees apart from some tens of hertz to most of Nyquist. The
// coefficients are the standard ones; the Q chain is read one sample late,
// which is what makes the quadrature come out right.
constexpr float kHilbertI[4] = {0.6923877778065f, 0.9360654322959f, 0.9882295226860f, 0.9987488452737f};
constexpr float kHilbertQ[4] = {0.4021921162426f, 0.8561710882420f, 0.9722909545651f, 0.9952884791278f};
} // namespace

const ParamDef *Shifter::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"shift", -500.0f, 500.0f, 0.0f, Curve::Linear, 0, "Hz"},
        {"fine", -20.0f, 20.0f, 0.0f, Curve::Linear, 0, "Hz"},
        {"spread", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"feedback", 0.0f, 0.9f, 0.0f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Shifter::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (int c = 0; c < 2; ++c) {
        for (int i = 0; i < 4; ++i) {
            apI[c][i].a = kHilbertI[i];
            apQ[c][i].a = kHilbertQ[i];
        }
    }
    reset();
}

void Shifter::reset() {
    for (int c = 0; c < 2; ++c) {
        for (int i = 0; i < 4; ++i) { apI[c][i].clear(); apQ[c][i].clear(); }
        delayed[c] = 0.0f;
        fb[c] = 0.0f;
    }
    phase = 0.0f;
}

bool Shifter::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float hz = p.get(Shift) + p.get(Fine);
    const float spread = p.get(Spread), feedback = p.get(Feedback), mix = p.get(Mix);
    const float inc = hz / sr;

    for (int32_t i = 0; i < frames; ++i) {
        const float in[2] = {L[i], stereoIn ? R[i] : L[i]};
        phase += inc;
        if (phase >= 1.0f) phase -= 1.0f;
        if (phase < 0.0f) phase += 1.0f;
        const float c = std::cos(phase * dsp::kTwoPi), s = std::sin(phase * dsp::kTwoPi);
        for (int ch = 0; ch < 2; ++ch) {
            float x = in[ch] + fb[ch] * feedback;
            float qi = x, qq = x;
            for (int k = 0; k < 4; ++k) qi = apI[ch][k].process(qi);
            for (int k = 0; k < 4; ++k) qq = apQ[ch][k].process(qq);
            // The Q chain is a sample behind, which is part of the network.
            const float q = delayed[ch];
            delayed[ch] = qq;
            // The right channel turns the other way when `spread` is up, which
            // is the thing no acoustic process does.
            const float sign = (ch == 1) ? (1.0f - 2.0f * spread) : 1.0f;
            const float wet = qi * c - q * s * sign;
            fb[ch] = wet;
            (ch == 0 ? L : R)[i] = in[ch] + (wet - in[ch]) * mix;
        }
    }
    return true;
}

// --- Harmonizer -------------------------------------------------------------------

const ParamDef *Harmonizer::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // Degrees of the scale, not semitones: +2 is "a third" whatever a
        // third happens to be on this note.
        {"interval", -7.0f, 7.0f, 2.0f, Curve::Stepped, 15, ""},
        {"interval2", -7.0f, 7.0f, 0.0f, Curve::Stepped, 15, ""},
        {"scale", 0.0f, 32.0f, 0.0f, Curve::Stepped, music::kScaleCount, ""},
        {"key", 0.0f, 11.0f, 0.0f, Curve::Stepped, 12, ""},
        {"window", 10.0f, 120.0f, 45.0f, Curve::Exponential, 0, "ms"},
        {"feedback", 0.0f, 0.85f, 0.0f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Harmonizer::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &l : line) l.prepare(static_cast<int32_t>(sr * 0.3f));
    reset();
}

void Harmonizer::reset() {
    for (auto &l : line) l.clear();
    for (auto &ch : voice) for (auto &v : ch) { v.phase = 0.0f; v.ratio = 1.0f; }
    zeroPrev = 0.0f;
    trackedHz = 0.0f;
    zeroCount = 0;
    zeroWindow = 0;
    confidence = 0.0f;
    fb[0] = fb[1] = 0.0f;
}

bool Harmonizer::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const int degrees[2] = {static_cast<int>(std::lround(p.get(Interval))),
                            static_cast<int>(std::lround(p.get(Interval2)))};
    const music::ScaleDef &scale =
        music::kScales[std::clamp(static_cast<int>(p.get(Scale) + 0.5f), 0, music::kScaleCount - 1)];
    const int key = std::clamp(static_cast<int>(p.get(Key) + 0.5f), 0, 11);
    const float win = clampf(p.get(Window) * 0.001f * sr, 64.0f, sr * 0.25f);
    const float feedback = p.get(Feedback), mix = p.get(Mix);
    // A new interval glides rather than jumps, over about thirty milliseconds.
    const float glide = dsp::onePoleCoeff(0.03f, sr);

    // What semitone shift each requested degree comes to, given the note
    // currently arriving. Recomputed per block: often enough to follow a line,
    // rarely enough to cost nothing.
    float semis[2];
    for (int v = 0; v < 2; ++v) {
        if (confidence > 0.25f && trackedHz > 40.0f) {
            const float note = 69.0f + 12.0f * std::log2(trackedHz / 440.0f);
            const int midi = static_cast<int>(std::lround(note));
            const int pc = music::floorMod(midi - key, 12);
            const int deg = music::degreeAtOrBelow(scale, pc);
            // Where that degree sits, and where the one N above it sits. The
            // difference is the interval this note actually wants.
            const int here = music::degreeInterval(scale, deg);
            const int there = music::degreeInterval(scale, deg + degrees[v]);
            semis[v] = static_cast<float>(there - here);
        } else {
            // No confident pitch: the plain chromatic reading of the degree,
            // which at least keeps the shift the size it was asked for.
            semis[v] = static_cast<float>(music::degreeInterval(scale, degrees[v]));
        }
    }
    const float target[2] = {std::pow(2.0f, semis[0] / 12.0f), std::pow(2.0f, semis[1] / 12.0f)};
    const bool second = degrees[1] != 0;

    for (int32_t i = 0; i < frames; ++i) {
        const float in[2] = {L[i], stereoIn ? R[i] : L[i]};
        const float mono = (in[0] + in[1]) * 0.5f;

        // Track on the dry input, never on the output: a harmonizer listening
        // to its own harmony would chase itself up the scale.
        if ((zeroPrev <= 0.0f) != (mono <= 0.0f)) ++zeroCount;
        zeroPrev = mono;
        if (++zeroWindow >= 1024) {
            const float hz = static_cast<float>(zeroCount) * sr / (2.0f * 1024.0f);
            // A steady pitch crosses zero twice a cycle and no more. Noise
            // crosses far more often, so a count that implies something absurd
            // is the tracker saying it does not know.
            if (hz > 40.0f && hz < 2000.0f) {
                trackedHz = trackedHz > 0.0f ? trackedHz + (hz - trackedHz) * 0.4f : hz;
                confidence += (1.0f - confidence) * 0.3f;
            } else {
                confidence *= 0.5f;
            }
            zeroCount = 0;
            zeroWindow = 0;
        }

        for (int c = 0; c < 2; ++c) {
            line[c].write(in[c] + fb[c] * feedback);
        }
        float wet[2] = {0.0f, 0.0f};
        for (int c = 0; c < 2; ++c) {
            for (int v = 0; v < (second ? 2 : 1); ++v) {
                Voice &vo = voice[c][v];
                vo.ratio += (target[v] - vo.ratio) * glide;
                // The read point slides against the write head at the rate the
                // shift asks for, and wraps inside one window.
                vo.phase += (1.0f - vo.ratio) / win;
                while (vo.phase >= 1.0f) vo.phase -= 1.0f;
                while (vo.phase < 0.0f) vo.phase += 1.0f;
                float ph2 = vo.phase + 0.5f;
                if (ph2 >= 1.0f) ph2 -= 1.0f;
                // Two taps half a window apart, crossfaded so the wrap always
                // happens where that tap is silent. sin over the window is
                // constant power against its partner.
                const float g1 = std::sin(vo.phase * 3.14159265f);
                const float g2 = std::sin(ph2 * 3.14159265f);
                const float t1 = line[c].read(vo.phase * win + 2.0f);
                const float t2 = line[c].read(ph2 * win + 2.0f);
                wet[c] += t1 * g1 + t2 * g2;
            }
        }
        const float norm = second ? 0.7071f : 1.0f;
        for (int c = 0; c < 2; ++c) {
            fb[c] = wet[c] * norm;
            (c == 0 ? L : R)[i] = in[c] + (wet[c] * norm - in[c]) * mix;
        }
    }
    return true;
}

// --- Gate -------------------------------------------------------------------------

const ParamDef *Gate::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"threshold", -80.0f, 0.0f, -45.0f, Curve::Linear, 0, "dB"},
        {"hyst", 0.0f, 24.0f, 4.0f, Curve::Linear, 0, "dB"},
        {"attack", 0.05f, 50.0f, 1.0f, Curve::Exponential, 0, "ms"},
        {"hold", 0.0f, 500.0f, 40.0f, Curve::Linear, 0, "ms"},
        {"release", 5.0f, 2000.0f, 150.0f, Curve::Exponential, 0, "ms"},
        {"duck", -90.0f, 0.0f, -90.0f, Curve::Linear, 0, "dB"},
        {"key", 20.0f, 2000.0f, 20.0f, Curve::Exponential, 0, "Hz"},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Gate::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &k : key) k.setSampleRate(sr);
    keyHz = -1.0f;
    reset();
}

void Gate::reset() {
    env = 0.0f;
    // **Closed, not open.** A gate that resets open passes whatever is
    // sitting in the input for as long as its release takes, which on a
    // panic is the one moment the engine has promised silence.
    gain = 0.0f;
    holdLeft = 0.0f;
    open = false;
    for (auto &k : key) k.reset();
}

bool Gate::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    const float openAt = dbToGain(p.get(Threshold));
    const float shutAt = dbToGain(p.get(Threshold) - p.get(Hyst));
    const float atk = dsp::onePoleCoeff(p.get(Attack) * 0.001f, sr);
    const float rel = dsp::onePoleCoeff(p.get(Release) * 0.001f, sr);
    const float shut = dbToGain(p.get(Duck));
    const float holdSamples = p.get(Hold) * 0.001f * sr;

    const float hz = p.get(Key);
    if (hz != keyHz) {
        keyHz = hz;
        for (auto &k : key) k.set(hz, 0.0f);
    }

    /**
     * How fast the *detector* lets go, which is not how fast the gate does.
     *
     * This was thirty milliseconds first, reasoning that a rectified sine
     * falls to nothing twice a cycle and a follower quicker than the lowest
     * note's period would decide the string had stopped. It does stop the
     * chatter, and it also makes **`hold` do nothing below about 30 ms**: a
     * 20 ms gap never got the envelope down to the threshold at all, so a
     * gate set to close immediately held on through it just as one set to
     * wait did. A control that cannot be heard over the bottom half of its
     * range is worse than the fault it was hiding.
     *
     * Two milliseconds, then, and the dips are `hold`'s problem - which is
     * what hold is for and how a real gate does it. Every peak over the
     * threshold re-arms the timer, so a note at 82 Hz re-arms every 12 ms
     * and the default 40 ms never runs out, while a gate set to zero shuts
     * the moment a drum stops.
     */
    static constexpr float kDetectRelease = 0.002f;
    const float detRel = dsp::onePoleCoeff(kDetectRelease, sr);

    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        // The key filter runs on the audio, per channel, and the rectifier
        // comes after it: filtering a signal that has already been rectified
        // would be filtering the envelope, which is a different instrument.
        const float kL = std::fabs(key[0].step(inL).hp);
        const float kR = std::fabs(key[1].step(inR).hp);
        const float det = kL > kR ? kL : kR;
        env = det > env ? det : dsp::undenormal(env + (det - env) * detRel);

        if (env > openAt) {
            open = true;
            holdLeft = holdSamples;
        } else if (env < shutAt) {
            if (holdLeft > 0.0f) holdLeft -= 1.0f;
            else open = false;
        }
        // Between the two thresholds nothing is decided: that band is the
        // hysteresis, and it is there because a signal sitting on one number
        // crosses it hundreds of times a second.

        const float want = open ? 1.0f : shut;
        const float c = want > gain ? atk : rel;
        gain = dsp::undenormal(gain + (want - gain) * c);

        L[i] = inL * gain;
        if (stereoIn) R[i] = inR * gain;
    }
    return stereoIn;
}

} // namespace acidulous::effect
