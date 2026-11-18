#include "Abacus.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <engine/dsp/Math.h>
#include <engine/machine/Voices.h>

namespace acidulous::machine {

using abacus::kGroups;

namespace {

constexpr float kTwoPi = 6.283185307179586f;
/** dB in one doubling of gain, so a slope of -6.02 dB per octave is a level of 1/n. */
constexpr float kDbPerLog2 = 6.0205999f;
/** log2(e), to turn a time constant into log2 of gain per second. */
constexpr float kLog2e = 1.4426950f;
/** A decay at or over this many seconds holds. */
constexpr float kHeld = 29.9f;
/** Gains under 2^-24 (-144 dB) are silence. */
constexpr float kFloorLog2 = -24.0f;
/** Where damping stops counting down, log2 of gain. */
constexpr float kDampFloor = -40.0f;
/** A voice whose gains all sum under this at both ends of a block is silent. */
constexpr float kSilent = 1e-6f;
/** The most power a voice's spectrum may have, in squared gains of partial 1: four times a 64-partial saw's 1.63. */
constexpr float kPowerCeiling = 6.5f;

const ParamDef kBase[Abacus::MacroBase] = {
        // --- the spectrum -----------------------------------------------
        {"partials", 0.0f, 2.0f, 2.0f, Curve::Stepped, 3, ""}, // 16, 32, 64
        {"slope", -24.0f, 6.0f, -6.0f, Curve::Linear, 0, "dB/oct"},
        {"odd", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"peak", 100.0f, 8000.0f, 1000.0f, Curve::Exponential, 0, "Hz"},
        {"peakgain", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"peakwidth", 0.1f, 3.0f, 0.7f, Curve::Exponential, 0, "oct"},
        {"vowel", 0.0f, 4.0f, 0.0f, Curve::Linear, 0, ""}, // a, e, i, o, u
        {"vowelamt", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"comb", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"combn", 2.0f, 16.0f, 3.0f, Curve::Stepped, 15, ""},
        {"law", 0.0f, 4.0f, 0.0f, Curve::Stepped, 5, ""}, // harmonic, string, bar, bell, plate
        {"stretch", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bslope", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, "dB/oct"},
        {"bodd", -2.0f, 2.0f, 0.0f, Curve::Linear, 0, ""},
        {"bpeak", -4.0f, 4.0f, 0.0f, Curve::Linear, 0, "oct"},
        {"bcomb", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"morph", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"menv", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"mtime", 0.001f, 8.0f, 0.3f, Curve::Exponential, 0, "s"},
        // --- partial time -----------------------------------------------
        {"damp", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"decay", 0.05f, 30.0f, 30.0f, Curve::Exponential, 0, "s"}, // 30 holds
        // --- the amp envelope -------------------------------------------
        {"attack", 0.001f, 8.0f, 0.005f, Curve::Exponential, 0, "s"},
        {"decay2", 0.005f, 8.0f, 1.0f, Curve::Exponential, 0, "s"},
        {"sustain", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"release", 0.005f, 12.0f, 0.3f, Curve::Exponential, 0, "s"},
        {"velocity", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"velbright", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        // --- space and motion -------------------------------------------
        {"spread", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"spreadmode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""}, // odd/even, scatter, low-high
        {"shimmer", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"shrate", 0.05f, 5.0f, 0.4f, Curve::Exponential, 0, "Hz"},
        {"lfo1wave", 0.0f, 8.0f, 0.0f, Curve::Stepped, 9, ""},
        {"lfo1rate", 0.01f, 20.0f, 5.0f, Curve::Exponential, 0, "Hz"},
        {"lfo1sync", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"lfo1pitch", 0.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"lfo1morph", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"lfo2wave", 0.0f, 8.0f, 0.0f, Curve::Stepped, 9, ""},
        {"lfo2rate", 0.01f, 20.0f, 0.3f, Curve::Exponential, 0, "Hz"},
        {"lfo2sync", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"lfo2bright", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"lfo2pan", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        // --- pitch and expression ---------------------------------------
        {"glide", 0.0f, 2.0f, 0.0f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"mpetimbre", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"mpepressure", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.0f, 0.18f, Curve::Linear, 0, ""}, // 15 dB under the top
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
};

// The base table plus the macros' knobs and routes.
struct Table {
    ParamDef defs[Abacus::Count];
    char names[Abacus::kMacros * Abacus::MacroParams][12];
    Table() {
        for (int32_t i = 0; i < Abacus::MacroBase; ++i) defs[i] = kBase[i];
        for (int k = 0; k < Abacus::kMacros; ++k) {
            const int32_t b = Abacus::MacroBase + k * Abacus::MacroParams;
            char *n = names[k * Abacus::MacroParams];
            std::snprintf(n, 12, "mac%d", k + 1);
            defs[b] = {n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
            for (int r = 0; r < 4; ++r) {
                char *d = names[k * Abacus::MacroParams + 1 + r];
                std::snprintf(d, 12, "mac%d_d%d", k + 1, r + 1);
                defs[b + 1 + r] = {d, 0.0f, Abacus::MacroDestCount - 1.0f, 0.0f, Curve::Stepped, Abacus::MacroDestCount, ""};
                char *a = names[k * Abacus::MacroParams + 5 + r];
                std::snprintf(a, 12, "mac%d_a%d", k + 1, r + 1);
                defs[b + 5 + r] = {a, -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
            }
        }
    }
};
const Table &table() {
    static const Table t;
    return t;
}

// Which param each macro destination moves in its own range; -1 for off and pitch.
constexpr int32_t kDestParam[Abacus::MacroDestCount] = {
    -1, Abacus::Slope, Abacus::Odd, Abacus::Peak, Abacus::PeakGain, Abacus::PeakWidth, Abacus::Vowel,
    Abacus::VowelAmount, Abacus::Comb, Abacus::Stretch, Abacus::BSlope, Abacus::BOdd, Abacus::BPeak, Abacus::BComb,
    Abacus::Morph, Abacus::MorphEnv, Abacus::MorphTime, Abacus::Damp, Abacus::Decay, Abacus::Attack, Abacus::Decay2,
    Abacus::Sustain, Abacus::Release, Abacus::VelocityAmount, Abacus::VelBright, Abacus::Spread, Abacus::Shimmer,
    Abacus::ShimmerRate, Abacus::Lfo1Rate, Abacus::Lfo1Pitch, Abacus::Lfo1Morph, Abacus::Lfo2Rate, Abacus::Lfo2Bright,
    Abacus::Lfo2Pan, Abacus::Glide, Abacus::Fine, Abacus::MpeTimbre, Abacus::MpePressure, Abacus::Volume, Abacus::Pan,
    -1,
};

/** The other way: each param's macro destination, or 0 for none. */
struct DestOf {
    int8_t of[Abacus::MacroBase] = {};
    DestOf() {
        for (int d = 1; d < Abacus::MacroDestCount; ++d)
            if (kDestParam[d] >= 0) of[kDestParam[d]] = static_cast<int8_t>(d);
    }
};
const DestOf kDestOf;

// The laws' mode tables, as ratios to the lowest mode.
//
// A free bar: the roots of cos(b) cosh(b) = 1, squared against the first;
// past the fourth, (2n + 1) pi / 2 is exact to many places.
constexpr double kBarRoots[4] = {4.730040745, 7.853204624, 10.995607838, 14.137165491};
// A church bell, from its hum: hum, prime, tierce, quint, nominal, then the
// upper partials. Past the table they grow by a power law.
constexpr double kBell[12] = {1.0, 2.0, 2.4, 3.0, 4.0, 5.0, 5.33, 6.0, 8.0, 10.67, 12.0, 13.5};

/** The vowels' first two formants, Hz: a, e, i, o, u. */
constexpr float kVowelHz[5][2] = {{730.0f, 1090.0f}, {530.0f, 1840.0f}, {270.0f, 2290.0f}, {570.0f, 840.0f}, {300.0f, 870.0f}};

double plateRatio(int n) {
    // The square plate with simply supported edges: modes at m^2 + k^2 for
    // m, k from 1, each value once, sorted, against the first (2).
    static double table[Abacus::kPartials];
    static bool built = false;
    if (!built) {
        int vals[400];
        int count = 0;
        for (int m = 1; m <= 19; ++m)
            for (int k = m; k <= 19; ++k) vals[count++] = m * m + k * k;
        std::sort(vals, vals + count);
        int out = 0;
        for (int i = 0; i < count && out < Abacus::kPartials; ++i)
            if (i == 0 || vals[i] != vals[i - 1]) table[out++] = vals[i] / 2.0;
        built = true;
    }
    return table[n];
}

} // namespace

Abacus::Abacus() {
    initParams();
    // Build the plate table here, off the audio thread.
    (void)plateRatio(0);
}

const ParamDef *Abacus::paramDefs(int32_t &count) const {
    count = Count;
    return table().defs;
}

float Abacus::ratioOf(int law, float stretch, int n) {
    const double h = n + 1.0; // the harmonic this partial would be
    double r = h;
    switch (law) {
    case String: {
        // A stiff string: n sqrt(1 + B n^2), against the first. Stretch moves
        // B over 3 octaves either side of a piano's bass.
        const double b = 0.0003 * std::pow(8.0, static_cast<double>(stretch));
        return static_cast<float>(h * std::sqrt((1.0 + b * h * h) / (1.0 + b)));
    }
    case Bar: {
        const double b = n < 4 ? kBarRoots[n] : (2.0 * n + 3.0) * 3.14159265358979 / 2.0;
        r = (b / kBarRoots[0]) * (b / kBarRoots[0]);
        break;
    }
    case Bell:
        r = n < 12 ? kBell[n] : kBell[11] * std::pow(h / 12.0, 1.4);
        break;
    case Plate: r = plateRatio(n); break;
    default:
        // Harmonic: stretch of 1 puts partial 64 about 144 cents sharp.
        return static_cast<float>(std::pow(h, 1.0 + 0.02 * static_cast<double>(stretch)));
    }
    // The modal laws: stretch -1 is harmonic, 0 the law, 1 twice as far from harmonic.
    const double k = 1.0 + static_cast<double>(stretch);
    return static_cast<float>(std::exp(std::log(h) + k * (std::log(r) - std::log(h))));
}

void Abacus::updateLaw(int law, float stretch, float damp) {
    if (law != lawFor || stretch != stretchFor) {
        for (int n = 0; n < kPartials; ++n) {
            ratio[n] = ratioOf(law, stretch, n);
            log2Ratio[n] = std::log2(ratio[n]);
        }
        lawFor = law;
        stretchFor = stretch;
        dampFor = -1.0f;
    }
    if (damp != dampFor) {
        // Partial n's decay is decay / r^(2 damp): log2 of gain falls by
        // r^(2 damp) log2(e) / decay a second.
        for (int n = 0; n < kPartials; ++n) dampPow[n] = kLog2e * std::exp2(2.0f * damp * log2Ratio[n]);
        dampFor = damp;
    }
}

float Abacus::val(int32_t p, bool goal) const {
    const float v = goal ? params_.target(p) : params_.get(p);
    const int d = p < MacroBase ? kDestOf.of[p] : 0;
    const float off = d > 0 ? (goal ? macGoal_[d] : mac_[d]) : 0.0f;
    if (off == 0.0f) return v;
    const ParamDef &def = params_.def(p);
    return def.map(def.unmap(v) + off);
}

int32_t Abacus::steppedOf(int32_t p) const { return static_cast<int32_t>(std::lround(params_.get(p))); }

void Abacus::updateMacros() {
    for (float &m : mac_) m = 0.0f;
    for (int k = 0; k < kMacros; ++k) {
        const int32_t b = MacroBase + k * MacroParams;
        const float knob = params_.get(b);
        if (knob == 0.0f) continue;
        for (int r = 0; r < 4; ++r) {
            const int d = static_cast<int>(params_.get(b + 1 + r) + 0.5f);
            if (d > McOff && d < MacroDestCount) mac_[d] += knob * params_.get(b + 5 + r);
        }
    }
}

void Abacus::updateMacroGoals() {
    for (float &m : macGoal_) m = 0.0f;
    for (int k = 0; k < kMacros; ++k) {
        const int32_t b = MacroBase + k * MacroParams;
        const float knob = params_.target(b);
        if (knob == 0.0f) continue;
        for (int r = 0; r < 4; ++r) {
            const int d = static_cast<int>(params_.target(b + 1 + r) + 0.5f);
            if (d > McOff && d < MacroDestCount) macGoal_[d] += knob * params_.target(b + 5 + r);
        }
    }
}

void Abacus::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    reset();
}

void Abacus::reset() {
    for (auto &v : voices) {
        v = Voice();
        v.amp.setSampleRate(sampleRate);
    }
    lfo[0] = dsp::LfoGen();
    lfo[1] = dsp::LfoGen();
    lfo[0].reset(0.0f);
    lfo[1].reset(0.25f);
    lfoValue[0] = lfoValue[1] = 0.0f;
    rng = kSeed;
    ageCounter = 0;
    lastPitch = -1.0f;
    bend = pressure_ = 0.0f;
    for (float &m : mac_) m = 0.0f;
    for (float &m : macGoal_) m = 0.0f;
    for (float &a : accL) a = 0.0f;
    for (float &a : accR) a = 0.0f;
    outL = outR = 0.0f;
    outSet = false;
}

int Abacus::activeVoices() const {
    int n = 0;
    for (const auto &v : voices) n += v.used ? 1 : 0;
    return n;
}

Abacus::Voice *Abacus::allocate() {
    for (auto &v : voices) if (!v.used) return &v;
    // Steal the quietest released voice, then the quietest of all.
    Voice *best = nullptr;
    for (auto &v : voices) {
        if (v.gate) continue;
        if (best == nullptr || v.amp.value() < best->amp.value()) best = &v;
    }
    if (best != nullptr) return best;
    for (auto &v : voices) {
        if (best == nullptr || v.amp.value() < best->amp.value() ||
            (v.amp.value() == best->amp.value() && v.age < best->age))
            best = &v;
    }
    return best;
}

void Abacus::startVoice(Voice &v, uint8_t note, uint8_t velocity) {
    updateMacroGoals();
    if (!v.used) {
        // From silence: every rotor at phase 0 and every gain 0. A stolen
        // voice keeps its rotors and gains, so it moves to the new note
        // without a jump.
        v.bank.clear();
        v.quiet = 0;
    }
    const float glide = goalOf(Glide);
    const float target = static_cast<float>(note);
    const bool gliding = glide > 0.001f && lastPitch >= 0.0f;
    v.glideFrom = gliding ? lastPitch : target;
    v.glidePos = gliding ? 0.0f : 1.0f;
    v.pitch = v.glideFrom;
    lastPitch = target;
    v.used = true;
    v.gate = true;
    v.note = note;
    v.bend = 0.0f;
    v.pressure = v.timbre = -1.0f;
    v.prsGlide = 0.0f;
    v.morphEnv = 1.0f;
    const float vel = static_cast<float>(velocity) / 127.0f;
    v.velGain = velocityGain(vel, std::clamp(goalOf(VelocityAmount), 0.0f, 1.0f));
    // Softer notes are darker: up to 18 dB per octave down at the lightest touch.
    v.velBright = std::clamp(goalOf(VelBright), 0.0f, 1.0f) * (vel - 1.0f) * 18.0f;
    v.age = ++ageCounter;
    // Seed this note's own generator, then deal its scatter places and
    // shimmer walks from it.
    v.rng = static_cast<uint32_t>(nextRand(rng) * 16777216.0f) * 2654435761u + 1u;
    for (int n = 0; n < kPartials; ++n) {
        v.damp[n] = 0.0f;
        v.place[n] = nextRand(v.rng) * 2.0f - 1.0f;
        v.shPhase[n] = nextRand(v.rng);
        v.shRate[n] = 0.7f + 0.6f * nextRand(v.rng);
        v.shL0[n] = nextRand(v.rng) * 2.0f - 1.0f;
        v.shL1[n] = nextRand(v.rng) * 2.0f - 1.0f;
        v.shP0[n] = nextRand(v.rng) * 2.0f - 1.0f;
        v.shP1[n] = nextRand(v.rng) * 2.0f - 1.0f;
    }
    v.panFor = -1.0f;
    v.amp.retrigger();
}

void Abacus::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = allocate();
    if (v == nullptr) return;
    startVoice(*v, note, velocity);
}

void Abacus::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Abacus::allNotesOff() {
    for (auto &v : voices) {
        if (!v.used) continue;
        v.gate = false;
        v.amp.release();
    }
}

void Abacus::channelPressure(uint8_t value) { pressure_ = static_cast<float>(value) / 127.0f; }
void Abacus::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }

void Abacus::noteBend(uint8_t note, float semitones) {
    if (Voice *v = voiceForNote(voices, note)) v->bend = semitones;
}
void Abacus::notePressure(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->pressure = static_cast<float>(value) / 127.0f;
}
void Abacus::noteTimbre(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->timbre = static_cast<float>(value) / 127.0f;
}

void Abacus::onBlock(int64_t, int64_t, float tempo) { bpm = tempo; }

void Abacus::renderVoice(Voice &v, int32_t frames) {
    const Block &b = blk;
    const float sec = static_cast<float>(frames) * b.dt;

    // The amp envelope, run per sample; its value at the block's end sets
    // the gains the ramp heads for.
    v.amp.set(0.0f, b.ampA, b.ampD, b.ampS, b.ampR, false);
    float env = 0.0f;
    for (int32_t i = 0; i < frames; ++i) env = v.amp.next();

    // Pitch: glide in note numbers, then the track's tuning, then the bends,
    // knobs, macro and LFO 1 as semitones on top.
    if (v.glidePos < 1.0f) {
        v.glidePos = b.glide > 0.001f ? std::min(1.0f, v.glidePos + sec / b.glide) : 1.0f;
        v.pitch = v.glideFrom + (static_cast<float>(v.note) - v.glideFrom) * v.glidePos;
    } else {
        v.pitch = static_cast<float>(v.note);
    }
    const float semis = b.tune + b.bendSemis + v.bend + lfoValue[0] * b.lfo1Pitch * 0.01f;
    const float f0 = noteHz(v.pitch) * std::exp2(semis * (1.0f / 12.0f));
    const float log2f0 = std::log2(std::max(f0, 1.0f));

    // Per-note expression: pressure brightens and lifts, slide morphs.
    const float prs = glidePressure(v.prsGlide, v.pressure, pressure_) * b.mpePressure;
    const float slide = (v.timbre >= 0.0f ? v.timbre : 0.0f) * b.mpeTimbre;

    // This voice's place between spectrum A and B.
    const float m = std::clamp(b.morph + b.morphEnv * v.morphEnv + lfoValue[0] * b.lfo1Morph + slide, 0.0f, 1.0f);
    v.morphEnv *= b.morphFall;
    if (v.morphEnv < 1e-6f) v.morphEnv = 0.0f;
    const float slope = std::clamp(b.slope + m * b.bSlope + v.velBright + prs * 12.0f + lfoValue[1] * b.lfo2Bright * 12.0f,
                                   -48.0f, 12.0f);
    const float odd = std::clamp(b.odd + m * b.bOdd, -1.0f, 1.0f);
    const float comb = std::clamp(b.comb + m * b.bComb, 0.0f, 1.0f);
    const float peakLog2 = b.peakLog2 + m * b.bPeak;
    const float slopeLog2 = slope * (1.0f / kDbPerLog2);
    const float peakLog2Gain = b.peakGain * (1.0f / kDbPerLog2);
    const float peakInv = 1.0f / b.peakHalf;
    const float evenMul = odd > 0.0f ? 1.0f - odd : 1.0f;
    const float oddMul = odd < 0.0f ? 1.0f + odd : 1.0f;
    const float combMul = 1.0f - comb;
    const float base = v.velGain * env * (1.0f + 0.5f * prs);

    // The pan gains, worked out again only when spread or its mode moves.
    if (v.panFor != b.spread || v.panModeFor != b.spreadMode) {
        for (int n = 0; n < kPartials; ++n) {
            float pos;
            switch (b.spreadMode) {
            case Scatter: pos = v.place[n]; break;
            case LowHigh: pos = std::log2(static_cast<float>(n + 1)) * (2.0f / 6.0f) - 1.0f; break; // by octave, 1 to 64
            default: pos = n == 0 ? 0.0f : ((n & 1) ? 1.0f : -1.0f); break; // the first in the middle, odd left, even right
            }
            pos *= b.spread;
            // Equal power, 1 and 1 in the middle so spread 0 is exactly mono.
            v.panL[n] = std::sqrt(1.0f - pos);
            v.panR[n] = std::sqrt(1.0f + pos);
        }
        v.panFor = b.spread;
        v.panModeFor = b.spreadMode;
    }

    // Damping counts down from note-on; a held decay leaves it alone.
    if (b.decay < kHeld) {
        const float k = sec / b.decay;
        for (int n = 0; n < b.count; ++n) v.damp[n] = std::max(kDampFloor, v.damp[n] - dampPow[n] * k);
    }

    // Shimmer walks: each partial moves from one seeded level and pitch to
    // the next, smoothly, at its own rate.
    const bool shimmer = b.shimmer > 0.0f;
    if (shimmer) {
        for (int n = 0; n < b.count; ++n) {
            float ph = v.shPhase[n] + b.shimmerStep * v.shRate[n];
            if (ph >= 1.0f) {
                ph -= 1.0f;
                v.shL0[n] = v.shL1[n];
                v.shP0[n] = v.shP1[n];
                v.shL1[n] = nextRand(v.rng) * 2.0f - 1.0f;
                v.shP1[n] = nextRand(v.rng) * 2.0f - 1.0f;
            }
            v.shPhase[n] = ph;
        }
    }

    // Every partial's target gain and rotation, four at a time with no
    // branches: masks zero what is silent, over the cutoff or past the count.
    using abacus::F4;
    using abacus::I4;
    using abacus::splat;
    using abacus::load4;
    using abacus::store4;
    using abacus::pick;
    const F4 zero = splat(0.0f);
    const F4 vf0 = splat(f0), vlog2f0 = splat(log2f0), vslope = splat(slopeLog2);
    const F4 vshL = splat(b.shimmer * (6.0f / kDbPerLog2));     // up to 6 dB either way
    const F4 vshP = splat(b.shimmer * (12.0f * 5.7762e-4f));    // up to 12 cents either way
    const F4 vpeak = splat(peakLog2), vpeakInv = splat(peakInv), vpeakGain = splat(peakLog2Gain);
    const F4 vF1 = splat(b.vowelF1), vF2 = splat(b.vowelF2), vVowel = splat(b.vowelAmount);
    const F4 vCut = splat(b.cutoffHz), vTheta = splat(kTwoPi / sampleRate), vPi = splat(3.14159265f);
    const F4 vTopHz = splat(sampleRate * 0.5f);
    const F4 vFloor = splat(kFloorLog2), vComb = splat(1.0f - combMul);
    // Index 0 is the first partial, odd; the lanes go odd, even, odd, even.
    const F4 parity = {oddMul, evenMul, oddMul, evenMul};
    const F4 vBase = splat(base);
    F4 total4 = zero, power4 = zero;
    for (int n = 0; n < b.count; n += 4) {
        const F4 l2 = load4(log2Ratio + n);
        F4 hz = vf0 * load4(ratio + n);
        F4 lg = vslope * l2;
        if (shimmer) {
            const F4 t = load4(v.shPhase + n);
            const F4 sm = t * t * (splat(3.0f) - splat(2.0f) * t);
            const F4 l0 = load4(v.shL0 + n), p0 = load4(v.shP0 + n);
            lg += vshL * (l0 + (load4(v.shL1 + n) - l0) * sm);
            hz *= splat(1.0f) + vshP * (p0 + (load4(v.shP1 + n) - p0) * sm);
        }
        const F4 lf = vlog2f0 + l2;
        if (peakLog2Gain > 0.0f) {
            const F4 d = (lf - vpeak) * vpeakInv;
            lg += vpeakGain * abacus::exp2v(zero - d * d);
        }
        F4 g = abacus::exp2v(lg) * parity * (splat(1.0f) - vComb * load4(combMask + n));
        if (b.vowelAmount > 0.0f) {
            const F4 d1 = (lf - vF1) * splat(1.0f / 0.35f);
            const F4 d2 = (lf - vF2) * splat(1.0f / 0.3f);
            const F4 resp = splat(0.06f) + abacus::exp2v(zero - d1 * d1) + splat(0.7f) * abacus::exp2v(zero - d2 * d2);
            g *= splat(1.0f) + vVowel * (resp - splat(1.0f));
        }
        // The ceiling measures the spectrum before damping, so a decay is heard.
        const F4 damp = load4(v.damp + n);
        g = pick((lg >= vFloor) & (hz < vCut), g, zero);
        power4 += g * g;
        g = pick(lg + damp >= vFloor, g * abacus::exp2v(damp) * vBase, zero);
        // The rotation only for partials heading somewhere; one fading out
        // keeps last block's.
        F4 c, sn;
        abacus::rotationv(pick(hz < vTopHz, hz * vTheta, vPi), c, sn);
        const I4 sounding = g != zero;
        store4(v.bank.wr + n, pick(sounding, c, load4(v.bank.wr + n)));
        store4(v.bank.wi + n, pick(sounding, sn, load4(v.bank.wi + n)));
        const F4 gl = g * load4(v.panL + n), gr = g * load4(v.panR + n);
        store4(tl + n, gl);
        store4(tr + n, gr);
        total4 += gl + gr;
    }
    for (int n = b.count; n < kPartials; ++n) tl[n] = tr[n] = 0.0f;
    float total = abacus::sum4(total4);
    // The power ceiling: a spectrum with more power than kPowerCeiling (a
    // saw's four times over) is scaled down to it, so a bright slope or a big
    // peak tilts the sound rather than making it tens of dB louder.
    const float power = abacus::sum4(power4);
    if (power > kPowerCeiling) {
        const float k = std::sqrt(kPowerCeiling / power);
        for (int n = 0; n < b.count; ++n) {
            tl[n] *= k;
            tr[n] *= k;
        }
        total *= k;
    }

    // Retire on what the voice can still put out: the sum of its gains is a
    // bound on its level. Two silent blocks, released or fully damped.
    float was = 0.0f;
    for (int n = 0; n < kPartials; ++n) was += v.bank.gl[n] + v.bank.gr[n];
    if (total < kSilent && was < kSilent) {
        if (++v.quiet >= 2 && (!v.gate || v.amp.value() < kSilent || b.decay < kHeld)) {
            v.used = v.gate = false;
            v.amp.kill();
            v.bank.clear();
            return;
        }
    } else {
        v.quiet = 0;
    }

    // The oscillators, eight partials at a time where both fours sound.
    for (int g = 0; g < kGroups; g += 2) {
        const bool a = v.bank.live(g, tl, tr), c = v.bank.live(g + 1, tl, tr);
        if (a || c) v.bank.run(a ? g * 4 : (g + 1) * 4, a && c, tl, tr, accL, accR, frames);
    }
}

bool Abacus::render(float *L, float *R, int32_t frames) {
    params_.tick();
    updateMacros();
    const float dt = 1.0f / sampleRate;

    // The LFOs belong to the machine and turn whether or not anything sounds.
    const float sync1 = steppedOf(Lfo1Sync) != 0 ? bpm / 60.0f : 1.0f;
    lfoValue[0] = lfo[0].advance(steppedOf(Lfo1Wave), paramOf(Lfo1Rate) * sync1, dt * frames, 0.0f, false);
    const float sync2 = steppedOf(Lfo2Sync) != 0 ? bpm / 60.0f : 1.0f;
    lfoValue[1] = lfo[1].advance(steppedOf(Lfo2Wave), paramOf(Lfo2Rate) * sync2, dt * frames, 0.0f, false);

    bool any = false;
    for (const auto &v : voices) any = any || v.used;
    if (!any) {
        // Asleep: nothing sounds and nothing is held.
        for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
        outSet = false;
        return true;
    }

    Block &b = blk;
    b.dt = dt;
    b.count = 16 << std::clamp(steppedOf(Partials), 0, 2);
    b.cutoffHz = 0.45f * sampleRate;
    b.slope = paramOf(Slope);
    b.odd = paramOf(Odd);
    b.peakLog2 = std::log2(paramOf(Peak));
    b.peakGain = paramOf(PeakGain);
    // peakwidth is the width in octaves where the boost is half its dB.
    b.peakHalf = std::max(0.05f, paramOf(PeakWidth) * 0.5f);
    b.comb = paramOf(Comb);
    b.combN = std::clamp(steppedOf(CombN), 2, 16);
    if (b.combN != combFor) {
        for (int n = 0; n < kPartials; ++n) combMask[n] = (n + 1) % b.combN == 0 ? 1.0f : 0.0f;
        combFor = b.combN;
    }
    b.bSlope = paramOf(BSlope);
    b.bOdd = paramOf(BOdd);
    b.bPeak = paramOf(BPeak);
    b.bComb = paramOf(BComb);
    {
        const float vw = std::clamp(paramOf(Vowel), 0.0f, 4.0f);
        const int i0 = std::min(static_cast<int>(vw), 3);
        const float f = vw - static_cast<float>(i0);
        b.vowelF1 = std::log2(kVowelHz[i0][0]) * (1.0f - f) + std::log2(kVowelHz[i0 + 1][0]) * f;
        b.vowelF2 = std::log2(kVowelHz[i0][1]) * (1.0f - f) + std::log2(kVowelHz[i0 + 1][1]) * f;
        b.vowelAmount = std::clamp(paramOf(VowelAmount), 0.0f, 1.0f);
    }
    b.morph = paramOf(Morph);
    b.morphEnv = paramOf(MorphEnv);
    b.decay = paramOf(Decay);
    b.shimmer = std::clamp(paramOf(Shimmer), 0.0f, 1.0f);
    b.spread = std::clamp(paramOf(Spread), 0.0f, 1.0f);
    b.spreadMode = std::clamp(steppedOf(SpreadMode), 0, 2);
    b.tune = static_cast<float>(steppedOf(Octave)) * 12.0f + static_cast<float>(steppedOf(Transpose)) +
             paramOf(Fine) * 0.01f + mac_[McPitch] * 24.0f;
    b.bendSemis = bend * static_cast<float>(steppedOf(BendRange));
    b.glide = paramOf(Glide);
    b.lfo1Pitch = paramOf(Lfo1Pitch);
    b.lfo1Morph = paramOf(Lfo1Morph);
    b.lfo2Bright = paramOf(Lfo2Bright);
    b.mpeTimbre = paramOf(MpeTimbre);
    b.mpePressure = paramOf(MpePressure);
    b.ampA = paramOf(Attack);
    b.ampD = paramOf(Decay2);
    b.ampS = paramOf(Sustain);
    b.ampR = paramOf(Release);
    updateLaw(std::clamp(steppedOf(Law), 0, LawCount - 1), paramOf(Stretch), std::clamp(paramOf(Damp), 0.0f, 1.0f));
    const float mtime = paramOf(MorphTime);
    const float shRate = paramOf(ShimmerRate);

    const float volume = paramOf(Volume) * kHouseTrim;
    const float pan = std::clamp(paramOf(Pan) + lfoValue[1] * paramOf(Lfo2Pan), -1.0f, 1.0f);
    const float goalL = volume * std::sqrt(1.0f - pan), goalR = volume * std::sqrt(1.0f + pan);
    if (!outSet) {
        outL = goalL;
        outR = goalR;
        outSet = true;
    }

    // In chunks of up to 64 frames, the gains' ramp length.
    for (int32_t at = 0; at < frames; at += 64) {
        const int32_t n = std::min<int32_t>(64, frames - at);
        const float sec = static_cast<float>(n) * dt;
        b.morphFall = std::exp(-sec / mtime);
        b.shimmerStep = shRate * sec;
        for (int32_t i = 0; i < n * 4; ++i) accL[i] = accR[i] = 0.0f;
        for (auto &v : voices)
            if (v.used) renderVoice(v, n);
        // Sum the lanes and ramp the output gains across the chunk.
        const float fromL = outL, fromR = outR;
        const float stepL = (goalL - fromL) / static_cast<float>(n), stepR = (goalR - fromR) / static_cast<float>(n);
        for (int32_t i = 0; i < n; ++i) {
            const float *aL = accL + i * 4, *aR = accR + i * 4;
            const float gL = fromL + stepL * static_cast<float>(i + 1);
            const float gR = fromR + stepR * static_cast<float>(i + 1);
            L[at + i] = (aL[0] + aL[1] + aL[2] + aL[3]) * gL;
            R[at + i] = (aR[0] + aR[1] + aR[2] + aR[3]) * gR;
        }
        outL = goalL;
        outR = goalR;
    }
    return true;
}

} // namespace acidulous::machine
