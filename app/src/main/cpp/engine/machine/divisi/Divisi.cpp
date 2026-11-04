#include "Divisi.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <engine/dsp/Math.h>
#include <engine/machine/Voices.h>

namespace acidulous::machine {

using dsp::clampf;

namespace {

/** Level for a key at full velocity, set so a chord sits with the other machines. */
constexpr float kHouse = 0.2f;
/** Each register's level at full against the violin's, so each knob at full is about as loud. */
constexpr float kViolinTrim = 1.2f, kViolaTrim = 1.05f, kCelloTrim = 1.18f, kBassTrim = 1.02f;
constexpr float kChoirTrim = 2.62f, kBrassTrim = 1.25f;
/** A key's switch into the registers: rises in 3 ms, falls in 30 ms. */
constexpr float kGateRise = 0.003f, kGateFall = 0.03f;
/** Octaves the brass filter opens over at full sweep, its cutoff closed, Hz, and its resonance. */
constexpr float kBrassOctaves = 7.0f, kBrassClosed = 180.0f, kBrassRes = 0.3f;
/** How much of its cutoff a poly key's brass filter moves with the key's pitch, octaves per octave. */
constexpr float kBrassTrack = 0.5f;
/** Brightness: how far a held note falls to, and how loud a note is at no velocity against full. */
constexpr float kBrassFloor = 0.7f, kBrassVelFloor = 0.3f;
/** The brightness's rise at no blat and the most blat adds, its fall, and the overshoot's two halves, seconds. */
constexpr float kBrightUp = 0.015f, kBrightUpBlat = 0.04f, kBrightDown = 0.08f;
constexpr float kOverFast = 0.02f, kOverSlow = 0.1f;
/** The overshoot at full blat, in brightness, and the scoop, semitones, and its time, seconds. */
constexpr float kOverMax = 0.6f, kScoopMax = 0.6f, kScoopTime = 0.03f;
/** The drive into the brass filter: at no loudness and what full loudness adds. */
constexpr float kDriveLow = 0.4f, kDriveSpan = 1.4f;
/** Samples between moves of the phaser's all-passes, and of the brass filters and their brightness. */
constexpr int kCoefEvery = 8, kBrassEvery = 16;
/** The ensemble: the lines' middle delay, and the slow and fast LFOs' reach either side of it, ms, and their rates, Hz. */
constexpr float kLineMs = 6.0f, kSlowMs = 1.8f, kFastMs = 0.3f;
constexpr float kSlowHz = 0.55f, kFastHz = 5.8f;
/** The phaser's sweep, Hz. */
constexpr float kPhaseLow = 180.0f, kPhaseHigh = 2600.0f;
/** Below this a key is silent, and the whole machine after enough of it. */
constexpr float kSilent = 1e-6f;
constexpr int32_t kSleepAfter = 4096;
constexpr float kTwoTo32 = 4294967296.0f;
/** A pitch ratio's departure from 1 per cent, near enough for a few tens of cents. */
constexpr float kPerCent = 0.69314718f / 1200.0f;
/** A player's walk: the time between new targets (shortest and the most added), its two smoothing stages, s. */
constexpr float kPickMin = 0.3f, kPickSpan = 0.6f, kWalkTime = 0.4f;
/** How fast the walk's delay is pulled back to the middle, s: how long a player stays sharp or flat. */
constexpr float kDriftHold = 1.5f;
/** How far either way the vibrato rate is spread over the players. */
constexpr float kVibSpread = 0.07f;
/** How fast a scatter delay glides to a new place, samples a sample (about 17 cents while it moves). */
constexpr float kSeatGlide = 0.01f;
/** The players' gains glide in about this long, s. */
constexpr float kGainTime = 0.02f;
/** The longest a player's line holds: scatter, drift and vibrato at their ends, with room, s. */
constexpr float kPlayerLine = 0.14f;
/** Each section's width, how far its players spread either side of its middle: violin, viola, cello, contrabass, choir, brass. */
constexpr float kWidth[6] = {0.7f, 0.6f, 0.6f, 0.4f, 0.8f, 0.5f};
/**
 * How far apart the players' delays typically are, ms, per ms of scatter and
 * per cent of drift and of vibrato (at 5.5 Hz), and the spread at which they
 * stop adding as one louder sound and start adding by power.
 */
constexpr float kSpreadScatter = 0.29f, kSpreadDrift = 0.09f, kSpreadVibrato = 0.014f, kSpreadLoose = 0.5f;
/** How loud the width-weighted mix is against the mix, by power, for the pan law's extra level at the sides. */
constexpr float kWideShare = 0.4f;

/** A step of a player's own generator, 0 to 1. */
inline float nextRand(uint32_t &r) {
    r = r * 1664525u + 1013904223u;
    return static_cast<float>(r >> 8) * (1.0f / 16777216.0f);
}
constexpr float kInvTwoTo32 = 1.0f / 4294967296.0f;

/** A sine of [turns], close enough for an LFO. */
inline float lfoSin(float turns) {
    const float x = turns - std::floor(turns + 0.5f); // -0.5..0.5
    const float y = 8.0f * x - 16.0f * x * std::fabs(x);
    return 0.225f * (y * std::fabs(y) - y) + y;
}

inline float polyBlep(float t, float dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0f;
    }
    if (t > 1.0f - dt) {
        t = (t - 1.0f) / dt;
        return t * t + t + t + 1.0f;
    }
    return 0.0f;
}

/** A band-limited saw from a divider's phase and step, both fractions of 2^32. */
inline float sawOf(uint32_t phase, uint32_t step) {
    const float t = static_cast<float>(phase) * kInvTwoTo32;
    const float dt = static_cast<float>(step) * kInvTwoTo32;
    return 2.0f * t - 1.0f - polyBlep(t, dt);
}

/** The choir's vowels, dark to bright: three formants each, Hz, and their levels. */
struct VowelShape {
    float hz[3];
    float level[3];
};
constexpr VowelShape kVowels[5] = {
    {{325.0f, 700.0f, 2530.0f}, {1.0f, 0.35f, 0.08f}},   // u
    {{450.0f, 800.0f, 2830.0f}, {1.0f, 0.5f, 0.1f}},     // o
    {{800.0f, 1150.0f, 2900.0f}, {1.0f, 0.6f, 0.15f}},   // a
    {{400.0f, 1600.0f, 2700.0f}, {1.0f, 0.35f, 0.2f}},   // e
    {{300.0f, 2100.0f, 3000.0f}, {1.0f, 0.25f, 0.2f}},   // i
};
/** A formant's width, Hz: a floor and a part of its centre, as wide as a section of voices rather than one. */
constexpr float kFormantWidth = 80.0f, kFormantSpread = 0.12f;
/** The choir's excitation: the 8' square, the 16' square and the 8' saw, which fills in the even harmonics. */
constexpr float kChoirSq8 = 0.7f, kChoirSq16 = 0.5f, kChoirSaw8 = 0.45f;
/** The choir's high-pass and its low-pass at the middle tone, Hz. */
constexpr float kChoirHigh = 120.0f, kChoirLow = 5000.0f;
/** Highest harmonic the choir's levelling counts, Hz, and how far it may move a key, either way. */
constexpr float kCompTop = 8000.0f, kCompMax = 6.0f;
/** The note the choir is levelled to, C4, and its vowel, aah. */
constexpr int kCompNote = 60;

/** A complex number, for the levelling's filter responses. */
struct Cx {
    float re, im;
};
inline Cx mul(Cx a, Cx b) { return {a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re}; }
inline Cx divide(Cx a, Cx b) {
    const float d = b.re * b.re + b.im * b.im;
    return {(a.re * b.re + a.im * b.im) / d, (a.im * b.re - a.re * b.im) / d};
}

} // namespace

