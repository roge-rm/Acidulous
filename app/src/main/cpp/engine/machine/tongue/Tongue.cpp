#include "Tongue.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>
#include <complex>

namespace acidulous::machine {

using dsp::clampf;
using namespace tongue;

namespace {

/** Level for a pluck at velocity 100 through the mouth's "ah", set so the bank sits with the other machines. */
constexpr float kHouse = 0.635f;
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

/** Where the knobs sit by default: a kind's own set, edge and pluck are what these mean. */
constexpr float kSetHome = 0.8f, kEdgeHome = 0.55f, kPluckHome = 0.6f;

/** Each chord's reeds, in semitones from the note. Unison is a few cents apart, as two harps never quite agree. */
constexpr float kChordSteps[Tongue::kChords][5] = {
    {0, 0, 0, 0, 0},
    {0, 0.07f, -0.06f, 0.12f, -0.11f},
    {0, 12, 24, -12, 36},
    {0, 7, 12, 19, 24},
    {0, 4, 7, 12, 16},
    {0, 3, 7, 12, 15},
    {0, 2, 4, 7, 9},
};

} // namespace

// Steel, khomus, morsing, brass, munnharpe and temir komuz are fitted to
// recordings (tools/tongue_reference/fit.py); the bamboo kinds, which no open
// recording covers, from what's written about them and by ear.
//                                             set   edge  pluck ring  over  ratio2 ratio3 lowCut frameHz ring   level pull  air  level reeds chord
const Tongue::KindVoice Tongue::kKindVoices[kKinds] = {
    /* steel      */ {0.80f, 0.55f, 0.60f, 1.00f, 0.30f, 6.267f, 17.55f, 0.0f, 4500.0f, 0.08f, 0.05f, 0.0f, 1.0f, 1.00f, 1, Unison},
    /* munnharpe  */ {0.60f, 0.30f, 0.60f, 0.80f, 0.30f, 6.267f, 17.55f, 400.0f, 4000.0f, 0.10f, 0.05f, 0.0f, 0.8f, 1.60f, 1, Unison},
    /* khomus     */ {0.80f, 0.55f, 0.60f, 1.00f, 0.30f, 6.267f, 17.55f, 0.0f, 3800.0f, 0.10f, 0.05f, 0.0f, 1.2f, 0.98f, 1, Unison},
    /* morsing    */ {1.00f, 0.30f, 1.00f, 0.60f, 0.25f, 6.267f, 17.55f, 0.0f, 2500.0f, 0.15f, 0.10f, 0.0f, 0.8f, 1.33f, 1, Unison},
    /* temir      */ {0.80f, 0.95f, 0.60f, 0.90f, 0.30f, 6.267f, 17.55f, 0.0f, 5000.0f, 0.10f, 0.05f, 0.0f, 0.8f, 1.35f, 1, Unison},
    /* brass      */ {1.00f, 0.95f, 1.00f, 1.20f, 0.40f, 5.600f, 15.50f, 200.0f, 3200.0f, 0.25f, 0.25f, 0.0f, 1.5f, 1.02f, 1, Unison},
    /* bamboo     */ {0.60f, 0.35f, 0.50f, 0.25f, 0.20f, 5.300f, 14.50f, 150.0f, 1400.0f, 0.04f, 0.30f, 0.0f, 1.8f, 1.92f, 1, Unison},
    /* mukkuri    */ {0.60f, 0.40f, 0.50f, 0.35f, 0.20f, 5.300f, 14.50f, 150.0f, 1100.0f, 0.05f, 0.35f, 25.0f, 1.5f, 1.52f, 1, Unison},
    /* genggong   */ {0.70f, 0.50f, 0.60f, 0.30f, 0.25f, 5.500f, 15.00f, 150.0f, 1600.0f, 0.06f, 0.40f, 12.0f, 1.3f, 1.53f, 1, Unison},
    /* kouxian    */ {0.70f, 0.60f, 0.60f, 0.50f, 0.30f, 5.600f, 15.00f, 150.0f, 2600.0f, 0.10f, 0.15f, 0.0f, 1.2f, 1.20f, 3, Pentatonic},
};

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
        // 0 is auto: the kind's own.
        {"reeds", 0.0f, static_cast<float>(kReeds), 0.0f, Curve::Stepped, kReeds + 1, ""},
        {"chord", 0.0f, static_cast<float>(kChords - 1), 0.0f, Curve::Stepped, kChords, ""},
        {"strum", 0.0f, 150.0f, 0.0f, Curve::Linear, 0, "ms"},
        {"order", 0.0f, static_cast<float>(kOrders - 1), 0.0f, Curve::Stepped, kOrders, ""},
    };
    count = Count;
    return defs;
}

