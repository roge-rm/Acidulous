#include "Diction.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

using dsp::clampf;
using dsp::kTwoPi;
using namespace diction;

namespace {

uint32_t nextRandom(uint32_t &s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return s;
}
float bipolar(uint32_t &s) { return static_cast<float>(nextRandom(s) >> 8) * (2.0f / 16777216.0f) - 1.0f; }

/** The vowel control's vowels, oo oh ah eh ee: from the back of the mouth to the front. */
constexpr float kKnobVowels[5][3] = {
    {300, 870, 2240}, {520, 900, 2400}, {730, 1090, 2440}, {530, 1840, 2480}, {270, 2290, 3010},
};
/** The fourth and fifth formants, where a voice gathers its ring. Much the same for every sound. */
constexpr float kHigh[2] = {3350, 3800};
constexpr float kHighWidth[2] = {200, 250};
/** Seconds the gains take to follow the levels worked out for them. */
constexpr float kLevelsFollow = 0.003f;
/** Seconds for hiss to stop once its sound is over. */
constexpr float kHissGone = 0.008f;
/** How much of the ring a moved throat loses is put back. All of it overshoots: the formants' skirts overlap. */
constexpr float kRingBack = 0.75f;

/** How wide a formant is where it is: wider the higher it sits, as measured in real voices. */
float widthOf(int32_t k, float hz) {
    switch (k) {
    case 0: return 50.0f + 0.04f * hz;
    case 1: return 75.0f + 0.015f * hz;
    default: return 30.0f + 0.045f * hz;
    }
}

/**
 * The shape the throat takes for [f], with the formant control's move, sung
 * at [hz]. A note above the first formant would miss it and go thin, so the
 * first formant rises to stay above the note, as a soprano opens her mouth
 * wider on a high note, and the formants widen a little with the pitch.
 */
Shape shapeOf(const float f[3], float nasal, float ratio, float hz) {
    Shape s{};
    for (int32_t k = 0; k < kFormants; ++k) {
        const float at = k < 3 ? f[k] : kHigh[k - 3];
        const float bw = k < 3 ? widthOf(k, at) : kHighWidth[k - 3];
        const float moved = shiftFormant(at, ratio);
        s.f[k] = moved;
        s.bw[k] = std::max(bw * moved / at, k < 2 ? 0.3f * hz : 0.0f);
    }
    s.f[0] = std::max(s.f[0], 1.15f * hz);
    s.f[1] = std::max(s.f[1], s.f[0] + 200.0f);
    s.nasal = nasal;
    return s;
}

/**
 * Each of the lower three formants sets the level above it by about the
 * square of how far it moved, so a moved throat's ring is put back by that
 * much, in dB.
 */
float ringFor(const float f[3], const Shape &moved) {
    float db = 0.0f;
    for (int32_t k = 0; k < 3; ++k) db -= 40.0f * std::log10(moved.f[k] / f[k]);
    return kRingBack * db;
}

bool isVowel(Kind k) { return k == Kind::Vowel || k == Kind::Diphthong; }
/** A sound that can be held when a note has no vowel: mmm, sss. */
bool holdable(Kind k) {
    return k == Kind::Nasal || k == Kind::Liquid || k == Kind::Glide || k == Kind::Fricative || k == Kind::Aspirate;
}

} // namespace

Diction::Diction() { initParams(); }

const ParamDef *Diction::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // oo, oh, ah, eh, ee, from the back of the mouth to the front, so
        // sweeping it moves through the vowels in between. What a note with
        // no words sings.
        {"vowel", 0.0f, 4.0f, 2.0f, Curve::Linear, 0, ""},
        // The size of the throat, apart from the pitch. Down is bigger and
        // darker, up is smaller and brighter.
        {"formant", -12.0f, 12.0f, 0.0f, Curve::Linear, 0, "st"},
        {"breath", 0.0f, 1.0f, 0.12f, Curve::Linear, 0, ""},
        // How long the consonants take: half as long to half as long again.
        {"consonants", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // How the words are said: prairie, central or american. The engine
        // doesn't read it; the words are turned into sounds with it before
        // they get here.
        {"accent", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},

        {"vibrato", 0.0f, 100.0f, 30.0f, Curve::Linear, 0, "ct"},
        {"vibratorate", 3.0f, 9.0f, 5.5f, Curve::Linear, 0, "Hz"},
        // A singer holds a note straight for a moment before the vibrato comes in.
        {"vibratodelay", 0.0f, 2.0f, 0.35f, Curve::Linear, 0, "s"},
        // How unsteady the voice is: the wander in a held note and the scoop
        // up into a new one.
        {"drift", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},

        {"glide", 0.0f, 1.0f, 0.08f, Curve::Linear, 0, "s"},
        {"attack", 0.005f, 1.0f, 0.04f, Curve::Exponential, 0, "s"},
        {"release", 0.01f, 2.0f, 0.18f, Curve::Exponential, 0, "s"},
        {"velocity", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},

        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},

        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Diction::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    phones = phoneTable(phoneCount);
    throat.prepare(sampleRate);
    amp.setSampleRate(sampleRate);
    reset();
}

void Diction::reset() {
    pendingCount = 0;
    heldCount = 0;
    note = 0;
    gate = false;
    sounding = false;
    velocity = 1.0f;
    pitch = 0.0f;
    glideFrom = 0.0f;
    glideDone = 1.0f;
    scoop = 0.0f;
    bend = 0.0f;
    pressure = 0.0f;
    modWheel = 0.0f;
    sinceOnset = 0.0f;
    vibratoPhase = 0.0f;
    wander = wanderTarget = 0.0f;
    wanderCountdown = 0;
    seed = 0x2545f491u;
    noiseSeed = 0x51ee7u;
    phase = 1.0f;
    phaseStep = 0.0f;
    strength = 1.0f;
    previousFlow = 0.0f;
    pulseScale = 1.0f;
    sungHz = Throat::kReferenceHz;
    snapLevels = false;
    stepCount = 0;
    stepIndex = 0;
    stepTime = 0.0f;
    letGo = false;
    wordsDone = false;
    for (int32_t k = 0; k < 3; ++k) {
        formants[k] = glideStart[k] = kKnobVowels[2][k];
        level[k] = levelStep[k] = 0.0f;
    }
    nasal = nasalStart = 0.0f;
    voiceGain = airGain = voiceGainTarget = airGainTarget = 1.0f;
    for (float &g : gainsFor) g = -1.0f;
    untilControl = 0;
    sinceLevels = 0;
    throat.reset();
    amp.reset();
    reading = fading = Reader{};
    fade = 1.0f;
    fadeStep = 0.0f;
    untilGrain = 0.0f;
    std::fill(acc.begin(), acc.end(), 0.0f);
    accHead = 0;
    aheadCount = 0;
    earlyCount = 0;
    pitchIn = 0;
    nextNote = 0;
}

void *Diction::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    // What's being read belongs to the old voice.
    reading = fading = Reader{};
    for (Step &s : steps) {
        s.join = nullptr;
        s.unit = nullptr;
        s.once = false;
    }
    fade = 1.0f;
    std::fill(acc.begin(), acc.end(), 0.0f);
    void *old = const_cast<RecordedVoice *>(voice);
    voice = static_cast<const RecordedVoice *>(object);
    return old;
}

