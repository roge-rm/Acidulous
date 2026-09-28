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
    for (int32_t i = 0; i < count && m < kMaxSteps; ++i) merged[m++] = next[i];
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
        }
    }
    if (wordsDone && !gate) amp.release();

    // --- the formants on their way ------------------------------------------------
    const Step &s = steps[stepIndex];
    float target[3];
    currentTargets(target);
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

void Diction::startNote(uint8_t n, uint8_t vel, bool legato) {
    note = n;
    sinceOnset = 0.0f;
    // From a note still sounding, the pitch glides over from where it is.
    // From silence there's nothing to glide from.
    glideFrom = pitch;
    glideDone = sounding && targetOf(Glide) > 0.0005f ? 0.0f : 1.0f;

    Step planned[kMaxSteps];
    int32_t count = 0;
    if (pendingCount > 0) {
        count = planSyllable(pending, pendingCount, planned, kMaxSteps);
    }
    if (count == 0) {
        Step &s = planned[count++];
        s = Step{};
        s.voice = 1.0f;
        s.length = -1.0f;
        s.glide = 0.05f;
        s.edge = 0.02f;
        s.knob = true;
    }
    pendingCount = 0;

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
        float glottal = g - previousFlow;
        previousFlow = g;
        // A little breath while the folds are open.
        if (phase < 0.56f) glottal += 0.004f * bipolar(noiseSeed);
        glottal *= pulseScale;
        phase += phaseStep;

        // The sources on their way to the step's levels.
        const Step &s = steps[stepIndex];
        const float want[3] = {s.voice, s.air, s.hiss};
        for (int32_t k = 0; k < 3; ++k) {
            const float d = want[k] - level[k];
            level[k] = std::fabs(d) <= std::fabs(levelStep[k]) ? want[k] : level[k] + levelStep[k];
        }
        // A breathy voice gives part of its tone to the air.
        const float voiced = level[0] * (1.0f - 0.5f * breath);
        const float air = level[1] + level[0] * breath;
        const float voice = throat.process(glottal * voiceGain * voiced,
                                           air > 0.0f ? bipolar(noiseSeed) * airGain * air : 0.0f,
                                           level[2] > 0.0f ? bipolar(noiseSeed) * level[2] : 0.0f);

        const float env = amp.next();
        const float out = voice * env * velocity * (1.0f + pressure * 0.3f) * volume;
        L[i] += out * panL;
        R[i] += out * panR;
        if (!amp.active()) {
            // Done: sleep until the next note, with nothing left ringing.
            sounding = false;
            stepCount = 0;
            throat.reset();
            break;
        }
    }
    sinceOnset += static_cast<float>(frames) / sampleRate;
    pendingCount = 0;
    return true;
}

} // namespace acidulous::machine