void Tongue::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    for (Voice &v : voices) {
        for (Tine &t : v.tines) t.reed.prepare(sampleRate);
    }
    reset();
}

void Tongue::reset() {
    for (Voice &v : voices) {
        for (Tine &t : v.tines) {
            t.reed.clear();
            t.gain = t.q = t.air = t.level = t.swing = 0.0f;
            t.wait = -1;
        }
        v.knock.clear();
        v.used = v.held = false;
        v.next = 0;
        v.quietBlocks = 0;
    }
    for (int k = 0; k < 3; ++k) {
        mouth[k].clear();
        mouthAir[k].clear();
    }
    bend = wheel = pressure = blown = 0.0f;
    dcIn = dcOut = cutIn = cutOut = 0.0f;
    noise = 0x9e3779b9u;
    clock = 0;
    controlLeft = 0;
    quietSamples = 0;
    asleep = true;
    for (int k = 0; k < 3; ++k) formants[k] = kMouths[2][k];
    moveMouth(true);
    mouthGain = mouthGainTarget;
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
    const KindVoice &k = kKindVoices[v.kind];
    // Let go with stop up, a finger on the reed shortens its ring.
    const float ring = targetOf(Ring) * k.ring;
    const float stopped = v.held ? ring : ring + (0.04f - ring) * std::sqrt(clampf(targetOf(Stop), 0.0f, 1.0f));
    for (int i = 0; i < v.count; ++i) {
        Tine &t = v.tines[i];
        const float hz = noteHz(v.baseNote + t.offset + bend * paramOf(BendRange));
        t.reed.setRatios(k.ratio2, k.ratio3);
        t.reed.tune(hz, stopped, targetOf(Overtones), k.overRing);
        t.perRadian = sampleRate / (6.28318530718f * hz);
        t.retuned = true;
    }
}

void Tongue::pluckTine(const Voice &v, Tine &t, float swing, float contact, float snap, float gain) {
    const KindVoice &k = kKindVoices[v.kind];
    if (k.pullMs > 0.0f) {
        t.reed.pull(swing, contact / 1.3e-3f * k.pullMs * 0.001f, snap);
    } else {
        t.reed.pluck(swing, contact, snap);
    }
    // The sound grows as the swing cubed; the level is the house law instead.
    // A reed still ringing keeps some of its swing through the catch, so the
    // swing it will have is both together.
    const float after = std::hypot(swing, tongue::Reed::kCaught * t.swing);
    t.gain = gain * kHeldSwing / (after * after * after);
}