Divisi::Divisi() { initParams(); }

const ParamDef *Divisi::paramDefs(int32_t &count) const {
    static const ParamDef first[MacroBase] = {
        {"violin", 0.0f, 1.0f, 0.8f, Curve::Linear, 0, ""},
        {"viola", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"cello", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"contrabass", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"choir", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        // The choir's vowel: u, o, a, e, i.
        {"vowel", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"brass", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        // How far the brass filter opens on a note, and how long a held note takes to fall back.
        {"sweep", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"brassdecay", 0.05f, 4.0f, 0.8f, Curve::Exponential, 0, "s"},
        // The brass attack: how far the filter overshoots, how late it opens and how far the pitch scoops up.
        {"blat", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"tone", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // paraphonic, poly
        {"mode", 0.0f, static_cast<float>(ModeCount - 1), 0.0f, Curve::Stepped, ModeCount, ""},
        {"attack", 0.002f, 5.0f, 0.25f, Curve::Exponential, 0, "s"},
        {"release", 0.02f, 10.0f, 1.5f, Curve::Exponential, 0, "s"},
        {"ensemble", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        {"speed", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"phaser", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"rate", 0.05f, 5.0f, 0.4f, Curve::Exponential, 0, "Hz"},
        {"feedback", 0.0f, 0.9f, 0.4f, Curve::Linear, 0, ""},
        {"tune", -100.0f, 100.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"octave", -2.0f, 2.0f, 0.0f, Curve::Stepped, 5, ""},
        {"bend", 0.0f, 12.0f, 2.0f, Curve::Linear, 0, "st"},
        {"velocity", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        // Each section's players, and how they differ: pitch drift, vibrato depth and rate, and how late each comes in.
        {"players", 1.0f, 16.0f, 1.0f, Curve::Stepped, 16, ""},
        {"drift", 0.0f, 25.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"vibrato", 0.0f, 60.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"vibrate", 2.0f, 9.0f, 5.5f, Curve::Exponential, 0, "Hz"},
        {"scatter", 0.0f, 80.0f, 0.0f, Curve::Linear, 0, "ms"},
    };
    static ParamDef defs[Count];
    static char names[kMacros * MacroParams][12];
    static bool built = false;
    if (!built) {
        for (int32_t i = 0; i < MacroBase; ++i) defs[i] = first[i];
        auto put = [&](int32_t i, const char *n, float mn, float mx, float df, Curve c, int32_t steps) {
            char *name = names[i - MacroBase];
            std::snprintf(name, 12, "%s", n);
            defs[i] = ParamDef{name, mn, mx, df, c, steps, ""};
        };
        for (int k = 0; k < kMacros; ++k) {
            char n[12];
            const int32_t b = MacroBase + k * MacroParams;
            std::snprintf(n, sizeof(n), "mac%d", k + 1);
            put(b, n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0);
            for (int r = 0; r < 4; ++r) {
                std::snprintf(n, sizeof(n), "mac%d_d%d", k + 1, r + 1);
                put(b + 1 + r, n, 0.0f, static_cast<float>(MacroDestCount - 1), 0.0f, Curve::Stepped, MacroDestCount);
                std::snprintf(n, sizeof(n), "mac%d_a%d", k + 1, r + 1);
                put(b + 5 + r, n, -1.0f, 1.0f, 0.0f, Curve::Linear, 0);
            }
        }
        built = true;
    }
    count = Count;
    return defs;
}

namespace {
// The macro destination each param answers to, or -1 for none.
struct DestMap {
    int8_t of[Divisi::Count];
    DestMap() {
        using D = Divisi;
        for (auto &d : of) d = -1;
        const int32_t pairs[][2] = {
            {D::Violin, D::McViolin}, {D::Viola, D::McViola}, {D::Cello, D::McCello},
            {D::Contrabass, D::McContrabass}, {D::Choir, D::McChoir}, {D::Vowel, D::McVowel},
            {D::Brass, D::McBrass}, {D::Sweep, D::McSweep}, {D::BrassDecay, D::McBrassDecay}, {D::Blat, D::McBlat},
            {D::Tone, D::McTone}, {D::Attack, D::McAttack}, {D::Release, D::McRelease},
            {D::Ensemble, D::McEnsemble}, {D::Speed, D::McSpeed}, {D::Phaser, D::McPhaser},
            {D::Rate, D::McRate}, {D::Feedback, D::McFeedback},
            {D::VelocityAmount, D::McVelocity}, {D::Volume, D::McVolume},
        };
        for (const auto &pr : pairs) of[pr[0]] = static_cast<int8_t>(pr[1]);
    }
};
const DestMap &destMap() {
    static const DestMap m;
    return m;
}
} // namespace

float Divisi::val(int32_t p, bool goal) const {
    const float v = read(p, goal);
    const int d = destMap().of[p];
    const float off = d < 0 ? 0.0f : (goal ? macGoal_ : mac_)[d];
    if (off == 0.0f) return v;
    const ParamDef &def = params_.def(p);
    return def.map(def.unmap(v) + off);
}

void Divisi::updateMacroGoals() {
    for (float &m : macGoal_) m = 0.0f;
    for (int k = 0; k < kMacros; ++k) {
        const int32_t b = MacroBase + k * MacroParams;
        const float knob = read(b, true);
        if (knob == 0.0f) continue;
        for (int r = 0; r < 4; ++r) {
            const int dest = static_cast<int>(read(b + 1 + r, true) + 0.5f);
            if (dest > McOff && dest < MacroDestCount) macGoal_[dest] += knob * read(b + 5 + r, true);
        }
    }
    // Two octaves either way.
    macGoal_[McPitch] = std::clamp(macGoal_[McPitch], -1.0f, 1.0f);
}

void Divisi::updateMacros() {
    updateMacroGoals();
    for (float &m : mac_) m = 0.0f;
    for (int k = 0; k < kMacros; ++k) {
        const int32_t b = MacroBase + k * MacroParams;
        const float knob = read(b, false);
        if (knob == 0.0f) continue;
        for (int r = 0; r < 4; ++r) {
            // The routes' destinations are steps, so their targets.
            const int dest = static_cast<int>(read(b + 1 + r, true) + 0.5f);
            if (dest > McOff && dest < MacroDestCount) mac_[dest] += knob * read(b + 5 + r, false);
        }
    }
    mac_[McPitch] = std::clamp(mac_[McPitch], -1.0f, 1.0f);
}

void Divisi::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    for (dsp::Svf *f : {&violinHigh, &violinLow, &violaHigh, &violaLow, &celloHigh, &celloLow, &bassLow,
                        &choirHigh, &choirLow, &formant[0], &formant[1], &formant[2], &brassLow}) {
        f->setSampleRate(sampleRate);
    }
    // The middle delay and both LFOs at their widest and fastest, with room.
    const int32_t ensembleLength = static_cast<int32_t>((kLineMs + 2.0f * (kSlowMs + kFastMs)) * 0.001f * sampleRate) + 8;
    line.prepare(ensembleLength);
    lineR.prepare(ensembleLength);
    lineSize = 1;
    while (static_cast<float>(lineSize) < kPlayerLine * sampleRate + 4.0f) lineSize <<= 1;
    lineMask = lineSize - 1;
    playerLines.assign(static_cast<size_t>(lineSize * 2), 0.0f);
    for (auto &row : compCache) for (float &c : row) c = 0.0f;
    compRef = choirPower((kVowelRows - 1) / 2, kCompNote);
    reset();
}

void Divisi::clearState() {
    for (dsp::Svf *f : {&violinHigh, &violinLow, &violaHigh, &violaLow, &celloHigh, &celloLow, &bassLow,
                        &choirHigh, &choirLow, &formant[0], &formant[1], &formant[2], &brassLow}) {
        f->reset();
    }
    line.clear();
    lineR.clear();
    std::fill(playerLines.begin(), playerLines.end(), 0.0f);
    lineWrite = lineQuiet = 0;
    snapPlayers = true;
    for (auto &side : apState) for (float &s : side) s = 0.0f;
    apBack[0] = apBack[1] = 0.0f;
    sharedBright = Bright();
    sharedDrive = 1.0f;
    shared = Env();
}

void Divisi::reset() {
    for (Key &k : keys) k = Key();
    clearState();
    for (float &m : mac_) m = 0.0f;
    for (float &m : macGoal_) m = 0.0f;
    bend = channelPressure_ = 0.0f;
    // The dividers start spread, as free-running oscillators would be.
    uint32_t seed = 0x9e3779b9u;
    for (int pc = 0; pc < 12; ++pc) {
        seed = seed * 1664525u + 1013904223u;
        topPhase[pc] = seed;
        topStep[pc] = 0;
        topHz[pc] = 0.0f;
    }
    slowPhase = 0.0f;
    fastPhase = 0.0f;
    phaserPhase = 0.0f;
    apCoef[0] = apCoef[1] = 0.0f;
    brassCountdown = phaserCountdown = 0;
    clock = 0;
    quietSamples = 0;
    asleep = true;
    seatPlayers();
}

void Divisi::seatPlayers() {
    for (int p = 0; p < kMaxPlayers; ++p) {
        Player &pl = players_[p];
        pl = Player();
        pl.rng = 0x2545f491u * static_cast<uint32_t>(p + 1) + 0x9e3779b9u;
        for (int i = 0; i < 3; ++i) nextRand(pl.rng);
        pl.target = nextRand(pl.rng) + nextRand(pl.rng) - 1.0f;
        pl.y1 = pl.y2 = pl.target;
        pl.nextPick = static_cast<int32_t>(nextRand(pl.rng) * kPickSpan * sampleRate);
        pl.vibPhase = nextRand(pl.rng);
        pl.vibRate = 1.0f + kVibSpread * (2.0f * nextRand(pl.rng) - 1.0f);
        pl.seat = p == 0 ? 0.0f : nextRand(pl.rng);
        pl.order = nextRand(pl.rng);
    }
    // The first player alone sits in the middle at full.
    players_[0].mid = 1.0f;
    dealRng = 0x51ed270bu;
    seatedFor = 0;
}

int Divisi::activeKeys() const {
    int n = 0;
    for (const Key &k : keys) n += k.used ? 1 : 0;
    return n;
}

int Divisi::ownChains() const {
    int n = 0;
    for (const Key &k : keys) n += (k.used && k.own) ? 1 : 0;
    return n;
}

int Divisi::heldCount() const {
    int n = 0;
    for (const Key &k : keys) n += (k.used && k.held) ? 1 : 0;
    return n;
}

Divisi::Key *Divisi::keyFor(uint8_t note) {
    for (Key &k : keys) {
        if (k.used && k.note == note) return &k;
    }
    for (Key &k : keys) {
        if (!k.used) return &k;
    }
    // The quietest let-go key, then the oldest held one.
    Key *pick = nullptr;
    for (Key &k : keys) {
        if (!k.held && (pick == nullptr || k.level < pick->level)) pick = &k;
    }
    if (pick != nullptr) return pick;
    pick = &keys[0];
    for (Key &k : keys) {
        if (k.age < pick->age) pick = &k;
    }
    return pick;
}

float Divisi::offset() const {
    return paramOf(Tune) / 100.0f + bend * paramOf(BendRange) + mac_[McPitch] * 24.0f;
}

void Divisi::formantsAt(float vowel, float hz[3], float k[3], float level[3]) {
    const float at = clampf(vowel, 0.0f, 1.0f) * 4.0f;
    const int i0 = std::min(static_cast<int>(at), 3);
    const float t = at - static_cast<float>(i0);
    const VowelShape &a = kVowels[i0], &b = kVowels[i0 + 1];
    for (int f = 0; f < 3; ++f) {
        hz[f] = a.hz[f] * std::pow(b.hz[f] / a.hz[f], t);
        const float q = clampf(hz[f] / (kFormantWidth + kFormantSpread * hz[f]), 1.0f, 20.0f);
        k[f] = 1.0f / q;
        level[f] = a.level[f] + (b.level[f] - a.level[f]) * t;
    }
}

float Divisi::choirPower(int row, int note) const {
    // The digital response of each Svf, from the bilinear transform: at
    // frequency f, x = tan(pi f / rate) / g.
    float hz[3], k[3], level[3];
    formantsAt(static_cast<float>(row) / static_cast<float>(kVowelRows - 1), hz, k, level);
    const float top = 0.45f * sampleRate;
    auto gOf = [&](float fc) { return std::tan(dsp::kPi * clampf(fc, 20.0f, top) / sampleRate); };
    float g[3];
    for (int j = 0; j < 3; ++j) g[j] = gOf(hz[j]);
    const float gHigh = gOf(kChoirHigh), gLow = gOf(kChoirLow);
    // Equal-tempered, so the cache holds under any tuning; a tuning moves a note too little to matter here.
    const float f8 = 440.0f * std::exp2((static_cast<float>(note) - 69.0f) / 12.0f);
    const float half = 0.5f * f8, end = std::fmin(kCompTop, top);
    double power = 0.0;
    for (int m = 1; half * static_cast<float>(m) < end; ++m) {
        // Harmonic m of the 16': odd ones are the 16' square's, even ones the
        // 8' square's and saw's, which share their phase.
        float amp;
        if (m & 1) {
            amp = kChoirSq16 * 2.0f / static_cast<float>(m);
        } else {
            const int n = m / 2;
            amp = ((n & 1) ? kChoirSq8 * 2.0f / static_cast<float>(n) : 0.0f) + kChoirSaw8 / static_cast<float>(n);
        }
        const float f = half * static_cast<float>(m);
        const float w = std::tan(dsp::kPi * f / sampleRate);
        Cx sum{0.0f, 0.0f};
        for (int j = 0; j < 3; ++j) {
            const float x = w / g[j];
            const Cx bp = divide({0.0f, x}, {1.0f - x * x, k[j] * x});
            sum.re += level[j] * k[j] * bp.re;
            sum.im += level[j] * k[j] * bp.im;
        }
        const float xh = w / gHigh, xl = w / gLow;
        const Cx hp = divide({-xh * xh, 0.0f}, {1.0f - xh * xh, 2.0f * xh});
        const Cx lp = divide({1.0f, 0.0f}, {1.0f - xl * xl, 2.0f * xl});
        const Cx h = mul(mul(sum, hp), lp);
        // As the ear weights it: about 4 dB more above 1.5 kHz.
        const float r = (f / 1500.0f) * (f / 1500.0f);
        const float ear = (1.0f + 2.5f * r) / (1.0f + r);
        power += static_cast<double>(amp * amp * (h.re * h.re + h.im * h.im) * ear);
    }
    return static_cast<float>(power);
}

float Divisi::compFor(float vowel, int note) {
    note = std::clamp(note, 0, 127);
    const float at = clampf(vowel, 0.0f, 1.0f) * static_cast<float>(kVowelRows - 1);
    const int r0 = std::min(static_cast<int>(at), kVowelRows - 2);
    const float t = at - static_cast<float>(r0);
    float c[2];
    for (int i = 0; i < 2; ++i) {
        float &cell = compCache[r0 + i][note];
        if (cell == 0.0f) {
            const float p = choirPower(r0 + i, note);
            cell = clampf(std::sqrt(compRef / std::fmax(p, 1e-12f)), 1.0f / kCompMax, kCompMax);
        }
        c[i] = cell;
    }
    return c[0] + (c[1] - c[0]) * t;
}

void Divisi::tuneChains() {
    const float off = offset();
    const double toStep = static_cast<double>(kTwoTo32) / static_cast<double>(sampleRate);
    // The dividers' lowest stage: the 32' of MIDI's lowest octave, tuned from
    // the middle octave's notes.
    for (int pc = 0; pc < 12; ++pc) {
        topHz[pc] = noteHz(60.0f + static_cast<float>(pc) + off) / 128.0f;
        topStep[pc] = static_cast<uint32_t>(std::fmin(static_cast<double>(topHz[pc]) * toStep, 2147483647.0));
    }
    for (Key &k : keys) {
        if (!k.used) continue;
        // The 8' the key wants against the 8' its divider gives.
        const float wanted = noteHz(static_cast<float>(k.sounding) + off + k.noteBend);
        const float divided = topHz[k.pc] * static_cast<float>(1 << k.octave) * 4.0f;
        if (!k.own && std::fabs(wanted / divided - 1.0f) > 1e-5f) {
            // Its own chain from here, starting where the shared one is.
            k.own = true;
            k.ownPhase = topPhase[k.pc] << k.octave;
        }
        if (k.own) k.ownStep = static_cast<uint32_t>(std::fmin(static_cast<double>(wanted) * 0.25 * toStep, 2147483647.0));
    }
}

void Divisi::noteOn(uint8_t note, uint8_t velocity) {
    updateMacroGoals(); // a note reads where the knobs are going, so renders repeat
    const bool poly = steppedTargetOf(Mode) == Poly;
    const bool first = heldCount() == 0;
    if (activeKeys() == 0) {
        // A note from silence: the players come in in a new order, at once,
        // since their lines hold nothing.
        const float scatter = clampf(goalOf(Scatter), 0.0f, 80.0f) * 0.001f * sampleRate;
        for (int p = 1; p < kMaxPlayers; ++p) {
            players_[p].seat = nextRand(dealRng);
            players_[p].seatDelay = scatter * players_[p].seat;
        }
    }
    Key *k = keyFor(note);
    const bool fresh = !k->used || k->note != note;
    if (!poly && first) {
        // Paraphonic, from no keys down: whatever is still ringing from the
        // last chord lets go, and the new chord swells from where the
        // envelope is.
        for (Key &o : keys) {
            if (&o != k && o.used) o.gateOn = false;
        }
    }
    if (!poly && first) sharedBright.start();
    if (fresh) {
        *k = Key();
        k->note = note;
        k->sounding = std::clamp(static_cast<int>(note) + 12 * steppedTargetOf(Octave), 0, 127);
        k->pc = k->sounding % 12;
        k->octave = k->sounding / 12;
    }
    const float vel = static_cast<float>(velocity) / 127.0f;
    k->gain = kHouse * velocityGain(vel, goalOf(VelocityAmount));
    k->vel = vel;
    // The brass scoops up into every note; a poly key opens its own filter.
    k->scoop = -kScoopMax * clampf(goalOf(Blat), 0.0f, 1.0f);
    if (poly) k->bright.start();
    k->held = true;
    k->gateOn = true;
    k->env.open = true;
    k->noteBend = 0.0f;
    k->pressure = -1.0f;
    k->used = true;
    k->age = ++clock;
    shared.open = true;
    asleep = false;
    quietSamples = 0;
}

void Divisi::noteOff(uint8_t note) {
    const bool poly = steppedTargetOf(Mode) == Poly;
    bool released = false;
    for (Key &k : keys) {
        if (k.used && k.held && k.note == note) {
            k.held = false;
            k.env.open = false;
            released = true;
        }
    }
    if (!released) return;
    if (heldCount() == 0) {
        // The last key up: the envelope lets the chord go as it is.
        shared.open = false;
    } else if (!poly) {
        for (Key &k : keys) {
            if (k.used && !k.held && k.note == note) k.gateOn = false;
        }
    }
}

void Divisi::allNotesOff() {
    for (Key &k : keys) {
        k.held = false;
        k.env.open = false;
    }
    shared.open = false;
}

void Divisi::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }

void Divisi::channelPressure(uint8_t value) { channelPressure_ = static_cast<float>(value) / 127.0f; }

void Divisi::noteBend(uint8_t note, float semitones) {
    for (Key &k : keys) {
        if (k.used && k.held && k.note == note) k.noteBend = semitones;
    }
}

void Divisi::notePressure(uint8_t note, uint8_t value) {
    for (Key &k : keys) {
        if (k.used && k.held && k.note == note) k.pressure = static_cast<float>(value) / 127.0f;
    }
}

namespace {
/** One over the overshoot's peak, the most its slow half less its fast half reaches. */
float overshootNorm() {
    const float t = kOverFast * kOverSlow / (kOverSlow - kOverFast) * std::log(kOverSlow / kOverFast);
    return 1.0f / (std::exp(-t / kOverSlow) - std::exp(-t / kOverFast));
}
} // namespace

float Divisi::stepBright(Bright &b, float target, float level, const BrightCoefs &c) {
    b.y = dsp::undenormal(b.y + (target - b.y) * (target > b.y ? c.up : c.down));
    b.fast *= c.fast;
    b.slow *= c.slow;
    if (b.slow < 1e-6f) b.fast = b.slow = 0.0f;
    // The fall runs as the note sounds, so a slow swell doesn't spend it.
    b.settle = dsp::undenormal(b.settle - b.settle * c.settle * level);
    return b.y * (kBrassFloor + (1.0f - kBrassFloor) * b.settle) + c.over * (b.slow - b.fast) * target;
}

void Divisi::runPlayers(const float *mid, const float *wide, float *outL, float *outR, int32_t n) {
    float *const bufM = playerLines.data();
    float *const bufW = bufM + lineSize;
    // Lines that have heard only silence for their whole length are all
    // zeros: nothing to write or read.
    bool silent = true;
    for (int32_t i = 0; i < n && silent; ++i) silent = mid[i] == 0.0f && wide[i] == 0.0f;
    lineQuiet = silent ? std::min(lineQuiet + n, lineSize + 1) : 0;
    const bool empty = lineQuiet > lineSize;
    if (!empty) {
        for (int32_t i = 0; i < n; ++i) {
            const int32_t w = (lineWrite + i) & lineMask;
            bufM[w] = mid[i];
            bufW[w] = wide[i];
        }
    }

    const float dt = static_cast<float>(n) / sampleRate;
    const float walk = 1.0f - std::exp(-dt / kWalkTime);
    const float glide = 1.0f - std::exp(-dt / kGainTime);
    // The walk's delay stays within this either way, seconds, so it reads from the middle of its reach.
    const float bound = kDriftHold * driftRatio;
    const float seatStep = kSeatGlide * static_cast<float>(n);
    const float top = static_cast<float>(lineSize - 2);
    const float perSample = 1.0f / static_cast<float>(n);
    for (int p = 0; p < kMaxPlayers; ++p) {
        Player &pl = players_[p];
        // Every player walks whether it plays or not, so adding one doesn't move the others.
        pl.nextPick -= n;
        if (pl.nextPick <= 0) {
            pl.target = nextRand(pl.rng) + nextRand(pl.rng) - 1.0f;
            pl.nextPick += static_cast<int32_t>((kPickMin + kPickSpan * nextRand(pl.rng)) * sampleRate);
        }
        pl.y1 += (pl.target - pl.y1) * walk;
        pl.y2 += (pl.y1 - pl.y2) * walk;
        // The delay's speed is the pitch's departure, pulled back to the middle.
        pl.x = clampf(pl.x + (driftRatio * pl.y2 - pl.x / kDriftHold) * dt, -bound, bound);
        const float vibF = vibHz * pl.vibRate;
        pl.vibPhase += vibF * dt;
        pl.vibPhase -= std::floor(pl.vibPhase);
        const float vibDelay = vibRatio / (2.0f * dsp::kPi * vibF) * (1.0f + lfoSin(pl.vibPhase));
        const float seatGoal = scatterSamples * pl.seat;
        pl.seatDelay = pl.seatDelay < seatGoal ? std::fmin(seatGoal, pl.seatDelay + seatStep)
                                               : std::fmax(seatGoal, pl.seatDelay - seatStep);
        const float d1 = clampf(pl.seatDelay + (pl.x + bound + vibDelay) * sampleRate, 0.0f, top);
        const float d0 = snapPlayers ? d1 : pl.delay;
        pl.delay = d1;

        const float tM = p < playerCount ? playerNorm : 0.0f;
        const float tS = tM * pl.pos;
        const float m0 = snapPlayers ? tM : pl.mid, s0 = snapPlayers ? tS : pl.side;
        pl.mid = std::fabs(tM - m0) < 1e-6f ? tM : m0 + (tM - m0) * glide;
        pl.side = std::fabs(tS - s0) < 1e-6f ? tS : s0 + (tS - s0) * glide;
        if (empty || (m0 == 0.0f && pl.mid == 0.0f)) continue;

        const float dd = (d1 - d0) * perSample;
        const float dm = (pl.mid - m0) * perSample;
        if (s0 == 0.0f && pl.side == 0.0f) {
            // In the middle: the mix alone.
            for (int32_t i = 0; i < n; ++i) {
                const float t = static_cast<float>(i + 1);
                const float d = d0 + dd * t;
                const int32_t whole = static_cast<int32_t>(d);
                const float frac = d - static_cast<float>(whole);
                const int32_t at = lineWrite + i - whole;
                const float a = bufM[at & lineMask], b = bufM[(at - 1) & lineMask];
                const float v = (m0 + dm * t) * (a + frac * (b - a));
                outL[i] += v;
                outR[i] += v;
            }
            continue;
        }
        const float ds = (pl.side - s0) * perSample;
        for (int32_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i + 1);
            const float d = d0 + dd * t;
            const int32_t whole = static_cast<int32_t>(d);
            const float frac = d - static_cast<float>(whole);
            const int32_t at = lineWrite + i - whole, i0 = at & lineMask, i1 = (at - 1) & lineMask;
            const float v = (m0 + dm * t) * (bufM[i0] + frac * (bufM[i1] - bufM[i0]));
            const float w = (s0 + ds * t) * (bufW[i0] + frac * (bufW[i1] - bufW[i0]));
            outL[i] += v - w;
            outR[i] += v + w;
        }
    }
}

bool Divisi::render(float *L, float *R, int32_t frames) {
    params_.tick();
    updateMacros();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    const float speed = std::exp2((clampf(paramOf(Speed), 0.0f, 1.0f) - 0.5f) * 2.0f);
    const float slowStep = kSlowHz * speed / sampleRate, fastStep = kFastHz * speed / sampleRate;
    const float phaserStep = paramOf(Rate) / sampleRate;
    if (asleep) {
        // The dividers and the LFOs keep turning.
        for (int pc = 0; pc < 12; ++pc) topPhase[pc] += topStep[pc] * static_cast<uint32_t>(frames);
        slowPhase += slowStep * static_cast<float>(frames);
        fastPhase += fastStep * static_cast<float>(frames);
        phaserPhase += phaserStep * static_cast<float>(frames);
        slowPhase -= std::floor(slowPhase);
        fastPhase -= std::floor(fastPhase);
        phaserPhase -= std::floor(phaserPhase);
        return true;
    }

    tuneChains();
    const bool poly = steppedTargetOf(Mode) == Poly;
    const float attack = std::fmax(paramOf(Attack), 0.001f);
    const float release = std::fmax(paramOf(Release), 0.005f);
    const float rise = 1.0f / (attack * sampleRate);
    const float fall = std::pow(10.0f, -5.0f / (release * sampleRate));
    const float gateRise = 1.0f / (kGateRise * sampleRate), gateFall = 1.0f / (kGateFall * sampleRate);
    const float sweep = clampf(paramOf(Sweep), 0.0f, 1.0f);

    // The registers' levels and voicings.
    const float tone = std::exp2((clampf(paramOf(Tone), 0.0f, 1.0f) - 0.5f) * 3.0f);
    const float lv[6] = {
        clampf(paramOf(Violin), 0.0f, 1.0f) * kViolinTrim, clampf(paramOf(Viola), 0.0f, 1.0f) * kViolaTrim,
        clampf(paramOf(Cello), 0.0f, 1.0f) * kCelloTrim, clampf(paramOf(Contrabass), 0.0f, 1.0f) * kBassTrim,
        clampf(paramOf(Choir), 0.0f, 1.0f) * kChoirTrim, clampf(paramOf(Brass), 0.0f, 1.0f) * kBrassTrim,
    };
    violinHigh.set(280.0f, 0.0f);
    violinLow.set(5500.0f * tone, 0.2f);
    violaHigh.set(140.0f, 0.0f);
    violaLow.set(2400.0f * tone, 0.15f);
    celloHigh.set(45.0f, 0.0f);
    celloLow.set(1600.0f * tone, 0.15f);
    bassLow.set(450.0f * tone, 0.1f);
    choirHigh.set(kChoirHigh, 0.0f);
    choirLow.set(kChoirLow * tone, 0.0f);
    const float vowel = clampf(paramOf(Vowel), 0.0f, 1.0f);
    float formantLevel[3];
    {
        float hz[3], k[3], level[3];
        formantsAt(vowel, hz, k, level);
        for (int f = 0; f < 3; ++f) {
            formant[f].set(hz[f], (2.0f - k[f]) / 1.96f);
            formantLevel[f] = level[f] * formant[f].bandNorm();
        }
    }
    const float ens = clampf(paramOf(Ensemble), 0.0f, 1.0f);
    const float reach = 0.4f + 0.6f * ens;
    const float msToSamples = 0.001f * sampleRate;
    // The line's middle, a whole number of samples so the dry sound isn't smoothed by the read.
    const float centre = std::round(kLineMs * msToSamples);
    const float phaser = clampf(paramOf(Phaser), 0.0f, 1.0f);
    const float feedback = clampf(paramOf(Feedback), 0.0f, 0.9f);
    const float volume = paramOf(Volume);

    // Each key's pressure as a swell, ramped across the block.
    // And its choir level, ramped the same way; a fresh key starts at its own.
    float swellFrom[kKeys], swellTo[kKeys], compFrom[kKeys], compTo[kKeys];
    const bool choirOn = lv[4] > 0.0f;
    for (int i = 0; i < kKeys; ++i) {
        Key &k = keys[i];
        if (!k.used) continue;
        swellFrom[i] = k.swell;
        k.swell = 1.0f + 0.5f * glidePressure(k.pressed, k.held ? k.pressure : 0.0f, k.held ? channelPressure_ : 0.0f);
        swellTo[i] = k.swell;
        if (choirOn) {
            const float c = compFor(vowel, k.sounding);
            compFrom[i] = k.choirComp > 0.0f ? k.choirComp : c;
            k.choirComp = c;
            compTo[i] = c;
        }
    }
    const float perFrame = 1.0f / static_cast<float>(frames);

    // The brass: its brightness steps, cutoff, scoop and drive.
    const bool brassOn = lv[5] > 0.0f;
    const float blat = clampf(paramOf(Blat), 0.0f, 1.0f);
    const float coefRate = sampleRate / static_cast<float>(kBrassEvery);
    static const float overNorm = overshootNorm();
    BrightCoefs bc;
    bc.up = dsp::onePoleCoeff(kBrightUp + kBrightUpBlat * blat, coefRate);
    bc.down = dsp::onePoleCoeff(kBrightDown, coefRate);
    bc.fast = std::exp(-1.0f / (kOverFast * coefRate));
    bc.slow = std::exp(-1.0f / (kOverSlow * coefRate));
    bc.settle = dsp::onePoleCoeff(paramOf(BrassDecay) / 4.6f, coefRate);
    bc.over = kOverMax * blat * overNorm;
    const float brassBase = kBrassClosed * tone, brassOct = kBrassOctaves * sweep;
    const float brassK = 2.0f - 1.96f * kBrassRes;
    const float brassTop = 0.45f * sampleRate, piOverRate = dsp::kPi / sampleRate;
    const float scoopFall = std::exp(-1.0f / (kScoopTime * sampleRate));
    const float scoopChunk = std::pow(scoopFall, 64.0f);
    // Paraphonic: the loudest key sounding sets how bright the shared filter gets.
    float paraVel = 0.0f;
    for (const Key &k : keys) {
        if (k.used && k.gateOn) paraVel = std::fmax(paraVel, k.vel);
    }
    const float paraVelB = kBrassVelFloor + (1.0f - kBrassVelFloor) * paraVel;

    // The players, and where each sits for this count.
    playerCount = std::clamp(steppedTargetOf(Players), 1, kMaxPlayers);
    if (playerCount != seatedFor) {
        // Their places across a section, evenly spread in each player's own order.
        seatedFor = playerCount;
        for (int p = 0; p < playerCount; ++p) {
            int rank = 0;
            for (int q = 0; q < playerCount; ++q) {
                const float oq = players_[q].order, op = players_[p].order;
                rank += (oq < op || (oq == op && q < p)) ? 1 : 0;
            }
            players_[p].pos = playerCount == 1 ? 0.0f
                : (2.0f * static_cast<float>(rank) + 1.0f) / static_cast<float>(playerCount) - 1.0f;
        }
    }
    {
        const float drift = clampf(paramOf(Drift), 0.0f, 25.0f), vib = clampf(paramOf(Vibrato), 0.0f, 60.0f);
        const float scatterMs = clampf(paramOf(Scatter), 0.0f, 80.0f);
        driftRatio = drift * kPerCent;
        vibRatio = vib * kPerCent;
        vibHz = clampf(paramOf(Vibrate), 2.0f, 9.0f);
        scatterSamples = scatterMs * 0.001f * sampleRate;
        // Players reading the same place add as one louder sound, by
        // amplitude; spread out, they add by power. In between, between.
        const float a = kSpreadScatter * scatterMs, b = kSpreadDrift * drift, c = kSpreadVibrato * vib * 5.5f / vibHz;
        const float together = std::exp(-(a * a + b * b + c * c) / (kSpreadLoose * kSpreadLoose));
        if (playerCount == 1) {
            playerNorm = 1.0f;
        } else {
            const float count = static_cast<float>(playerCount);
            const float sides = (count * count - 1.0f) / (3.0f * count * count); // the mean of a place squared
            playerNorm = std::pow(count, -(0.5f + 0.5f * together)) / std::sqrt(1.0f + kWideShare * sides);
        }
    }

    float outPeak = 0.0f;
    constexpr int32_t kChunk = 64;
    float mid[kChunk], wide[kChunk], outL[kChunk], outR[kChunk], envAt[kChunk];
    float bus4[kChunk], bus8[kChunk], bus16[kChunk], bus32[kChunk], busSq[kChunk], busBr[kChunk];
    for (int32_t at = 0; at < frames; at += kChunk) {
        const int32_t n = std::min(kChunk, frames - at);
        for (int32_t i = 0; i < n; ++i) bus4[i] = bus8[i] = bus16[i] = bus32[i] = busSq[i] = busBr[i] = 0.0f;

        for (int ki = 0; ki < kKeys; ++ki) {
            Key &k = keys[ki];
            if (!k.used) continue;
            // The key's 32' phase and step; the higher footages are shifts of it.
            const int shift = k.octave;
            uint32_t phase = k.own ? k.ownPhase : (topPhase[k.pc] + topStep[k.pc] * static_cast<uint32_t>(at)) << shift;
            const uint32_t step = k.own ? k.ownStep : topStep[k.pc] << shift;
            // A footage is played while it's under 0.45 of the rate and its step hasn't wrapped.
            const double hz32 = (k.own ? static_cast<double>(k.ownStep) : static_cast<double>(topStep[k.pc]) * static_cast<double>(1 << shift)) *
                                static_cast<double>(sampleRate) / static_cast<double>(kTwoTo32);
            const double limit = 0.45 * static_cast<double>(sampleRate);
            const bool has32 = hz32 < limit, has16 = hz32 * 2.0 < limit, has8 = hz32 * 4.0 < limit, has4 = hz32 * 8.0 < limit;
            float peak = 0.0f;

            // The brass saw's 16' step across the chunk, gliding up out of the scoop.
            const float r0 = k.scoop == 0.0f ? 1.0f : std::exp2(k.scoop / 12.0f);
            k.scoop *= n == kChunk ? scoopChunk : std::pow(scoopFall, static_cast<float>(n));
            if (k.scoop > -1e-4f) k.scoop = 0.0f;
            const float r1 = k.scoop == 0.0f ? 1.0f : std::exp2(k.scoop / 12.0f);
            const bool kb = brassOn && has16;
            const uint32_t step16 = step << 1;
            const bool exact = r0 == 1.0f;
            float bstep = static_cast<float>(step16) * r0;
            const float dstep = static_cast<float>(step16) * (r1 - r0) / static_cast<float>(n);
            const float velB = kBrassVelFloor + (1.0f - kBrassVelFloor) * k.vel;
            const float trackOct = kBrassTrack * static_cast<float>(k.sounding - 60) / 12.0f;
            if (kb && !k.brassOn) {
                // From the divider's own phase, so it starts in step with the strings.
                k.brassOn = true;
                k.brassPhase = phase << 1;
                k.brassLp = BrassLp();
                k.brassCountdown = 0;
            } else if (!kb) {
                k.brassOn = false;
            }
            if (kb && !poly) {
                k.drive = kDriveLow + kDriveSpan * shared.level * velB;
                k.invDrive = 1.0f / k.drive;
            }
            for (int32_t i = 0; i < n; ++i) {
                // Its switch, and its own envelope.
                if (k.gateOn) k.gate = std::fmin(1.0f, k.gate + gateRise);
                else k.gate = std::fmax(0.0f, k.gate - gateFall);
                k.env.step(rise, fall);
                const float swell = swellFrom[ki] + (swellTo[ki] - swellFrom[ki]) * static_cast<float>(at + i + 1) * perFrame;
                const float g = k.gain * swell * k.gate * (poly ? k.env.level : 1.0f);
                if (g > 0.0f) {
                    const float s32 = has32 ? sawOf(phase, step) : 0.0f;
                    const float s16 = has16 ? sawOf(phase << 1, step << 1) : 0.0f;
                    const float s8 = has8 ? sawOf(phase << 2, step << 2) : 0.0f;
                    const float s4 = has4 ? sawOf(phase << 3, step << 3) : 0.0f;
                    bus32[i] += g * s32;
                    bus16[i] += g * s16;
                    bus8[i] += g * s8;
                    bus4[i] += g * s4;
                    // A divider's square is twice its saw less the next saw up.
                    const float sq8 = has4 ? 2.0f * s8 - s4 : 0.0f;
                    const float sq16 = has8 ? 2.0f * s16 - s8 : 0.0f;
                    if (choirOn) {
                        const float comp = compFrom[ki] + (compTo[ki] - compFrom[ki]) * static_cast<float>(at + i + 1) * perFrame;
                        busSq[i] += g * comp * (kChoirSq8 * sq8 + kChoirSq16 * sq16 + kChoirSaw8 * s8);
                    }
                    peak = std::fmax(peak, g);
                }
                if (kb) {
                    if (poly && k.brassCountdown-- <= 0) {
                        // This key's brightness, from its loudness and velocity and the pressure's swell.
                        k.brassCountdown = kBrassEvery - 1;
                        const float lvl = k.env.level * k.gate;
                        const float target = lvl * velB * swell;
                        const float b = stepBright(k.bright, target, lvl, bc);
                        const float fc = std::fmin(brassBase * std::exp2(brassOct * b + trackOct), brassTop);
                        k.brassLp.set(std::tan(fc * piOverRate), brassK);
                        k.drive = kDriveLow + kDriveSpan * std::fmin(target, 1.0f);
                        k.invDrive = 1.0f / k.drive;
                    }
                    const uint32_t bs = exact ? step16 : static_cast<uint32_t>(bstep);
                    bstep += dstep;
                    float x = 0.5f * sawOf(k.brassPhase, bs);
                    if (has8) x += sawOf(k.brassPhase << 1, bs << 1);
                    k.brassPhase += bs;
                    // A soft drive, louder notes harder; a small signal passes at unity.
                    const float y = g * dsp::fastTanh(k.drive * x) * k.invDrive;
                    busBr[i] += poly ? k.brassLp.run(y) : y;
                }
                phase += step;
            }
            if (k.own) k.ownPhase = phase;
            if (at == 0) k.level = peak;
            else k.level = std::fmax(k.level, peak);
        }

        for (int32_t i = 0; i < n; ++i) {
            // The shared brass filter's brightness (paraphonic).
            if (!poly && brassCountdown-- <= 0) {
                brassCountdown = kBrassEvery - 1;
                const float b = stepBright(sharedBright, shared.level * paraVelB, shared.level, bc);
                brassLow.set(brassBase * std::exp2(brassOct * b), kBrassRes);
            }
            const float x8 = bus8[i];
            // The mix, and the same weighted by each section's width.
            float m = 0.0f, w = 0.0f;
            if (lv[0] > 0.0f) {
                const float v = lv[0] * violinLow.lowpass(violinHigh.highpass(x8 + 0.35f * bus4[i]));
                m += v;
                w += kWidth[0] * v;
            } else { violinLow.reset(); violinHigh.reset(); }
            if (lv[1] > 0.0f) {
                const float v = lv[1] * violaLow.lowpass(violaHigh.highpass(x8 + 0.25f * bus16[i]));
                m += v;
                w += kWidth[1] * v;
            } else { violaLow.reset(); violaHigh.reset(); }
            if (lv[2] > 0.0f) {
                const float v = lv[2] * celloLow.lowpass(celloHigh.highpass(bus16[i]));
                m += v;
                w += kWidth[2] * v;
            } else { celloLow.reset(); celloHigh.reset(); }
            if (lv[3] > 0.0f) {
                const float v = lv[3] * bassLow.lowpass(bus32[i]);
                m += v;
                w += kWidth[3] * v;
            } else bassLow.reset();
            if (lv[4] > 0.0f) {
                const float c = choirHigh.highpass(busSq[i]);
                float f = 0.0f;
                for (int j = 0; j < 3; ++j) f += formantLevel[j] * formant[j].bandpass(c);
                const float v = lv[4] * choirLow.lowpass(f);
                m += v;
                w += kWidth[4] * v;
            } else {
                choirHigh.reset();
                choirLow.reset();
                for (dsp::Svf &f : formant) f.reset();
            }
            if (brassOn) {
                const float v = lv[5] * (poly ? busBr[i] : brassLow.lowpass(busBr[i]));
                m += v;
                w += kWidth[5] * v;
            }
            if (!brassOn || poly) brassLow.reset();
            mid[i] = m;
            wide[i] = w;
            outL[i] = outR[i] = 0.0f;
            shared.step(rise, fall);
            envAt[i] = shared.level;
        }

        runPlayers(mid, wide, outL, outR, n);
        lineWrite = (lineWrite + n) & lineMask;
        snapPlayers = false;

        for (int32_t i = 0; i < n; ++i) {
            float inL = outL[i], inR = outR[i];
            if (!poly) {
                inL *= envAt[i];
                inR *= envAt[i];
            }
            inL *= volume;
            inR *= volume;

            // The ensemble: the dry sound is each line's middle, and each side
            // reads two places moved by both LFOs, the right side's moved the
            // opposite way. Mirrored about the middle, the two sides comb a
            // partial by the same amount at every moment, so a narrow sound
            // like the choir doesn't swing from side to side, and their pitch
            // moves cancel in the mono sum.
            line.write(inL);
            lineR.write(inR);
            const float dryL = line.read(centre), dryR = lineR.read(centre);
            float l = dryL, r = dryR;
            if (ens > 0.0f) {
                float wetL = 0.0f, wetR = 0.0f;
                for (int j = 0; j < 2; ++j) {
                    const float third = static_cast<float>(j) / 3.0f;
                    const float move = reach * msToSamples * (kSlowMs * lfoSin(slowPhase + third) + kFastMs * lfoSin(fastPhase + third));
                    const float w = j == 0 ? 0.65f : 0.35f;
                    wetL += w * line.read(centre + move);
                    wetR += w * lineR.read(centre - move);
                }
                l = dryL + ens * (wetL - dryL);
                r = dryR + ens * (wetR - dryR);
            }
            slowPhase += slowStep;
            fastPhase += fastStep;
            if (slowPhase >= 1.0f) slowPhase -= 1.0f;
            if (fastPhase >= 1.0f) fastPhase -= 1.0f;

            // The phaser: four all-passes a side swept by one LFO, the right a quarter turn on.
            if (phaser > 0.0f) {
                if (phaserCountdown-- <= 0) {
                    phaserCountdown = kCoefEvery - 1;
                    for (int s = 0; s < 2; ++s) {
                        const float sweepAt = 0.5f + 0.5f * lfoSin(phaserPhase + 0.25f * static_cast<float>(s));
                        const float hz = kPhaseLow * std::pow(kPhaseHigh / kPhaseLow, sweepAt);
                        const float w = std::tan(dsp::kPi * hz / sampleRate);
                        apCoef[s] = (1.0f - w) / (1.0f + w);
                    }
                }
                float *io[2] = {&l, &r};
                for (int s = 0; s < 2; ++s) {
                    const float a = apCoef[s];
                    float x = *io[s] + feedback * apBack[s];
                    for (float &st : apState[s]) {
                        const float y = st - a * x;
                        st = dsp::undenormal(x + a * y);
                        x = y;
                    }
                    apBack[s] = x;
                    *io[s] += phaser * 0.5f * (x - *io[s]);
                }
            } else {
                for (auto &side : apState) for (float &st : side) st = 0.0f;
                apBack[0] = apBack[1] = 0.0f;
                phaserCountdown = 0;
            }
            phaserPhase += phaserStep;
            if (phaserPhase >= 1.0f) phaserPhase -= 1.0f;

            L[at + i] = l;
            R[at + i] = r;
            outPeak = std::fmax(outPeak, std::fmax(std::fabs(l), std::fabs(r)));
        }
    }
    for (int pc = 0; pc < 12; ++pc) topPhase[pc] += topStep[pc] * static_cast<uint32_t>(frames);

    // Keys let go of: paraphonic ones when their switch has closed or the
    // envelope has finished, poly ones when their own has.
    bool any = false;
    for (Key &k : keys) {
        if (!k.used) continue;
        const bool done = poly ? k.env.idle() : ((!k.gateOn && k.gate == 0.0f) || (!k.held && shared.idle()));
        if (done) k.used = false;
        any = any || k.used;
    }
    if (!any && shared.idle() && outPeak < kSilent) {
        quietSamples += frames;
        if (quietSamples > kSleepAfter) {
            asleep = true;
            clearState();
        }
    } else {
        quietSamples = 0;
    }
    return true;
}

} // namespace acidulous::machine
