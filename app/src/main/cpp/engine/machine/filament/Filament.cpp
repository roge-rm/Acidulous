#include "Filament.h"
#include <algorithm>
#include <cstdio>
#include <cstring>

namespace acidulous::machine {
using namespace dsp;

namespace {
constexpr float kPiF = 3.14159265f;
float noteToHz(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
// Intervals in semitones for the sympathetic bank, by tuning.
const int kSymIntervals[Filament::SymCount][Filament::kSympathetic] = {
    {-24, -12, 0, 12, 24, 36},   // octaves
    {-12, -5, 0, 7, 12, 19},     // fifths
    {0, 4, 7, 12, 16, 19},       // major
    {0, 3, 7, 12, 15, 19},       // minor
    {0, 12, 19, 24, 28, 31},     // harmonic series, near enough
    {-1, 0, 1, 11, 12, 13},      // unison, detuned: a course of six
};
} // namespace

Filament::Filament() { initParams(); }

const ParamDef *Filament::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static char names[Count][12];
    static bool built = false;
    if (!built) {
        auto put = [&](int32_t i, const char *n, float mn, float mx, float df, Curve c, int32_t steps,
                       const char *u) {
            std::snprintf(names[i], sizeof(names[i]), "%s", n);
            defs[i] = ParamDef{names[i], mn, mx, df, c, steps, u};
        };
        auto lin = [&](int32_t i, const char *n, float mn, float mx, float df, const char *u = "") {
            put(i, n, mn, mx, df, Curve::Linear, 0, u);
        };
        auto exp_ = [&](int32_t i, const char *n, float mn, float mx, float df, const char *u = "") {
            put(i, n, mn, mx, df, Curve::Exponential, 0, u);
        };
        auto step = [&](int32_t i, const char *n, int32_t c, float df) {
            put(i, n, 0.0f, static_cast<float>(c - 1), df, Curve::Stepped, c, "");
        };

        step(ExciterMode, "exciter", ExciterCount, 0.0f);
        lin(Position, "position", 0.02f, 0.5f, 0.22f);
        lin(Hardness, "hardness", 0.0f, 1.0f, 0.4f);
        lin(Pressure, "pressure", 0.0f, 1.0f, 0.5f);
        lin(Speed, "speed", 0.0f, 1.0f, 0.4f);
        lin(Noise, "grit", 0.0f, 1.0f, 0.5f);
        exp_(ExcitLength, "length", 0.0005f, 0.2f, 0.006f, "s");
        lin(ExternalGain, "in gain", 0.0f, 4.0f, 1.0f);

        lin(Damping, "sustain", 0.0f, 1.0f, 0.8f);
        lin(DampingKey, "sustainkey", 0.0f, 1.0f, 0.5f);
        lin(Tone, "tone", 0.0f, 1.0f, 0.45f);
        lin(ToneKey, "tonekey", 0.0f, 1.0f, 0.4f);
        lin(Dispersion, "stiffness", 0.0f, 1.0f, 0.0f);
        put(DispersionStages, "stages", 0.0f, 4.0f, 2.0f, Curve::Stepped, 5, "");
        lin(Tension, "tension", 0.0f, 1.0f, 0.15f);

        lin(DamperPos, "damper at", 0.0f, 1.0f, 0.5f);
        lin(DamperPressure, "damper", 0.0f, 1.0f, 0.0f);
        lin(Rattle, "rattle", 0.0f, 1.0f, 0.0f);
        lin(RattleThreshold, "rattle at", 0.01f, 1.0f, 0.35f);

        lin(Detune, "detune", 0.0f, 50.0f, 3.0f, "c");
        lin(Spread, "spread", 0.0f, 1.0f, 0.35f);
        lin(Couple, "couple", 0.0f, 1.0f, 0.25f);

        step(SympatheticOn, "sympathy", 2, 0.0f);
        step(SympatheticTune, "symtune", SymCount, 0.0f);
        lin(SympatheticLevel, "symlevel", 0.0f, 1.0f, 0.35f);
        lin(SympatheticDamping, "symsustain", 0.0f, 1.0f, 0.9f);
        lin(SympatheticSpread, "symwide", 0.0f, 1.0f, 0.7f);

        step(BodyOn, "body", 2, 1.0f);
        lin(BodySize, "size", 0.0f, 1.0f, 0.5f);
        lin(BodyMix, "bodymix", 0.0f, 1.0f, 0.35f);
        lin(BodyDamp, "bodydamp", 0.0f, 1.0f, 0.5f);

        lin(Drive, "drive", 0.0f, 1.0f, 0.1f);
        lin(Volume, "volume", 0.0f, 1.0f, 0.8f);
        lin(Pan, "pan", -1.0f, 1.0f, 0.0f);
        lin(Dry, "exciter out", 0.0f, 1.0f, 0.0f);

        exp_(AmpAttack, "attack", 0.0005f, 0.5f, 0.001f, "s");
        exp_(AmpRelease, "damp time", 0.01f, 4.0f, 0.25f, "s");
        exp_(Eg1A, "eg1atk", 0.001f, 8.0f, 0.01f, "s");
        exp_(Eg1D, "eg1dec", 0.005f, 12.0f, 0.5f, "s");
        lin(Eg1S, "eg1sus", 0.0f, 1.0f, 0.5f);
        exp_(Eg1R, "eg1rel", 0.005f, 12.0f, 0.4f, "s");
        exp_(Eg2A, "eg2atk", 0.001f, 8.0f, 1.0f, "s");
        exp_(Eg2D, "eg2dec", 0.005f, 12.0f, 2.0f, "s");
        lin(Eg2S, "eg2sus", 0.0f, 1.0f, 1.0f);
        exp_(Eg2R, "eg2rel", 0.005f, 12.0f, 1.0f, "s");
        step(Lfo1Wave, "lfo1wave", LfoGen::WaveCount, 0.0f);
        exp_(Lfo1Rate, "lfo1rate", 0.01f, 20.0f, 0.4f, "Hz");
        step(Lfo1Sync, "lfo1sync", 6, 0.0f);
        lin(Lfo1Depth, "lfo1depth", 0.0f, 1.0f, 1.0f);
        step(Lfo2Wave, "lfo2wave", LfoGen::WaveCount, 1.0f);
        exp_(Lfo2Rate, "lfo2rate", 0.01f, 20.0f, 3.0f, "Hz");
        step(Lfo2Sync, "lfo2sync", 6, 0.0f);
        lin(Lfo2Depth, "lfo2depth", 0.0f, 1.0f, 1.0f);
        for (int m = 0; m < kMatrixSlots; ++m) {
            char n[12];
            const int base = MatrixBase + m * kMatrixParams;
            std::snprintf(n, sizeof(n), "m%d_src", m + 1);
            step(base + XSrc, n, SourceCount, 0.0f);
            std::snprintf(n, sizeof(n), "m%d_dst", m + 1);
            step(base + XDest, n, DestCount, 0.0f);
            std::snprintf(n, sizeof(n), "m%d_amt", m + 1);
            lin(base + XDepth, n, -1.0f, 1.0f, 0.0f);
        }
        step(VoiceMode, "voicemode", 3, 0.0f);
        exp_(Glide, "glide", 0.001f, 2.0f, 0.001f, "s");
        lin(BendRange, "bend", 0.0f, 12.0f, 2.0f);
        lin(Octave, "octave", -3.0f, 3.0f, 0.0f);
        lin(Transpose, "transpose", -12.0f, 12.0f, 0.0f);
        lin(Fine, "fine", -50.0f, 50.0f, 0.0f, "c");
        lin(VelocityAmount, "velocity", 0.0f, 1.0f, 0.8f);
        step(Release, "on release", 2, 1.0f); // ring on, or damp
        built = true;
    }
    count = Count;
    return defs;
}

int32_t Filament::steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

void Filament::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.a.prepare(sampleRate);
        v.b.prepare(sampleRate);
    }
    for (auto &s : sympathetic) s.prepare(sampleRate);
    for (auto &e : eg) e.setSampleRate(sampleRate);
    lastTuning = -1;
    reset();
}

