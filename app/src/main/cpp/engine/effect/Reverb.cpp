#include "Reverb.h"
#include <algorithm>
#include <cmath>
#include <engine/core/Settings.h>
#include <engine/dsp/Math.h>

namespace acidulous::effect {

using dsp::clampf;
using dsp::fastTanh;

namespace {

inline float peakEnvelope(float &env, float in, float attack, float release) {
    const float a = std::fabs(in);
    env += (a - env) * (a > env ? attack : release);
    return env;
}

// --- room ---

// The room's comb and allpass tunings at 44.1 kHz, scaled in prepare(). The
// right channel is offset by 23 samples for width.
constexpr int kCombBase[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassBase[4] = {556, 441, 341, 225};
constexpr float kSizeStretchMax = 1.9f;

/** The tail's typical level, which the bit crusher quantises against. */
constexpr float kReverbNominal = 0.25f;
/**
 * How much of the shifted tail goes back into each comb. Found by testing
 * every room size. The worst case is the smallest room, since it has the most
 * headroom under unity and so gets injected hardest. 0.15 stays stable and
 * still gives a clearly audible shimmer.
 */
constexpr float kCombInject = 0.15f;

// Keeps loop states out of denormals, so the reverb renders the same
// whatever the FPU's flush-to-zero flag is set to.
inline float undenormal(float v) {
    static constexpr float kTiny = 1.0e-20f;
    return v + kTiny - kTiny;
}

// --- plate ---

/** The plate's lengths are given at this rate and scaled to the engine's. */
constexpr float kPlateRef = 29761.0f;
constexpr int kPlateDiffLen[4] = {142, 107, 379, 277};
constexpr float kPlateDiffG[4] = {0.75f, 0.75f, 0.625f, 0.625f};
// The two halves of the tank: modulated allpass, delay, allpass, delay.
constexpr int kPlateAp1[2] = {672, 908};
constexpr int kPlateD1[2] = {4453, 4217};
constexpr int kPlateAp2[2] = {1800, 2656};
constexpr int kPlateD2[2] = {3720, 3163};
constexpr float kPlateLoop = 672 + 4453 + 1800 + 3720 + 908 + 4217 + 2656 + 3163;
/** The modulated allpass and the second allpass in each half. */
constexpr float kPlateG1 = 0.7f, kPlateG2 = 0.5f;
// Output taps, a side: which half, which line (0 delay 1, 1 allpass 2,
// 2 delay 2), where and with what sign. Left reads mostly the right half and
// right the left, which is what makes the plate wide.
struct Tap { int half, line, at; float sign; };
constexpr Tap kPlateTapL[7] = {{1, 0, 266, 1}, {1, 0, 2974, 1}, {1, 1, 1913, -1}, {1, 2, 1996, 1},
                               {0, 0, 1990, -1}, {0, 1, 187, -1}, {0, 2, 1066, -1}};
constexpr Tap kPlateTapR[7] = {{0, 0, 353, 1}, {0, 0, 3627, 1}, {0, 1, 1228, -1}, {0, 2, 2673, 1},
                               {1, 0, 2111, -1}, {1, 1, 335, -1}, {1, 2, 121, -1}};
/** Size stretches the tank from this much of its length to that. */
constexpr float kPlateScaleMin = 0.65f, kPlateScaleRange = 0.7f;
constexpr float kPlateModRate[2] = {0.61f, 0.87f};
/** Into the tank and out of it: the plate at its defaults about as loud as the room. */
constexpr float kPlateIn = 0.5f, kPlateOut = 0.36f;
constexpr float kPlateInject = 0.5f;

// --- hall ---

constexpr float kHallMs[8] = {29.3f, 33.7f, 38.9f, 43.1f, 49.7f, 55.1f, 61.3f, 68.9f};
constexpr float kHallModRate[8] = {0.11f, 0.13f, 0.17f, 0.19f, 0.23f, 0.29f, 0.31f, 0.37f};
/** Lean runs four of the eight lines, spread across the lengths. */
constexpr int kHallLean[4] = {1, 3, 4, 6};
constexpr int kHallAll[8] = {0, 1, 2, 3, 4, 5, 6, 7};
// Line k takes the left input if k is even, the right if odd, with this sign.
constexpr float kHallInSign[8] = {1, 1, -1, -1, 1, 1, -1, -1};
// Two orthogonal rows, for left and right out.
constexpr float kHallRowL[8] = {1, -1, 1, -1, 1, -1, 1, -1};
constexpr float kHallRowR[8] = {1, 1, -1, -1, 1, 1, -1, -1};
constexpr float kHallDiffMs[2][2] = {{3.1f, 4.7f}, {3.7f, 5.3f}};
constexpr float kHallDiffG = 0.6f;
constexpr float kHallScaleMin = 0.5f, kHallScaleRange = 1.1f;
/** Lows last this much longer than mids. */
constexpr float kHallLowRatio = 1.5f;
constexpr float kHallLowHz = 400.0f, kHallHighHz = 3500.0f;
// Early reflections a side, before size and distance stretch them.
constexpr float kErMs[2][8] = {{7.3f, 11.9f, 17.1f, 23.3f, 29.9f, 37.1f, 44.9f, 53.3f},
                               {8.9f, 13.3f, 19.7f, 25.1f, 31.7f, 39.5f, 47.3f, 56.9f}};
constexpr float kErGain[8] = {0.9f, -0.75f, 0.64f, 0.55f, -0.46f, 0.38f, -0.31f, -0.25f};
constexpr float kHallIn = 0.5f, kHallOut = 0.15f, kHallErLevel = 0.03f;
constexpr float kHallInject = 0.4f;

// --- shared ---

/** Modulation at default, and what wobble adds at the top, in seconds. */
constexpr float kModDepth = 0.00025f, kWobbleDepth = 0.0015f;
/** Distance at the top: the pre-delay it adds in ms, and the air loss's cutoff. */
constexpr float kDistancePreMs = 80.0f, kDistanceAirHz = 3000.0f;
/** Duck pulls fully at this envelope level and above. */
constexpr float kDuckFull = 0.2f;
/**
 * Bloom: the input level that counts as playing, the time constant it falls
 * back with, and the one it rises with at the top (full in about 2 s).
 */
constexpr float kBloomFloor = 0.003f, kBloomFall = 0.25f, kBloomRise = 0.7f;

/** An allpass on a whole-sample line: H = (z^-n - g) / (1 - g z^-n). */
inline float allpass(Reverb::Line &l, int32_t n, float g, float x) {
    const float d = l.at(n);
    const float v = undenormal(x + g * d);
    l.write(v);
    return d - g * v;
}

inline void rotate(float osc[2], const float rot[2]) {
    const float c = osc[0] * rot[0] - osc[1] * rot[1];
    const float s = osc[1] * rot[0] + osc[0] * rot[1];
    osc[0] = c;
    osc[1] = s;
}

/** Pulls a rotating phasor back onto the unit circle; once a block is plenty. */
inline void renormalise(float osc[2]) {
    const float k = 1.5f - 0.5f * (osc[0] * osc[0] + osc[1] * osc[1]);
    osc[0] *= k;
    osc[1] *= k;
}

inline float decayGain(float seconds, float rt60) { return std::pow(10.0f, -3.0f * seconds / rt60); }

} // namespace

// --- Line ---------------------------------------------------------------------

void Reverb::Line::prepare(int32_t n) {
    size = std::max(4, n);
    buf.assign(static_cast<size_t>(size), 0.0f);
    wr = 0;
}

void Reverb::Line::clear() {
    std::fill(buf.begin(), buf.end(), 0.0f);
    wr = 0;
}

float Reverb::Line::frac(float d) const {
    if (d < 1.0f) d = 1.0f;
    if (d > static_cast<float>(size - 2)) d = static_cast<float>(size - 2);
    float f = 0.0f;
    const int32_t i0 = dsp::wrappedReadIndex(wr, d, size, f);
    const int32_t i1 = i0 + 1 >= size ? 0 : i0 + 1;
    return buf[static_cast<size_t>(i0)] * (1.0f - f) + buf[static_cast<size_t>(i1)] * f;
}

// --- Params -------------------------------------------------------------------

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
        {"gain", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, "dB"},
        // room, plate, hall, spring, early, space
        {"model", 0.0f, 5.0f, 0.0f, Curve::Stepped, kModels, ""},
        {"duck", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"duckrel", 50.0f, 2000.0f, 300.0f, Curve::Exponential, 0, "ms"},
        {"distance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bloom", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

// --- Lifecycle ----------------------------------------------------------------

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
        // The pre-delay keeps its old length, since the length changes how a
        // fractional read rounds. The long one has room for distance's extra
        // pre-delay and the hall's latest reflection.
        pre[c].prepare(static_cast<int32_t>(sr * 0.21f));
        preLong[c].prepare(static_cast<int32_t>(sr * 0.5f));
        // Longer than the shimmer window, so the read point can slide without
        // catching the write head.
        shimmerLine[c].prepare(static_cast<int32_t>(sr * 0.12f));
    }

