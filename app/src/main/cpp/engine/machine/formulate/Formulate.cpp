#include "Formulate.h"
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

using formulate::Program;
using formulate::Vars;

namespace {
float mtof(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
/** The pulse's four classic duties, and everything between them. */
constexpr uint32_t kFull = 0xffffffffu;
} // namespace

Formulate::Formulate() { initParams(); }

const ParamDef *Formulate::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"wave", 0.0f, 4.0f, 0.0f, Curve::Stepped, 5, ""}, // pulse tri saw noise silence
        {"duty", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"bits", 1.0f, 8.0f, 8.0f, Curve::Stepped, 8, ""},
        {"crush", 1.0f, 64.0f, 1.0f, Curve::Exponential, 0, ""},
        {"sub", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"noiseshort", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"pwmdepth", 0.0f, 0.5f, 0.0f, Curve::Linear, 0, ""},
        {"pwmrate", 0.05f, 20.0f, 2.0f, Curve::Exponential, 0, "Hz"},
        {"formula", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},      // how much of it
        {"formulamode", 0.0f, 4.0f, 1.0f, Curve::Stepped, 5, ""}, // off replace ring gate xor
        {"timekeyed", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
        {"timescale", 0.125f, 8.0f, 1.0f, Curve::Exponential, 0, ""},
        {"a", 0.0f, 255.0f, 128.0f, Curve::Linear, 0, ""},
        {"b", 0.0f, 255.0f, 64.0f, Curve::Linear, 0, ""},
        {"c", 0.0f, 255.0f, 32.0f, Curve::Linear, 0, ""},
        {"smooth", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"framerate", 1.0f, 120.0f, 50.0f, Curve::Exponential, 0, "Hz"},
        {"framesync", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"tableretrig", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
        {"cutoff", 60.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
        {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"filtertype", 0.0f, 11.0f, 1.0f, Curve::Stepped, 12, ""},
        {"ampattack", 0.0005f, 4.0f, 0.002f, Curve::Exponential, 0, "s"},
        {"ampdecay", 0.005f, 8.0f, 0.2f, Curve::Exponential, 0, "s"},
        {"ampsustain", 0.0f, 1.0f, 0.8f, Curve::Linear, 0, ""},
        {"amprelease", 0.005f, 8.0f, 0.08f, Curve::Exponential, 0, "s"},
        {"mono", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"glide", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"velocity", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Formulate::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.amp.setSampleRate(sampleRate);
        v.filter.setSampleRate(sampleRate);
    }
    reset();
}

void Formulate::reset() {
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.amp.kill();
        v.filter.reset();
        v.phase = v.subPhase = 0;
        v.lfsr = 0x7fffu;
        v.timeAcc = 0.0;
        v.t = 0;
        v.step = 0;
        v.frameAcc = 0.0f;
        v.smoothed = 0.0f;
    }
    pwmPhase = 0.0f;
}

void *Formulate::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    void *old = const_cast<Program *>(program);
    program = static_cast<const Program *>(object);
    return old;
}

Formulate::Voice *Formulate::allocate() {
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

void Formulate::noteOn(uint8_t note, uint8_t velocity) {
    const bool mono = steppedOf(Mono) != 0;
    Voice *v = mono ? &voices[0] : allocate();
    if (v == nullptr) return;
    const float glide = paramOf(Glide);
    const bool gliding = glide > 0.001f && v->used;
    v->glideFrom = gliding ? v->freq : mtof(static_cast<float>(note));
    v->glidePos = gliding ? 0.0f : 1.0f;
    v->freq = v->glideFrom;
    v->used = true;
    v->gate = true;
    v->note = note;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->age = ++ageCounter;
    if (steppedOf(TableRetrigger) != 0 || !gliding) {
        v->step = 0;
        v->frameAcc = 0.0f;
        // The formula's clock restarts with the note: bytebeat's shape comes
        // from where its counter is, so a note has to start at the start or
        // every one sounds different.
        v->timeAcc = 0.0;
        v->t = 0;
    }
    v->amp.retrigger();
}

void Formulate::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Formulate::allNotesOff() {
    for (auto &v : voices) {
        if (!v.used) continue;
        v.gate = false;
        v.amp.release();
    }
}

void Formulate::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }
void Formulate::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Formulate::onBlock(int64_t, int64_t, float tempo) { bpm = tempo; }

