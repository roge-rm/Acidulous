#include "Tongue.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>

namespace acidulous::machine {

using dsp::clampf;
using namespace tongue;

namespace {

/** Level for a pluck at velocity 100 through the mouth's "ah", set so the bank sits with the other machines. */
constexpr float kHouse = 0.7f;
/** Air through the slot against the reed's own push, before breath. */
constexpr float kAirLevel = 0.4f;
/** The gap left when the reed fills the slot, in slot half-widths. */
constexpr float kGap = 0.05f;
/** Where breath keeps the reed swinging, in the swing's units. */
constexpr float kHeldSwing = 0.6f;
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

Tongue::Tongue() { initParams(); }

const ParamDef *Tongue::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"model", 0.0f, static_cast<float>(kKinds - 1), 0.0f, Curve::Stepped, kKinds, ""},
        {"tune", -100.0f, 100.0f, 0.0f, Curve::Linear, 0, "cents"},
        // Where the reed rests in its slot: off the middle, the two pulses a
        // cycle part unevenly and the even harmonics come up against the odd.
        {"set", -1.0f, 1.0f, 0.8f, Curve::Linear, 0, ""},
        // How closely the reed fits its slot: closer is sharper pulses, brighter.
        {"edge", 0.0f, 1.0f, 0.55f, Curve::Linear, 0, ""},
        {"ring", 0.2f, 10.0f, 6.0f, Curve::Exponential, 0, "s"},
        {"pluck", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"overtones", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"mouth", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"focus", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        // How much of the sound comes through the mouth, against straight
        // out of the slot: the formants' peaks over a flat drone.
        {"depth", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"glide", 5.0f, 500.0f, 60.0f, Curve::Exponential, 0, "ms"},
        {"breath", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"air", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"sustain", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"stop", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        {"voices", 1.0f, 4.0f, 1.0f, Curve::Stepped, 4, ""},
        {"velocity", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"bend", 0.0f, 12.0f, 2.0f, Curve::Linear, 0, "st"},
        {"octave", -2.0f, 2.0f, 0.0f, Curve::Stepped, 5, ""},
        {"volume", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Tongue::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    for (Voice &v : voices) v.reed.prepare(sampleRate);
    reset();
}

void Tongue::reset() {
    for (Voice &v : voices) {
        v.reed.clear();
        v.used = v.held = false;
        v.gain = 0.0f;
        v.q = v.air = v.level = v.swing = 0.0f;
        v.quietBlocks = 0;
    }
    for (int k = 0; k < 3; ++k) {
        mouth[k].clear();
        mouthAir[k].clear();
    }
    bend = wheel = pressure = blown = 0.0f;
    dcIn = dcOut = 0.0f;
    noise = 0x9e3779b9u;
    clock = 0;
    controlLeft = 0;
    quietSamples = 0;
    asleep = true;
    for (int k = 0; k < 3; ++k) formants[k] = kMouths[2][k];
    moveMouth(true);
    mouthGain = mouthGainTarget = 1.0f;
    powerIn = powerOut = 0.0f;
}

int Tongue::activeVoices() const {
    int n = 0;
    for (const Voice &v : voices) n += v.used ? 1 : 0;
    return n;
}

Tongue::Voice *Tongue::voiceFor(uint8_t note) {
    const int cap = std::clamp(steppedTargetOf(Voices), 1, kVoices);
    // One harp a note; at one voice, one harp replucked.
    for (int i = 0; i < cap; ++i) {
        if (voices[i].used && voices[i].note == note) return &voices[i];
    }
    for (int i = 0; i < cap; ++i) {
        if (!voices[i].used) return &voices[i];
    }
    Voice *oldest = &voices[0];
    for (int i = 0; i < cap; ++i) {
        if (voices[i].age < oldest->age) oldest = &voices[i];
    }
    return oldest;
}

void Tongue::retune(Voice &v) {
    const float hz = noteHz(v.baseNote + bend * paramOf(BendRange));
    // Let go with stop up, a finger on the reed shortens its ring.
    const float ring = targetOf(Ring);
    const float stopped = v.held ? ring : ring + (0.04f - ring) * std::sqrt(clampf(targetOf(Stop), 0.0f, 1.0f));
    v.reed.tune(hz, stopped, targetOf(Overtones), 0.3f);
    v.perRadian = sampleRate / (6.28318530718f * hz);
    v.retuned = true;
}

void Tongue::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = voiceFor(note);
    const float vel = static_cast<float>(velocity) / 127.0f;
    v->note = note;
    v->baseNote = static_cast<float>(note) + 12.0f * static_cast<float>(steppedTargetOf(Octave)) + targetOf(Tune) / 100.0f;
    v->held = true;
    retune(*v);
    // Harder is a bigger swing (brighter, as more of it is out of the slot)
    // and a shorter flick of the finger (more of the overtones). The flick
    // is timed for a G3 reed and scales with the reed, so every note has
    // the same colour.
    const float swing = 0.45f + 0.55f * vel;
    const float contact = (3.0f - 2.7f * clampf(targetOf(Pluck), 0.0f, 1.0f)) * (1.15f - 0.3f * vel) * 0.001f *
                          (196.0f / v->reed.hz());
    // A sharp flick snaps off the reed: the ping of its overtones.
    const float snap = (0.25f + 0.75f * clampf(targetOf(Pluck), 0.0f, 1.0f)) * (0.6f + 0.4f * vel);
    v->reed.pluck(swing, contact, snap);
    // The sound grows as the swing cubed; the level is the house law instead.
    // A reed still ringing keeps some of its swing through the catch, so the
    // swing it will have is both together.
    const float after = std::hypot(swing, tongue::Reed::kCaught * v->swing);
    v->gain = kHouse * velocityGain(vel, targetOf(VelocityAmount)) * kHeldSwing / (after * after * after);
    v->used = true;
    v->quietBlocks = 0;
    v->age = ++clock;
    asleep = false;
    quietSamples = 0;
}

void Tongue::noteOff(uint8_t note) {
    for (Voice &v : voices) {
        if (v.used && v.held && v.note == note) {
            v.held = false;
            retune(v);
        }
    }
}

void Tongue::allNotesOff() {
    for (Voice &v : voices) {
        if (v.used && v.held) {
            v.held = false;
            retune(v);
        }
    }
}

void Tongue::pitchBend(int16_t value14) {
    bend = static_cast<float>(value14) / 8192.0f;
    for (Voice &v : voices) {
        if (v.used) retune(v);
    }
}

void Tongue::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) wheel = static_cast<float>(value) / 127.0f;
}