    // The plate at its largest, with the most modulation wobble can ask for.
    const float plMax = (kPlateScaleMin + kPlateScaleRange) * sr / kPlateRef;
    const auto plLen = [&](int ref) { return static_cast<int32_t>(static_cast<float>(ref) * plMax) + 4; };
    const int32_t modRoom = static_cast<int32_t>((kModDepth + kWobbleDepth) * sr) + 4;
    for (int k = 0; k < kPlateDiff; ++k) plDiff[k].prepare(plLen(kPlateDiffLen[k]));
    for (int h = 0; h < 2; ++h) {
        plAp1[h].prepare(plLen(kPlateAp1[h]) + modRoom);
        plD1[h].prepare(plLen(kPlateD1[h]));
        plAp2[h].prepare(plLen(kPlateAp2[h]));
        plD2[h].prepare(plLen(kPlateD2[h]));
        const float w = dsp::kTwoPi * kPlateModRate[h] / sr;
        plRot[h][0] = std::cos(w);
        plRot[h][1] = std::sin(w);
    }

    const float haMax = (kHallScaleMin + kHallScaleRange) * sr * 0.001f;
    for (int i = 0; i < kHallLines; ++i) {
        // The read swings up to two mod depths past the line's length.
        haLine[i].prepare(static_cast<int32_t>(kHallMs[i] * haMax) + 2 * modRoom);
        const float w = dsp::kTwoPi * kHallModRate[i] / sr;
        haRot[i][0] = std::cos(w);
        haRot[i][1] = std::sin(w);
    }
    for (int c = 0; c < 2; ++c) {
        for (int k = 0; k < 2; ++k) {
            haLenDiff[c][k] = std::max(1, static_cast<int32_t>(kHallDiffMs[c][k] * 0.001f * sr));
            haDiff[c][k].prepare(haLenDiff[c][k] + 4);
        }
    }
    haLowCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * kHallLowHz), sr);
    haHighCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * kHallHighHz), sr);
    plSizeWas = haSizeWas = -1.0f;
    reset();
}