void Tongue::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = voiceFor(note);
    const float vel = static_cast<float>(velocity) / 127.0f;
    const bool ringing = v->used && v->note == note;
    v->kind = std::clamp(steppedTargetOf(Model), 0, kKinds - 1);
    const KindVoice &k = kKindVoices[v->kind];
    const int reeds = steppedTargetOf(Reeds);
    const int count = std::clamp(reeds > 0 ? reeds : k.reeds, 1, kReeds);
    const int chordKnob = steppedTargetOf(Chord);
    const int chord = std::clamp(chordKnob > 0 ? chordKnob : k.chord, 1, kChords - 1);
    if (!ringing || count != v->count) {
        // A new harp: reeds that weren't sounding start still.
        for (int i = v->count; i < count; ++i) {
            v->tines[i].reed.clear();
            v->tines[i].q = v->tines[i].air = v->tines[i].level = v->tines[i].swing = 0.0f;
        }
        v->next = 0;
    }
    v->count = count;
    for (int i = 0; i < count; ++i) v->tines[i].offset = kChordSteps[chord][i];
    v->note = note;
    v->baseNote = static_cast<float>(note) + 12.0f * static_cast<float>(steppedTargetOf(Octave)) + targetOf(Tune) / 100.0f;
    v->held = true;
    retune(*v);

    // Harder is a bigger swing (brighter, as more of it is out of the slot)
    // and a shorter flick of the finger (more of the overtones). The flick
    // is timed for a G3 reed and scales with the reed, so every note has
    // the same colour.
    const float pluck = clampf(k.pluck + targetOf(Pluck) - kPluckHome, 0.0f, 1.0f);
    const float swing = 0.45f + 0.55f * vel;
    // Reeds plucked together share the level; one at a time, each has it all.
    const int order = steppedTargetOf(Order);
    const float share = order == InTurn ? 1.0f : 1.0f / std::sqrt(static_cast<float>(count));
    const float gain = kHouse * k.level * velocityGain(vel, targetOf(VelocityAmount)) * share;
    // A sharp flick snaps off the reed: the ping of its overtones.
    const float snap = (0.25f + 0.75f * pluck) * (0.6f + 0.4f * vel);
    const auto contactFor = [&](const Tine &t) {
        return (3.0f - 2.7f * pluck) * (1.15f - 0.3f * vel) * 0.001f * (196.0f / t.reed.hz());
    };
    // A strum: each reed a step later, in the order asked for. In turn plucks
    // one reed a note, the next each time.
    const int step = static_cast<int>(targetOf(Strum) * 0.001f * sampleRate);
    int scatter[kReeds] = {0, 1, 2, 3, 4};
    if (order == Scatter) {
        for (int i = count - 1; i > 0; --i) std::swap(scatter[i], scatter[nextRandom(noise) % static_cast<uint32_t>(i + 1)]);
    }
    for (int i = 0; i < count; ++i) {
        Tine &t = v->tines[i];
        int place = i;
        if (order == Down) place = count - 1 - i;
        else if (order == Scatter) place = scatter[i];
        else if (order == InTurn) place = i == v->next % count ? 0 : -1;
        if (place < 0) continue;
        const float c = contactFor(t);
        if (place * step == 0) {
            pluckTine(*v, t, swing, c, snap, gain);
            t.wait = -1;
        } else {
            t.wait = place * step;
            t.waitSwing = swing;
            t.waitContact = c;
            t.waitSnap = snap;
            t.waitGain = gain;
        }
    }
    if (order == InTurn) v->next = (v->next + 1) % count;
    // The frame rings too, once a pluck.
    v->knock.set(k.frameHz, k.frameRing, sampleRate);
    v->knock.strike(k.frameLevel * kHouse * k.level * velocityGain(vel, targetOf(VelocityAmount)) * swing);
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
    // The mouth gives back the power it's given: what its peaks add is
    // worked out from their response at each harmonic of the lowest reed,
    // weighted as the drone measures (the 1st to 3rd weaker, level from the
    // 4th to the 16th, falling above), and taken out again.
    float hz = 0.0f;
    for (const Voice &v : voices) {
        if (!v.used) continue;
        for (int r = 0; r < v.count; ++r) {
            const float f = v.tines[r].reed.hz();
            if (hz == 0.0f || f < hz) hz = f;
        }
    }
    if (hz <= 0.0f) hz = 196.0f;
    const float lift = kMouthBoost * clampf(knob(Depth), 0.0f, 1.0f);
    // Only worked out again when the mouth, its depth or the pitch has moved.
    const float now[6] = {formants[0], formants[1], formants[2], focus, lift, hz};
    bool moved = jump;
    for (int k = 0; k < 6; ++k) moved = moved || std::fabs(now[k] - lastMouth[k]) > 1e-3f * std::fabs(now[k]) + 1e-6f;
    if (!moved) return;
    for (int k = 0; k < 6; ++k) lastMouth[k] = now[k];
    // At most 24 of the first 48 harmonics, spread evenly.
    const int top = std::min(48, static_cast<int>(0.45f * sampleRate / hz));
    const int stride = (top + 23) / 24;
    double sum = 0.0, weights = 0.0;
    for (int h = 1; h <= top; h += stride) {
        const double w = 6.283185307179586 * h * hz / sampleRate;
        const std::complex<double> z1 = std::polar(1.0, -w), z2 = z1 * z1;
        std::complex<double> response = 1.0;
        for (int k = 0; k < 3; ++k) {
            const diction::Bandpass &f = mouth[k];
            response += static_cast<double>(lift * kFormantShare[k]) * (static_cast<double>(f.b0) + static_cast<double>(f.b2) * z2) /
                        (1.0 + static_cast<double>(f.a1) * z1 + static_cast<double>(f.a2) * z2);
        }
        const double low = (h / 3.5) * (h / 3.5), high = (h / 16.0) * (h / 16.0);
        const double weight = low / (1.0 + low) / (1.0 + high);
        sum += std::norm(response) * weight;
        weights += weight;
    }
    mouthGainTarget = weights > 0.0 ? static_cast<float>(1.0 / std::sqrt(std::fmax(sum / weights, 1e-6))) : 1.0f;
}

