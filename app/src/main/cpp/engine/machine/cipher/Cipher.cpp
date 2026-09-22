#include "Cipher.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cstdio>
#include <cstring>

namespace acidulous::machine {

// Make-up for the normalised band sum. Bands tile the spectrum and are close
// to decorrelated, so the sum of N of them grows about as sqrt(N) - which is
// why this is a constant and the band count is not in it.
constexpr float kBandMakeup = 16.0f;
// What the summed bands reach before the output stage, and what the carrier
// reaches before its own - the levels each saturator should treat as nominal,
// so the knob changes shape rather than volume.
constexpr float kNominal = 0.3f;
constexpr float kCarrierNominal = 0.5f;
// The consonant path's level. It belongs under the vocoded signal - a
// vocoder with no path for consonants turns every "s" into a hole, but one
// whose noise outweighs its bands is not a vocoder at all.
constexpr float kSibilanceMakeup = 2.0f;
// Where the bank sits in the volume knob's travel, set from Init. With the
// bands normalised the machine no longer runs permanently saturated, so it
// needs a house level like every other machine rather than a tanh holding it
// down.
constexpr float kHouse = 0.94f;
// The volume knob's top, named once so the knob and the clamp that guards it
// cannot drift apart again.
constexpr float kVolumeMax = 2.0f;
using namespace dsp;

namespace {
constexpr float kPiF = 3.14159265f;
float noteToHz(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
} // namespace

Cipher::Cipher() { initParams(); }

const ParamDef *Cipher::paramDefs(int32_t &count) const {
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

        put(Bands, "bands", 4.0f, 40.0f, 16.0f, Curve::Stepped, 37, "");
        // The bottom of the bank, at 80 Hz rather than 110.
        //
        // A vocoder whose lowest band starts above the speaker's fundamental
        // cannot measure half of what it is given. Measured on the voice this
        // bank was voiced against: a median fundamental of 125 Hz, but 48 per
        // cent of voiced frames below 110 and 25 per cent below 80. The cost
        // of reaching lower is that the same number of bands covers more
        // octaves and each one is wider, so this is as low as it goes before
        // the resolution where speech is actually understood starts to suffer.
        exp_(LowHz, "low", 40.0f, 600.0f, 80.0f, "Hz");
        exp_(HighHz, "high", 1500.0f, 16000.0f, 8000.0f, "Hz");
        lin(BandQ, "q", 0.0f, 1.0f, 0.55f);
        step(Slope, "slope", 2, 1.0f); // one pole pair, or two
        step(RoleMode, "role", RoleCount, 0.0f);
        exp_(Attack, "attack", 0.0005f, 0.2f, 0.004f, "s");
        exp_(Release, "release", 0.005f, 2.0f, 0.06f, "s");
        lin(Smear, "smear", -1.0f, 1.0f, 0.0f);
        lin(Gate, "gate", 0.0f, 1.0f, 0.0f);
        lin(GateDepth, "gatedepth", 0.0f, 1.0f, 1.0f);
        lin(Shift, "shift", -12.0f, 12.0f, 0.0f);
        lin(Stretch, "stretch", -1.0f, 1.0f, 0.0f);
        step(RemapMode, "remap", RemapCount, 0.0f);
        lin(RemapAmount, "remapamt", 0.0f, 1.0f, 1.0f);
        put(Seed, "seed", 0.0f, 31.0f, 0.0f, Curve::Stepped, 32, "");
        step(Freeze, "freeze", 2, 0.0f);
        lin(FreezeMorph, "frzmorph", 0.0f, 1.0f, 1.0f);
        exp_(FreezeDecay, "frzdecay", 0.05f, 30.0f, 30.0f, "s");
        lin(Sibilance, "sibilance", 0.0f, 1.0f, 0.4f);
        exp_(SibilanceHz, "sibhz", 1500.0f, 12000.0f, 4500.0f, "Hz");
        lin(SibilanceLevel, "siblevel", 0.0f, 1.0f, 0.5f);
        step(PitchTrack, "track", 2, 0.0f);
        exp_(TrackGlide, "trackglide", 0.001f, 0.5f, 0.03f, "s");
        lin(TrackAmount, "trackamt", 0.0f, 1.0f, 1.0f);
        lin(Feedback, "feedback", 0.0f, 0.95f, 0.0f);
        lin(FeedbackTone, "fbtone", 0.0f, 1.0f, 0.5f);
        step(CarrierWaveA, "wave a", CarrierWaveCount, 0.0f);
        step(CarrierWaveB, "wave b", CarrierWaveCount, 2.0f);
        lin(CarrierMix, "mix", 0.0f, 1.0f, 0.35f);
        lin(Detune, "detune", 0.0f, 50.0f, 12.0f, "c");
        lin(PulseWidth, "pw", 0.05f, 0.95f, 0.35f);
        lin(SubLevel, "sub", 0.0f, 1.0f, 0.2f);
        lin(NoiseLevel, "noise", 0.0f, 1.0f, 0.08f);
        lin(CarrierDrive, "cardrive", 0.0f, 1.0f, 0.25f);
        lin(Unvoiced, "unvoiced", 0.0f, 1.0f, 0.0f);
        lin(Dry, "dry", 0.0f, 1.0f, 0.0f);
        lin(Wet, "wet", 0.0f, 1.0f, 1.0f);
        lin(Drive, "drive", 0.0f, 1.0f, 0.15f);
        // To 2.0, as Manual's does, and for a reason particular to this
        // machine: a vocoder's output level is set by a modulator it does not
        // control - on a phone, whatever the player is speaking at - and the
        // patches that throw the most spectrum away (few bands, a narrow
        // range, every other band removed, a noise carrier) ran out of knob
        // four decibels under the bank's line with nothing left to give.
        lin(Volume, "volume", 0.0f, kVolumeMax, 0.8f);
        lin(Pan, "pan", -1.0f, 1.0f, 0.0f);
        exp_(AmpAttack, "ampatk", 0.001f, 4.0f, 0.01f, "s");
        exp_(AmpDecay, "ampdec", 0.005f, 8.0f, 0.5f, "s");
        lin(AmpSustain, "ampsus", 0.0f, 1.0f, 1.0f);
        exp_(AmpRelease, "amprel", 0.005f, 8.0f, 0.2f, "s");
        exp_(Eg1A, "eg1atk", 0.001f, 8.0f, 0.01f, "s");
        exp_(Eg1D, "eg1dec", 0.005f, 12.0f, 0.5f, "s");
        lin(Eg1S, "eg1sus", 0.0f, 1.0f, 0.6f);
        exp_(Eg1R, "eg1rel", 0.005f, 12.0f, 0.4f, "s");
        exp_(Eg2A, "eg2atk", 0.001f, 8.0f, 0.8f, "s");
        exp_(Eg2D, "eg2dec", 0.005f, 12.0f, 2.0f, "s");
        lin(Eg2S, "eg2sus", 0.0f, 1.0f, 1.0f);
        exp_(Eg2R, "eg2rel", 0.005f, 12.0f, 1.0f, "s");
        step(Lfo1Wave, "lfo1wave", LfoGen::WaveCount, 0.0f);
        exp_(Lfo1Rate, "lfo1rate", 0.01f, 20.0f, 0.3f, "Hz");
        step(Lfo1Sync, "lfo1sync", 6, 0.0f);
        lin(Lfo1Depth, "lfo1depth", 0.0f, 1.0f, 1.0f);
        step(Lfo2Wave, "lfo2wave", LfoGen::WaveCount, 1.0f);
        exp_(Lfo2Rate, "lfo2rate", 0.01f, 20.0f, 2.0f, "Hz");
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
        exp_(Glide, "glide", 0.0f + 0.001f, 2.0f, 0.001f, "s");
        lin(BendRange, "bend", 0.0f, 12.0f, 2.0f);
        lin(Octave, "octave", -3.0f, 3.0f, 0.0f);
        lin(Transpose, "transpose", -12.0f, 12.0f, 0.0f);
        lin(Fine, "fine", -50.0f, 50.0f, 0.0f, "c");
        lin(VelocityAmount, "vel", 0.0f, 1.0f, 0.3f);
        built = true;
    }
    count = Count;
    return defs;
}

int32_t Cipher::steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

void Cipher::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    invSampleRate = 1.0f / sampleRate;
    for (auto &b : bands) {
        b.analysis1.setSampleRate(sampleRate);
        b.analysis2.setSampleRate(sampleRate);
        b.synthesis1.setSampleRate(sampleRate);
        b.synthesis2.setSampleRate(sampleRate);
    }
    sibilanceFilter.setSampleRate(sampleRate);
    sibilanceShaper.setSampleRate(sampleRate);
    for (auto &v : voices) v.amp.setSampleRate(sampleRate);
    for (auto &e : eg) e.setSampleRate(sampleRate);
    lastCount = -1;
    for (auto &k : lastBandKey) k = NAN; // the coefficients mean another rate now
    rebuildBands();
    reset();
}

