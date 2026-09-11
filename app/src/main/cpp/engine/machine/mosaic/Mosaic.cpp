#include "Mosaic.h"

#include <cstdio>

namespace acidulous::machine {

using dsp::clampf;
using dsp::kPi;
using dsp::kTwoPi;
using dsp::mtof;

namespace {
constexpr int kSyncCount = 9;
constexpr float kSyncBeats[kSyncCount] = {0.0f, 0.25f, 0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f, 32.0f};

inline float rnd(uint32_t &s) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return static_cast<float>(s & 0xffffff) / 16777216.0f;
}
} // namespace

Mosaic::Mosaic() { initParams(); }

const ParamDef *Mosaic::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static char names[Count][16];
    static bool built = false;
    if (!built) {
        auto putN = [&](int32_t i, const char *name, float mn, float mx, float df, Curve c, int32_t steps,
                        const char *unit) {
            std::snprintf(names[i], sizeof(names[i]), "%s", name);
            defs[i] = {names[i], mn, mx, df, c, steps, unit};
        };
        auto put = [&](int32_t i, const char *fmt, int arg, float mn, float mx, float df, Curve c, int32_t steps,
                       const char *unit) {
            std::snprintf(names[i], sizeof(names[i]), fmt, arg);
            defs[i] = {names[i], mn, mx, df, c, steps, unit};
        };
        putN(LayerScan, "scan", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, "");
        putN(ScanAmount, "scanamt", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(KeyFade, "keyfade", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, "st");
        putN(VelFade, "velfade", 0.0f, 64.0f, 0.0f, Curve::Linear, 0, "");
        putN(Start, "start", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(LoopModeIndex, "loop", 0.0f, LoopModeCount - 1.0f, 0.0f, Curve::Stepped, LoopModeCount, "");
        putN(Reverse, "reverse", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, "");
        putN(EnvSourceIndex, "envfrom", 0.0f, EnvSourceCount - 1.0f, 0.0f, Curve::Stepped, EnvSourceCount, "");
        putN(GrainMode, "grain", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, "");
        putN(GrainPos, "gpos", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(GrainRate, "grate", -2.0f, 2.0f, 1.0f, Curve::Linear, 0, "");
        putN(GrainSize, "gsize", 5.0f, 500.0f, 80.0f, Curve::Exponential, 0, "ms");
        putN(GrainDensity, "gdensity", 1.0f, 120.0f, 20.0f, Curve::Exponential, 0, "");
        putN(GrainSpray, "gspray", 0.0f, 1.0f, 0.05f, Curve::Linear, 0, "");
        putN(GrainPitch, "gpitch", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, "st");
        putN(AmpAttack, "a_attack", 0.001f, 10.0f, 0.002f, Curve::Exponential, 0, "s");
        putN(AmpDecay, "a_decay", 0.002f, 15.0f, 1.0f, Curve::Exponential, 0, "s");
        putN(AmpSustain, "a_sustain", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, "");
        putN(AmpRelease, "a_release", 0.002f, 15.0f, 0.15f, Curve::Exponential, 0, "s");
        putN(FilterType, "f_type", 0.0f, dsp::MultiFilter::TypeCount - 1.0f, 3.0f, Curve::Stepped,
             dsp::MultiFilter::TypeCount, "");
        putN(FilterFreq, "f_freq", 20.0f, 20000.0f, 18000.0f, Curve::Exponential, 0, "Hz");
        putN(FilterRes, "f_res", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(FilterEnv, "f_env", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(FilterKey, "f_key", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(FilterAttack, "f_attack", 0.001f, 10.0f, 0.002f, Curve::Exponential, 0, "s");
        putN(FilterDecay, "f_decay", 0.002f, 15.0f, 0.8f, Curve::Exponential, 0, "s");
        putN(FilterSustain, "f_sustain", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, "");
        putN(FilterRelease, "f_release", 0.002f, 15.0f, 0.3f, Curve::Exponential, 0, "s");
        putN(Coarse, "coarse", -24.0f, 24.0f, 0.0f, Curve::Stepped, 49, "st");
        putN(Fine, "fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "");
        putN(Glide, "glide", 0.0f, 2.0f, 0.0f, Curve::Linear, 0, "s");
        putN(GlideMode, "glidemode", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, "");
        putN(BendRange, "bend", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, "st");
        putN(Octave, "octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, "");
        putN(Transpose, "transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, "st");
        putN(VoiceMode, "voicemode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, "");
        putN(Volume, "volume", 0.0f, 1.0f, 0.8f, Curve::Linear, 0, "");
        putN(Pan, "pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        putN(VelocityAmount, "velamt", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, "");
        putN(VelToFilter, "veltofilter", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        for (int l = 0; l < kLfos; ++l) {
            const int32_t b = LfoBase + l * LfoParams;
            const int n = l + 1;
            put(b + LWave, "l%d_wave", n, 0.0f, dsp::LfoGen::WaveCount - 1.0f, 0.0f, Curve::Stepped,
                dsp::LfoGen::WaveCount, "");
            put(b + LRate, "l%d_rate", n, 0.01f, 40.0f, 3.0f, Curve::Exponential, 0, "Hz");
            put(b + LSync, "l%d_sync", n, 0.0f, kSyncCount - 1.0f, 0.0f, Curve::Stepped, kSyncCount, "");
            put(b + LDelay, "l%d_delay", n, 0.0f, 5.0f, 0.0f, Curve::Linear, 0, "s");
            put(b + LPhase, "l%d_phase", n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
            put(b + LKeySync, "l%d_keysync", n, 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, "");
        }
        for (int e = 0; e < kModEgs; ++e) {
            const int32_t b = EgBase + e * EgParams;
            const int n = e + 1;
            put(b + EAttack, "e%d_attack", n, 0.001f, 10.0f, 0.002f, Curve::Exponential, 0, "s");
            put(b + EDecay, "e%d_decay", n, 0.002f, 15.0f, 0.5f, Curve::Exponential, 0, "s");
            put(b + ESustain, "e%d_sustain", n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
            put(b + ERelease, "e%d_release", n, 0.002f, 15.0f, 0.3f, Curve::Exponential, 0, "s");
        }
        for (int m = 0; m < kMatrixSlots; ++m) {
            const int32_t b = MatrixBase + m * MatrixParams;
            const int n = m + 1;
            put(b + XSrc, "m%02d_src", n, 0.0f, SourceCount - 1.0f, 0.0f, Curve::Stepped, SourceCount, "");
            put(b + XSrc2, "m%02d_src2", n, 0.0f, SourceCount - 1.0f, 0.0f, Curve::Stepped, SourceCount, "");
            put(b + XDest, "m%02d_dest", n, 0.0f, DestCount - 1.0f, 0.0f, Curve::Stepped, DestCount, "");
            put(b + XDepth, "m%02d_depth", n, -1.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        }
        built = true;
    }
    count = Count;
    return defs;
}

void Mosaic::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    for (auto &v : voices) {
        v.amp.setSampleRate(sampleRate);
        v.filterEg.setSampleRate(sampleRate);
        for (auto &e : v.modEg) e.setSampleRate(sampleRate);
        v.filter.setSampleRate(sampleRate);
    }
    reset();
}

void Mosaic::reset() {
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.layerCount = 0;
        v.amp.kill();
        v.filterEg.kill();
        for (auto &e : v.modEg) e.kill();
        v.filter.reset();
        for (auto &g : v.grain) g.active = false;
        for (auto &m : v.mod) m = 0.0f;
    }
    modWheel = pressure = bend = 0.0f;
}

void *Mosaic::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    // Voices hold pointers into the old map, so they have to stop with it.
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.layerCount = 0;
        v.amp.kill();
        for (auto &g : v.grain) g.active = false;
    }
    void *old = const_cast<SampleMap *>(map);
    map = static_cast<const SampleMap *>(object);
    return old;
}

Mosaic::Voice *Mosaic::allocate() {
    Voice *best = nullptr;
    for (auto &v : voices) if (!v.used) return &v;
    for (auto &v : voices) {
        if (v.gate) continue;
        if (best == nullptr || v.age < best->age) best = &v;
    }
    if (best != nullptr) return best;
    for (auto &v : voices) if (best == nullptr || v.age < best->age) best = &v;
    return best;
}

void Mosaic::noteOn(uint8_t note, uint8_t velocity) {
    if (map == nullptr || map->zones.empty()) return;
    const int mode = stepOf(VoiceMode);
    Voice *vp = (mode == 1 || mode == 2) ? &voices[0] : allocate();
    if (vp == nullptr) return;
    Voice &v = *vp;
    const bool wasHeld = v.used && v.gate;
    const bool retrigger = !(mode == 2 && wasHeld);

    const float glideSeconds = paramOf(Glide);
    const bool gliding = glideSeconds > 0.001f && v.used && (stepOf(GlideMode) == 0 || v.gate);
    v.glideFrom = gliding ? v.freq : mtof(static_cast<float>(note));
    v.glidePos = gliding ? 0.0f : 1.0f;
    v.freq = v.glideFrom;
    v.used = true;
    v.gate = true;
    v.note = note;
    v.velocity = velocity;
    v.age = ageCounter++;
    v.random = rnd(v.rng) * 2.0f - 1.0f;

    // Which layers sound. Scan slides the velocity axis away from the played
    // velocity, so the map can be walked by a knob instead of by playing harder.
    const float scanAmount = clampf(paramOf(ScanAmount), 0.0f, 1.0f);
    const float scanValue = clampf(paramOf(LayerScan) + v.mod[DstScan], 0.0f, 1.0f) * 127.0f;

    // Scan spans the playable velocities, 1 to 127: at zero it would fall
    // below every zone and the note would simply not sound.
    const float scanned = 1.0f + scanValue * 126.0f / 127.0f;
    const float effectiveVel = static_cast<float>(velocity) * (1.0f - scanAmount) + scanned * scanAmount;

    int32_t zones[kZonesPerVoice];
    float gains[kZonesPerVoice];
    const int32_t n = map->select(note, static_cast<int>(effectiveVel + 0.5f), paramOf(KeyFade), paramOf(VelFade),
                                  zones, gains, kZonesPerVoice);
    v.layerCount = n;
    const float startAt = clampf(paramOf(Start) + v.mod[DstStart], 0.0f, 0.999f);
    for (int32_t i = 0; i < n; ++i) {
        const MapZone &z = map->zones[static_cast<size_t>(zones[i])];
        Layer &L = v.layer[i];
        L.zone = &z;
        L.sample = &map->samples[static_cast<size_t>(z.sample)];
        L.gain = gains[i] * z.gain;
        L.pan = z.pan;
        L.finished = false;
        if (retrigger) L.pos = startAt * static_cast<double>(L.sample->frames);
    }

    if (!retrigger) return;
    // The envelope can come from the panel or from what the file asked for.
    const bool fromFile = stepOf(EnvSourceIndex) == EnvFile && n > 0 && v.layer[0].zone->hasEnvelope;
    if (fromFile) {
        const MapZone &z = *v.layer[0].zone;
        v.amp.set(0.0f, z.attack, z.decay, z.sustain, z.release, false);
    } else {
        v.amp.set(0.0f, paramOf(AmpAttack), paramOf(AmpDecay), paramOf(AmpSustain), paramOf(AmpRelease), false);
    }
    v.amp.trigger();
    v.filterEg.set(0.0f, paramOf(FilterAttack), paramOf(FilterDecay), paramOf(FilterSustain), paramOf(FilterRelease), false);
    v.filterEg.trigger();
    for (int e = 0; e < kModEgs; ++e) {
        const int32_t b = EgBase + e * EgParams;
        v.modEg[e].set(0.0f, paramOf(b + EAttack), paramOf(b + EDecay), paramOf(b + ESustain), paramOf(b + ERelease), false);
        v.modEg[e].trigger();
    }
    for (int l = 0; l < kLfos; ++l) {
        const int32_t b = LfoBase + l * LfoParams;
        if (paramOf(b + LKeySync) >= 0.5f) v.lfo[l].trigger(paramOf(b + LPhase), paramOf(b + LDelay));
    }
    v.grainOffset = 0.0;
    v.grainTimer = 0.0f;
    for (auto &g : v.grain) g.active = false;
}

void Mosaic::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
            v.filterEg.release();
            for (auto &e : v.modEg) e.release();
        }
    }
}

void Mosaic::allNotesOff() {
    for (auto &v : voices) {
        v.gate = false;
        v.used = false;
        v.layerCount = 0;
        v.amp.kill();
        for (auto &g : v.grain) g.active = false;
    }
}

void Mosaic::onBlock(int64_t, int64_t, float bpmNow) { bpm = bpmNow; }
void Mosaic::controlChange(uint8_t cc, uint8_t value) { if (cc == 1) modWheel = static_cast<float>(value) / 127.0f; }
void Mosaic::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }
void Mosaic::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }

float Mosaic::sourceValue(const Voice &v, int src) const {
    switch (src) {
    case SrcOn: return 1.0f;
    case SrcModWheel: return modWheel;
    case SrcPressure: return pressure;
    case SrcVelocity: return static_cast<float>(v.velocity) / 127.0f;
    case SrcKeyTrack: return (static_cast<float>(v.note) - 60.0f) / 48.0f;
    case SrcRandom: return v.random;
    case SrcAmpEg: return v.amp.value();
    case SrcFilterEg: return v.filterEg.value();
    case SrcEg1: return v.modEg[0].value();
    case SrcEg2: return v.modEg[1].value();
    case SrcLfo1: return v.lfo[0].value();
    case SrcLfo2: return v.lfo[1].value();
    default: return 0.0f;
    }
}

void Mosaic::updateVoiceMod(Voice &v, float blockSeconds) {
    for (int l = 0; l < kLfos; ++l) {
        const int32_t b = LfoBase + l * LfoParams;
        const int sync = stepOf(b + LSync);
        float hz = paramOf(b + LRate);
        if (sync > 0) hz = bpm / (60.0f * kSyncBeats[sync]);
        hz *= std::exp2(v.mod[DstLfo1Rate + l] * 4.0f);
        v.lfo[l].advance(stepOf(b + LWave), hz, blockSeconds, 0.0f, false);
    }
    for (auto &m : v.mod) m = 0.0f;
    for (int s = 0; s < kMatrixSlots; ++s) {
        const int32_t b = MatrixBase + s * MatrixParams;
        const int dest = stepOf(b + XDest);
        if (dest == DstOff) continue;
        const int src = stepOf(b + XSrc);
        if (src == SrcOff) continue;
        const int src2 = stepOf(b + XSrc2);
        const float a = sourceValue(v, src);
        const float bm = src2 == SrcOff ? 1.0f : sourceValue(v, src2);
        v.mod[dest] += a * bm * paramOf(b + XDepth);
    }
}

float Mosaic::readSample(const SampleData &s, double pos) {
    if (pos < 0.0) pos = 0.0;
    const int32_t i = static_cast<int32_t>(pos);
    if (i >= s.frames - 1) return s.frames > 0 ? s.left[static_cast<size_t>(s.frames - 1)] : 0.0f;
    const float f = static_cast<float>(pos - i);
    return s.left[static_cast<size_t>(i)] * (1.0f - f) + s.left[static_cast<size_t>(i + 1)] * f;
}

void Mosaic::renderVoice(Voice &v, int32_t frames, float *outL, float *outR) {
    const float bendSemis = bend * paramOf(BendRange);
    const float semis = paramOf(Coarse) + paramOf(Fine) * 0.01f + paramOf(Transpose) + 12.0f * paramOf(Octave) +
                        bendSemis + v.mod[DstPitch] * 24.0f;
    const float pitchMul = std::exp2(semis / 12.0f);
    const float vel = static_cast<float>(v.velocity) / 127.0f;
    // A power curve, not a straight line: SoundFonts express dynamics through
    // modulators this reader ignores, so the machine's own velocity response
    // has to cover the range a sampled instrument needs.
    const float velAmp = paramOf(VelocityAmount) <= 0.001f ? 1.0f
                                                           : std::pow(clampf(vel, 0.001f, 1.0f),
                                                                      paramOf(VelocityAmount) * 2.5f);
    const float volume = clampf(paramOf(Volume) + v.mod[DstAmp], 0.0f, 2.0f) * velAmp * 0.7f;
    const float panBase = clampf(paramOf(Pan) + v.mod[DstPan], -1.0f, 1.0f);
    const int loopMode = stepOf(LoopModeIndex);
    const bool reverse = paramOf(Reverse) >= 0.5f;
    const bool grains = paramOf(GrainMode) >= 0.5f;

    const float filterBase = paramOf(FilterFreq) *
        std::exp2(paramOf(FilterKey) * (static_cast<float>(v.note) - 60.0f) / 12.0f +
                  v.mod[DstFilterFreq] * 6.0f + paramOf(VelToFilter) * vel * 4.0f);
    const float filterEnvAmt = paramOf(FilterEnv);
    const float filterRes = clampf(paramOf(FilterRes) + v.mod[DstFilterRes], 0.0f, 1.0f);
    const int filterType = stepOf(FilterType);

    const float glideSeconds = paramOf(Glide);
    const float glideStep = glideSeconds > 0.001f ? 1.0f / (glideSeconds * sampleRate) : 1.0f;
    const float targetFreq = mtof(static_cast<float>(v.note));
    const float glideOctaves = v.glidePos < 1.0f ? std::log2(targetFreq / v.glideFrom) : 0.0f;

    // Grain settings, all modulatable.
    const float gPos = clampf(paramOf(GrainPos) + v.mod[DstGrainPos], 0.0f, 1.0f);
    const float gRate = clampf(paramOf(GrainRate) + v.mod[DstGrainRate] * 2.0f, -4.0f, 4.0f);
    const float gSizeMs = clampf(paramOf(GrainSize) * std::exp2(v.mod[DstGrainSize] * 2.0f), 2.0f, 1000.0f);
    const float gDensity = clampf(paramOf(GrainDensity) * std::exp2(v.mod[DstGrainDensity] * 2.0f), 0.5f, 400.0f);
    const float gSpray = clampf(paramOf(GrainSpray) + v.mod[DstGrainSpray], 0.0f, 1.0f);
    const float gPitch = clampf(paramOf(GrainPitch) + v.mod[DstGrainPitch] * 24.0f, 0.0f, 48.0f);

    for (int32_t i = 0; i < frames; ++i) {
        if (v.glidePos < 1.0f) {
            v.glidePos += glideStep;
            if (v.glidePos >= 1.0f) { v.glidePos = 1.0f; v.freq = targetFreq; }
            else v.freq = v.glideFrom * std::exp2(glideOctaves * v.glidePos);
        } else {
            v.freq = targetFreq;
        }

        const float env = v.amp.next();
        const float fenv = v.filterEg.next();
        for (auto &e : v.modEg) e.next();

        float l = 0.0f, r = 0.0f;
        if (grains && v.layerCount > 0) {
            // Grain mode plays the loudest layer only: a cloud does not want
            // layering, and this keeps eight readers per voice rather than
            // thirty-two.
            int32_t best = 0;
            for (int32_t z = 1; z < v.layerCount; ++z) if (v.layer[z].gain > v.layer[best].gain) best = z;
            Layer &L = v.layer[best];
            if (L.sample != nullptr && L.sample->frames > 1) {
                const double natural = static_cast<double>(v.freq) / mtof(static_cast<float>(L.zone->rootKey)) *
                                       std::exp2(L.zone->tuneCents / 1200.0f) * pitchMul *
                                       (static_cast<double>(L.sample->rate) / static_cast<double>(sampleRate));
                v.grainOffset += natural * static_cast<double>(gRate);
                const double span = static_cast<double>(L.sample->frames);
                if (v.grainOffset > span) v.grainOffset -= span;
                if (v.grainOffset < -span) v.grainOffset += span;

                v.grainTimer -= 1.0f;
                if (v.grainTimer <= 0.0f) {
                    v.grainTimer = sampleRate / gDensity;
                    for (auto &g : v.grain) {
                        if (g.active) continue;
                        double p = static_cast<double>(gPos) * span + v.grainOffset +
                                   static_cast<double>((rnd(v.rng) * 2.0f - 1.0f) * gSpray) * span * 0.5;
                        while (p < 0.0) p += span;
                        while (p >= span) p -= span;
                        g.pos = p;
                        g.inc = natural * std::exp2((rnd(v.rng) * 2.0f - 1.0f) * gPitch / 12.0f);
                        g.length = static_cast<int32_t>(gSizeMs * 0.001f * sampleRate);
                        if (g.length < 8) g.length = 8;
                        g.age = 0;
                        g.gain = 1.0f;
                        g.active = true;
                        break;
                    }
                }
                float sum = 0.0f;
                for (auto &g : v.grain) {
                    if (!g.active) continue;
                    // Hann window, so grains fade in and out instead of clicking.
                    const float t = static_cast<float>(g.age) / static_cast<float>(g.length);
                    const float w = 0.5f - 0.5f * std::cos(t * kTwoPi);
                    sum += readSample(*L.sample, g.pos) * w;
                    g.pos += g.inc;
                    if (g.pos >= span) g.pos -= span;
                    if (g.pos < 0.0) g.pos += span;
                    if (++g.age >= g.length) g.active = false;
                }
                sum *= L.gain;
                const float angle = (clampf(panBase + L.pan, -1.0f, 1.0f) + 1.0f) * 0.25f * kPi;
                l += sum * std::cos(angle) * 1.4142f;
                r += sum * std::sin(angle) * 1.4142f;
            }
        } else {
            for (int32_t z = 0; z < v.layerCount; ++z) {
                Layer &L = v.layer[z];
                if (L.sample == nullptr || L.finished || L.sample->frames < 2) continue;
                const double inc = static_cast<double>(v.freq) / mtof(static_cast<float>(L.zone->rootKey)) *
                                   std::exp2(L.zone->tuneCents / 1200.0f) * pitchMul *
                                   (static_cast<double>(L.sample->rate) / static_cast<double>(sampleRate));
                const float s = readSample(*L.sample, L.pos) * L.gain;
                const float angle = (clampf(panBase + L.pan, -1.0f, 1.0f) + 1.0f) * 0.25f * kPi;
                l += s * std::cos(angle) * 1.4142f;
                r += s * std::sin(angle) * 1.4142f;

                L.pos += reverse ? -inc : inc;
                const bool wantLoop = loopMode == LoopForward ||
                                      (loopMode == LoopFromFile && L.zone->loopStart >= 0 && L.zone->loopEnd > L.zone->loopStart);
                const double loopA = L.zone->loopStart >= 0 ? static_cast<double>(L.zone->loopStart) : 0.0;
                const double loopB = L.zone->loopEnd > L.zone->loopStart ? static_cast<double>(L.zone->loopEnd)
                                                                         : static_cast<double>(L.sample->frames - 1);
                if (wantLoop && loopB > loopA + 1.0) {
                    if (L.pos >= loopB) L.pos -= (loopB - loopA);
                    if (L.pos < loopA && reverse) L.pos += (loopB - loopA);
                } else if (L.pos >= static_cast<double>(L.sample->frames - 1) || L.pos < 0.0) {
                    L.finished = true;
                }
            }
        }

        if ((i & 15) == 0) {
            v.filter.set(filterBase * std::exp2(filterEnvAmt * fenv * 4.0f), filterRes, filterType, 0, 0.0f);
        }
        const float g = env * volume;
        outL[i] = v.filter.process(l) * g;
        outR[i] = v.filter.process(r) * g;
    }
}

bool Mosaic::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) { L[i] = 0.0f; R[i] = 0.0f; }
    if (map == nullptr) return true;
    const float blockSeconds = static_cast<float>(frames) / sampleRate;
    for (auto &v : voices) {
        if (!v.used) continue;
        updateVoiceMod(v, blockSeconds);
        renderVoice(v, frames, voiceL, voiceR);
        for (int32_t i = 0; i < frames; ++i) { L[i] += voiceL[i]; R[i] += voiceR[i]; }
        bool anyPlaying = paramOf(GrainMode) >= 0.5f;
        for (int32_t z = 0; z < v.layerCount && !anyPlaying; ++z) if (!v.layer[z].finished) anyPlaying = true;
        if ((!v.gate && !v.amp.active()) || !anyPlaying) {
            v.used = false;
            v.layerCount = 0;
        }
    }
    return true;
}

} // namespace acidulous::machine
