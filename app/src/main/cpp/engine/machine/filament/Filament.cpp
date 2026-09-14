#include "Filament.h"
#include <engine/machine/Voices.h>
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
        lin(MpeTimbre, "mpetimbre", 0.0f, 1.0f, 0.0f);
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
        // Half of the longest string this machine can hold, which is as far
        // back as a pick can be from the bridge.
        v.pick.assign(static_cast<size_t>(sampleRate / 36.0f) + 4, 0.0f);
        v.pickWrite = 0;
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
        v.exciteDc = v.lastPick = 0.0f;
        std::fill(v.pick.begin(), v.pick.end(), 0.0f);
        v.pickWrite = 0;
    }
    for (auto &s : sympathetic) s.clear();
    for (auto &bq : body) bq.reset();
    stringLevel = 0.0f;
    rngState = kRngSeed;
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
    v->bend = 0.0f;
    v->pressure = v->timbre = -1.0f;
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
    // **A pluck displaces the whole string, so it fills the whole loop.**
    //
    // `length` is the contact time - how long a finger or a pick is against
    // the string - and it is a time, honestly. But the *displacement* it
    // leaves behind is spread along the entire string, and a waveguide's
    // string is one period long. Put in as a burst shorter than that and the
    // burst simply goes round as a burst: measured on the nylon patch at
    // note 52, one millisecond of excitation in a six millisecond loop came
    // out as two milliseconds of sound and four of near silence, over and
    // over - a pulse train at the right pitch rather than a string, thirty
    // decibels between the loud part and the quiet part of every period.
    //
    // It was also the last of this machine's fixed times that should have
    // been a turn: at the bottom of a range the burst covered a sixth of the
    // loop and at the top two thirds of it, so the same patch was a different
    // instrument at each end and seven decibels louder at the top.
    //
    // A **hammer** is the other case and does not get this. It does not
    // displace the string, it hits one point of it and leaves: the contact
    // time is the whole of what it has to say, and stretching it to a period
    // put the struck patch's octave above its fundamental and tripled the
    // corner at its onset. Pluck and pick set a shape along the string;
    // hammer, bow and breath act at a point.
    // A **hammer** does not get that: it does not displace the string, it hits
    // one point of it and leaves. But its contact time is a fraction of the
    // string's period and not a fixed number of milliseconds, for the same
    // reason a piano's treble hammers are harder and lighter than its bass
    // ones. Fixed, the default six milliseconds is two thirds of a period at
    // the bottom of a range and four whole periods at the top, and a pulse
    // four periods long has nothing at the note to give it: the prepared
    // patch was twelve decibels quieter at the top of its range than the
    // bottom with an identical peak, which is a hammer too slow to move the
    // string it is hitting. `length` is read at middle C and scales from
    // there, so every note is struck the same way and what changes across a
    // range is the string rather than the hammer.
    const float turn = sampleRate / std::max(20.0f, v->target);
    const bool spread = mode == Pluck || mode == Pick;
    const float contact = mode == Hammer ? lengthSeconds * 261.63f / std::max(20.0f, v->target)
                                         : lengthSeconds;
    // A shorter contact is a smaller blow unless the hammer hits harder for
    // it, and a hammer of one mass at one speed carries the same energy
    // whatever it lands on. Without this the scaling above traded a twelve
    // decibel taper for a fifteen: the struck patch spans four octaves and
    // its contact at the top is a quarter of the one at the bottom.
    if (mode == Hammer) {
        v->exciteGain *= clampf(std::sqrt(v->target / 261.63f), 0.35f, 3.0f);
    }
    v->exciteLeft = static_cast<int32_t>(spread ? std::max(contact * sampleRate, turn)
                                                : contact * sampleRate);
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

void Filament::noteBend(uint8_t note, float semitones) {
    if (Voice *v = voiceForNote(voices, note)) v->bend = semitones;
}

void Filament::notePressure(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->pressure = static_cast<float>(value) / 127.0f;
}

