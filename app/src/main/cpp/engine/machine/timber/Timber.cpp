#include "Timber.h"
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

namespace {
constexpr float kTwoPi = 6.28318530718f;
float mtof(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
} // namespace

Timber::Timber() { initParams(); }

const ParamDef *Timber::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // What starts the air moving, and what shape it is moving in. These
        // two between them are the whole woodwind family.
        {"family", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},
        {"bore", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        // The instrument, as the lowest note it has. Everything about the
        // tube below your fingers follows from this and the note.
        {"body", 30.0f, 500.0f, 146.8f, Curve::Exponential, 0, "Hz"},
        {"lattice", 300.0f, 6000.0f, 1500.0f, Curve::Exponential, 0, "Hz"},
        {"holes", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"fingering", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"below", 0.2f, 3.0f, 1.0f, Curve::Linear, 0, ""},
        {"answer", 0.0f, 0.55f, 0.2f, Curve::Linear, 0, ""},
        {"register", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},
        {"reed", 0.05f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"embouchure", 0.25f, 0.9f, 0.55f, Curve::Linear, 0, ""},
        {"pressure", 0.0f, 1.3f, 0.85f, Curve::Linear, 0, ""},
        {"breath", 0.0f, 1.0f, 0.12f, Curve::Linear, 0, ""},
        {"jet", 0.2f, 1.2f, 0.5f, Curve::Linear, 0, ""},
        {"aim", -0.8f, 0.8f, 0.3f, Curve::Linear, 0, ""},
        {"bell", 0.05f, 0.9f, 0.4f, Curve::Linear, 0, ""},
        {"loss", 0.97f, 1.0f, 0.999f, Curve::Linear, 0, ""},
        {"tongue", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        {"tonguetime", 0.002f, 0.12f, 0.02f, Curve::Exponential, 0, "s"},
        {"flutter", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"flutterrate", 8.0f, 45.0f, 25.0f, Curve::Exponential, 0, "Hz"},
        {"keys", 0.0f, 1.0f, 0.25f, Curve::Linear, 0, ""},
        {"attack", 0.002f, 2.0f, 0.03f, Curve::Exponential, 0, "s"},
        {"decay", 0.005f, 4.0f, 0.4f, Curve::Exponential, 0, "s"},
        {"sustain", 0.0f, 1.0f, 0.9f, Curve::Linear, 0, ""},
        {"release", 0.005f, 4.0f, 0.15f, Curve::Exponential, 0, "s"},
        {"vibrato", 0.0f, 60.0f, 8.0f, Curve::Linear, 0, "cents"},
        {"vibratorate", 0.5f, 12.0f, 5.0f, Curve::Exponential, 0, "Hz"},
        {"vibratodelay", 0.0f, 2.0f, 0.3f, Curve::Linear, 0, "s"},
        {"cutoff", 200.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
        {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"filtertype", 0.0f, 11.0f, 1.0f, Curve::Stepped, 12, ""},
        {"mono", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
        {"glide", 0.0f, 1.0f, 0.04f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"velocity", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Timber::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.amp.setSampleRate(sampleRate);
        v.filter.setSampleRate(sampleRate);
        v.pipe.prepare(sampleRate);
    }
    reset();
}

void Timber::reset() {
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.amp.kill();
        v.filter.reset();
        v.pipe.clear();
        v.tongueLeft = v.keyLeft = v.keyState = 0.0f;
    }
    flutterPhase = 0.0f;
    rng = kRngSeed; // the breath noise, from the top
}

void Timber::allNotesOff() {
    for (auto &v : voices) {
        if (!v.used) continue;
        v.gate = false;
        v.amp.release();
    }
}

Timber::Voice *Timber::allocate() {
    for (auto &v : voices) if (!v.used) return &v;
    Voice *best = nullptr;
    for (auto &v : voices) {
        if (v.gate) continue;
        if (best == nullptr || v.age < best->age) best = &v;
    }
    if (best != nullptr) return best;
    for (auto &v : voices) if (best == nullptr || v.age < best->age) best = &v;
    return best;
}

void Timber::noteOn(uint8_t note, uint8_t velocity) {
    const bool mono = steppedOf(Mono) != 0;
    Voice *vp = mono ? &voices[0] : allocate();
    if (vp == nullptr) return;
    Voice &v = *vp;

    const float glide = paramOf(Glide);
    // A slur is a note that arrives without the tongue and without the
    // instrument being restarted; a tongued note is stopped and started
    // again. On a wind instrument that is the difference between two
    // articulations, not two envelopes.
    const bool slurred = glide > 0.001f && v.used && v.gate;
    v.glideFrom = slurred ? v.freq : mtof(static_cast<float>(note));
    v.glidePos = slurred ? 0.0f : 1.0f;
    v.freq = v.glideFrom;
    v.used = true;
    v.gate = true;
    v.note = note;
    v.velocity = static_cast<float>(velocity) / 127.0f;
    v.age = ++ageCounter;
    v.vibratoPhase = 0.0f;
    v.vibratoLeft = paramOf(VibratoDelay);
    // The pads of the keys hitting the body: a real instrument's other
    // sound, and the one a sample library needs a separate layer for.
    v.keyLeft = paramOf(Keys) > 0.001f ? 0.004f * sampleRate : 0.0f;
    if (!slurred) {
        v.tongueLeft = paramOf(TongueTime) * sampleRate;
        v.amp.retrigger();
    } else {
        v.tongueLeft = 0.0f;
    }
}

void Timber::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Timber::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Timber::channelPressure(uint8_t value) { aftertouch = static_cast<float>(value) / 127.0f; }
void Timber::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }
void Timber::onBlock(int64_t, int64_t, float) {}

bool Timber::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;

    const float dt = 1.0f / sampleRate;
    const int32_t family = std::clamp(steppedOf(Family), 0, 2);
    const bool cylinder = steppedOf(Bore) == 0;
    const float body = paramOf(Body);
    const float lattice = paramOf(Lattice);
    const float holes = paramOf(Holes);
    const float fingering = paramOf(Fingering);
    const float below = paramOf(Below);
    const float answer = paramOf(Answer);
    const int32_t reg = std::clamp(steppedOf(Reg), 0, 2);
    const float reed = paramOf(Reed);
    const float embouchure = paramOf(Embouchure);
    const float mouth = paramOf(Pressure);
    const float breathNoise = paramOf(Breath);
    const float jet = paramOf(Jet), aim = paramOf(Aim);
    const float bell = paramOf(Bell), loss = paramOf(Loss);
    const float tongueDepth = paramOf(Tongue);
    const float flutter = paramOf(Flutter), flutterRate = paramOf(FlutterRate);
    const float keys = paramOf(Keys);
    const float vibrato = paramOf(Vibrato), vibratoRate = paramOf(VibratoRate);
    const float glide = paramOf(Glide);
    const float bendMul = std::pow(2.0f, bend * paramOf(BendRange) / 12.0f);
    const float tuneMul = std::pow(2.0f, (paramOf(Octave_) * 12.0f + paramOf(Transpose) +
                                          paramOf(Fine) * 0.01f) / 12.0f);
    const float velAmount = paramOf(VelocityAmount);
    const float drive = paramOf(Drive), volume = paramOf(Volume);
    const float panKnob = paramOf(Pan);
    const float panL = std::cos((panKnob + 1.0f) * 0.25f * 3.14159265f);
    const float panR = std::sin((panKnob + 1.0f) * 0.25f * 3.14159265f);

    // The register vent: the same fingering with a small hole opened to
    // stop the fundamental, so the note sits on a higher partial of the
    // tube. A cylinder has no second partial to sit on - which is exactly
    // why a clarinet's register key gives a twelfth and not an octave, and
    // why the instrument has a "break" in the middle of its range that no
    // other woodwind has. Asking one for its octave anyway puts the note
    // between two modes, where it plays whatever it likes.
    const int32_t mode = reg == Natural ? 1 : (reg == Octave ? (cylinder ? 3 : 2) : 3);

    flutterPhase += flutterRate * dt * static_cast<float>(frames);
    while (flutterPhase >= 1.0f) flutterPhase -= 1.0f;
    const float flutterNow = flutter * 0.5f * (1.0f - std::cos(flutterPhase * kTwoPi));

    lastLattice = lattice * (1.0f - fingering * 0.7f);

    for (auto &v : voices) {
        if (!v.used) continue;
        v.amp.set(0.0f, paramOf(Attack), paramOf(Decay), paramOf(Sustain), paramOf(Release), false);
        v.filter.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType),
                     dsp::MultiFilter::Clean, 0.0f);

