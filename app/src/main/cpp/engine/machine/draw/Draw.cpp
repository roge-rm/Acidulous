#include "Draw.h"
#include <engine/dsp/Math.h>
#include <engine/machine/Voices.h>
#include <engine/machine/draw/DrawTuning.h>
#include <algorithm>
#include <cmath>

namespace acidulous::machine {

using dsp::clampf;
using namespace draw;

namespace {

/** Level for a note at velocity 100 at its kind's pressure, set so the bank sits with the other machines. */
constexpr float kHouse = 0.107f;
/**
 * A reed sounds louder as it's blown harder; the house law sets the level
 * instead, so this much of that growth is taken out (as a power of the
 * pressure against the kind's own).
 */
constexpr float kLoudPower = 0.5f;
/** The breath's own noise against the reed. */
constexpr float kHiss = 0.03f;
/**
 * White noise smoothed by two 4 Hz poles keeps about a tenth of its swing;
 * this brings it back so a kind's wander is the share it says.
 */
constexpr float kWanderScale = 10.0f;
/** A note's other reeds against its first: two reeds never match. */
constexpr float kOtherReed = 0.8f;
/** Below this a voice is silent. */
constexpr float kSilent = 2e-5f;

uint32_t nextRandom(uint32_t &s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return s;
}
float white(uint32_t &s) { return static_cast<float>(nextRandom(s) >> 8) * (2.0f / 16777216.0f) - 1.0f; }

} // namespace

Draw::Draw() { initParams(); }

const ParamDef *Draw::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"model", 0.0f, static_cast<float>(kKinds - 1), static_cast<float>(Accordion), Curve::Stepped, kKinds, ""},
        {"tune", -100.0f, 100.0f, 0.0f, Curve::Linear, 0, "cents"},
        // How hard the reeds are blown: half to twice the kind's own pressure.
        {"pressure", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"attack", 5.0f, 600.0f, 40.0f, Curve::Exponential, 0, "ms"},
        {"release", 5.0f, 800.0f, 60.0f, Curve::Exponential, 0, "ms"},
        // The reeds' rest gap ("set"): closer speaks sooner and brighter, and chokes sooner.
        {"set", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // The size of each reed's cell: its resonance against the reed's.
        {"chamber", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // The air heard: the breath's hiss and the jet's turbulence, from none to twice the kind's own.
        {"air", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"voices", 1.0f, static_cast<float>(kVoices), static_cast<float>(kVoices), Curve::Stepped, kVoices, ""},
        {"velocity", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"bend", 0.0f, 12.0f, 2.0f, Curve::Linear, 0, "st"},
        {"octave", -2.0f, 2.0f, 0.0f, Curve::Stepped, 5, ""},
        {"volume", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        // 0 is auto: the kind's own (an accordion's are two).
        {"reeds", 0.0f, static_cast<float>(kReeds), 0.0f, Curve::Stepped, kReeds + 1, ""},
        // How far a note's reeds are tuned apart: dry at 0, wet at 25 and more.
        {"detune", 0.0f, 40.0f, 15.0f, Curve::Linear, 0, "cents"},
    };
    count = Count;
    return defs;
}

void Draw::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    for (Voice &v : voices) {
        for (FreeReed &r : v.reeds) r.prepare(sampleRate);
    }
    reset();
}

void Draw::reset() {
    uint32_t seed = 0x51f15e5u;
    for (Voice &v : voices) {
        for (FreeReed &r : v.reeds) {
            r.clear();
            r.seed(seed += 0x9e3779b9u);
        }
        v.wander1 = v.wander2 = 0.0f;
        v.used = v.held = false;
        v.blown = v.aim = 0.0f;
        v.pressure = -1.0f;
        v.gain = v.level = 0.0f;
        v.quietBlocks = 0;
    }
    bend = channelPressure_ = 0.0f;
    lowState = 0.0f;
    bodyX1 = bodyX2 = bodyY1 = bodyY2 = 0.0f;
    dcIn = dcOut = 0.0f;
    noise = 0x2545f491u;
    clock = 0;
    quietSamples = 0;
    asleep = true;
}

int Draw::activeVoices() const {
    int n = 0;
    for (const Voice &v : voices) n += v.used ? 1 : 0;
    return n;
}

const FreeReed *Draw::reedFor(uint8_t note) const {
    for (const Voice &v : voices) {
        if (v.used && v.note == note) return &v.reeds[0];
    }
    return nullptr;
}

Draw::Voice *Draw::voiceFor(uint8_t note) {
    const int cap = std::clamp(steppedTargetOf(Voices), 1, kVoices);
    for (int i = 0; i < cap; ++i) {
        if (voices[i].used && voices[i].note == note) return &voices[i];
    }
    for (int i = 0; i < cap; ++i) {
        if (!voices[i].used) return &voices[i];
    }
    // All busy: the one let go longest ago, or failing that the oldest.
    Voice *pick = nullptr;
    for (int i = 0; i < cap; ++i) {
        if (!voices[i].held && (pick == nullptr || voices[i].age < pick->age)) pick = &voices[i];
    }
    if (pick != nullptr) return pick;
    pick = &voices[0];
    for (int i = 0; i < cap; ++i) {
        if (voices[i].age < pick->age) pick = &voices[i];
    }
    return pick;
}