void Reverb::clearRoom() {
    for (auto &ch : combs) for (auto &c : ch) { c.line.clear(); c.store = 0.0f; }
    for (auto &ch : aps) for (auto &a : ch) a.line.clear();
    wobblePhase = 0.0f;
}

void Reverb::clearPlate() {
    for (auto &l : plDiff) l.clear();
    for (int h = 0; h < 2; ++h) {
        plAp1[h].clear();
        plD1[h].clear();
        plAp2[h].clear();
        plD2[h].clear();
        plDamp[h] = 0.0f;
        plCross[h] = 0.0f;
        // Out of step, so the halves never move together.
        plOsc[h][0] = h == 0 ? 1.0f : 0.0f;
        plOsc[h][1] = h == 0 ? 0.0f : 1.0f;
    }
}

void Reverb::clearHall() {
    for (int i = 0; i < kHallLines; ++i) {
        haLine[i].clear();
        haLow[i] = haHigh[i] = 0.0f;
        const float ph = dsp::kTwoPi * static_cast<float>(i) * 0.37f;
        haOsc[i][0] = std::cos(ph);
        haOsc[i][1] = std::sin(ph);
    }
    for (auto &ch : haDiff) for (auto &l : ch) l.clear();
}

void Reverb::reset() {
    clearRoom();
    clearPlate();
    clearHall();
    for (auto &p : pre) p.clear();
    for (auto &p : preLong) p.clear();
    for (auto &l : shimmerLine) l.clear();
    for (int c = 0; c < 2; ++c) {
        shimmerPhase[c] = 0.0f;
        shimmerFb[c] = 0.0f;
        shimDcX[c] = 0.0f;
        shimDcY[c] = 0.0f;
        shimLp[c] = 0.0f;
        crushAcc[c] = 0.0f;
        crushHeld[c] = 0.0f;
        airLp[c] = 0.0f;
    }
    lp[0] = lp[1] = 0.0f;
    gateEnv = 0.0f;
    gateHold = 0;
    inputEnv = 0.0f;
    duckEnv = bloomDet = bloomEnv = 0.0f;
    lastModel = -1;
    lastFull = fullQuality();
}

// --- Per block ----------------------------------------------------------------