// --- a recorded voice ------------------------------------------------------------

namespace {

constexpr int32_t kWindowSize = 1024;

const float *hannTable() {
    static float table[kWindowSize];
    static bool made = false;
    if (!made) {
        for (int32_t i = 0; i < kWindowSize; ++i) {
            table[i] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) / static_cast<float>(kWindowSize - 1));
        }
        made = true;
    }
    return table;
}

/** Seconds to cross from one recorded vowel to the next, at least. */
constexpr float kShortestFade = 0.03f;
/** Seconds a recorded consonant ending the words takes to fade, rather than run on into the vowel after it. */
constexpr float kRecordedGone = 0.03f;
/** How much of its vowel a quick note's word sings before the next word may begin. */
constexpr float kVowelFirst = 0.06f;
/** Seconds to cross into a recorded consonant. */
constexpr float kJoinFade = 0.015f;
/**
 * Seconds of the vowel kept either side of a recorded consonant, where it
 * meets a vowel: the way in and out. More, and the vowel it was sung with
 * was heard instead of the word's.
 */
constexpr float kIntoVowel = 0.025f;
constexpr float kOutOfVowel = 0.025f;
/** And where it meets another consonant. */
constexpr float kIntoConsonant = 0.005f;

} // namespace

void Diction::chooseUnit(const Step &s, const float target[3]) {
    // Only a vowel, or the vowel control, chooses: through a consonant the
    // vowel before it carries on, under the consonant's own level.
    const Kind kind = phones[s.phone < phoneCount ? s.phone : 0].kind;
    if (!s.knob && !isVowel(kind)) return;
    // A diphthong's move is read once through, set up as its step begins.
    if (s.once) return;
    // A vowel is sung as itself; the vowel control and the ends of a
    // diphthong by what they sound nearest to.
    const RecordedVoice::Unit *u = s.unit != nullptr ? s.unit
                                   : !s.knob && kind == Kind::Vowel ? voice->forPhone(s.phone) : nullptr;
    if (u == nullptr) u = voice->nearest(target);
    if (u == nullptr || u == reading.owner) return;
    Reader r;
    r.owner = u;
    r.sound = &u->sound;
    r.gain = u->gain;
    r.from = u->from;
    r.to = u->to;
    r.held = true;
    r.pos = static_cast<float>(u->from);
    startReading(r, std::max(kShortestFade, s.glide));
}

void Diction::startReading(const Reader &r, float seconds) {
    if (reading.sound == nullptr) {
        reading = r;
        fade = 1.0f;
        return;
    }
    fading = reading;
    reading = r;
    fade = 0.0f;
    fadeStep = 1.0f / (std::max(0.005f, seconds) * sampleRate);
}

void Diction::advance(Reader &r) {
    if (r.sound == nullptr) return;
    if (!r.held) {
        // Once through, and on into what was sung after it while it fades.
        const bool inside = r.pos >= static_cast<float>(r.quietFrom) && r.pos < static_cast<float>(r.quietTo);
        r.pos = std::min(r.pos + (inside ? r.speed : 1.0f) * r.rate, static_cast<float>(r.sound->frames - 1));
        return;
    }
    r.pos += r.dir;
    if (r.pos >= static_cast<float>(r.to - 1)) r.dir = -1.0f;
    else if (r.pos <= static_cast<float>(r.from)) r.dir = 1.0f;
}

const audio::Epoch *Diction::epochOf(const Reader &r) {
    if (r.sound == nullptr) return nullptr;
    const int32_t idx = r.sound->epochAt(r.pos);
    return idx < 0 ? nullptr : &r.sound->epochs[static_cast<size_t>(idx)];
}

void Diction::layGrain(const Reader &r, float weight, float period, float ratio) {
    if (r.sound == nullptr) return;
    const audio::Utterance &u = *r.sound;
    const int32_t idx = u.epochAt(r.pos);
    if (idx < 0) return;
    const audio::Epoch &e = u.epochs[static_cast<size_t>(idx)];
    // Half a grain is the source's period, so a grain holds one glottal
    // pulse, read faster or slower to move the formants.
    const float half = clampf(e.period, 2.0f, 2000.0f);
    const auto n = static_cast<int32_t>(2.0f * half / ratio);
    if (n < 2 || n >= kAccum) return;
    // Laid closer than half overlap as the pitch rises, the windows sum to
    // more, but the pulses they hold don't line up, so they don't add up in
    // full: the square root of the windows' correction keeps a scale on one
    // vowel within a few dB of its own note, where the full correction lost
    // five by a fifth up.
    float gain = std::sqrt(clampf(2.0f * period / static_cast<float>(n), 0.0f, 1.0f)) * weight * r.gain;
    if (r.quiet < 1.0f) {
        // Eased in and out over 10 ms either side, so turning it down doesn't click.
        const float ramp = 0.01f * sampleRate;
        const float at = static_cast<float>(e.at);
        const float inside = std::min(clampf((at - static_cast<float>(r.quietFrom) + ramp) / ramp, 0.0f, 1.0f),
                                      clampf((static_cast<float>(r.quietTo) + ramp - at) / ramp, 0.0f, 1.0f));
        gain *= 1.0f + (r.quiet - 1.0f) * inside;
    }
    const float *window = hannTable();
    const float wStep = static_cast<float>(kWindowSize - 1) / static_cast<float>(n - 1);
    const int32_t frames = u.frames;
    const float span = static_cast<float>(n - 1) * ratio;
    const float from = clampf(static_cast<float>(e.at) - half, 0.0f, std::max(0.0f, static_cast<float>(frames - 2) - span));

    // The singer's breath. Each pulse is blended with the one either side,
    // lined up on their own pitch marks: the voice repeats from pulse to
    // pulse and stays, breath doesn't and averages away. On a phone's mic
    // an inch away it lifted the harmonics 4 to 10 dB clear of the noise
    // between them. The breath control keeps as much as it's turned up.
    const float side = e.voiced ? 0.25f * (1.0f - clampf(paramOf(Breath), 0.0f, 1.0f)) : 0.0f;
    float fromPrev = from, fromNext = from;
    auto neighbour = [&](int32_t j, float &start) {
        if (j < 0 || j >= static_cast<int32_t>(u.epochs.size())) return false;
        const audio::Epoch &o = u.epochs[static_cast<size_t>(j)];
        if (!o.voiced || std::fabs(o.period - e.period) > 0.15f * e.period) return false;
        start = static_cast<float>(o.at) - half;
        return start >= 0.0f && start + span < static_cast<float>(frames - 2);
    };
    const bool prev = side > 0.0f && neighbour(idx - 1, fromPrev);
    const bool next = side > 0.0f && neighbour(idx + 1, fromNext);
    auto at = [&](float sp) {
        const auto i0 = static_cast<int32_t>(sp);
        const float frac = sp - static_cast<float>(i0);
        return u.mono[static_cast<size_t>(i0)] * (1.0f - frac) + u.mono[static_cast<size_t>(i0 + 1)] * frac;
    };
    // A one-pole low-pass at about 8 kHz for a hiss measured brighter than
    // heard (RecordedVoice::Join::bright): a few dB off at 10 and above.
    const float soft = r.soften ? 1.0f - std::exp(-kTwoPi * 8000.0f / sampleRate) : 1.0f;
    float lowpassed = 0.0f;
    for (int32_t k = 0; k < n; ++k) {
        const float step = static_cast<float>(k) * ratio;
        const float sp = from + step;
        if (static_cast<int32_t>(sp) + 1 >= frames) break;
        float x = at(sp);
        if (r.soften) x = lowpassed += soft * (x - lowpassed);
        if (side > 0.0f) {
            const float xp = prev ? at(fromPrev + step) : x;
            const float xn = next ? at(fromNext + step) : x;
            x = (1.0f - 2.0f * side) * x + side * (xp + xn);
        }
        acc[static_cast<size_t>((accHead + k) & (kAccum - 1))] += x * window[static_cast<int32_t>(static_cast<float>(k) * wStep)] * gain;
    }
}