void Filament::reset() {
    for (auto &v : voices) {
        v.used = false;
        v.gate = false;
        v.a.clear();
        v.b.clear();
        v.exciteLeft = 0;
    }
    for (auto &s : sympathetic) s.clear();
    for (auto &bq : body) bq.reset();
    stringLevel = 0.0f;
}

// The sympathetic bank follows whatever was played last, so it is a set of
// strings in the same key rather than a fixed drone.
void Filament::retuneSympathetic(float rootHz) {
    const int32_t tuning = steppedOf(SympatheticTune);
    if (std::fabs(rootHz - lastRoot) < 0.5f && tuning == lastTuning) return;
    lastRoot = rootHz;
    lastTuning = tuning;
    const float spread = paramOf(SympatheticSpread);
    for (int i = 0; i < kSympathetic; ++i) {
        const float semis = static_cast<float>(kSymIntervals[tuning][i]);
        // A little out, by design: six strings exactly in tune do not shimmer.
        const float cents = (static_cast<float>(i) - 2.5f) * 2.0f * spread;
        sympatheticHz[i] = rootHz * std::pow(2.0f, (semis + cents * 0.01f) / 12.0f);
        sympathetic[i].setFrequency(sympatheticHz[i]);
    }
}

void Filament::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = nullptr;
    for (auto &cand : voices) if (!cand.used) { v = &cand; break; }
    if (v == nullptr) {
        // Steal the quietest string rather than the oldest: a note that has
        // decayed is the one nobody will miss.
        float quietest = 1e9f;
        for (auto &cand : voices) {
            const float l = cand.a.level();
            if (l < quietest) { quietest = l; v = &cand; }
        }
    }
    v->used = true;
    v->gate = true;
    v->note = note;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->key01 = clampf((static_cast<float>(note) - 24.0f) / 72.0f, 0.0f, 1.0f);
    v->target = noteToHz(static_cast<float>(note));
    v->freq = v->target;
    v->damp = 0.0f;
    rngState = rngState * 1664525u + 1013904223u;
    v->pan = ((static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f);
    const float velAmt = paramOf(VelocityAmount);
    v->exciteGain = (1.0f - velAmt + velAmt * v->velocity);
    const int32_t mode = steppedOf(ExciterMode);
    const float lengthSeconds = mode == Bow || mode == Breath || mode == External
                                    ? 0.0f
                                    : paramOf(ExcitLength);
    v->exciteLeft = static_cast<int32_t>(lengthSeconds * sampleRate);
    retuneSympathetic(v->target);
}