ReedMake Draw::makeFor(int32_t kind) const {
    ReedMake k = kMakes_[kKindVoices[kind].make];
    // The knobs move each kind's make from where it sits: twice or half either way.
    k.set *= std::pow(2.0f, (clampf(targetOf(Set), 0.0f, 1.0f) - 0.5f) * 1.0f);
    k.cell *= std::pow(2.0f, (clampf(targetOf(Chamber), 0.0f, 1.0f) - 0.5f) * 1.5f);
    k.turbulence *= 2.0f * clampf(targetOf(Air), 0.0f, 1.0f);
    return k;
}

void Draw::retune(Voice &v) {
    const float note = v.baseNote + bend * paramOf(BendRange);
    // Each reed is filed to sound its note: the table says how far its own
    // frequency sits from what it plays, between notes in a straight line.
    const float *table = kReedTuning[kKindVoices[v.kind].make];
    const float at = clampf(note, 0.0f, 127.0f);
    const int lo = std::min(126, static_cast<int>(at));
    const float cents = table[lo] + (table[lo + 1] - table[lo]) * (at - static_cast<float>(lo));
    // Free reeds are made from about 27 Hz to 4.5 kHz; keys beyond play the nearest.
    const ReedMake make = makeFor(v.kind);
    // Two reeds sit at 0 and +detune; three at -detune, 0 and +detune, as a French musette.
    const float apart = clampf(targetOf(Detune), 0.0f, 40.0f);
    for (int i = 0; i < v.count; ++i) {
        const float off = v.count == 3 ? apart * static_cast<float>(i - 1) : apart * static_cast<float>(i);
        v.reeds[i].make(clampf(noteHz(note) * std::pow(2.0f, (cents + off) / 1200.0f), 27.5f, 4500.0f), make);
    }
}

float Draw::aimFor(const Voice &v) const {
    const KindVoice &k = kKindVoices[v.kind];
    // The knob sets half to twice the kind's pressure; velocity blows harder
    // as far as the velocity knob lets it; pressure (the note's own, or the
    // channel's) adds up to half again.
    const float knob = std::pow(2.0f, (clampf(targetOf(Pressure), 0.0f, 1.0f) - 0.5f) * 2.0f);
    const float amount = clampf(targetOf(VelocityAmount), 0.0f, 1.0f);
    const float byVelocity = 1.0f - amount * 0.6f * (1.0f - v.velocity);
    const float squeeze = v.pressure >= 0.0f ? v.pressure : channelPressure_;
    return std::fmin(k.most, k.pressure * knob * byVelocity * (1.0f + 0.5f * squeeze));
}

void Draw::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = voiceFor(note);
    const bool sounding = v->used && v->note == note;
    v->kind = std::clamp(steppedTargetOf(Model), 0, kKinds - 1);
    v->note = note;
    v->baseNote = static_cast<float>(note) + 12.0f * static_cast<float>(steppedTargetOf(Octave)) + targetOf(Tune) / 100.0f;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->held = true;
    v->pressure = -1.0f;
    const int knob = steppedTargetOf(Reeds);
    const int count = std::clamp(knob > 0 ? knob : kKindVoices[v->kind].reeds, 1, kReeds);
    if (!sounding || count != v->count) {
        for (FreeReed &r : v->reeds) r.clear();
        v->blown = 0.0f;
    }
    v->count = count;
    // Reeds sounding together share the level.
    v->share = 1.0f / std::sqrt(static_cast<float>(count));
    retune(*v);
    v->aim = aimFor(*v);
    v->struck = std::fmax(v->aim, 1.0f);
    v->squeeze = 1.0f;
    // The level follows the house law; how much louder a harder-blown reed
    // is, is taken out.
    const KindVoice &k = kKindVoices[v->kind];
    v->gain = kHouse * k.level * velocityGain(v->velocity, targetOf(VelocityAmount)) /
              std::pow(std::fmax(v->aim, 1.0f) / k.pressure, kLoudPower);
    v->used = true;
    v->quietBlocks = 0;
    v->age = ++clock;
    asleep = false;
    quietSamples = 0;
}

void Draw::noteOff(uint8_t note) {
    for (Voice &v : voices) {
        if (v.used && v.held && v.note == note) {
            v.held = false;
            v.aim = 0.0f;
        }
    }
}

void Draw::allNotesOff() {
    for (Voice &v : voices) {
        if (v.used && v.held) {
            v.held = false;
            v.aim = 0.0f;
        }
    }
}

void Draw::pitchBend(int16_t value14) {
    bend = static_cast<float>(value14) / 8192.0f;
    for (Voice &v : voices) {
        if (v.used) retune(v);
    }
}

void Draw::channelPressure(uint8_t value) {
    channelPressure_ = static_cast<float>(value) / 127.0f;
    for (Voice &v : voices) {
        if (v.used && v.held) v.aim = aimFor(v);
    }
}