struct Reverb::Block {
    // The room's, as it always read them.
    bool freeze;
    float stretch, fb, damp, inGain, mix, gate, preSamples, toneCoeff, envAtk, envRel, gateRel;
    int32_t holdSamples;
    float shimmer;
    int32_t bits;
    float crush, shimWin, shimDamp, wobbleInc, wobbleDepth;
    bool wobbling;
    int32_t nCombs, nAps;
    float combScale;
    // Shared knobs. `shared` is off when duck, distance and bloom are all 0,
    // and then the output is the room's to the bit.
    bool shared, bloomOn;
    float duck, duckAtk, duckRel, distance, bloom, bloomRise, bloomFall, bloomBase, bloomDetRel;
    float airCoeff, wetGain, dryGain, wetWidth, dryWidth;
    // Plate and hall.
    bool full;
    float modDepth, diffMul, erGain;
    float erAt[2][kHallEr];
};

bool Reverb::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const auto &p = params_;
    int32_t model = static_cast<int32_t>(p.get(Model) + 0.5f);
    // Spring, early and space are still to come and play the room.
    if (model != Plate && model != Hall) model = Room;
    const bool full = fullQuality();
    if (model != lastModel || full != lastFull) {
        // The tank coming in starts empty, or it would replay an old tail.
        // A quality switch keeps the room's tail, since its lean path reads the same lines.
        if (lastModel >= 0 && (model != lastModel || model != Room)) {
            if (model == Room) clearRoom();
            else if (model == Plate) clearPlate();
            else clearHall();
        }
        lastModel = model;
        lastFull = full;
    }

    Block b{};
    b.freeze = p.get(Freeze) >= 0.5f;
    const float size = p.get(Size);
    b.stretch = 1.0f + (kSizeStretchMax - 1.0f) * size;
    b.fb = b.freeze ? 1.0f : 0.72f + 0.26f * size;
    b.damp = b.freeze ? 0.0f : p.get(Damp) * 0.6f;
    b.inGain = b.freeze ? 0.0f : 0.015f;
    b.mix = p.get(Mix);
    b.gate = p.get(Gate);
    b.distance = p.get(Distance);
    const float preMs = p.get(PreDelay) + kDistancePreMs * b.distance;
    b.preSamples = clampf(preMs * 0.001f * sr, 1.0f, sr * (0.2f + 0.08f * b.distance));
    b.toneCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * p.get(Tone)), sr);
    b.envAtk = dsp::onePoleCoeff(0.001f, sr);
    b.envRel = dsp::onePoleCoeff(0.05f, sr);
    b.gateRel = dsp::onePoleCoeff(0.006f, sr);
    b.holdSamples = static_cast<int32_t>((0.04f + b.gate * 0.5f) * sr);
    b.shimmer = p.get(Shimmer);
    b.bits = static_cast<int32_t>(p.get(Bits) + 0.5f);
    b.crush = p.get(Crush);
    const float wobble = p.get(Wobble);
    // The shimmer window. The read point slides through it to come out an
    // octave up, with two taps half a window apart for the crossfade.
    b.shimWin = sr * 0.045f;
    // The shimmer's own damping. Brighter rooms let it climb further.
    b.shimDamp = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * (1200.0f + 2400.0f * (1.0f - b.damp))), sr);
    // Combs drift by up to a couple of ms each, so the tail never settles into
    // a fixed comb pattern.
    b.wobbleInc = 0.23f / sr;
    b.wobbleDepth = wobble * sr * 0.0015f;
    b.wobbling = b.wobbleDepth > 0.0f;
    // Lean quality uses half the combs and allpasses, for a thinner but
    // cheaper tail. The sum is scaled up so a lean room isn't quieter.
    b.nCombs = full ? kCombs : kCombs / 2;
    b.nAps = full ? kAps : kAps / 2;
    b.combScale = static_cast<float>(kCombs) / static_cast<float>(b.nCombs);

    b.duck = p.get(Duck);
    b.bloom = p.get(Bloom);
    b.bloomOn = b.bloom > 0.0f;
    b.shared = b.duck > 0.0f || b.distance > 0.0f || b.bloomOn;
    b.duckAtk = dsp::onePoleCoeff(0.005f, sr);
    b.duckRel = dsp::onePoleCoeff(p.get(DuckRel) * 0.001f, sr);
    b.bloomRise = dsp::onePoleCoeff(kBloomRise * b.bloom, sr);
    b.bloomFall = dsp::onePoleCoeff(kBloomFall, sr);
    b.bloomDetRel = dsp::onePoleCoeff(0.1f, sr);
    b.bloomBase = 1.0f - 0.9f * b.bloom;
    b.airCoeff = dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * 20000.0f * std::pow(kDistanceAirHz / 20000.0f, b.distance)), sr);
    b.wetGain = 1.0f + b.distance;
    b.dryGain = 1.0f - 0.5f * b.distance;
    b.wetWidth = 1.0f + 0.6f * b.distance;
    b.dryWidth = 1.0f - 0.6f * b.distance;

    b.full = full;
    b.modDepth = (kModDepth + wobble * kWobbleDepth) * sr;
    b.diffMul = 1.0f;
    b.erGain = kHallErLevel * (1.0f - 0.75f * b.distance);
    const float erScale = (0.6f + 0.8f * size) * (1.0f + 1.2f * b.distance) * 0.001f * sr;
    for (int c = 0; c < 2; ++c)
        for (int k = 0; k < kHallEr; ++k) b.erAt[c][k] = kErMs[c][k] * erScale;

    switch (model) {
        case Plate:
            updatePlate(size, p.get(Damp), b.freeze);
            run(L, R, frames, stereoIn, b, [this](const Block &bb, const float *d, float *o) { plateSample(bb, d, o); });
            for (auto &o : plOsc) renormalise(o);
            break;
        case Hall:
            updateHall(size, p.get(Damp), b.freeze);
            run(L, R, frames, stereoIn, b, [this](const Block &bb, const float *d, float *o) { hallSample(bb, d, o); });
            for (auto &o : haOsc) renormalise(o);
            break;
        default:
            run(L, R, frames, stereoIn, b, [this](const Block &bb, const float *d, float *o) { roomSample(bb, d, o); });
            break;
    }
    return true;
}