void Tongue::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }

void Tongue::moveMouth(bool jump) {
    // A jump (a reset) takes where the knobs are going, not where they are.
    const auto knob = [&](int32_t p) { return jump ? targetOf(p) : paramOf(p); };
    // The vowel: oo oh ah eh ee along the control, the mod wheel on top.
    const float m = clampf(knob(Mouth) + wheel, 0.0f, 1.0f) * 4.0f;
    const int lo = std::min(3, static_cast<int>(m));
    const float t = m - static_cast<float>(lo);
    float target[3];
    for (int k = 0; k < 3; ++k) target[k] = kMouths[lo][k] + (kMouths[lo + 1][k] - kMouths[lo][k]) * t;
    const float follow = jump ? 1.0f : 1.0f - std::exp(-static_cast<float>(kControl) / (knob(Glide) * 0.001f * sampleRate));
    for (int k = 0; k < 3; ++k) formants[k] += (target[k] - formants[k]) * follow;
    // Focus narrows the lower formants until each brings out one harmonic.
    const float focus = clampf(knob(Focus), 0.0f, 1.0f);
    for (int k = 0; k < 3; ++k) {
        const float bw = diction::widthOf(k, formants[k]);
        const float narrow = k < 2 ? std::fmax(12.0f, bw * (1.0f - 0.9f * focus)) : bw * (1.0f - 0.5f * focus);
        mouth[k].set(formants[k], narrow, sampleRate);
        mouthAir[k].set(formants[k], 2.0f * bw, sampleRate);
    }
    // The mouth gives back the power it's given: what its peaks add over
    // the last few tens of milliseconds is taken out again, down to -26 dB.
    if (powerIn > 1e-12f) {
        mouthGainTarget = clampf(std::sqrt(powerIn / std::fmax(powerOut, 1e-20f)), 0.05f, 1.0f);
    }
}