// --- words into steps ---------------------------------------------------------

int32_t Diction::planSyllable(const uint8_t *p, int32_t count, Step *out, int32_t capacity) const {
    const float stretch = 0.5f + clampf(targetOf(Consonants), 0.0f, 1.0f);
    auto phoneAt = [&](int32_t i) -> const Phone & {
        const int32_t c = i >= 0 && i < count ? p[i] : 0;
        return phones[c < phoneCount ? c : 0];
    };
    auto named = [&](int32_t i, const char *name) { return i >= 0 && i < count && std::strcmp(phoneAt(i).name, name) == 0; };

    // The one sound held for as long as the note: the last vowel, or with no
    // vowel the last sound that can be held.
    int32_t hold = -1;
    for (int32_t i = count - 1; i >= 0 && hold < 0; --i) if (isVowel(phoneAt(i).kind)) hold = i;
    for (int32_t i = count - 1; i >= 0 && hold < 0; --i) if (holdable(phoneAt(i).kind)) hold = i;

    // Where the formants are going once past [i]: the next vowel, or the one before.
    auto vowelNear = [&](int32_t i, float f[3]) {
        for (int32_t j = i + 1; j < count; ++j) {
            if (isVowel(phoneAt(j).kind)) { std::memcpy(f, phoneAt(j).f, sizeof(float) * 3); return true; }
        }
        for (int32_t j = i - 1; j >= 0; --j) {
            if (isVowel(phoneAt(j).kind)) { std::memcpy(f, phoneAt(j).to, sizeof(float) * 3); return true; }
        }
        std::memcpy(f, phones[0].f, sizeof(float) * 3);
        return false;
    };

    int32_t n = 0;
    auto add = [&](const float f[3], uint8_t code, float voice, float air, float hiss, float nose, float length,
                   float glide, float edge) {
        if (n >= capacity) return;
        Step &s = out[n++];
        std::memcpy(s.f, f, sizeof(s.f));
        s.phone = code;
        s.voice = voice;
        s.air = air;
        s.hiss = hiss;
        s.nasal = nose;
        s.length = length;
        s.glide = glide;
        s.edge = edge;
        s.knob = false;
    };

    // How fast the formants move into the next sound, and the sources fade:
    // set by the sound before it.
    float glideIn = 0.05f, edgeIn = 0.02f;
    for (int32_t i = 0; i < count; ++i) {
        const Phone &ph = phoneAt(i);
        const auto code = static_cast<uint8_t>(p[i]);
        const bool held = i == hold;
        const bool coda = hold >= 0 && i > hold;
        const float length = held ? -1.0f : ph.length * stretch;
        switch (ph.kind) {
        case Kind::Vowel:
        case Kind::Diphthong: {
            // Before an R, caught's vowel is sore's in every accent.
            const bool beforeR = named(i + 1, "R") && (named(i, "AO") || named(i, "OC"));
            const float *f = beforeR ? phones[phoneCode("OR", 2)].f : ph.f;
            if (ph.kind == Kind::Vowel) {
                add(f, code, 1.0f, 0, 0, 0, held ? -1.0f : ph.length, glideIn, edgeIn);
            } else {
                // A diphthong sits on its first vowel and moves to the second at
                // its end: held, it moves when the note is let go.
                add(f, code, 1.0f, 0, 0, 0, held ? -1.0f : 0.09f, glideIn, edgeIn);
                add(ph.to, code, 1.0f, 0, 0, 0, held ? 0.1f : 0.08f, 0.1f, 0.02f);
            }
            glideIn = 0.05f;
            edgeIn = 0.02f;
            break;
        }
        case Kind::Stop: {
            float f[3];
            std::memcpy(f, ph.f, sizeof(f));
            float next[3];
            const bool vowelNext = vowelNear(i, next) && i + 1 < count && isVowel(phoneAt(i + 1).kind);
            if (ph.place == Place::Velum) {
                // Where the tongue meets the palate depends on the vowel: K
                // before ee is further forward than before oo.
                f[1] = clampf(next[1] + 150.0f, 1300.0f, 2300.0f);
                f[2] = std::max(f[1] + 250.0f, 2200.0f);
            }
            // Closed. A voiced stop hums behind its closure.
            add(f, code, ph.voiced ? 0.1f : 0.0f, 0, 0, 0, ph.length * stretch, 0.04f, 0.012f);
            // The burst as it opens: a click of a few milliseconds, softer at
            // the end of a word.
            add(f, code, ph.voiced ? 0.15f : 0.0f, 0, ph.noise * (coda ? 0.6f : 1.0f), 0, 0.004f, 0.0f, 0.0005f);
            const Kind after = i + 1 < count ? phoneAt(i + 1).kind : Kind::Vowel;
            const bool sonorantNext = i + 1 < count && (after == Kind::Liquid || after == Kind::Glide);
            if (!ph.voiced && vowelNext && !named(i - 1, "S")) {
                // Breath before the vowel starts, with the formants already on
                // their way to it. Not after an S: spin's P has none.
                add(next, 0, 0, 0.15f, 0, 0, 0.035f * stretch, 0.05f, 0.003f);
                glideIn = 0.03f;
            } else if (!ph.voiced && sonorantNext && !named(i - 1, "S")) {
                // Before an L, R, W or Y the breath goes through that sound's
                // shape, as in twin and play: the burst runs on into the
                // voice instead of standing alone.
                add(phoneAt(i + 1).f, 0, 0, 0.15f, 0, 0, 0.025f * stretch, 0.03f, 0.003f);
                glideIn = 0.03f;
            } else if (!ph.voiced && coda) {
                add(f, 0, 0, 0.25f, 0, 0, 0.03f, 0.03f, 0.004f);
                glideIn = 0.045f;
            } else {
                glideIn = 0.045f;
            }
            edgeIn = 0.008f;
            break;
        }
        case Kind::Affricate:
            add(ph.f, code, ph.voiced ? 0.1f : 0.0f, 0, 0, 0, 0.04f * stretch, 0.04f, 0.012f);
            add(ph.f, code, ph.voiced ? 0.35f : 0.0f, 0, ph.noise, 0, held ? -1.0f : (ph.length - 0.04f) * stretch,
                0.02f, 0.004f);
            glideIn = 0.04f;
            edgeIn = 0.01f;
            break;
        case Kind::Fricative:
            add(ph.f, code, ph.voiced ? 0.4f : 0.0f, 0, ph.noise, 0, length, 0.04f, 0.015f);
            // F and TH hiss alike; it's the way the vowel moves away from
            // them that tells them apart, so the move is slow enough to hear.
            glideIn = ph.place == Place::Teeth || ph.place == Place::Lips ? 0.07f : 0.04f;
            // The voice comes in as the hiss goes, so the two overlap rather
            // than leaving a gap between them.
            edgeIn = kHissGone;
            break;
        case Kind::Aspirate: {
            // H is breath through the vowel's own shape.
            float next[3];
            vowelNear(i, next);
            add(next, code, 0, 0.6f, 0, 0, length, 0.02f, 0.015f);
            glideIn = 0.03f;
            edgeIn = 0.02f;
            break;
        }
        case Kind::Nasal:
            add(ph.f, code, 0.5f, 0, 0, 1.0f, length, 0.035f, 0.015f);
            glideIn = 0.035f;
            edgeIn = 0.015f;
            break;
        case Kind::Liquid: {
            // An L after the vowel is the dark one: the back of the tongue up.
            static const float kDarkL[3] = {450, 900, 2600};
            const bool dark = coda && named(i, "L");
            const bool l = named(i, "L");
            add(dark ? kDarkL : ph.f, code, 0.8f, 0, 0, 0, length, l ? 0.03f : 0.04f, 0.02f);
            // The tongue lets go of an L quickly; slower, it's a W.
            glideIn = l ? 0.03f : 0.06f;
            edgeIn = 0.02f;
            break;
        }
        case Kind::Glide:
            add(ph.f, code, 0.85f, 0, 0, 0, length, 0.04f, 0.02f);
            glideIn = 0.08f;
            edgeIn = 0.02f;
            break;
        case Kind::Flap:
            // The tongue taps the gum on its way past: a quick, clear dip in
            // the voice. Gentler, it didn't register.
            add(ph.f, code, 0.25f, 0, 0, 0, ph.length * stretch, 0.012f, 0.004f);
            glideIn = 0.025f;
            edgeIn = 0.005f;
            break;
        }
    }
    return n;
}