/** One sample of the hardware, 0..255. */
int32_t Formulate::oscSample(Voice &v, int32_t wave, int32_t duty, float dt, float freq, int32_t subLevel) {
    const uint32_t inc = static_cast<uint32_t>(freq * dt * 4294967296.0f);
    v.phase += inc;
    v.subPhase += inc / 2; // an octave down, as the NES's second pulse so often was
    int32_t out = 128;
    switch (wave) {
    case Pulse: {
        const uint32_t edge = static_cast<uint32_t>(std::clamp(duty, 1, 254)) * (kFull / 255u);
        out = v.phase < edge ? 255 : 0;
        break;
    }
    case Triangle: {
        // Sixteen steps up and sixteen down, which is exactly what the NES
        // did and exactly why its triangle buzzes.
        const uint32_t p = v.phase >> 27; // 0..31
        const uint32_t level = p < 16 ? p : 31 - p;
        out = static_cast<int32_t>(level * 17);
        break;
    }
    case Saw:
        out = static_cast<int32_t>(v.phase >> 24);
        break;
    case Noise: {
        // A shift register, clocked at the note's own rate: the short tap is
        // the metallic one.
        v.noisePhase += freq * dt * 16.0f;
        while (v.noisePhase >= 1.0f) {
            v.noisePhase -= 1.0f;
            const uint32_t bit = ((v.lfsr >> 0) ^ (v.lfsr >> (steppedOf(NoiseShort) != 0 ? 6 : 1))) & 1u;
            v.lfsr = (v.lfsr >> 1) | (bit << 14);
        }
        out = (v.lfsr & 1u) != 0 ? 255 : 0;
        break;
    }
    default:
        out = 128;
        break;
    }
    if (subLevel > 0 && wave != Silence) {
        const int32_t sub = v.subPhase < 0x80000000u ? 255 : 0;
        out = (out * (255 - subLevel) + sub * subLevel) / 255;
    }
    return std::clamp(out, 0, 255);
}