// The bank is laid out logarithmically, because hearing is, and because a
// linear bank spends half its filters above 10 kHz where speech has nothing
// to say.
void Cipher::rebuildBands() {
    const int32_t count = std::max(4, std::min(kMaxBands, steppedOf(Bands)));
    // The edges move with the matrix, two octaves either way at full depth.
    // They were offered as destinations and read by nothing: the bank is laid
    // out here, once a block, and the matrix had never been asked.
    const float low = mod[DstBandLow] == 0.0f ? paramOf(LowHz)
                                              : clampf(paramOf(LowHz) * std::exp2(mod[DstBandLow] * 2.0f), 40.0f, 600.0f);
    const float high = mod[DstBandHigh] == 0.0f ? paramOf(HighHz)
                                                : clampf(paramOf(HighHz) * std::exp2(mod[DstBandHigh] * 2.0f), 1500.0f, 16000.0f);
    const float q = paramOf(BandQ);
    if (count == lastCount && std::fabs(low - lastLow) < 0.5f && std::fabs(high - lastHigh) < 0.5f &&
        std::fabs(q - lastQ) < 0.002f) {
        return;
    }
    lastCount = count;
    lastLow = low;
    lastHigh = high;
    lastQ = q;
    bandCount = count;
    const float ratio = std::log(high / low) / static_cast<float>(count - 1);
    for (int i = 0; i < count; ++i) {
        const float hz = low * std::exp(ratio * static_cast<float>(i));
        bands[i].centre = hz;
    }
}