// --- The shared loop ----------------------------------------------------------

template <typename Tank>
void Reverb::run(float *L, float *R, int32_t frames, bool stereoIn, const Block &b0, Tank &&tank) {
    Block b = b0;
    for (int32_t i = 0; i < frames; ++i) {
        const float inL = L[i], inR = stereoIn ? R[i] : L[i];
        pre[0].write(inL);
        pre[1].write(inR);
        preLong[0].write(inL);
        preLong[1].write(inR);
        float dry[2];
        if (b.distance > 0.0f) {
            dry[0] = preLong[0].read(b.preSamples);
            dry[1] = preLong[1].read(b.preSamples);
        } else {
            dry[0] = pre[0].read(b.preSamples);
            dry[1] = pre[1].read(b.preSamples);
        }
        // Bloom: while there's input the tank's feed rises from thin to full,
        // and it falls back once the input stops.
        if (b.bloomOn) {
            const float det = peakEnvelope(bloomDet, 0.5f * (std::fabs(inL) + std::fabs(inR)), b.envAtk, b.bloomDetRel);
            bloomDet = undenormal(bloomDet);
            bloomEnv += ((det > kBloomFloor ? 1.0f : 0.0f) - bloomEnv) * (det > kBloomFloor ? b.bloomRise : b.bloomFall);
            bloomEnv = undenormal(bloomEnv);
            const float g = b.bloomBase + (1.0f - b.bloomBase) * bloomEnv;
            dry[0] *= g;
            dry[1] *= g;
            b.diffMul = 1.0f - 0.7f * b.bloom * (1.0f - bloomEnv);
        }
        float out[2];
        tank(b, dry, out);
        // Gate: lets the tail through for a hold time after each hit, then
        // cuts it. The classic gated drum room.
        float g = 1.0f;
        if (b.gate > 0.0f) {
            const float env = peakEnvelope(inputEnv, (std::fabs(inL) + std::fabs(inR)), b.envAtk, b.envRel);
            if (env > 0.05f) { gateHold = b.holdSamples; gateEnv = 1.0f; }
            if (gateHold > 0) --gateHold; else gateEnv += (0.0f - gateEnv) * b.gateRel;
            g = gateEnv;
        }
        if (!b.shared) {
            L[i] = inL + out[0] * b.mix * g;
            R[i] = inR + out[1] * b.mix * g;
            continue;
        }
        // Duck: the dry's envelope pulls the tail down.
        const float env = peakEnvelope(duckEnv, 0.5f * (std::fabs(inL) + std::fabs(inR)), b.duckAtk, b.duckRel);
        duckEnv = undenormal(duckEnv);
        const float dg = b.wetGain * (1.0f - b.duck * std::min(1.0f, env / kDuckFull));
        float w0 = out[0] * dg, w1 = out[1] * dg;
        float d0 = inL, d1 = inR;
        if (b.distance > 0.0f) {
            // Air loss on the tail, a wider tail and a narrower, quieter dry.
            airLp[0] = undenormal(airLp[0] + (w0 - airLp[0]) * b.airCoeff);
            airLp[1] = undenormal(airLp[1] + (w1 - airLp[1]) * b.airCoeff);
            const float wm = 0.5f * (airLp[0] + airLp[1]), ws = 0.5f * (airLp[0] - airLp[1]) * b.wetWidth;
            w0 = wm + ws;
            w1 = wm - ws;
            const float dm = 0.5f * (inL + inR), ds = 0.5f * (inL - inR) * b.dryWidth;
            d0 = (dm + ds) * b.dryGain;
            d1 = (dm - ds) * b.dryGain;
        }
        L[i] = d0 + w0 * b.mix * g;
        R[i] = d1 + w1 * b.mix * g;
    }
}