bool Tongue::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    if (asleep) return true;

    // The block's settings.
    const float edgeKnob = paramOf(Edge) - kEdgeHome;
    const float setKnob = paramOf(Set) - kSetHome;
    const float depth = clampf(paramOf(Depth), 0.0f, 1.0f);
    const float breath = clampf(paramOf(Breath) + pressure, 0.0f, 1.0f);
    const float airNoise = clampf(paramOf(Air), 0.0f, 1.0f);
    const float sustain = clampf(paramOf(Sustain), 0.0f, 1.0f);
    const float volume = paramOf(Volume);
    const float ringKnob = paramOf(Ring);
    // The kind's low cut, from the model knob: one for the whole machine.
    const float lowCut = kKindVoices[std::clamp(steppedTargetOf(Model), 0, kKinds - 1)].lowCut;
    const float cutPole = lowCut > 0.0f ? std::exp(-6.28318530718f * lowCut / sampleRate) : 1.0f;
    float offCentre[kVoices] = {};
    for (int n = 0; n < kVoices; ++n) {
        Voice &v = voices[n];
        if (!v.used) continue;
        const KindVoice &k = kKindVoices[v.kind];
        const float halfWidth = 0.25f - 0.23f * clampf(k.edge + edgeKnob, 0.0f, 1.0f);
        // The reed rests off the slot's middle by up to five eighths of a
        // full swing: the pulses come unevenly and the even harmonics up with
        // them. Never more than kReach of the swing it has, so a dying note
        // still passes through the slot.
        offCentre[n] = clampf(k.set + setKnob, -1.0f, 1.0f) * 0.625f;
        const float ring = ringKnob * k.ring;
        for (int i = 0; i < v.count; ++i) {
            Tine &t = v.tines[i];
            // While the key is held, breath feeds the reed until it swings at
            // kHeldSwing: more than its loss below that, less above.
            const float lack = 1.0f - (t.level * t.level) / (kHeldSwing * kHeldSwing);
            // The reed takes at least a sample and a half to cross the slot at a
            // held swing, or the pulses fall between samples: high notes get a
            // looser fit.
            t.width = std::fmax(halfWidth, 0.9f * 6.28318530718f * t.reed.hz() / sampleRate);
            // A pulse's power goes with the slot's width: scaled back to the
            // default fit's (edge 0.55) so the fit changes the colour, not the level.
            t.fit = std::sqrt(t.width * kDefaultWidth);
            t.reed.setDrive(v.held ? sustain * breath * (6.907755f / ring + 6.0f) * lack : 0.0f);
        }
    }
    const float breathStep = (breath - blown) / static_cast<float>(frames);
    const float dcPole = 1.0f - 6.28318530718f * 20.0f / sampleRate;
    const float gainFollow = 1.0f - std::exp(-1.0f / (0.005f * sampleRate));

    bool any = false;
    for (int32_t i = 0; i < frames; ++i) {
        if (--controlLeft <= 0) {
            controlLeft = kControl;
            moveMouth(false);
        }
        mouthGain += (mouthGainTarget - mouthGain) * gainFollow;
        float push = 0.0f, air = 0.0f, hiss = 0.0f;
        // Breath moves across the block: the air is a change, so a step would click.
        blown += breathStep;
        float knocks = 0.0f;
        for (int n = 0; n < kVoices; ++n) {
            Voice &v = voices[n];
            if (!v.used) continue;
            const float airScale = kKindVoices[v.kind].air;
            knocks += v.knock.step();
            for (int r = 0; r < v.count; ++r) {
                Tine &t = v.tines[r];
                if (t.wait >= 0 && t.wait-- == 0) pluckTine(v, t, t.waitSwing, t.waitContact, t.waitSnap, t.waitGain);
                const float x = t.reed.step(0.0f);
                // How far the reed swings, from where it is and how fast it's going.
                const float vel = t.reed.velocity();
                t.swing += (std::sqrt(x * x + vel * vel) - t.swing) * 0.02f;
                const float reach = kReach * t.swing;
                const float u = (x - clampf(offCentre[n], -reach, reach)) / t.width;
                const float u2 = u * u;
                // In the slot (|u| < 1) the reed pushes the air; out of it, it doesn't.
                const float inSlot = 1.0f / (1.0f + u2 * u2);
                // Pitch and slot width are taken out. A wider swing pushes more air
                // through the slot (measured: a dying pluck's fundamental falls with
                // the swing), so it grows as the swing cubed.
                const float q = vel * inSlot * t.fit * t.swing / kHeldSwing;
                // Breath through the opening the reed leaves.
                const float open = blown * (kGap + 1.0f - inSlot);
                // A new pitch changes the scale of both: they start again from here.
                if (t.retuned) {
                    t.q = q;
                    t.air = open;
                    t.retuned = false;
                }
                push += (q - t.q) * t.gain * t.perRadian;
                t.q = q;
                air += (open - t.air) * kAirLevel * kHouse * t.perRadian * t.width;
                hiss += white(noise) * open * airNoise * airScale * kHouse;
                t.air = open;
                t.level += (std::fabs(x) - t.level) * 0.0005f;
            }
        }
        // The kind's low cut, on the harp's own sound.
        float dry = push + air + knocks;
        if (lowCut > 0.0f) {
            const float cut = cutPole * (cutOut + dry - cutIn);
            cutIn = dry;
            cutOut = cut;
            dry = cut;
        } else {
            cutIn = cutOut = 0.0f;
        }
        // The mouth's peaks are added to the sound, so they can only lift a
        // harmonic: a formant's centre is in phase with what passes through.
        float peaks = 0.0f, breathed = 0.0f;
        for (int k = 0; k < 3; ++k) {
            peaks += kFormantShare[k] * mouth[k].process(dry);
            breathed += kFormantShare[k] * mouthAir[k].process(hiss);
        }
        const float shaped = dry + kMouthBoost * depth * peaks;
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
        bool quiet = true;
        for (int r = 0; r < v.count; ++r) {
            const Tine &t = v.tines[r];
            quiet = quiet && t.level < kSilent && !t.reed.plucking() && t.wait < 0;
        }
        if (quiet) {
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