bool Tongue::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    if (asleep) return true;

    // The block's settings.
    const float edge = clampf(paramOf(Edge), 0.0f, 1.0f);
    const float halfWidth = 0.25f - 0.23f * edge;
    // The reed rests off the slot's middle by up to five eighths of a full
    // swing: the pulses come unevenly and the even harmonics up with them.
    // Never more than kReach of the swing it has, so a dying note still
    // passes through the slot.
    const float offCentre = clampf(paramOf(Set), -1.0f, 1.0f) * 0.625f;
    const float depth = clampf(paramOf(Depth), 0.0f, 1.0f);
    const float breath = clampf(paramOf(Breath) + pressure, 0.0f, 1.0f);
    const float airNoise = clampf(paramOf(Air), 0.0f, 1.0f);
    const float sustain = clampf(paramOf(Sustain), 0.0f, 1.0f);
    const float volume = paramOf(Volume);
    const float ring = paramOf(Ring);
    for (Voice &v : voices) {
        if (!v.used) continue;
        // While the key is held, breath feeds the reed until it swings at
        // kHeldSwing: more than its loss below that, less above.
        const float lack = 1.0f - (v.level * v.level) / (kHeldSwing * kHeldSwing);
        // The reed takes at least a sample and a half to cross the slot at a
        // held swing, or the pulses fall between samples: high notes get a
        // looser fit.
        v.width = std::fmax(halfWidth, 0.9f * 6.28318530718f * v.reed.hz() / sampleRate);
        // A pulse's power goes with the slot's width: scaled back to the
        // default fit's (edge 0.55) so the fit changes the colour, not the level.
        v.fit = std::sqrt(v.width * kDefaultWidth);
        v.reed.setDrive(v.held ? sustain * breath * (6.907755f / ring + 6.0f) * lack : 0.0f);
    }
    const float breathStep = (breath - blown) / static_cast<float>(frames);
    const float dcPole = 1.0f - 6.28318530718f * 20.0f / sampleRate;
    // Down in a millisecond, up over 50: until the formants have rung up the
    // mouth reads quieter than it is.
    const float gainFall = 1.0f - std::exp(-1.0f / (0.001f * sampleRate));
    const float gainRise = 1.0f - std::exp(-1.0f / (0.05f * sampleRate));
    const float powerFollow = 1.0f - std::exp(-1.0f / (0.03f * sampleRate));

    bool any = false;
    for (int32_t i = 0; i < frames; ++i) {
        if (--controlLeft <= 0) {
            controlLeft = kControl;
            moveMouth(false);
        }
        mouthGain += (mouthGainTarget - mouthGain) * (mouthGainTarget < mouthGain ? gainFall : gainRise);
        float push = 0.0f, air = 0.0f, hiss = 0.0f;
        // Breath moves across the block: the air is a change, so a step would click.
        blown += breathStep;
        for (Voice &v : voices) {
            if (!v.used) continue;
            const float x = v.reed.step(0.0f);
            // How far the reed swings, from where it is and how fast it's going.
            const float vel = v.reed.velocity();
            v.swing += (std::sqrt(x * x + vel * vel) - v.swing) * 0.02f;
            const float reach = kReach * v.swing;
            const float u = (x - clampf(offCentre, -reach, reach)) / v.width;
            const float u2 = u * u;
            // In the slot (|u| < 1) the reed pushes the air; out of it, it doesn't.
            const float inSlot = 1.0f / (1.0f + u2 * u2);
            // Pitch and slot width are taken out. A wider swing pushes more air
            // through the slot (measured: a dying pluck's fundamental falls with
            // the swing), so it grows as the swing cubed.
            const float q = vel * inSlot * v.fit * v.swing / kHeldSwing;
            // Breath through the opening the reed leaves.
            const float open = blown * (kGap + 1.0f - inSlot);
            // A new pitch changes the scale of both: they start again from here.
            if (v.retuned) {
                v.q = q;
                v.air = open;
                v.retuned = false;
            }
            push += (q - v.q) * v.gain * v.perRadian;
            v.q = q;
            air += (open - v.air) * kAirLevel * kHouse * v.perRadian * v.width;
            hiss += white(noise) * open * airNoise * kHouse;
            v.air = open;
            v.level += (std::fabs(x) - v.level) * 0.0005f;
        }
        const float dry = push + air;
        // The mouth's peaks are added to the sound, so they can only lift a
        // harmonic: a formant's centre is in phase with what passes through.
        float peaks = 0.0f, breathed = 0.0f;
        for (int k = 0; k < 3; ++k) {
            peaks += kFormantShare[k] * mouth[k].process(dry);
            breathed += kFormantShare[k] * mouthAir[k].process(hiss);
        }
        const float shaped = dry + kMouthBoost * depth * peaks;
        powerIn += (dry * dry - powerIn) * powerFollow;
        powerOut += (shaped * shaped - powerOut) * powerFollow;
        const float wet = mouthGain * shaped + breathed;
        // No DC out of a mouth.
        const float y = wet - dcIn + dcPole * dcOut;
        dcIn = wet;
        dcOut = y;
        L[i] = R[i] = y * volume;
        any = any || std::fabs(y) > kSilent;
    }

    for (Voice &v : voices) {
        if (!v.used) continue;
        if (v.level < kSilent && !v.reed.plucking()) {
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