void Draw::notePressure(uint8_t note, uint8_t value) {
    for (Voice &v : voices) {
        if (v.used && v.held && v.note == note) {
            v.pressure = static_cast<float>(value) / 127.0f;
            v.aim = aimFor(v);
        }
    }
}

bool Draw::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    if (asleep) return true;

    const KindVoice &body = kKindVoices[std::clamp(steppedTargetOf(Model), 0, kKinds - 1)];
    // The body: a one-pole low-pass, and a resonance added on top (a band-pass, peak gain 1).
    const float lowPole = std::exp(-6.2831853f * body.lowPass / sampleRate);
    const float bw = 6.2831853f * body.bodyHz / sampleRate;
    const float q = std::fmax(0.3f, body.bodyHz / std::fmax(1.0f, body.bodyWidth));
    const float alpha = std::sin(bw) / (2.0f * q), a0 = 1.0f + alpha;
    const float bb0 = alpha / a0, ba1 = -2.0f * std::cos(bw) / a0, ba2 = (1.0f - alpha) / a0;
    const float dcPole = 1.0f - 6.2831853f * 20.0f / sampleRate;
    const float airAmount = clampf(paramOf(Air), 0.0f, 1.0f);
    const float volume = paramOf(Volume);
    const float attack = std::fmax(0.005f, targetOf(Attack) * 0.001f) * sampleRate;
    const float release = std::fmax(0.005f, targetOf(Release) * 0.001f) * sampleRate;
    const float squeezeFollow = 1.0f - std::exp(-1.0f / (0.02f * sampleRate));
    const float wanderFollow = 1.0f - std::exp(-6.2831853f * 4.0f / sampleRate);

    // Each voice's breath or bellows: a straight rise to the pressure over
    // the attack, and down over the release.
    float rise[kVoices] = {}, fall[kVoices] = {};
    for (int n = 0; n < kVoices; ++n) {
        const Voice &v = voices[n];
        if (!v.used) continue;
        const KindVoice &k = kKindVoices[v.kind];
        rise[n] = k.pressure / attack;
        fall[n] = k.pressure / release;
    }

    bool any = false;
    for (int32_t i = 0; i < frames; ++i) {
        float sum = 0.0f;
        for (int n = 0; n < kVoices; ++n) {
            Voice &v = voices[n];
            if (!v.used) continue;
            if (v.blown < v.aim) v.blown = std::fmin(v.aim, v.blown + rise[n]);
            else if (v.blown > v.aim) v.blown = std::fmax(v.aim, v.blown - fall[n]);
            const KindVoice &k = kKindVoices[v.kind];
            // No breath or bellows is perfectly even: a slow wander below about 4 Hz.
            const float w = white(noise);
            v.wander1 += (w - v.wander1) * wanderFollow;
            v.wander2 += (v.wander1 - v.wander2) * wanderFollow;
            const float blowing = v.blown * (1.0f + k.wander * kWanderScale * v.wander2);
            // Pressure added after the note started is louder as well as
            // brighter, as a player squeezing a key expects.
            if (v.held) v.squeeze += (v.aim / v.struck - v.squeeze) * squeezeFollow;
            float out = 0.0f;
            for (int r = 0; r < v.count; ++r) {
                // No two reeds are quite alike: the others a little softer than the first.
                const float one = v.reeds[r].step(blowing, k.supply) * (r == 0 ? 1.0f : kOtherReed);
                // A reed driven somewhere it can't follow starts again rather than sounding.
                if (std::isfinite(one)) out += one;
                else v.reeds[r].clear();
            }
            out *= v.gain * v.squeeze * v.share;
            // The breath's own noise, as strong as it's blown.
            const float hiss = white(noise) * 2.0f * airAmount * kHiss * v.gain * v.blown / k.pressure;
            const float s = out + hiss;
            v.level += (std::fabs(out) - v.level) * 0.001f;
            sum += s;
        }
        lowState = sum + lowPole * (lowState - sum);
        const float bodyIn = lowState;
        const float bp = bb0 * bodyIn - bb0 * bodyX2 - ba1 * bodyY1 - ba2 * bodyY2;
        bodyX2 = bodyX1;
        bodyX1 = bodyIn;
        bodyY2 = bodyY1;
        bodyY1 = bp;
        const float wet = bodyIn + body.bodyLift * bp;
        const float y = wet - dcIn + dcPole * dcOut;
        dcIn = wet;
        dcOut = y;
        L[i] = R[i] = y * volume;
        any = any || std::fabs(y) > kSilent;
    }

    for (Voice &v : voices) {
        if (!v.used) continue;
        if (!v.held && v.blown <= 0.0f && v.level < kSilent) {
            if (++v.quietBlocks > 8) v.used = false;
        } else {
            v.quietBlocks = 0;
        }
    }
    quietSamples = any || activeVoices() > 0 ? 0 : quietSamples + frames;
    if (quietSamples > 8192) asleep = true;
    return true;
}

} // namespace acidulous::machine