        if (v.glidePos < 1.0f) {
            v.glidePos = std::min(1.0f, v.glidePos + (glide > 0.001f ? dt * frames / glide : 1.0f));
            v.freq = v.glideFrom + (mtof(static_cast<float>(v.note)) - v.glideFrom) * v.glidePos;
        } else {
            v.freq = mtof(static_cast<float>(v.note));
        }

        const float vibratoDepth = v.vibratoLeft > 0.0f ? 0.0f : vibrato * (0.35f + modWheel * 0.65f);
        if (v.vibratoLeft > 0.0f) v.vibratoLeft -= dt * frames;
        v.vibratoPhase += vibratoRate * dt * frames;
        while (v.vibratoPhase >= 1.0f) v.vibratoPhase -= 1.0f;
        const float vib = std::sin(v.vibratoPhase * kTwoPi) * vibratoDepth;

        const float env0 = v.amp.value();
        const float vel = 1.0f - velAmount + velAmount * v.velocity;
        const float push = mouth * env0 * vel * (1.0f + aftertouch * 0.3f);

        const float hz = v.freq * tuneMul * bendMul * std::pow(2.0f, vib / 1200.0f);
        v.pipe.setNote(hz);
        v.pipe.setShape(cylinder, mode);
        v.pipe.setTube(body);
        v.pipe.setLattice(lattice, fingering, holes);
        v.pipe.setBelow(below);
        v.pipe.setFork(answer);
        v.pipe.setReed(family, reed, embouchure);
        v.pipe.setJet(jet, aim);
        v.pipe.setBell(0.9f, bell);
        v.pipe.setLoss(loss);
        v.pipe.setPressure(push);
        v.pipe.setDrive(1.0f);
        v.pipe.tune();