void Filament::noteTimbre(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->timbre = static_cast<float>(value) / 127.0f;
}
void Filament::onBlock(int64_t, int64_t, float tempo) { bpm = tempo > 1.0f ? tempo : 120.0f; }

float Filament::sourceValue(int32_t src, const Voice &v) const {
    switch (src) {
    case SrcOn: return 1.0f;
    case SrcModWheel: return modWheel;
    // A finger's own pressure if it sent any, the channel's otherwise.
    case SrcPressure: return v.pressure >= 0.0f ? v.pressure : pressure;
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
    // Thirty hertz: under every note this machine plays and over everything
    // a player's arm does.
    const float exciteDcCoeff = 1.0f - std::exp(-2.0f * kPiF * 30.0f / sampleRate);
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
        // ...and the sympathetic strings the same way, against their own
        // pitches, or the high ones in the bank die while the low ones hang.
        const float symRing = 0.1f * std::pow(60.0f, symDamping);
        sympathetic[i].setDamping(
            std::fmin(0.99995f, std::exp(-1.0f / (std::fmax(20.0f, sympatheticHz[i]) * symRing))),
            0.2f + 0.6f * paramOf(Tone));
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
            const float keyTone = 1.0f - paramOf(ToneKey) * v.key01 * 0.5f;
            const float tone = clampf((paramOf(Tone) + mod[DstTone]) * keyTone, 0.02f, 1.0f);
            const float freq = v.freq * pitchScale * noteBendMul(v) * std::pow(2.0f, mod[DstPitch]);

            // **How long the string rings is a time, not a loop gain.**
            //
            // A fixed gain per turn is a fixed loss per turn, and a string
            // goes round f times a second - so the same number gave a low
            // string twice the ring of the one an octave up and four times
            // the one above that. Two octaves up from the note it was voiced
            // at, every patch here was gone before the harness could find a
            // pitch in it: not a wrong note, no note. The same law the
            // woodwinds' walls needed, in the other direction.
            //
            // So `sustain` is a time - a twentieth of a second at nothing, a
            // little over half a second in the middle, eight at the top - and the gain follows
            // from it and the pitch. `sustainkey` then does what it says
            // instead of the opposite: it shortens the top of the keyboard a
            // little, as a real instrument's short strings are.
            //
            // Letting go shortens that time rather than scaling the gain,
            // because a damped string is still a string and a hand on a high
            // one stops it just as fast as on a low one.
            if (v.damp > 0.0f) v.damp = std::fmin(1.0f, v.damp + releaseCoeff * 4.0f);
            const float keyDamp = 1.0f - paramOf(DampingKey) * v.key01 * 0.35f;
            const float sustain = clampf(paramOf(Damping) + mod[DstDamping], 0.0f, 1.0f);
            const float ring = 0.05f * std::pow(160.0f, sustain) * keyDamp * (1.0f - 0.92f * v.damp);
            const float damped = std::fmin(
                0.99995f, std::exp(-1.0f / (std::fmax(20.0f, freq) * std::fmax(0.002f, ring))));

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
                    // A pick is a harder, narrower contact than a finger, so
                    // what it puts in is the same disturbance with more of
                    // its top: a first difference, which is six decibels an
                    // octave of tilt and nothing else.
                    //
                    // It used to be the noise multiplied by an alternating
                    // plus and minus one - a square wave at Nyquist, which
                    // does not brighten a spectrum, it *inverts* it, and
                    // puts the whole burst up against the sample rate where
                    // no string has a partial. The Wire patch started twelve
                    // times brighter than it continued and no setting on the
                    // string could reach it, because none of it was on the
                    // string.
                    const float raw = noise * (0.3f + 0.7f * grit);
                    excite = (mode == Pick ? (raw - v.lastPick * 0.7f) * 1.25f : raw) * v.exciteGain;
                    v.lastPick = raw;
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
            // **Nothing slow goes into a string.**
            //
            // A bow leans on a string with a steady force and a jet blows
            // at it with a steady pressure, and neither is a wave: a steady
            // force is a static deflection that the two ends take. So the
            // slow part of the drive does not go in. (On its own this was
            // not what cured the bow's wander - that was the loop's own
            // high-pass, which now sits under the note; see kDcBelow. This
            // is here because it is true, and it keeps a hammer's blow,
            // which is a squared sine and never negative, from leaning on
            // the string after it has left.)
            v.exciteDc += (excite - v.exciteDc) * exciteDcCoeff;
            excite -= v.exciteDc;
            exciterOut += excite;

            // **Pick position.** A string plucked a fraction b along its
            // length gets harmonic n in proportion to sin(n.pi.b): every
            // harmonic with a node under the pick is missing, which is why
            // playing by the bridge is thin and nasal and over the hole is
            // round. In a waveguide that is one comb on the excitation,
            // `1 - z^-bL` over a string L samples long - its nulls fall
            // exactly where sin(n.pi.b) does, and at the middle of the
            // string it takes out the even harmonics and leaves the
            // fundamental at its loudest, which is what plucking there does.
            //
            // This comment described that and the code underneath it did
            // something else: it handed the *second string of the course* a
            // fraction less excitation. That is a level difference between
            // two strings, not a pick position, and it was measurably
            // nothing - moving `position` from 0.08 to 0.3 on the steel
            // patch changed no column at all. Four patches set it expecting
            // the comb.
            //
            // Slide walks the pick up the string, which is the brightest
            // thing a finger can do to one.
            const float slide = v.timbre >= 0.0f ? v.timbre : 0.0f;
            const float pos = clampf(position + mod[DstPosition] +
                                         slide * paramOf(MpeTimbre) * 0.28f,
                                     0.02f, 0.5f);
            // A pluck, a pick and a hammer all act at one point and leave,
            // so all three get the comb - a piano's strike point at a
            // seventh of the string, chosen to lose the seventh harmonic, is
            // the same fact. A bow or a jet is a force held against the
            // string for the whole note and does not comb it this way.
            const bool atAPoint = mode == Pluck || mode == Pick || mode == Hammer;
            if (atAPoint && !v.pick.empty()) {
                const auto size = static_cast<int32_t>(v.pick.size());
                const int32_t back = std::clamp(
                    static_cast<int32_t>(pos * sampleRate / std::max(20.0f, freq)), 1, size - 1);
                const int32_t at = (v.pickWrite - back + size) % size;
                const float earlier = v.pick[static_cast<size_t>(at)];
                v.pick[static_cast<size_t>(v.pickWrite)] = excite;
                v.pickWrite = (v.pickWrite + 1) % size;
                // Halved, because a comb peaks at twice what goes into it.
                excite = (excite - earlier) * 0.5f;
            }
            const float a = v.a.step(excite);
            const float b = v.b.step(excite);
            const float coupled = (a + b) * 0.5f;
            // A course is two strings over one bridge, and a bridge *shares*
            // energy rather than making it. Each string is given a fraction
            // of the difference, so what one gains the other loses and the
            // pair rings exactly as long as one string would; and the
            // fraction is a fraction of a whole turn, not of a sample.
            //
            // Handing each string the other's output per sample did both
            // things wrong. Over a 145-sample turn at `couple` 0.8 that was
            // two and a third added to a loop gain already at 0.999, so the
            // Wire patch was fourteen decibels louder with the coupling on
            // than off and its note-on read fifty thousand times the edge of
            // its own tone.
            const float share = (b - a) * couple * 0.05f;
            v.a.exciteOverTurn(share);
            v.b.exciteOverTurn(-share);

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
            // ...per turn as well, and for the same reason: six strings each
            // given eight per cent of the played one every sample is forty
            // times a turn at the bottom of the range, and they never stopped.
            symFeed = sumForSympathy * clampf(symLevel + mod[DstSympathetic], 0.0f, 1.0f) * 0.6f;
            float symOut = 0.0f;
            for (int i = 0; i < kSympathetic; ++i)
                symOut += sympathetic[i].step(symFeed * sympathetic[i].turnScale());
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