// A band should be about as wide as the gap to its neighbour. Any narrower
// and the bank has holes in it; any wider and every band hears every other
// one, which is what makes a vocoder sound like a blanket. So resonance is
// derived from how many bands are covering how many octaves, and the knob
// only leans on that.
float Cipher::bandResonance(float q) const {
    // The edges the bank was actually laid out on, modulated or not.
    const float octaves = std::log2(std::fmax(1.01f, lastHigh / lastLow));
    const float perOctave = static_cast<float>(bandCount - 1) / std::fmax(0.5f, octaves);
    const float wanted = std::fmax(0.7f, perOctave * 1.45f * (0.45f + 1.1f * q));
    return clampf((2.0f - 1.0f / wanted) / 1.96f, 0.0f, 0.995f);
}

void Cipher::reset() {
    for (auto &v : voices) { v.used = false; v.gate = false; v.amp.kill(); }
    for (int i = 0; i < kMaxBands; ++i) {
        bands[i].analysis1.reset(); bands[i].analysis2.reset();
        bands[i].synthesis1.reset(); bands[i].synthesis2.reset();
        bands[i].envelope = 0.0f;
        bands[i].held = 0.0f;
    }
    sibilanceFilter.reset();
    sibilanceShaper.reset();
    for (auto &e : eg) e.reset();
    feedbackSample = 0.0f;
    loudness = brightness = 0.0f;
    quietBlocks = 0;
    asleep = false;
}

void Cipher::noteOn(uint8_t note, uint8_t velocity) {
    // **The two mod envelopes, which had never run** - the third machine with
    // this fault and the last one `tools/modsource_test.sh` can find. They
    // are the machine's, not a voice's, so the first note of a phrase starts
    // them and a note added to a held chord does not.
    bool held = false;
    for (const auto &cand : voices) if (cand.used && cand.gate) { held = true; break; }
    if (!held) for (auto &e : eg) e.retrigger();

    Voice *v = nullptr;
    for (auto &cand : voices) if (!cand.used) { v = &cand; break; }
    if (v == nullptr) {
        v = &voices[0];
        for (auto &cand : voices) if (!cand.gate) { v = &cand; break; }
    }
    const bool wasIdle = !v->used;
    v->used = true;
    v->gate = true;
    v->note = note;
    v->bend = 0.0f;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->key01 = clampf((static_cast<float>(note) - 24.0f) / 72.0f, 0.0f, 1.0f);
    v->target = noteToHz(static_cast<float>(note));
    if (wasIdle) v->freq = v->target;
    v->amp.set(0.0f, targetOf(AmpAttack), targetOf(AmpDecay), targetOf(AmpSustain), targetOf(AmpRelease), false);
    v->amp.retrigger();
}

void Cipher::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
    bool held = false;
    for (const auto &cand : voices) if (cand.used && cand.gate) { held = true; break; }
    if (!held) for (auto &e : eg) e.release();
}

void Cipher::allNotesOff() {
    for (auto &v : voices) { v.gate = false; v.amp.release(); }
    for (auto &e : eg) e.release();
}

void Cipher::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Cipher::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }
void Cipher::pitchBend(int16_t value14) {
    bendSemis = (static_cast<float>(value14) / 8192.0f) * paramOf(BendRange);
}

void Cipher::noteBend(uint8_t note, float semitones) {
    if (Voice *v = voiceForNote(voices, note)) v->bend = semitones;
}
void Cipher::onBlock(int64_t, int64_t, float tempo) { bpm = tempo > 1.0f ? tempo : 120.0f; }