// --- Shared tail extras ---------------------------------------------------------

float Reverb::lofi(int c, float t, const Block &b) {
    // Lo-fi tail: quantised and sample-held after the tank and before the
    // mix, so the dry signal isn't affected.
    if (b.bits < 16) {
        const float step = kReverbNominal / static_cast<float>(1 << b.bits);
        t = std::round(t / step) * step;
    }
    if (b.crush > 1.001f) {
        crushAcc[c] += 1.0f / b.crush;
        if (crushAcc[c] >= 1.0f) { crushAcc[c] -= 1.0f; crushHeld[c] = t; }
        t = crushHeld[c];
    }
    return t;
}

void Reverb::shimmerStep(int c, float t, const Block &b) {
    // Shimmer: the tail read back an octave up, for the tank to take in.
    if (b.shimmer > 0.0f) {
        shimmerLine[c].write(t);
        // The delay shrinks by one sample per sample, so the read point moves
        // at twice the rate and the tail comes back an octave up. Adding
        // instead would hold the read point still.
        shimmerPhase[c] -= 1.0f / b.shimWin;
        while (shimmerPhase[c] < 0.0f) shimmerPhase[c] += 1.0f;
        float ph2 = shimmerPhase[c] + 0.5f;
        if (ph2 >= 1.0f) ph2 -= 1.0f;
        const float g1 = std::sin(shimmerPhase[c] * 3.14159265f);
        const float g2 = std::sin(ph2 * 3.14159265f);
        const float shifted = shimmerLine[c].read(shimmerPhase[c] * b.shimWin + 2.0f) * g1 +
                              shimmerLine[c].read(ph2 * b.shimWin + 2.0f) * g2;
        // Remove DC before it goes round again. The shifter passes DC
        // unchanged, so it would build up in the loop. A one-pole blocker at
        // about 5 Hz stops it.
        const float dc = shifted - shimDcX[c] + 0.9995f * shimDcY[c];
        shimDcX[c] = shifted;
        shimDcY[c] = dc;
        // Low-pass it every time round. Each pass moves the tail up an
        // octave, so without damping it would climb forever. NaNs are dropped
        // here too, since a feedback path would keep them.
        const float bounded = std::isfinite(dc) ? clampf(dc, -2.0f, 2.0f) : 0.0f;
        shimLp[c] += (bounded - shimLp[c]) * b.shimDamp;
        // Soft-limit the feedback. No fixed injection amount is safe for every
        // size, so the limiter is what keeps the loop bounded, like a shimmer
        // pedal.
        shimmerFb[c] = undenormal(fastTanh(shimLp[c] * 1.6f) * 0.55f);
    } else {
        shimmerFb[c] = 0.0f;
    }
}

// --- Room ---------------------------------------------------------------------

void Reverb::roomSample(const Block &b, const float dry[2], float out[2]) {
    for (int c = 0; c < 2; ++c) {
        const float x = dry[c] * b.inGain;
        float acc = 0.0f;
        int combIndex = 0;
        for (int32_t ci = 0; ci < b.nCombs; ++ci) {
            Comb &comb = combs[c][ci];
            // Each comb wobbles on its own phase. Without the offset they'd
            // all lengthen together, which is a pitch bend. Only computed
            // when wobble is on, since it's sixteen sines a sample.
            float drift = 0.0f;
            if (b.wobbling) {
                const float ph = wobblePhase + static_cast<float>(combIndex) * 0.125f + (c == 1 ? 0.5f : 0.0f);
                drift = b.wobbleDepth * std::sin((ph - std::floor(ph)) * dsp::kTwoPi);
            }
            const float y = comb.line.read(static_cast<float>(comb.len) * b.stretch + drift);
            comb.store = undenormal(y * (1.0f - b.damp) + comb.store * b.damp);
            // The shimmer uses only the headroom left under unity (1 - fb),
            // or the loop gain goes above one and blows up. So a frozen
            // reverb (fb = 1) can't shimmer. kCombInject accounts for the
            // shimmer going into every comb.
            comb.line.write(x + comb.store * b.fb + shimmerFb[c] * b.shimmer * (1.0f - b.fb) * kCombInject);
            acc += y;
            ++combIndex;
        }
        for (int32_t ai = 0; ai < b.nAps; ++ai) {
            Allpass &ap = aps[c][ai];
            const float y = ap.line.read(static_cast<float>(ap.len));
            ap.line.write(acc + y * 0.5f);
            acc = y - acc;
        }
        acc *= b.combScale;
        lp[c] += (acc - lp[c]) * b.toneCoeff;
        const float t = lofi(c, lp[c], b);
        shimmerStep(c, t, b);
        out[c] = t;
    }
    wobblePhase += b.wobbleInc;
    if (wobblePhase >= 1.0f) wobblePhase -= 1.0f;
}