bool Formulate::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;

    const float dt = 1.0f / sampleRate;
    const int32_t wave = steppedOf(WaveForm);
    const int32_t combine = steppedOf(FormulaMode);
    const float mix = paramOf(FormulaMix);
    const bool keyed = steppedOf(TimeKeyed) != 0;
    const float timeScale = paramOf(TimeScale);
    const int32_t macroA = static_cast<int32_t>(paramOf(MacroA));
    const int32_t macroB = static_cast<int32_t>(paramOf(MacroB));
    const int32_t macroC = static_cast<int32_t>(paramOf(MacroC));
    const float smooth = paramOf(Smooth);
    const int32_t bits = steppedOf(Bits);
    const float crush = paramOf(Crush);
    const int32_t subLevel = static_cast<int32_t>(paramOf(SubLevel) * 255.0f);
    const float frameHz = steppedOf(FrameSync) != 0 ? bpm / 60.0f * 4.0f : paramOf(FrameRate);
    const float cutoff = paramOf(Cutoff), reso = paramOf(Resonance);
    const int32_t ftype = steppedOf(FilterType);
    const float ampA = paramOf(AmpAttack), ampD = paramOf(AmpDecay), ampS = paramOf(AmpSustain), ampR = paramOf(AmpRelease);
    const float tune = std::pow(2.0f, (paramOf(Octave) * 12.0f + paramOf(Transpose) + paramOf(Fine) * 0.01f) / 12.0f);
    const float bendMul = std::pow(2.0f, bend * paramOf(BendRange) / 12.0f);
    const float glide = paramOf(Glide);
    const float velAmount = paramOf(VelocityAmount);
    const float drive = paramOf(Drive), volume = paramOf(Volume), pan = paramOf(Pan);
    const float pwmDepth = paramOf(PwmDepth), pwmRate = paramOf(PwmRate);
    const float panL = std::cos((pan + 1.0f) * 0.25f * 3.14159265f);
    const float panR = std::sin((pan + 1.0f) * 0.25f * 3.14159265f);
    const float smoothCoeff = smooth <= 0.0001f ? 1.0f : std::exp(-1.0f / (smooth * smooth * 60.0f + 1.0f));

    const Program *prog = program;
    const bool hasFormula = prog != nullptr && !prog->formula.empty() && mix > 0.0001f && combine != CombineOff;

    pwmPhase += pwmRate * dt * static_cast<float>(frames);
    while (pwmPhase >= 1.0f) pwmPhase -= 1.0f;
    const float pwmValue = std::sin(pwmPhase * 6.2831853f);

    for (auto &v : voices) {
        if (!v.used) continue;
        v.amp.set(0.0f, ampA, ampD, ampS, ampR, false);
        v.filter.set(cutoff, reso, ftype, dsp::MultiFilter::Clean, 0.0f);
        const float vel = 1.0f - velAmount + velAmount * v.velocity;

        for (int32_t i = 0; i < frames; ++i) {
            const float env = v.amp.next();
            if (env <= 0.0000005f && !v.gate) { v.used = false; break; }

            // The table clock: one step every 1/frameHz, arp, duty and
            // volume together, because that is how a tracker did it.
            v.frameAcc += frameHz * dt;
            while (v.frameAcc >= 1.0f) { v.frameAcc -= 1.0f; ++v.step; }
            int32_t arpSemis = 0, dutyTable = -1, volTable = 255;
            if (prog != nullptr) {
                if (!prog->arp.empty()) arpSemis = prog->arp.at(v.step);
                if (!prog->duty.empty()) dutyTable = std::clamp(prog->duty.at(v.step), 0, 255);
                if (!prog->vol.empty()) volTable = std::clamp(prog->vol.at(v.step), 0, 255);
            }

            if (v.glidePos < 1.0f) {
                v.glidePos = std::min(1.0f, v.glidePos + (glide > 0.001f ? dt / glide : 1.0f));
                v.freq = v.glideFrom + (mtof(static_cast<float>(v.note)) - v.glideFrom) * v.glidePos;
            } else {
                v.freq = mtof(static_cast<float>(v.note));
            }
            const float freq = v.freq * tune * bendMul * std::pow(2.0f, static_cast<float>(arpSemis) / 12.0f);

            int32_t duty = dutyTable >= 0 ? dutyTable : static_cast<int32_t>((paramOf(Duty) + pwmDepth * pwmValue) * 255.0f);
            const int32_t oscValue = oscSample(v, wave, duty, dt, freq, subLevel);

            // The formula's own clock. Keyed, it runs with the note - so an
            // expression is an instrument and not a tape.
            v.timeAcc += keyed ? (freq / 55.0) * timeScale : static_cast<double>(timeScale);
            while (v.timeAcc >= 1.0) { v.timeAcc -= 1.0; ++v.t; }

            int32_t value = oscValue;
            if (hasFormula) {
                rng = rng * 1664525u + 1013904223u;
                Vars vars;
                vars.t = static_cast<int32_t>(v.t);
                vars.f = static_cast<int32_t>(freq);
                vars.n = v.note;
                vars.v = static_cast<int32_t>(v.velocity * 127.0f);
                vars.x = oscValue;
                vars.a = macroA;
                vars.b = macroB;
                vars.c = macroC;
                vars.s = static_cast<int32_t>(v.step);
                vars.r = static_cast<int32_t>((rng >> 16) & 0xff);
                vars.sr = static_cast<int32_t>(sampleRate);
                const int32_t f = prog->formula.eval(vars) & 0xff;
                int32_t combined = f;
                switch (combine) {
                case Ring: combined = (f * oscValue) >> 8; break;
                case Gate: combined = f >= 128 ? oscValue : 128; break;
                case Xor: combined = f ^ oscValue; break;
                default: combined = f; break; // Replace
                }
                value = static_cast<int32_t>(oscValue + (combined - oscValue) * mix);
            }

            // The hardware's own limits, after everything: fewer bits, and a
            // slower clock.
            if (bits < 8) {
                const int32_t levels = 1 << bits;
                value = (value * levels / 256) * 256 / levels;
            }
            float sample = (static_cast<float>(value) - 128.0f) / 128.0f;
            if (crush > 1.001f) {
                v.crushAcc += 1.0f / crush;
                if (v.crushAcc >= 1.0f) { v.crushAcc -= 1.0f; v.held = sample; }
                sample = v.held;
            }
            if (smooth > 0.0001f) {
                v.smoothed += (sample - v.smoothed) * (1.0f - smoothCoeff);
                sample = v.smoothed;
            }
            sample *= env * vel * (static_cast<float>(volTable) / 255.0f);
            sample = v.filter.process(sample);
            L[i] += sample;
            R[i] += sample;
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float s = L[i] * volume * 0.5f;
        if (drive > 0.0001f) {
            const float k = 1.0f + drive * 12.0f;
            s = dsp::fastTanh(s * k) / std::sqrt(k);
        }
        L[i] = s * panL * 1.4142f;
        R[i] = s * panR * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