float Cipher::sourceValue(int32_t src) const {
    switch (src) {
    case SrcOn: return 1.0f;
    case SrcModWheel: return modWheel;
    case SrcPressure: return pressure;
    case SrcVelocity: {
        float v = 0.0f;
        for (const auto &voice : voices) if (voice.used && voice.gate) v = std::fmax(v, voice.velocity);
        return v;
    }
    case SrcKeyTrack: {
        for (const auto &voice : voices) if (voice.used && voice.gate) return voice.key01;
        return 0.0f;
    }
    case SrcEg1: return eg[0].value();
    case SrcEg2: return eg[1].value();
    case SrcLfo1: return lfoValue[0];
    case SrcLfo2: return lfoValue[1];
    // The modulator itself is a modulation source: what it is doing can
    // drive anything, not only the band it lands in.
    case SrcLoudness: return loudness;
    case SrcBrightness: return brightness;
    case SrcPitchTrack: return clampf((trackedNote - 36.0f) / 48.0f, 0.0f, 1.0f);
    default: return 0.0f;
    }
}

void Cipher::applyMatrix() {
    for (int32_t d = 0; d < DestCount; ++d) mod[d] = 0.0f;
    for (int m = 0; m < kMatrixSlots; ++m) {
        const int base = MatrixBase + m * kMatrixParams;
        const int32_t src = steppedOf(base + XSrc);
        const int32_t dst = steppedOf(base + XDest);
        if (src == SrcOff || dst == DstOff) continue;
        mod[dst] += sourceValue(src) * paramOf(base + XDepth);
    }
}

// Which analysis band drives this synthesis band. This is the machine.
int32_t Cipher::mappedBand(int32_t band) const {
    const int32_t n = bandCount;
    const int32_t last = n - 1;
    switch (steppedOf(RemapMode)) {
    case MapReverse: return last - band;
    case MapMirror: return band < n / 2 ? band * 2 : last - (band - n / 2) * 2;
    case MapOddEven: return band < n / 2 ? band * 2 : (band - n / 2) * 2 + 1;
    case MapFold: return band < n / 2 ? band : last - band;
    case MapShuffle: return shuffleMap[band];
    default: return band;
    }
}