int32_t Diction::planRecorded(const uint8_t *p, int32_t count, Step *out, int32_t capacity) const {
    const float stretch = 0.5f + clampf(targetOf(Consonants), 0.0f, 1.0f);
    auto phoneAt = [&](int32_t i) -> const Phone & {
        const int32_t c = i >= 0 && i < count ? p[i] : 0;
        return phones[c < phoneCount ? c : 0];
    };
    int32_t hold = -1;
    for (int32_t i = count - 1; i >= 0 && hold < 0; --i) if (isVowel(phoneAt(i).kind)) hold = i;
    for (int32_t i = count - 1; i >= 0 && hold < 0; --i) if (holdable(phoneAt(i).kind)) hold = i;

    int32_t n = 0;
    auto add = [&](const float f[3], uint8_t code, float length, float glide) -> Step * {
        if (n >= capacity) return nullptr;
        Step &s = out[n++];
        s = Step{};
        std::memcpy(s.f, f, sizeof(s.f));
        s.phone = code;
        s.voice = 1.0f;
        s.length = length;
        s.glide = glide;
        s.edge = 0.01f;
        return &s;
    };
    for (int32_t i = 0; i < count; ++i) {
        const Phone &ph = phoneAt(i);
        const auto code = static_cast<uint8_t>(p[i]);
        const bool held = i == hold;
        if (ph.kind == Kind::Vowel) {
            add(ph.f, code, held ? -1.0f : ph.length, 0.04f);
            continue;
        }
        if (ph.kind == Kind::Diphthong) {
            if (const RecordedVoice::Unit *d = voice->diphthong(code)) {
                // As the singer sang it: the first vowel held, then their own
                // move to the second, at its own pace. Made from two vowels,
                // the prairie oh barely moved at all and row lost its w.
                Step *first = add(ph.f, code, held ? -1.0f : 0.09f, 0.04f);
                // On past where the move is marked done (70% of the way) by as
                // much again, into the second vowel. A slow glide, sung all
                // the way through a take, is read faster so the whole fits.
                const int32_t end = std::min(d->sound.frames - 1, d->glideTo + (d->glideTo - d->glideFrom));
                const float sung = static_cast<float>(end - d->glideFrom) / sampleRate;
                const float move = clampf(sung, 0.06f, 0.25f);
                Step *second = add(ph.to, code, move, 0.02f);
                if (first == nullptr || second == nullptr) break;
                first->unit = d;
                second->unit = d;
                second->once = true;
                second->from = d->glideFrom;
                second->to = end;
                second->rate = std::max(1.0f, sung / move);
                continue;
            }
            add(ph.f, code, held ? -1.0f : 0.09f, 0.04f);
            add(ph.to, code, held ? 0.1f : 0.08f, 0.1f);
            continue;
        }
        // A consonant, from between the vowels most like the word's: the next
        // vowel for one before the vowel, the last for one after.
        const Phone *near = nullptr;
        if (hold < 0 || i < hold) {
            for (int32_t j = i + 1; j < count && near == nullptr; ++j) if (isVowel(phoneAt(j).kind)) near = &phoneAt(j);
        }
        for (int32_t j = i - 1; j >= 0 && near == nullptr; --j) if (isVowel(phoneAt(j).kind)) near = &phoneAt(j);
        const float *vowel = near != nullptr ? (i < hold ? near->f : near->to) : kKnobVowels[2];
        const RecordedVoice::Join *join = voice->consonant(code, vowel);
        if (join == nullptr) continue;
        // Some of the vowel either side, where it meets one: the way in and
        // out is the consonant too. Not where it meets another consonant, or
        // the cluster of street would have vowels in it.
        const float into = i > 0 && isVowel(phoneAt(i - 1).kind) ? kOutOfVowel : kIntoConsonant;
        const float outOf = i + 1 < count && isVowel(phoneAt(i + 1).kind) ? kIntoVowel : kIntoConsonant;
        const int32_t frames = join->sound.frames;
        const int32_t from = held ? join->from : std::max(0, join->from - static_cast<int32_t>(into * sampleRate));
        int32_t to = held ? join->to : std::min(frames, join->to + static_cast<int32_t>(outOf * sampleRate));
        // After an S (or F, SH, TH) a stop has no breath after its burst: st,
        // not st-h. Sung alone it has, and the breath made stream rough.
        const bool afterHiss = i > 0 && phoneAt(i - 1).kind == Kind::Fricative;
        if (!held && afterHiss && ph.kind == Kind::Stop && join->burst > join->from) {
            to = std::min(to, join->burst + static_cast<int32_t>(0.012f * sampleRate));
        }
        // A stop ending a word is its closure and burst: sung between two
        // vowels it went on into the next one, and a G after an ee-like vowel
        // opened into a y, so bag came out baj.
        const bool endsWord = hold >= 0 && i > hold && (i + 1 >= count || !isVowel(phoneAt(i + 1).kind));
        if (!held && endsWord && ph.kind == Kind::Stop && join->burst > join->from) {
            to = std::min(to, join->burst + static_cast<int32_t>(0.025f * sampleRate));
        }
        if (to - from < 2) continue;
        // Sung for recording, a consonant is slow and clear, two to four times
        // as long as in a song, and ran each word into the next note. Read
        // faster, down to about the built-in voice's length, and never slower
        // than it was sung. A stop gets half again, for its burst and breath.
        // A W, Y, L or R is a movement, and read five times faster a W's lips
        // were gone before they'd rounded: twinkle came out tyinkle. Those
        // get twice, an R three times (chosen by ear: shorter, it had an L
        // in it), and a nasal twice. A hiss or an H gets twice too, and a
        // voiced one, whose buzz hides part of its hiss, three times: at the
        // built-in length a recorded SH, V or TH kept 20 to 30 ms of its hiss,
        // against the 60 to 125 it was sung with.
        float longer = 1.0f;
        if (ph.kind == Kind::Stop || ph.kind == Kind::Affricate) longer = 1.5f;
        else if (std::strcmp(ph.name, "R") == 0) longer = 3.0f;
        else if (ph.kind == Kind::Glide || ph.kind == Kind::Liquid || ph.kind == Kind::Nasal) longer = 2.0f;
        else if (ph.kind == Kind::Fricative) longer = ph.voiced ? 3.0f : 2.0f;
        else if (ph.kind == Kind::Aspirate) longer = 2.0f;
        const float want = ph.length * stretch * longer * sampleRate;
        const auto sung = static_cast<float>(std::min(join->to, to) - join->from);
        // The last sound of the words, if it's one a singer can hold (an R,
        // L, M, N), isn't hurried: rushed, the R ending "are" never got its
        // third formant down before it faded.
        const bool last = i + 1 == count && hold >= 0 && i > hold;
        const bool sonorant = ph.kind == Kind::Liquid || ph.kind == Kind::Nasal;
        const float speed = held || (last && sonorant) ? 1.0f : std::max(1.0f, sung / std::max(1.0f, want));
        const float played = static_cast<float>(to - from) - sung + sung / speed;
        Step *s = add(vowel, code, held ? -1.0f : played / sampleRate, 0.02f);
        if (s == nullptr) break;
        s->join = join;
        s->from = from;
        s->to = to;
        s->speed = speed;
    }
    return n;
}

