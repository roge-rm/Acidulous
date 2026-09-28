#include "Diction.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

using dsp::clampf;
using dsp::kTwoPi;
using namespace diction;

namespace {

/** Hann window, looked up rather than computed for every sample of every grain. */
constexpr int32_t kWindowSize = 1024;
const float *hannTable() {
    static float table[kWindowSize];
    static const bool built = [] {
        for (int32_t i = 0; i < kWindowSize; ++i) {
            table[i] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) / static_cast<float>(kWindowSize - 1));
        }
        return true;
    }();
    (void)built;
    return table;
}

uint32_t nextRandom(uint32_t &s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return s;
}
float bipolar(uint32_t &s) { return static_cast<float>(nextRandom(s) >> 8) * (2.0f / 16777216.0f) - 1.0f; }

} // namespace

Diction::Diction() { initParams(); }

const ParamDef *Diction::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // oo, oh, ah, eh, ee, from the back of the mouth to the front, so
        // sweeping it moves through the vowels in between.
        {"vowel", 0.0f, 4.0f, 2.0f, Curve::Linear, 0, ""},
        // The size of the throat, apart from the pitch. Down is bigger and
        // darker, up is smaller and brighter.
        {"formant", -12.0f, 12.0f, 0.0f, Curve::Linear, 0, "st"},
        {"breath", 0.0f, 1.0f, 0.12f, Curve::Linear, 0, ""},

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
    bank = &builtInBank(sr);
    holdLength = 1e9;
    for (const auto &u : bank->vowels) holdLength = std::min(holdLength, static_cast<double>(u.holdTo - u.holdFrom));
    acc.assign(kAccum, 0.0f);
    amp.setSampleRate(sampleRate);
    hannTable();
    reset();
}

void Diction::reset() {
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
    head = 0.0;
    airHead = 0.0;
    untilGrain = 0.0f;
    accHead = 0;
    std::fill(acc.begin(), acc.end(), 0.0f);
    amp.reset();
}

void Diction::startNote(uint8_t n, uint8_t vel, bool legato) {
    note = n;
    sinceOnset = 0.0f;
    // From a note still sounding, the pitch glides over from where it is.
    // From silence there's nothing to glide from.
    glideFrom = pitch;
    glideDone = sounding && targetOf(Glide) > 0.0005f ? 0.0f : 1.0f;
    if (legato) {
        // Joined to the note before: the glide carries the pitch across and
        // the voice doesn't start again.
        gate = true;
        return;
    }
    velocity = velocityGain(static_cast<float>(vel) / 127.0f, targetOf(VelocityAmount));
    // Arriving from a little below, more for a less steady voice.
    scoop = -0.5f * targetOf(Drift);
    if (sounding) {
        amp.trigger(); // from where the release had got to, so no click
    } else {
        amp.retrigger();
        untilGrain = 0.0f;
        std::fill(acc.begin(), acc.end(), 0.0f);
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
        // Back to the last key still down, as a singer would.
        startNote(held[heldCount - 1], 0, true);
    } else {
        gate = false;
        amp.release();
    }
}