void Filament::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            // Letting go either damps the string or leaves it ringing, which
            // is the difference between a piano with the pedal down and up.
            v.damp = steppedOf(Release) != 0 ? 1.0f : 0.0f;
        }
    }
}

void Filament::allNotesOff() {
    for (auto &v : voices) { v.gate = false; v.damp = 1.0f; }
}

void Filament::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Filament::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }
void Filament::pitchBend(int16_t value14) {
    bendSemis = (static_cast<float>(value14) / 8192.0f) * paramOf(BendRange);
}
void Filament::onBlock(int64_t, int64_t, float tempo) { bpm = tempo > 1.0f ? tempo : 120.0f; }

float Filament::sourceValue(int32_t src, const Voice &v) const {
    switch (src) {
    case SrcOn: return 1.0f;
    case SrcModWheel: return modWheel;
    case SrcPressure: return pressure;
    case SrcVelocity: return v.velocity;
    case SrcKeyTrack: return v.key01;
    case SrcRandom: return v.pan * 0.5f + 0.5f;
    case SrcEg1: return eg[0].value();
    case SrcEg2: return eg[1].value();
    case SrcLfo1: return lfoValue[0];
    case SrcLfo2: return lfoValue[1];
    case SrcStringLevel: return clampf(v.a.level() * 8.0f, 0.0f, 1.0f);
    default: return 0.0f;
    }
}