void Diction::beginSteps(const Step *next, int32_t count, bool fromSilence) {
    Step merged[kMaxSteps];
    int32_t m = 0;
    if (!fromSilence && stepIndex < stepCount) {
        // What the last note still had to say: the end of a held word, or the
        // rest of one already ending.
        const Step &now = steps[stepIndex];
        int32_t from = stepIndex + 1;
        if (now.length >= 0.0f && !wordsDone) {
            merged[m] = now;
            merged[m].length = std::max(0.005f, now.length - stepTime);
            ++m;
        }
        for (int32_t i = from; i < stepCount && m < kMaxSteps; ++i) merged[m++] = steps[i];
    }
    // The same held sound ending one note and starting the next is one
    // sound, as in twinkle little: sung twice, the L between went through W.
    if (m > 0 && count > 0 && next[0].phone != 0 && next[0].phone == merged[m - 1].phone &&
        next[0].phone < phoneCount) {
        const Kind k = phones[next[0].phone].kind;
        if (k == Kind::Liquid || k == Kind::Nasal || k == Kind::Fricative || k == Kind::Glide) --m;
    }
    newestVowel = -1;
    for (int32_t i = 0; i < count && m < kMaxSteps; ++i) {
        if (newestVowel < 0 && beginsNote(next[i])) newestVowel = m;
        merged[m++] = next[i];
    }
    std::memcpy(steps, merged, sizeof(Step) * static_cast<size_t>(m));
    stepCount = m;
    letGo = false;
    wordsDone = m == 0;
    if (m == 0) return;
    if (fromSilence) {
        // From silence the throat is already in place, and a closed stop has
        // nothing to be quiet after.
        Step &first = steps[0];
        if (first.voice <= 0.1f && first.air == 0.0f && first.hiss == 0.0f && first.length > 0.0f) {
            first.length = std::min(first.length, 0.015f);
        }
        float f[3];
        if (first.knob) knobFormants(f); else std::memcpy(f, first.f, sizeof(f));
        std::memcpy(formants, f, sizeof(f));
        nasal = first.nasal;
    }
    enterStep(0);
    // From silence the levels start where they should be, not easing over
    // from the last note's.
    snapLevels = fromSilence;
}

void Diction::enterStep(int32_t index) {
    stepIndex = index;
    stepTime = 0.0f;
    const Step &s = steps[index];
    std::memcpy(glideStart, formants, sizeof(glideStart));
    nasalStart = nasal;
    const float target[3] = {s.voice, s.air, s.hiss};
    const float samples = std::max(1.0f, s.edge * sampleRate);
    for (int32_t k = 0; k < 3; ++k) levelStep[k] = (target[k] - level[k]) / samples;
    // Hiss stops as the narrowing opens, whatever comes next. Faded over the
    // next sound's time, a T's burst lasted as long as the W after it.
    if (s.hiss < level[2]) levelStep[2] = (s.hiss - level[2]) / std::max(1.0f, std::min(s.edge, kHissGone) * sampleRate);
    if (s.hiss > 0.0f && s.phone > 0 && s.phone < phoneCount) throat.setHiss(phones[s.phone].band);
    for (float &g : gainsFor) g = -1.0f; // work them out for where this step is going
    if (voice != nullptr && s.once && s.unit != nullptr) {
        Reader r;
        r.owner = &s;
        r.sound = &s.unit->sound;
        r.gain = s.unit->gain;
        r.from = s.from;
        r.to = s.to;
        r.held = false;
        r.pos = static_cast<float>(s.from);
        r.rate = s.rate;
        startReading(r, 0.02f);
    }
    if (voice != nullptr && s.join != nullptr) {
        Reader r;
        r.owner = &s;
        r.sound = &s.join->sound;
        r.gain = s.join->gain;
        r.from = s.from;
        r.to = s.to;
        r.held = s.length < 0.0f;
        r.pos = static_cast<float>(s.from);
        r.quietFrom = s.join->from;
        r.quietTo = s.join->to;
        r.quiet = s.join->consonantGain;
        r.speed = s.speed;
        r.rate = s.rate;
        r.soften = s.join->bright;
        startReading(r, kJoinFade * std::min(1.0f, 1.0f / s.rate));
    }
}

void Diction::knobFormants(float f[3]) const {
    const float v = clampf(paramOf(Vowel), 0.0f, 4.0f);
    const int32_t lower = std::min(static_cast<int32_t>(v), 3);
    const float t = v - static_cast<float>(lower);
    for (int32_t k = 0; k < 3; ++k) f[k] = kKnobVowels[lower][k] + (kKnobVowels[lower + 1][k] - kKnobVowels[lower][k]) * t;
}

void Diction::currentTargets(float f[3]) const {
    const Step &s = steps[stepIndex];
    if (s.knob) knobFormants(f); else std::memcpy(f, s.f, sizeof(float) * 3);
}