void Diction::allNotesOff() {
    heldCount = 0;
    gate = false;
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

void Diction::addGrain(const float *data, int32_t frames, const audio::Epoch &e, float gain, float targetPeriod,
                       float ratio) {
    const float srcPeriod = clampf(e.period, 2.0f, 2000.0f);
    // A grain holds one glottal pulse: half of it is the source's period (see
    // Molt, where two pulses in a grain sounded an octave down).
    const float half = srcPeriod;
    const auto n = static_cast<int32_t>(2.0f * half / ratio);
    if (n < 2 || n >= kAccum) return;
    // Hann windows sum to one at half overlap. Laid tighter they'd add up, so
    // they're turned down; laid wider they're left alone.
    const float g = gain * clampf(2.0f * targetPeriod / static_cast<float>(n), 0.0f, 1.0f);
    const float *window = hannTable();
    const float wStep = static_cast<float>(kWindowSize - 1) / static_cast<float>(n - 1);
    const float span = static_cast<float>(n - 1) * ratio;
    const float from = clampf(static_cast<float>(e.at) - half, 0.0f,
                              std::max(0.0f, static_cast<float>(frames - 2) - span));
    for (int32_t k = 0; k < n; ++k) {
        const float sp = from + static_cast<float>(k) * ratio;
        const auto i0 = static_cast<int32_t>(sp);
        if (i0 + 1 >= frames) break;
        const float frac = sp - static_cast<float>(i0);
        const float s = data[i0] * (1.0f - frac) + data[i0 + 1] * frac;
        const float w = window[static_cast<int32_t>(static_cast<float>(k) * wStep)];
        acc[static_cast<size_t>((accHead + k) & (kAccum - 1))] += s * w * g;
    }
}

void Diction::layGrain(const Unit &unit, float weight, float targetPeriod, float formantRatio, float breath) {
    const audio::Utterance &u = unit.sound;
    const int32_t idx = u.epochAt(static_cast<float>(unit.holdFrom + head));
    if (idx < 0) return;
    const audio::Epoch &e = u.epochs[static_cast<size_t>(idx)];
    // The high band moves a third as far as the low: a big throat keeps its
    // ring and a small one doesn't turn shrill.
    const float highRatio = std::pow(formantRatio, 0.35f);
    // A breathy voice gives part of its tone to the air.
    const float voiced = weight * (1.0f - 0.5f * breath);
    addGrain(unit.low.data(), u.frames, e, voiced, targetPeriod, formantRatio);
    addGrain(unit.high.data(), u.frames, e, voiced, targetPeriod, highRatio);
    if (breath > 0.001f) {
        // Fresh air every grain, the same length as the voice's grain.
        audio::Epoch at = e;
        at.at = unit.holdFrom + static_cast<int32_t>(airHead);
        addGrain(unit.air.data(), u.frames, at, weight * breath, targetPeriod, formantRatio);
    }
}

float Diction::layGrains(float vowel, float formantRatio, float breath) {
    // --- where the pitch is this period ---------------------------------------
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
    // Vibrato, brought in over a third of a second once its delay has passed.
    const float delay = paramOf(VibratoDelay);
    const float fadeIn = clampf((sinceOnset - delay) / 0.3f, 0.0f, 1.0f);
    const float depth = (paramOf(Vibrato) + modWheel * 60.0f) * 0.01f * fadeIn;
    const float vibrato = depth * std::sin(vibratoPhase);
    // The wander heads for a new small offset every few tenths of a second.
    const float drift = paramOf(Drift);
    // Ten cents of wander at most: enough to sound held by a person, and
    // still in tune.
    const float sung = pitch + vibrato + wander * drift * 0.1f + scoop;
    const float targetPeriod = clampf(sampleRate / noteHz(sung), 8.0f, 2000.0f);

    // --- one grain from each vowel the knob is between -----------------------
    const float v = clampf(vowel, 0.0f, static_cast<float>(kVowels - 1));
    const int32_t lower = std::min(static_cast<int32_t>(v), kVowels - 2);
    const float t = v - static_cast<float>(lower);
    if (t < 0.999f) layGrain(bank->vowels[lower], 1.0f - t, targetPeriod, formantRatio, breath);
    if (t > 0.001f) layGrain(bank->vowels[lower + 1], t, targetPeriod, formantRatio, breath);

    // --- and move the slow things on by one period -------------------------------
    airHead += targetPeriod;
    if (airHead >= holdLength) airHead -= holdLength;
    const float seconds = targetPeriod / sampleRate;
    vibratoPhase += kTwoPi * paramOf(VibratoRate) * seconds;
    if (vibratoPhase > kTwoPi) vibratoPhase -= kTwoPi;
    scoop *= std::exp(-seconds / 0.06f);
    wander += (wanderTarget - wander) * (1.0f - std::exp(-seconds / 0.25f));
    wanderCountdown -= static_cast<int32_t>(targetPeriod);
    if (wanderCountdown <= 0) {
        wanderTarget = bipolar(seed);
        wanderCountdown = static_cast<int32_t>(sampleRate * (0.3f + 0.3f * (bipolar(seed) + 1.0f)));
    }
    return targetPeriod;
}

bool Diction::render(float *L, float *R, int32_t frames) {
    if (!sounding || bank == nullptr) return true; // asleep: nothing held and nothing ringing

    const float vowel = paramOf(Vowel);
    const float formantRatio = std::exp2(paramOf(Formant) / 12.0f);
    const float breath = clampf(paramOf(Breath), 0.0f, 1.0f);
    amp.set(0.0f, paramOf(Attack), 0.001f, 1.0f, paramOf(Release), false);
    const float volume = paramOf(Volume);
    const float pan = paramOf(Pan);
    const float panL = std::cos((pan + 1.0f) * 0.25f * dsp::kPi);
    const float panR = std::sin((pan + 1.0f) * 0.25f * dsp::kPi);

    for (int32_t i = 0; i < frames; ++i) {
        while (untilGrain <= 0.0f) untilGrain += layGrains(vowel, formantRatio, breath);
        untilGrain -= 1.0f;
        head += 1.0;
        if (head >= holdLength) head -= holdLength;

        const float voice = acc[static_cast<size_t>(accHead)];
        acc[static_cast<size_t>(accHead)] = 0.0f;
        accHead = (accHead + 1) & (kAccum - 1);

        const float env = amp.next();
        const float s = voice * env * velocity * (1.0f + pressure * 0.3f) * volume;
        L[i] += s * panL;
        R[i] += s * panR;
        if (!amp.active()) {
            // Done: sleep until the next note, with nothing left in the ring.
            sounding = false;
            std::fill(acc.begin(), acc.end(), 0.0f);
            break;
        }
    }
    sinceOnset += static_cast<float>(frames) / sampleRate;
    return true;
}

} // namespace acidulous::machine