float Cipher::carrierSample(Voice &v, float glideK, float detuneMul, float pitchScale, int32_t waveA,
                            int32_t waveB, float mix, float pw, float sub) {
    v.freq += (v.target - v.freq) * glideK;
    const float f = v.freq * pitchScale * noteBendMul(v);
    const float inc = f * invSampleRate;
    v.phaseA += inc;
    if (v.phaseA >= 1.0f) v.phaseA -= 1.0f;
    v.phaseB += inc * detuneMul;
    if (v.phaseB >= 1.0f) v.phaseB -= 1.0f;
    v.phaseSub += inc * 0.5f;
    if (v.phaseSub >= 1.0f) v.phaseSub -= 1.0f;

    auto shape = [&](int32_t wave, float phase) -> float {
        switch (wave) {
        case WavePulse: return phase < pw ? 1.0f : -1.0f;
        case WaveSuper: {
            // Three saws a little apart: a carrier wants density more than
            // it wants purity, because the filters will shape it anyway.
            float s = 0.0f;
            for (int i = 0; i < 3; ++i) {
                const float p = std::fmod(phase * (1.0f + 0.004f * static_cast<float>(i - 1)) + 0.31f * i, 1.0f);
                s += 2.0f * p - 1.0f;
            }
            return s * 0.4f;
        }
        case WaveNoise: {
            rngState = rngState * 1664525u + 1013904223u;
            return (static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f;
        }
        case WaveRing: return (2.0f * phase - 1.0f) * std::sin(6.2831853f * phase * 3.0f);
        default: return 2.0f * phase - 1.0f;
        }
    };
    const float a = shape(waveA, v.phaseA);
    const float b = shape(waveB, v.phaseB);
    const float subOut = (v.phaseSub < 0.5f ? 1.0f : -1.0f) * sub;
    return a * (1.0f - mix) + b * mix + subOut;
}

bool Cipher::render(float *L, float *R, int32_t frames) {
    params_.tick();
    applyMatrix(); // first: the band edges are destinations now
    rebuildBands();

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
    // Stepped in the sample loop only if the matrix names one. See Filament's.
    bool egWanted = false;
    for (int m = 0; m < kMatrixSlots && !egWanted; ++m) {
        const int base = MatrixBase + m * kMatrixParams;
        if (steppedOf(base + XDest) == DstOff) continue;
        const int32_t src = steppedOf(base + XSrc);
        egWanted = src == SrcEg1 || src == SrcEg2;
    }

    // A shuffle has to be stable or the bank would boil; it is rebuilt only
    // when the seed changes.
    const int32_t seed = steppedOf(Seed);
    if (seed != shuffleSeed || lastCount != bandCount) {
        shuffleSeed = seed;
        for (int i = 0; i < bandCount; ++i) shuffleMap[i] = i;
        uint32_t s = static_cast<uint32_t>(seed) * 2654435761u + 12345u;
        for (int i = bandCount - 1; i > 0; --i) {
            s = s * 1664525u + 1013904223u;
            const int j = static_cast<int>((s >> 16) % static_cast<uint32_t>(i + 1));
            std::swap(shuffleMap[i], shuffleMap[j]);
        }
    }

    const int32_t role = steppedOf(RoleMode);
    const bool twoPole = steppedOf(Slope) != 0;
    const float attack = paramOf(Attack), release = paramOf(Release);
    const float smear = clampf(paramOf(Smear) + mod[DstSmear], -1.0f, 1.0f);
    const float q = clampf(paramOf(BandQ) + mod[DstQ], 0.0f, 1.0f);
    const float shift = clampf(paramOf(Shift) + mod[DstShift] * 12.0f, -24.0f, 24.0f);
    const float stretch = clampf(paramOf(Stretch) + mod[DstStretch], -1.0f, 1.0f);
    const float remapAmount = clampf(paramOf(RemapAmount) + mod[DstRemapAmount], 0.0f, 1.0f);
    const bool freeze = steppedOf(Freeze) != 0;
    const float freezeMorph = clampf(paramOf(FreezeMorph) + mod[DstFreezeMorph], 0.0f, 1.0f);
    // Per *sample*, because that is where it is applied. It was worked out
    // for a whole block - `exp(-frames / (t * rate))` - and then applied on
    // every sample of it, so a held spectrum let go sixty-four times faster
    // than the knob said: Held Vowel's thirty seconds were half of one, and
    // "one vowel sustains for as long as the key is down" did not.
    const float freezeDecay = std::exp(-1.0f / (paramOf(FreezeDecay) * sampleRate));
    const float gate = clampf(paramOf(Gate) + mod[DstGate], 0.0f, 1.0f);
    const float gateDepth = paramOf(GateDepth);
    const float sibilance = paramOf(Sibilance);
    const float sibLevel = paramOf(SibilanceLevel);
    const float feedback = clampf(paramOf(Feedback) + mod[DstFeedback], 0.0f, 0.95f);
    const float fbTone = paramOf(FeedbackTone);
    const int32_t waveA = steppedOf(CarrierWaveA), waveB = steppedOf(CarrierWaveB);
    const float mix = clampf(paramOf(CarrierMix) + mod[DstCarrierMix], 0.0f, 1.0f);
    const float detune = paramOf(Detune), pw = paramOf(PulseWidth), sub = paramOf(SubLevel);
    // The second oscillator's offset and the glide's coefficient, worked out
    // once: a `pow` and a divide of numbers that hold still for the block.
    const float detuneMul = std::exp2(detune / 1200.0f);
    const float glideSeconds = paramOf(Glide);
    const float glideK = glideSeconds <= 0.002f ? 1.0f
                                                : clampf(invSampleRate / glideSeconds, 0.0f, 1.0f);
    const float noiseLevel = clampf(paramOf(NoiseLevel) + mod[DstNoise], 0.0f, 1.0f);
    const float carrierDrive = paramOf(CarrierDrive);
    const float dry = paramOf(Dry), wet = paramOf(Wet);
    const float drive = clampf(paramOf(Drive) + mod[DstDrive], 0.0f, 1.0f);
    const float carrierDriveK = 1.0f + carrierDrive * 6.0f;
    const float carrierDriveNorm = kCarrierNominal / std::tanh(kCarrierNominal * carrierDriveK);
    const float driveK = 1.0f + drive * 8.0f;
    const float driveNorm = kNominal / std::tanh(kNominal * driveK);
    // Clamped to the knob's own maximum, not to a number that used to match it.
    //
    // This said 1.5 while the parameter ran to 1.0, so modulation could push
    // past the knob - reasonable. Raising the knob to 2.0 turned the same line
    // into a lid: the four patches that needed the top of the range measured
    // identically at 1.6 and at 2.0, and no amount of levelling moved them.
    const float volume = clampf(paramOf(Volume) + mod[DstVolume], 0.0f, kVolumeMax);
    const float pan = clampf(paramOf(Pan) + mod[DstPan], -1.0f, 1.0f);
    // A pan does not move inside a block, and a drive's compensation is a
    // property of the knob rather than of the sample: all four were trig or
    // `tanh` being called once per output sample for an answer that never
    // changed.
    const float panAngle = (pan + 1.0f) * 0.25f * kPiF;
    const float panL = std::cos(panAngle) * 1.4142f, panR = std::sin(panAngle) * 1.4142f;
    const float velAmt = paramOf(VelocityAmount);
    const bool track = steppedOf(PitchTrack) != 0;
    const float trackAmount = paramOf(TrackAmount);
    const float pitchScale = std::pow(2.0f, (bendSemis + paramOf(Octave) * 12.0f + paramOf(Transpose) +
                                             paramOf(Fine) * 0.01f + mod[DstCarrierPitch] * 12.0f) / 12.0f);

    sibilanceFilter.set(paramOf(SibilanceHz), 0.4f);
    sibilanceShaper.set(paramOf(SibilanceHz), 0.25f);

    // Band tuning for the synthesis side: shifted, stretched, or both. The
    // analysis bank stays where it is, which is what makes a shift move the
    // formants rather than the whole sound.
    //
    // **Only when something it depends on has moved.** Four filters a band,
    // each a `tan`, and two `pow`s and two `exp`s besides - two hundred and
    // fifty libm calls a block at forty bands, every block, for a bank whose
    // knobs sit still for minutes at a time.
    const float bandKey[] = {static_cast<float>(bandCount), shift, stretch, q, smear, attack, release,
                             lastLow, lastHigh};
    bool bandsMoved = false;
    for (size_t k = 0; k < sizeof(bandKey) / sizeof(bandKey[0]); ++k) {
        if (bandKey[k] != lastBandKey[k]) { bandsMoved = true; lastBandKey[k] = bandKey[k]; }
    }
    for (int i = 0; bandsMoved && i < bandCount; ++i) {
        const float t = bandCount > 1 ? static_cast<float>(i) / static_cast<float>(bandCount - 1) : 0.0f;
        const float warp = 1.0f + stretch * (t - 0.5f) * 1.6f;
        const float hz = clampf(bands[i].centre * std::pow(2.0f, shift / 12.0f) * warp, 20.0f, sampleRate * 0.45f);
        const float res = bandResonance(q);
        bands[i].synthesis1.set(hz, res);
        bands[i].synthesis2.set(hz, res);
        bands[i].analysis1.set(bands[i].centre, res);
        bands[i].analysis2.set(bands[i].centre, res);
        // Smear: the release time is scaled across the bank, so one end of
        // the spectrum lets go before the other and the sound trails.
        const float spread = std::pow(4.0f, smear * (t - 0.5f) * 2.0f);
        bands[i].attackCoeff = 1.0f - std::exp(-1.0f / std::fmax(1.0f, attack * sampleRate));
        bands[i].releaseCoeff = 1.0f - std::exp(-1.0f / std::fmax(1.0f, release * spread * sampleRate));
    }

    const InputBus &bus = InputBus::get();
    const float *in = bus.live() ? bus.block() : nullptr;
    float sumLoud = 0.0f, sumBright = 0.0f, sumWeight = 0.0f;

    // **No note, nothing coming in and the bank empty: nothing to do.**
    //
    // With nothing playing the bank still ran - two filters a band on the
    // analysis side and two on the synthesis, forty bands, every sample, all
    // of them filtering zeros. Sixty microseconds a block on the dev box for a
    // track that was not playing, the most of any machine here.
    //
    // Strict about what counts as empty: no voice, no input over -120 dB,
    // every band's envelope *and* its frozen hold under that too - a held
    // spectrum is waiting for a note, not finished - and two quiet blocks of
    // output behind it. So what it leaves is zeros, and it zeroes the filters
    // on the way in so that what wakes is what a reset would have made.
    bool anyVoice = false;
    for (const auto &v : voices) anyVoice = anyVoice || v.used;
    float inputPeak = 0.0f;
    if (in != nullptr) {
        for (int32_t n = 0; n < frames * 2; ++n) inputPeak = std::fmax(inputPeak, std::fabs(in[n]));
    }
    bool bankQuiet = true;
    for (int i = 0; i < bandCount && bankQuiet; ++i) bankQuiet = bands[i].envelope < 1e-6f && bands[i].held < 1e-6f;
    if (!anyVoice && inputPeak < 1e-6f && bankQuiet && quietBlocks >= 2) {
        if (!asleep) {
            for (int i = 0; i < kMaxBands; ++i) {
                Band &b = bands[i];
                b.analysis1.reset(); b.analysis2.reset();
                b.synthesis1.reset(); b.synthesis2.reset();
                b.envelope = b.held = 0.0f;
            }
            sibilanceFilter.reset();
            sibilanceShaper.reset();
            sibilanceEnv = feedbackLp = feedbackSample = 0.0f;
            loudness = brightness = 0.0f;
            asleep = true;
        }
        if (egWanted) for (int32_t n = 0; n < frames; ++n) { eg[0].next(); eg[1].next(); }
        for (int32_t n = 0; n < frames; ++n) L[n] = R[n] = 0.0f;
        return true;
    }
    asleep = false;

    for (int32_t n = 0; n < frames; ++n) {
        if (egWanted) { eg[0].next(); eg[1].next(); }
        const float external = in != nullptr ? 0.5f * (in[static_cast<size_t>(n) * 2] + in[static_cast<size_t>(n) * 2 + 1]) : 0.0f;

        // The carrier: the internal oscillators, or the input if the roles
        // have been swapped.
        float carrier = 0.0f;
        float ampSum = 0.0f;
        for (auto &v : voices) {
            if (!v.used) continue;
            const float env = v.amp.next();
            if (!v.gate && env < 0.0003f) { v.used = false; continue; }
            if (track && trackedHz > 20.0f) {
                v.target = trackedHz * trackAmount + noteToHz(static_cast<float>(v.note)) * (1.0f - trackAmount);
            } else {
                v.target = noteToHz(static_cast<float>(v.note));
            }
            const float velGain = 1.0f - velAmt + velAmt * v.velocity;
            carrier += carrierSample(v, glideK, detuneMul, pitchScale, waveA, waveB, mix, pw, sub) *
                       env * velGain;
            ampSum += env;
        }
        if (noiseLevel > 0.0f) {
            rngState = rngState * 1664525u + 1013904223u;
            carrier += ((static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f) * noiseLevel;
        }
        if (carrierDrive > 0.0f) {
            // The carrier's own saturation, normalised the same way. It had no
            // compensation at all, so turning it up thinned the carrier and
            // quietened it at once - and the vocoder imposes the modulator's
            // envelope on whatever the carrier is, so that loss passes
            // straight through to the output.
            carrier = std::tanh(carrier * carrierDriveK) * carrierDriveNorm;
        }
        carrier *= 0.5f;

        float modulator = external;
        float source = carrier;
        if (role == InputIsCarrier) {
            modulator = carrier;
            source = external;
        }
        if (feedback > 0.0f) {
            feedbackLp += (feedbackSample - feedbackLp) * (0.02f + 0.9f * fbTone);
            modulator += feedbackLp * feedback;
        }

        // Analyse, map, synthesise.
        float out = 0.0f;
        float loud = 0.0f, bright = 0.0f, weight = 0.0f;
        for (int i = 0; i < bandCount; ++i) {
            Band &b = bands[i];
            // Normalised, so a band measures how loud the modulator is in its
            // range rather than how narrow the band happens to be.
            float a = b.analysis1.bandpass(modulator) * b.analysis1.bandNorm();
            if (twoPole) a = b.analysis2.bandpass(a) * b.analysis2.bandNorm();
            const float rectified = std::fabs(a);
            const float coeff = rectified > b.envelope ? b.attackCoeff : b.releaseCoeff;
            b.envelope += (rectified - b.envelope) * coeff;
            loud += b.envelope;
            bright += b.envelope * static_cast<float>(i);
            weight += 1.0f;
        }
        for (int i = 0; i < bandCount; ++i) {
            Band &b = bands[i];
            const int32_t from = mappedBand(i);
            const float direct = bands[i].envelope;
            const float remapped = bands[from < 0 ? 0 : (from >= bandCount ? bandCount - 1 : from)].envelope;
            float amount = direct + (remapped - direct) * remapAmount;
            if (freeze) {
                b.held = b.held * freezeDecay;
                if (b.held < amount) b.held = amount;
                amount = b.held + (amount - b.held) * (1.0f - freezeMorph);
            } else {
                b.held = amount;
            }
            if (gate > 0.0f) {
                const float threshold = gate * 0.25f;
                if (amount < threshold) amount *= 1.0f - gateDepth;
            }
            // The same normalisation on the way out.
            //
            // `Svf::bandpass` returns the raw band output, whose peak gain is
            // its own Q - and this machine derives Q from how many bands cover
            // how many octaves, so *the band count was a volume knob*. With
            // drive off, Init measured -5.1 dB peak at four bands and +54.8 at
            // forty: sixty decibels of swing from a control that is supposed
            // to change the resolution of the vocoder, not its level. The
            // output then sat so far over full scale that the drive stage was
            // not a drive at all but the only thing keeping the machine from
            // destroying itself, which is why all nine patches peaked at
            // exactly the same -3.7 dB.
            //
            // `bandNorm()` is the correction already made to MultiFilter this
            // round, for the same reason: a filter setting should change the
            // character, not the level.
            float s = b.synthesis1.bandpass(source) * b.synthesis1.bandNorm();
            if (twoPole) s = b.synthesis2.bandpass(s) * b.synthesis2.bandNorm();
            out += s * amount * kBandMakeup;
        }
        if (weight > 0.0f) {
            sumLoud += loud / weight;
            sumBright += bandCount > 1 ? (bright / std::fmax(1e-6f, loud)) / static_cast<float>(bandCount - 1) : 0.0f;
            sumWeight += 1.0f;
        }

        // Sibilance: a vocoder with no path for consonants turns every "s"
        // into a hole. The top of the modulator is passed through directly.
        if (sibilance > 0.0f) {
            const float hiss = sibilanceFilter.highpass(modulator);
            const float level = std::fabs(hiss);
            sibilanceEnv += (level - sibilanceEnv) * (level > sibilanceEnv ? 0.02f : 0.0008f);
            // Turned up means *more* sibilance, which is what the name says.
            //
            // The knob was the gate's threshold, so raising it let less
            // through - a control that does the opposite of its label, and
            // one this bank's first draft set to 0.6 on every patch that was
            // meant to have consonants.
            if (sibilanceEnv > (1.0f - sibilance) * 0.05f) {
                rngState = rngState * 1664525u + 1013904223u;
                const float noise = (static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f;
                // Shaped to where an "s" actually lives, and at a level
                // that sits under the vocoder rather than over it.
                //
                // This was flat white noise times six. Measured on the
                // octave bands, it put 48 dB more energy in the top octave
                // than the whole vocoder produced - so every patch came out
                // with an identical -10.4 dB top band, and the difference
                // between Classic and a fully reversed bank fell from 11.7 dB
                // to 2.0. The bank map is the machine, and the consonant path
                // was drowning it.
                // Band-passed, not high-passed. White noise above a corner
                // is *more* top-heavy than white noise, because the top
                // octave is the widest - and the bank stops at `high`, so
                // everything above it was sibilance and nothing else. A band
                // around the corner puts the consonant where the consonant
                // is and leaves the air above it alone.
                const float shaped =
                    sibilanceShaper.bandpass(noise) * sibilanceShaper.bandNorm();
                out += shaped * sibilanceEnv * sibLevel * kSibilanceMakeup;
            }
        }

        // Pitch tracking: zero crossings over a window. Crude next to
        // autocorrelation and about a thousandth of the cost, which is the
        // right trade when it is steering a carrier rather than tuning one.
        if (track) {
            if ((zeroPrev <= 0.0f) != (modulator <= 0.0f)) ++zeroCount;
            zeroPrev = modulator;
            if (++zeroWindow >= 1024) {
                const float hz = static_cast<float>(zeroCount) * sampleRate / (2.0f * 1024.0f);
                if (hz > 40.0f && hz < 2000.0f) {
                    const float glideK = clampf(1024.0f / (paramOf(TrackGlide) * sampleRate), 0.0f, 1.0f);
                    trackedHz += (hz - trackedHz) * glideK;
                    trackedNote = 69.0f + 12.0f * std::log2(std::fmax(20.0f, trackedHz) / 440.0f);
                }
                zeroCount = 0;
                zeroWindow = 0;
            }
        }

        float x = out * wet + (role == InputIsCarrier ? external : carrier) * dry;
        if (drive > 0.0f) {
            // Normalised on the nominal level. The divisor used to be
            // `1 + drive * 1.5`, a guess that handed small signals eleven
            // decibels at the top of the knob and capped everything else at
            // 0.4 - but it never showed, because until the bands were
            // normalised this stage was permanently slammed and acting as the
            // machine's limiter rather than as a drive.
            x = std::tanh(x * driveK) * driveNorm;
        }
        feedbackSample = x;
        x *= volume * kHouse;
        L[n] = x * panL;
        R[n] = x * panR;
    }

    if (sumWeight > 0.0f) {
        loudness = clampf(sumLoud / sumWeight * 8.0f, 0.0f, 1.0f);
        brightness = clampf(sumBright / sumWeight, 0.0f, 1.0f);
    }
    if (anyVoice || inputPeak >= 1e-6f) {
        quietBlocks = 0;
    } else {
        float peak = 0.0f;
        for (int32_t n = 0; n < frames; ++n) peak = std::fmax(peak, std::fmax(std::fabs(L[n]), std::fabs(R[n])));
        quietBlocks = peak < 1e-6f ? quietBlocks + 1 : 0;
    }
    return true;
}

} // namespace acidulous::machine