void Diction::control() {
    const float dt = static_cast<float>(kControl) / sampleRate;
    if (stepCount == 0) return;

    // --- through the words ----------------------------------------------------
    // The first call for a step only sets it up; time moves from the next.
    if (!snapLevels) stepTime += dt;
    const Step &now = steps[stepIndex];
    const bool done = now.length < 0.0f ? letGo && stepTime >= 0.04f : stepTime >= now.length;
    if (done && !wordsDone) {
        if (stepIndex + 1 < stepCount) {
            enterStep(stepIndex + 1);
        } else {
            wordsDone = true;
            // The last sound has had its time. A voice dies away with the
            // release, but hiss and breath stop: left on, a final S hissed for
            // as long as the release.
            const float samples = kHissGone * sampleRate;
            levelStep[1] = -level[1] / samples;
            levelStep[2] = -level[2] / samples;
            steps[stepIndex].air = 0.0f;
            steps[stepIndex].hiss = 0.0f;
            // A recorded consonant goes on into the vowel sung after it: a
            // final T would come out as ta.
            if (steps[stepIndex].join != nullptr) {
                // Faded rather than stopped: cut in 8 ms, a final S ended
                // sharp. An R, L, M or N dies away slower still, as a voice does.
                const Kind k = phones[steps[stepIndex].phone < phoneCount ? steps[stepIndex].phone : 0].kind;
                const float gone = k == Kind::Liquid || k == Kind::Nasal ? 2.0f * kRecordedGone : kRecordedGone;
                levelStep[0] = -level[0] / (gone * sampleRate);
                steps[stepIndex].voice = 0.0f;
            }
        }
    }
    if (wordsDone && !gate) amp.release();

    // --- the formants on their way ------------------------------------------------
    const Step &s = steps[stepIndex];
    float target[3];
    currentTargets(target);
    if (voice != nullptr) chooseUnit(s, target);
    const float t = s.glide > 0.0f ? std::min(1.0f, stepTime / s.glide) : 1.0f;
    const float eased = t * t * (3.0f - 2.0f * t);
    for (int32_t k = 0; k < 3; ++k) formants[k] = glideStart[k] + (target[k] - glideStart[k]) * eased;
    nasal = nasalStart + (s.nasal - nasalStart) * eased;
    const float ratio = std::exp2(paramOf(Formant) / 12.0f);
    const Shape shape = shapeOf(formants, nasal, ratio, sungHz);
    throat.setShape(shape);
    throat.setRing(ringFor(formants, shape));

    // The levels for where the formants are, worked out again only when that
    // moves: on the way from one sound to the next (every few periods, not
    // every one), the vowel control swept, or the formant control turned.
    // Worked out for where they were heading instead, a vowel came in loud
    // from a P, whose shape the same gain makes louder.
    const bool moving = t < 1.0f;
    // A new step, or a note from silence, works them out at once.
    if (moving && !snapLevels && gainsFor[0] >= 0.0f && ++sinceLevels < kLevelsEvery) return followLevels(s, dt);
    sinceLevels = 0;
    const float *at = moving ? formants : target;
    const float key[5] = {at[0], at[1], at[2], ratio, sungHz};
    bool moved = false;
    for (int32_t k = 0; k < 5; ++k) moved |= std::fabs(key[k] - gainsFor[k]) > 0.002f * std::fabs(key[k]);
    if (moved) {
        std::memcpy(gainsFor, key, sizeof(key));
        const Shape heading = shapeOf(at, moving ? nasal : s.nasal, ratio, sungHz);
        throat.levelsFor(heading, ringFor(at, heading), sungHz, voiceGainTarget, airGainTarget);
    }
    followLevels(s, dt);
}

void Diction::followLevels(const Step &s, float dt) {
    // Close behind: the levels are worked out for where the formants are, so
    // lagging them only lets a formant crossing a harmonic through.
    (void)s;
    const float follow = snapLevels ? 1.0f : 1.0f - std::exp(-dt / kLevelsFollow);
    voiceGain += (voiceGainTarget - voiceGain) * follow;
    airGain += (airGainTarget - airGain) * follow;
    snapLevels = false;
}

// --- notes ----------------------------------------------------------------------

void Diction::lyric(const uint8_t *p, int32_t count) {
    pendingCount = std::clamp(count, 0, static_cast<int32_t>(kMaxPhones));
    if (pendingCount > 0) std::memcpy(pending, p, static_cast<size_t>(pendingCount));
}

int32_t Diction::planWords(const uint8_t *p, int32_t count, Step *out) const {
    int32_t n = 0;
    if (count > 0) n = voice != nullptr ? planRecorded(p, count, out, kMaxSteps) : planSyllable(p, count, out, kMaxSteps);
    if (n == 0) {
        Step &s = out[n++];
        s = Step{};
        s.voice = 1.0f;
        s.length = -1.0f;
        s.glide = 0.05f;
        s.edge = 0.02f;
        s.knob = true;
    }
    return n;
}

bool Diction::beginsNote(const Step &s) const {
    return s.knob || s.length < 0.0f || isVowel(phones[s.phone < phoneCount ? s.phone : 0].kind);
}

float Diction::onsetOf(const Step *planned, int32_t count) const {
    float seconds = 0.0f;
    for (int32_t i = 0; i < count && !beginsNote(planned[i]); ++i) seconds += planned[i].length;
    return seconds;
}

bool Diction::keepsLength(const Step &s) const {
    const Kind k = phones[s.phone < phoneCount ? s.phone : 0].kind;
    return k == Kind::Liquid || k == Kind::Glide;
}

void Diction::squeeze(Step &s, float f) {
    if (s.length <= 0.0f) return;
    s.length *= f;
    s.glide *= f;
    s.edge *= f;
    s.rate /= f;
}

float Diction::stillToSay() const {
    if (!sounding || wordsDone || stepCount == 0) return 0.0f;
    float seconds = 0.0f;
    const Step &now = steps[stepIndex];
    if (now.length >= 0.0f) seconds += std::max(0.0f, now.length - stepTime);
    for (int32_t i = stepIndex + 1; i < stepCount; ++i) seconds += std::max(0.0f, steps[i].length);
    return seconds;
}

void Diction::wordsAhead(const uint8_t *p, int32_t count, uint8_t n, uint8_t vel, int32_t inFrames) {
    if (aheadCount == kAhead) return; // the oldest are the ones due first; this one comes on time
    Ahead &a = ahead[aheadCount++];
    a.count = std::clamp(count, 0, static_cast<int32_t>(kMaxPhones));
    std::memcpy(a.phones, p, static_cast<size_t>(a.count));
    a.note = n;
    a.velocity = vel;
    a.in = inFrames;
    Step planned[kMaxSteps];
    a.onset = onsetOf(planned, planWords(a.phones, a.count, planned));
}

bool Diction::readyForNext() const {
    // The newest word's vowel, not the last word's: its end can still be
    // sounding under the newest word's start.
    if (!sounding || wordsDone || stepCount == 0 || newestVowel < 0) return true;
    return stepIndex > newestVowel || (stepIndex == newestVowel && stepTime >= kVowelFirst);
}