void Filament::applyMatrix(const Voice &v, float *dest) {
    for (int32_t d = 0; d < DestCount; ++d) dest[d] = 0.0f;
    for (int m = 0; m < kMatrixSlots; ++m) {
        const int base = MatrixBase + m * kMatrixParams;
        const int32_t src = steppedOf(base + XSrc);
        const int32_t dst = steppedOf(base + XDest);
        if (src == SrcOff || dst == DstOff) continue;
        dest[dst] += sourceValue(src, v) * paramOf(base + XDepth);
    }
}

bool Filament::render(float *L, float *R, int32_t frames) {
    params_.tick();
    const float dt = static_cast<float>(frames) / sampleRate;
    static const float kSyncBeats[6] = {0.0f, 4.0f, 2.0f, 1.0f, 0.5f, 1.0f / 3.0f};
    for (int i = 0; i < 2; ++i) {
        const int32_t sync = steppedOf(i == 0 ? Lfo1Sync : Lfo2Sync);
        const float free = paramOf(i == 0 ? Lfo1Rate : Lfo2Rate);
        const float hz = sync == 0 ? free : (bpm / 60.0f) / kSyncBeats[sync];
        lfoValue[i] = lfo[i].advance(steppedOf(i == 0 ? Lfo1Wave : Lfo2Wave), hz, dt, 0.0f, false) *
                      paramOf(i == 0 ? Lfo1Depth : Lfo2Depth);
    }
    eg[0].set(0.0f, paramOf(Eg1A), paramOf(Eg1D), paramOf(Eg1S), paramOf(Eg1R), false);
    eg[1].set(0.0f, paramOf(Eg2A), paramOf(Eg2D), paramOf(Eg2S), paramOf(Eg2R), false);

    const int32_t mode = steppedOf(ExciterMode);
    const float position = paramOf(Position);
    const float hardness = paramOf(Hardness);
    const float bowPressure = paramOf(Pressure);
    const float bowSpeed = paramOf(Speed);
    const float grit = paramOf(Noise);
    const float externalGain = paramOf(ExternalGain);
    const float dispersion = paramOf(Dispersion);
    const int32_t stages = steppedOf(DispersionStages);
    const float tension = paramOf(Tension);
    const float detune = paramOf(Detune);
    const float spread = paramOf(Spread);
    const float couple = paramOf(Couple);
    const bool symOn = steppedOf(SympatheticOn) != 0;
    const float symLevel = paramOf(SympatheticLevel);
    const float symDamping = paramOf(SympatheticDamping);
    const bool bodyOn = steppedOf(BodyOn) != 0;
    const float bodyMix = paramOf(BodyMix);
    const float drive = paramOf(Drive);
    const float volume = paramOf(Volume);
    const float panBase = paramOf(Pan);
    const float dry = paramOf(Dry);
    const float pitchScale = std::pow(2.0f, (bendSemis + paramOf(Octave) * 12.0f + paramOf(Transpose) +
                                             paramOf(Fine) * 0.01f) / 12.0f);
    const float releaseCoeff = 1.0f - std::exp(-1.0f / std::fmax(1.0f, paramOf(AmpRelease) * sampleRate));
    const float rattle = paramOf(Rattle);
    const float rattleAt = paramOf(RattleThreshold);

    if (bodyOn) {
        // Four modes standing in for a box. Size moves them together; damp
        // widens them, which is the difference between a guitar and a crate.
        static const float kModeHz[kBodyModes] = {110.0f, 220.0f, 400.0f, 780.0f};
        const float size = std::pow(2.0f, (0.5f - paramOf(BodySize)) * 2.2f);
        const float q = 1.0f + (1.0f - paramOf(BodyDamp)) * 9.0f;
        for (int i = 0; i < kBodyModes; ++i) {
            body[i].peak(clampf(kModeHz[i] * size, 40.0f, 6000.0f), 9.0f, q, sampleRate);
        }
    }
    for (int i = 0; i < kSympathetic; ++i) {
        sympathetic[i].setDamping(0.95f + 0.05f * symDamping, 0.2f + 0.6f * paramOf(Tone));
        sympathetic[i].setDispersion(dispersion * 0.7f, stages);
    }

    const InputBus &bus = InputBus::get();
    const float *in = bus.live() ? bus.block() : nullptr;
    float symFeed = 0.0f;

    for (int32_t n = 0; n < frames; ++n) {
        float mixL = 0.0f, mixR = 0.0f, exciterOut = 0.0f;
        float sumForSympathy = 0.0f;
        float loudest = 0.0f;

        for (auto &v : voices) {
            if (!v.used) continue;
            applyMatrix(v, mod);

            // Damping, brightness and tuning, note by note: a short string
            // rings for less time and darker, as a real one does.
            const float keyDamp = 1.0f - paramOf(DampingKey) * v.key01 * 0.35f;
            const float sustain = clampf(paramOf(Damping) + mod[DstDamping], 0.0f, 1.0f) * keyDamp;
            const float loopGain = 0.9f + 0.0999f * sustain - v.damp * (1.0f - std::exp(-releaseCoeff)) * 0.0f;
            const float keyTone = 1.0f - paramOf(ToneKey) * v.key01 * 0.5f;
            const float tone = clampf((paramOf(Tone) + mod[DstTone]) * keyTone, 0.02f, 1.0f);
            const float freq = v.freq * pitchScale * std::pow(2.0f, mod[DstPitch]);

            // Letting go: the string is damped by shortening its loop gain
            // over the release time rather than by an envelope, because a
            // damped string is still a string.
            if (v.damp > 0.0f) v.damp = std::fmin(1.0f, v.damp + releaseCoeff * 4.0f);
            const float damped = v.damp > 0.0f ? loopGain * (1.0f - 0.06f * v.damp) : loopGain;

            v.a.setFrequency(freq);
            v.b.setFrequency(freq * std::pow(2.0f, (detune + mod[DstDetune] * 50.0f) / 1200.0f));
            v.a.setDamping(damped, tone);
            v.b.setDamping(damped, tone);
            v.a.setDispersion(clampf(dispersion + mod[DstDispersion], 0.0f, 1.0f), stages);
            v.b.setDispersion(clampf(dispersion + mod[DstDispersion], 0.0f, 1.0f), stages);
            v.a.setTension(clampf(tension + mod[DstTension], 0.0f, 1.0f));
            v.b.setTension(clampf(tension + mod[DstTension], 0.0f, 1.0f));
            const float damperP = clampf(paramOf(DamperPos) + mod[DstDamperPos], 0.0f, 1.0f);
            const float damperF = clampf(paramOf(DamperPressure) + mod[DstDamperPressure], 0.0f, 1.0f);
            v.a.setDamper(damperP, damperF);
            v.b.setDamper(damperP, damperF);

            // Excitation.
            float excite = 0.0f;
            rngState = rngState * 1664525u + 1013904223u;
            const float noise = (static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f;
            switch (mode) {
            case Pluck:
            case Pick:
                if (v.exciteLeft > 0) {
                    const float shape = mode == Pick ? (v.exciteLeft % 2 ? 1.0f : -1.0f) : 1.0f;
                    excite = noise * (0.3f + 0.7f * grit) * shape * v.exciteGain;
                    --v.exciteLeft;
                }
                break;
            case Hammer:
                if (v.exciteLeft > 0) {
                    // A hammer is a soft impulse, and softer the harder you
                    // hit it: felt compresses.
                    const float t = 1.0f - static_cast<float>(v.exciteLeft) / std::fmax(1.0f, paramOf(ExcitLength) * sampleRate);
                    const float soft = std::sin(kPiF * t);
                    excite = soft * soft * (1.0f - hardness * 0.6f) * v.exciteGain * 2.0f;
                    --v.exciteLeft;
                }
                break;
            case Bow: {
                if (!v.gate) break;
                // Friction against the string as it is now: the bow grips
                // while the two move together and lets go when the string
                // slips past it. The curve has to fall away on both sides or
                // the bow only ever adds energy and the note runs away.
                const float p = clampf(bowPressure + mod[DstPressure], 0.0f, 1.0f);
                const float relative = bowSpeed * 0.5f - v.a.velocity();
                const float width = 0.08f + 0.5f * (1.0f - p);
                const float grip = relative / (width + relative * relative / width);
                excite = (grip * p * 0.12f + noise * grit * 0.01f) * v.exciteGain;
                break;
            }
            case Breath:
                if (!v.gate) break;
                // An air jet saturates as the resonator fills: past a point
                // blowing harder does not make it louder, it makes it
                // overblow, which is the instrument, not a bug.
                excite = (noise * (0.2f + 0.8f * grit) * 0.25f + 0.02f) *
                         clampf(bowPressure + mod[DstPressure], 0.0f, 1.0f) * v.exciteGain *
                         (1.0f - std::tanh(std::fabs(v.a.velocity()) * 1.6f) * 0.9f);
                break;
            case External:
                // The string is played by whatever is coming in. This is the
                // one a sampled string library cannot do at all.
                if (in != nullptr) {
                    excite = 0.5f * (in[static_cast<size_t>(n) * 2] + in[static_cast<size_t>(n) * 2 + 1]) *
                             externalGain * v.exciteGain;
                }
                break;
            default: break;
            }
            exciterOut += excite;

            // Pick position: the same disturbance a little later, inverted,
            // which is a comb and is why a bridge pickup is thin.
            const float pos = clampf(position + mod[DstPosition], 0.02f, 0.5f);
            const float a = v.a.step(excite);
            const float b = v.b.step(excite * (1.0f - pos));
            const float coupled = (a + b) * 0.5f;
            v.a.excite(b * couple * 0.02f);
            v.b.excite(a * couple * 0.02f);

            float voiceOut = coupled;
            if (rattle > 0.0f && std::fabs(voiceOut) > rattleAt) {
                // Something loose on the string: it buzzes only when driven.
                const float over = std::fabs(voiceOut) - rattleAt;
                voiceOut += (voiceOut > 0.0f ? -1.0f : 1.0f) * over * rattle * 1.6f;
            }
            sumForSympathy += voiceOut;
            loudest = std::fmax(loudest, v.a.level());

            const float pan = clampf(panBase + v.pan * spread + mod[DstPan], -1.0f, 1.0f);
            const float angle = (pan + 1.0f) * 0.25f * kPiF;
            mixL += voiceOut * std::cos(angle) * 1.4142f;
            mixR += voiceOut * std::sin(angle) * 1.4142f;

            if (!v.gate && v.a.level() < 0.00005f && v.b.level() < 0.00005f && v.exciteLeft <= 0) {
                v.used = false;
            }
        }

        // The sympathetic bank hears everything and answers.
        if (symOn) {
            symFeed = sumForSympathy * clampf(symLevel + mod[DstSympathetic], 0.0f, 1.0f) * 0.08f;
            float symOut = 0.0f;
            for (int i = 0; i < kSympathetic; ++i) symOut += sympathetic[i].step(symFeed);
            symOut *= 0.2f * clampf(symLevel + mod[DstSympathetic], 0.0f, 1.0f);
            mixL += symOut;
            mixR += symOut * 0.85f;
        }

        float outL = mixL, outR = mixR;
        if (bodyOn) {
            float bl = 0.0f, br = 0.0f;
            for (int i = 0; i < kBodyModes; ++i) {
                const float s = body[i].process(i == 0 ? mixL : mixR);
                if (i % 2 == 0) bl += s; else br += s;
            }
            outL = mixL * (1.0f - bodyMix) + bl * bodyMix * 0.5f;
            outR = mixR * (1.0f - bodyMix) + br * bodyMix * 0.5f;
        }
        outL += exciterOut * dry;
        outR += exciterOut * dry;
        if (drive > 0.0f) {
            outL = std::tanh(outL * (1.0f + drive * 8.0f));
            outR = std::tanh(outR * (1.0f + drive * 8.0f));
        }
        L[n] = outL * volume;
        R[n] = outR * volume;
        stringLevel = loudest;
    }
    return true;
}

} // namespace acidulous::machine