        for (int32_t i = 0; i < frames; ++i) {
            const float env = v.amp.next();
            if (env <= 0.0000005f && !v.gate) { v.used = false; break; }

            // The tongue: on the reed at the start of a note, and back on
            // it however many times a second a flutter asks for.
            float stop = flutterNow * tongueDepth;
            if (v.tongueLeft > 0.0f) { stop = tongueDepth; v.tongueLeft -= 1.0f; }
            v.pipe.setTongue(stop);

            rng = rng * 1664525u + 1013904223u;
            const float white = static_cast<float>(rng >> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f;
            const float breath = push * env / (env0 > 0.0001f ? env0 : 1.0f);
            float s = v.pipe.step(breath, white * breathNoise * 0.35f * breath);

            // A pad closing on the body: a click, not a note.
            if (v.keyLeft > 0.0f) {
                v.keyLeft -= 1.0f;
                v.keyState = v.keyState * 0.85f + white * 0.15f;
                s += v.keyState * keys * 0.5f;
            }

            const float out = v.filter.process(s) * env;
            L[i] += out;
            R[i] += out;
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float l = L[i] * volume * 1.4f, r = R[i] * volume * 1.4f;
        if (drive > 0.0001f) {
            const float k = 1.0f + drive * 8.0f;
            l = dsp::fastTanh(l * k) / std::sqrt(k);
            r = dsp::fastTanh(r * k) / std::sqrt(k);
        }
        L[i] = l * panL * 1.4142f;
        R[i] = r * panR * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