void Diction::wordsDue(int32_t frames) {
    for (int32_t i = 0; i < earlyCount; ++i) earlies[i].wait -= frames;
    while (earlyCount > 0 && earlies[0].wait <= 0) {
        // Begun for a note that never came: the transport stopped, or another
        // clip was launched. The one being sung is let go.
        if (earlyCount == 1) {
            gate = false;
            letGo = true;
            amp.release();
        }
        for (int32_t i = 1; i < earlyCount; ++i) earlies[i - 1] = earlies[i];
        --earlyCount;
    }
    if (pitchIn > 0 && (pitchIn -= frames) <= 0) {
        // The last word's said: over to the new note's pitch.
        note = nextNote;
        glideFrom = pitch;
        glideDone = targetOf(Glide) > 0.0005f ? 0.0f : 1.0f;
    }
    // The next word waits for this one to sing a little of its vowel, so a
    // quick note's word is heard at all; after that it has the time it needs,
    // as a singer gives a quick note's vowel only a moment.
    if (aheadCount == 0 || earlyCount == kAhead || !readyForNext()) {
        for (int32_t i = 0; i < aheadCount; ++i) ahead[i].in -= frames;
        return;
    }
    Ahead &a = ahead[0];
    float still = sounding ? stillToSay() : 0.0f;
    const float lead = a.onset + still;
    if (static_cast<float>(a.in) <= lead * sampleRate) {
        Step planned[kMaxSteps];
        const int32_t count = planWords(a.phones, a.count, planned);
        pendingCount = 0;
        // Between quick notes there isn't time for all of it: the last word's
        // end and this one's start are said faster, as a singer does, down to
        // about a third.
        const float time = std::max(0.0f, static_cast<float>(a.in) / sampleRate);
        if (lead > time && lead > 0.0f) {
            // An R, L, W or Y keeps at least 120 ms: squeezed with the rest,
            // stream's R lost its low third formant and came out an L, and
            // kept whole, it made the vowel after it land 200 ms late. The
            // others take up the difference, as far as they can.
            constexpr float kShortestMovement = 0.12f;
            const float even = time / lead;
            auto kept = [&](const Step &s) {
                return s.length > 0.0f ? std::min(1.0f, std::max(even, kShortestMovement / s.length)) : 1.0f;
            };
            float movements = 0.0f, keptLength = 0.0f;
            for (int32_t i = 0; i < count && !beginsNote(planned[i]); ++i) {
                if (keepsLength(planned[i])) { movements += planned[i].length; keptLength += planned[i].length * kept(planned[i]); }
            }
            for (int32_t i = stepIndex + 1; i < stepCount; ++i) {
                if (keepsLength(steps[i]) && steps[i].length > 0.0f) { movements += steps[i].length; keptLength += steps[i].length * kept(steps[i]); }
            }
            const float rest = lead - movements;
            const float f = rest > 0.0f ? clampf((time - keptLength) / rest, 0.35f, 1.0f) : 1.0f;
            for (int32_t i = 0; i < count && !beginsNote(planned[i]); ++i) squeeze(planned[i], keepsLength(planned[i]) ? kept(planned[i]) : f);
            for (int32_t i = stepIndex + 1; i < stepCount; ++i) squeeze(steps[i], keepsLength(steps[i]) ? kept(steps[i]) : f);
            still = stillToSay();
        }
        const bool joined = gate && sounding;
        const uint8_t was = note;
        startPlanned(a.note, a.velocity, gate, planned, count);
        if (joined && still > 0.0f && was != a.note) {
            // Still at the last note's pitch while its word ends.
            nextNote = a.note;
            note = was;
            glideDone = 1.0f;
            pitchIn = static_cast<int32_t>(still * sampleRate);
        }
        earlies[earlyCount++] = Early{a.note, a.in + static_cast<int32_t>(0.1f * sampleRate)};
        for (int32_t i = 1; i < aheadCount; ++i) ahead[i - 1] = ahead[i];
        --aheadCount;
    }
    for (int32_t i = 0; i < aheadCount; ++i) ahead[i].in -= frames;
}

void Diction::startNote(uint8_t n, uint8_t vel, bool legato) {
    Step planned[kMaxSteps];
    const int32_t count = planWords(pending, pendingCount, planned);
    pendingCount = 0;
    startPlanned(n, vel, legato, planned, count);
}

void Diction::startPlanned(uint8_t n, uint8_t vel, bool legato, const Step *planned, int32_t count) {
    note = n;
    sinceOnset = 0.0f;
    // From a note still sounding, the pitch glides over from where it is.
    // From silence there's nothing to glide from.
    glideFrom = pitch;
    glideDone = sounding && targetOf(Glide) > 0.0005f ? 0.0f : 1.0f;

    if (legato) {
        // Joined to the note before: the glide carries the pitch across and
        // the voice doesn't start again, but the words move on.
        gate = true;
        beginSteps(planned, count, false);
        return;
    }
    velocity = velocityGain(static_cast<float>(vel) / 127.0f, targetOf(VelocityAmount));
    // Arriving from a little below, more for a less steady voice.
    scoop = -0.5f * targetOf(Drift);
    if (sounding) {
        amp.trigger(); // from where the release had got to, so no click
        beginSteps(planned, count, false);
    } else {
        amp.retrigger();
        // The throat takes its shape for this note's pitch from the start.
        sungHz = noteHz(targetNote());
        phase = 1.0f;
        previousFlow = 0.0f;
        throat.reset();
        for (float &l : level) l = 0.0f;
        untilControl = 0;
        beginSteps(planned, count, true);
    }
    gate = true;
    sounding = true;
}

void Diction::noteOn(uint8_t n, uint8_t vel) {
    int32_t begun = -1;
    for (int32_t i = 0; i < earlyCount && begun < 0; ++i) if (earlies[i].note == n) begun = i;
    if (begun >= 0) {
        // Begun early for its words: only the key is new.
        for (int32_t i = begun + 1; i < earlyCount; ++i) earlies[i - 1] = earlies[i];
        --earlyCount;
        pendingCount = 0;
        int32_t kept = 0;
        for (int32_t i = 0; i < heldCount; ++i) if (held[i] != n) held[kept++] = held[i];
        heldCount = std::min(kept, kHeld - 1);
        held[heldCount++] = n;
        gate = true;
        return;
    }
    // Words known ahead for this note that never got started: it sings them now, on time.
    if (aheadCount > 0 && ahead[0].note == n) {
        for (int32_t i = 1; i < aheadCount; ++i) ahead[i - 1] = ahead[i];
        --aheadCount;
    }
    // Remembered newest last. A key already held moves to the end.
    int32_t kept = 0;
    for (int32_t i = 0; i < heldCount; ++i) if (held[i] != n) held[kept++] = held[i];
    heldCount = kept;
    if (heldCount == kHeld) {
        for (int32_t i = 1; i < kHeld; ++i) held[i - 1] = held[i];
        --heldCount;
    }
    held[heldCount++] = n;
    startNote(n, vel, gate);
}

void Diction::noteOff(uint8_t n) {
    int32_t kept = 0;
    for (int32_t i = 0; i < heldCount; ++i) if (held[i] != n) held[kept++] = held[i];
    heldCount = kept;
    // A note begun early can't end before it's begun: until it has, this is
    // an older note letting go under the new word.
    if (earlyCount > 0) return;
    if (!gate || n != note) return;
    if (heldCount > 0) {
        // Back to the last key still down, as a singer would, still on the
        // same word.
        note = held[heldCount - 1];
        glideFrom = pitch;
        glideDone = targetOf(Glide) > 0.0005f ? 0.0f : 1.0f;
    } else {
        // The word finishes: the end of a diphthong and the consonants after
        // the vowel, then the release.
        gate = false;
        letGo = true;
        if (wordsDone || stepCount == 0) amp.release();
    }
}