// --- Plate --------------------------------------------------------------------

void Reverb::updatePlate(float size, float damp, bool freeze) {
    if (size == plSizeWas && damp == plDampWas && freeze == plFreezeWas) return;
    plSizeWas = size;
    plDampWas = damp;
    plFreezeWas = freeze;
    plScale = (kPlateScaleMin + kPlateScaleRange * size) * sr / kPlateRef;
    const auto len = [&](int ref) { return std::max(1, static_cast<int32_t>(static_cast<float>(ref) * plScale)); };
    for (int k = 0; k < kPlateDiff; ++k) plLenDiff[k] = len(kPlateDiffLen[k]);
    for (int h = 0; h < 2; ++h) {
        plLenAp1[h] = static_cast<float>(kPlateAp1[h]) * plScale;
        plLenD1[h] = len(kPlateD1[h]);
        plLenAp2[h] = len(kPlateAp2[h]);
        plLenD2[h] = len(kPlateD2[h]);
    }
    for (int k = 0; k < 7; ++k) {
        plTapL[k] = len(kPlateTapL[k].at);
        plTapR[k] = len(kPlateTapR[k].at);
    }
    // The decay is set as a time, 0.4 s to 8 s, and spread over the four
    // gain stages a trip round the figure eight passes.
    const float rt = 0.4f * std::pow(20.0f, size);
    const float loopSeconds = kPlateLoop * plScale / sr;
    plGain = freeze ? 1.0f : decayGain(loopSeconds * 0.25f, rt);
    // Damping: 14 kHz down to 1.5 kHz in the loop.
    const float hz = 14000.0f * std::pow(1500.0f / 14000.0f, damp);
    plDampCoeff = freeze ? 1.0f : dsp::onePoleCoeff(1.0f / (dsp::kTwoPi * hz), sr);
}

void Reverb::plateSample(const Block &b, const float dry[2], float out[2]) {
    const float inGain = b.freeze ? 0.0f : kPlateIn;
    float x = (dry[0] + dry[1]) * 0.5f * inGain;
    // Lean runs two of the four diffusers.
    for (int k = 0; k < kPlateDiff; k += b.full ? 1 : 2) x = allpass(plDiff[k], plLenDiff[k], kPlateDiffG[k] * b.diffMul, x);

    const float inject = b.shimmer * (1.0f - plGain) * kPlateInject;
    float cross[2];
    for (int h = 0; h < 2; ++h) {
        const float in = x + plCross[h ^ 1] + shimmerFb[h] * inject;
        rotate(plOsc[h], plRot[h]);
        const float d = plAp1[h].frac(plLenAp1[h] + b.modDepth * plOsc[h][1]);
        const float v = undenormal(in - kPlateG1 * d);
        plAp1[h].write(v);
        const float a = d + kPlateG1 * v;
        const float t = plD1[h].at(plLenD1[h]);
        plD1[h].write(a);
        plDamp[h] = undenormal(plDamp[h] + (t - plDamp[h]) * plDampCoeff);
        const float bb = allpass(plAp2[h], plLenAp2[h], kPlateG2, plDamp[h] * plGain);
        const float e = plD2[h].at(plLenD2[h]);
        plD2[h].write(bb);
        cross[h] = undenormal(e * plGain);
    }
    plCross[0] = cross[0];
    plCross[1] = cross[1];

    const auto tap = [&](const Tap &t, int32_t at) {
        const Line &l = t.line == 0 ? plD1[t.half] : (t.line == 1 ? plAp2[t.half] : plD2[t.half]);
        return t.sign * l.at(at);
    };
    // Lean reads four taps a side.
    const int taps = b.full ? 7 : 4;
    float sum[2] = {0.0f, 0.0f};
    for (int k = 0; k < taps; ++k) {
        sum[0] += tap(kPlateTapL[k], plTapL[k]);
        sum[1] += tap(kPlateTapR[k], plTapR[k]);
    }
    const float outScale = kPlateOut * (b.full ? 1.0f : 1.27f);
    for (int c = 0; c < 2; ++c) {
        lp[c] += (sum[c] * outScale - lp[c]) * b.toneCoeff;
        const float t = lofi(c, lp[c], b);
        shimmerStep(c, t, b);
        out[c] = t;
    }
}