void Diction::allNotesOff() {
    aheadCount = 0;
    earlyCount = 0;
    pitchIn = 0;
    heldCount = 0;
    gate = false;
    letGo = true;
    amp.release();
}

void Diction::controlChange(uint8_t cc, uint8_t value) {
    // The mod wheel adds vibrato on top of the knob's.
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}

void Diction::pitchBend(int16_t value14) {
    bend = static_cast<float>(value14) / 8192.0f * static_cast<float>(steppedOf(BendRange));
}

void Diction::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }

float Diction::targetNote() const {
    return static_cast<float>(note) + static_cast<float>(steppedOf(Transpose)) +
           12.0f * static_cast<float>(steppedOf(Octave)) + bend;
}

float Diction::nextPeriod() {
    // A glide arrives on the note in the glide time and stops there, eased at
    // both ends. A curve that only approached it would leave the note flat or
    // sharp for as long as it lasted.
    const float want = targetNote();
    if (glideDone < 1.0f) {
        const float t = glideDone;
        const float eased = t * t * (3.0f - 2.0f * t);
        pitch = glideFrom + (want - glideFrom) * eased;
        glideDone += (sampleRate / noteHz(pitch)) / (std::max(0.001f, paramOf(Glide)) * sampleRate);
    } else {
        pitch = want;
    }
    sungHz = noteHz(pitch);
    // Vibrato, brought in over a third of a second once its delay has passed.
    const float delay = paramOf(VibratoDelay);
    const float fadeIn = clampf((sinceOnset - delay) / 0.3f, 0.0f, 1.0f);
    const float depth = (paramOf(Vibrato) + modWheel * 60.0f) * 0.01f * fadeIn;
    const float vibrato = depth * std::sin(vibratoPhase);
    // Ten cents of wander at most: enough to sound held by a person, and
    // still in tune.
    const float sung = pitch + vibrato + wander * paramOf(Drift) * 0.1f + scoop;
    const float period = clampf(sampleRate / noteHz(sung), 8.0f, 2000.0f);

    // Move the slow things on by one period.
    const float seconds = period / sampleRate;
    vibratoPhase += kTwoPi * paramOf(VibratoRate) * seconds;
    if (vibratoPhase > kTwoPi) vibratoPhase -= kTwoPi;
    scoop *= std::exp(-seconds / 0.06f);
    wander += (wanderTarget - wander) * (1.0f - std::exp(-seconds / 0.25f));
    wanderCountdown -= static_cast<int32_t>(period);
    if (wanderCountdown <= 0) {
        wanderTarget = bipolar(seed);
        wanderCountdown = static_cast<int32_t>(sampleRate * (0.3f + 0.3f * (bipolar(seed) + 1.0f)));
    }
    return period;
}

bool Diction::render(float *L, float *R, int32_t frames) {
    // The rack's buffer still holds its last block: written through, even
    // asleep. Left, it came round every block as a 750 Hz tone.
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    wordsDue(frames);
    if (!sounding) {
        pendingCount = 0; // words for a note that never came
        return true;      // asleep: nothing held and nothing ringing
    }

    const float breath = clampf(paramOf(Breath), 0.0f, 1.0f);
    amp.set(0.0f, paramOf(Attack), 0.001f, 1.0f, paramOf(Release), false);
    const float volume = paramOf(Volume);
    const float pan = paramOf(Pan);
    const float panL = std::cos((pan + 1.0f) * 0.25f * dsp::kPi);
    const float panR = std::sin((pan + 1.0f) * 0.25f * dsp::kPi);
    const float referencePeriod = sampleRate / Throat::kReferenceHz;

    for (int32_t i = 0; i < frames; ++i) {
        if (--untilControl < 0) {
            control();
            untilControl = kControl - 1;
        }

        float glottal = 0.0f;
        float recorded = 0.0f;
        if (voice != nullptr) {
            // A recorded voice: one of the singer's own pulses a period.
            if (untilGrain <= 0.0f) {
                // A voiced part at the note's pitch; an unvoiced one (an S, the
                // breath of a P) copied at its own rate, since it has no pitch.
                const Reader &lead = fade >= 0.5f || fading.sound == nullptr ? reading : fading;
                const audio::Epoch *e = epochOf(lead);
                const float period = e != nullptr && !e->voiced ? clampf(e->period, 16.0f, 2000.0f) : nextPeriod();
                const float ratio = std::exp2(paramOf(Formant) / 12.0f);
                if (fade < 1.0f) layGrain(fading, 1.0f - fade, period, ratio);
                layGrain(reading, fade, period, ratio);
                untilGrain += period;
            }
            untilGrain -= 1.0f;
            recorded = acc[static_cast<size_t>(accHead)];
            acc[static_cast<size_t>(accHead)] = 0.0f;
            accHead = (accHead + 1) & (kAccum - 1);
            advance(reading);
            if (fade < 1.0f) {
                advance(fading);
                fade = std::min(1.0f, fade + fadeStep);
            }
        } else {
            // The folds: a pulse a period, a little stronger or weaker each time,
            // scaled so every pitch meets the throat at the same level.
            if (phase >= 1.0f) {
                phase -= 1.0f;
                const float period = nextPeriod();
                phaseStep = 1.0f / period;
                strength = 1.0f + 0.03f * bipolar(noiseSeed);
                pulseScale = period / referencePeriod;
            }
            const float g = throat.flow(phase) * strength;
            // The mouth radiates the change in flow, not the flow itself.
            glottal = g - previousFlow;
            previousFlow = g;
            // A little breath while the folds are open.
            if (phase < 0.56f) glottal += 0.004f * bipolar(noiseSeed);
            glottal *= pulseScale;
            phase += phaseStep;
        }

        // The sources on their way to the step's levels.
        const Step &s = steps[stepIndex];
        const float want[3] = {s.voice, s.air, s.hiss};
        for (int32_t k = 0; k < 3; ++k) {
            const float d = want[k] - level[k];
            level[k] = std::fabs(d) <= std::fabs(levelStep[k]) ? want[k] : level[k] + levelStep[k];
        }
        // A breathy voice gives part of its tone to the air. A recorded voice
        // has its own breath, which the grains keep or take away instead.
        const float airy = voice != nullptr ? 0.0f : breath;
        const float voiced = level[0] * (1.0f - 0.5f * airy);
        const float air = level[1] + level[0] * airy;
        const float sung = throat.process(glottal * voiceGain * voiced,
                                          air > 0.0f ? bipolar(noiseSeed) * airGain * air : 0.0f,
                                          level[2] > 0.0f ? bipolar(noiseSeed) * level[2] : 0.0f) +
                           recorded * voiced;

        const float env = amp.next();
        const float out = sung * env * velocity * (1.0f + pressure * 0.3f) * volume;
        L[i] += out * panL;
        R[i] += out * panR;
        if (!amp.active()) {
            // Done: sleep until the next note, with nothing left ringing.
            sounding = false;
            stepCount = 0;
            throat.reset();
            reading = fading = Reader{};
            fade = 1.0f;
            std::fill(acc.begin(), acc.end(), 0.0f);
            untilGrain = 0.0f;
            break;
        }
    }
    sinceOnset += static_cast<float>(frames) / sampleRate;
    pendingCount = 0;
    return true;
}

} // namespace acidulous::machine