// --- Hall ---------------------------------------------------------------------

void Reverb::updateHall(float size, float damp, bool freeze) {
    if (size == haSizeWas && damp == haDampWas && freeze == haFreezeWas) return;
    haSizeWas = size;
    haDampWas = damp;
    haFreezeWas = freeze;
    haScale = kHallScaleMin + kHallScaleRange * size;
    // 0.8 s to 12 s. Lows last longer and damp shortens the highs.
    const float rt = 0.8f * std::pow(15.0f, size);
    const float rtHigh = rt * std::max(0.08f, 1.0f - 0.85f * damp);
    for (int i = 0; i < kHallLines; ++i) {
        haLen[i] = kHallMs[i] * 0.001f * haScale * sr;
        const float s = haLen[i] / sr;
        haGMid[i] = freeze ? 1.0f : decayGain(s, rt);
        haGLow[i] = freeze ? 1.0f : decayGain(s, rt * kHallLowRatio);
        haGHigh[i] = freeze ? 1.0f : decayGain(s, rtHigh);
    }
}

void Reverb::hallSample(const Block &b, const float dry[2], float out[2]) {
    const float inGain = b.freeze ? 0.0f : kHallIn;
    float in[2];
    for (int c = 0; c < 2; ++c) {
        float x = dry[c] * inGain;
        for (int k = 0; k < 2; ++k) x = allpass(haDiff[c][k], haLenDiff[c][k], kHallDiffG * b.diffMul, x);
        in[c] = x;
    }

    const int n = b.full ? kHallLines : 4;
    const int *act = b.full ? kHallAll : kHallLean;
    float y[kHallLines], z[kHallLines];
    float sum = 0.0f;
    for (int k = 0; k < n; ++k) {
        const int i = act[k];
        rotate(haOsc[i], haRot[i]);
        y[k] = haLine[i].frac(haLen[i] + b.modDepth * (1.0f + haOsc[i][1]));
        // Three bands in one: highs at gHigh, mids at gMid, lows at gLow.
        haLow[i] = undenormal(haLow[i] + (y[k] - haLow[i]) * haLowCoeff);
        haHigh[i] = undenormal(haHigh[i] + (y[k] - haHigh[i]) * haHighCoeff);
        z[k] = haGHigh[i] * y[k] + (haGMid[i] - haGHigh[i]) * haHigh[i] + (haGLow[i] - haGMid[i]) * haLow[i];
        sum += z[k];
    }
    // Householder: every line gets itself less 2/n of the sum.
    const float h = sum * (2.0f / static_cast<float>(n));
    const float inject = b.shimmer * kHallInject;
    float o[2] = {0.0f, 0.0f};
    for (int k = 0; k < n; ++k) {
        const int i = act[k];
        const int c = k & 1;
        const float headroom = 1.0f - haGMid[i];
        haLine[i].write(undenormal(z[k] - h + in[c] * kHallInSign[k] + shimmerFb[c] * inject * headroom));
        o[0] += kHallRowL[k] * y[k];
        o[1] += kHallRowR[k] * y[k];
    }
    const float outScale = kHallOut * (b.full ? 1.0f : 1.41f);

    // Early reflections, from the pre-delayed dry.
    float er[2] = {0.0f, 0.0f};
    for (int c = 0; c < 2; ++c) {
        for (int k = 0; k < kHallEr; ++k) er[c] += kErGain[k] * preLong[c].read(b.preSamples + b.erAt[c][k]);
    }

    for (int c = 0; c < 2; ++c) {
        const float wet = o[c] * outScale + er[c] * b.erGain * (b.freeze ? 0.0f : 1.0f);
        lp[c] += (wet - lp[c]) * b.toneCoeff;
        const float t = lofi(c, lp[c], b);
        shimmerStep(c, t, b);
        out[c] = t;
    }
}

} // namespace acidulous::effect
