#include "Pollen.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>
#include <engine/core/InputBus.h>
#include <engine/dsp/Math.h>
#include <engine/inputmod/Scales.h>

namespace acidulous::machine {

using audio::Take;
using audio::View;

namespace {
// The house level.
//
// Pollen had none, and a cloud is quiet by nature: ninety-six grains each
// windowed to nothing at both ends sum to far less than their peaks suggest.
// The bank's median patch sat seven decibels under the line the rest of the
// factory is levelled to, and twenty-one of thirty-six ended up pinned at the
// top of the volume knob still short of it - which is a levelling that has
// run out of room, not a bank that is quiet.
//
// This does not decide how loud Pollen is; the bank is levelled either way.
// It decides where in the knob's travel a patch sits, and it is set from
// Init, which carries no volume line of its own.
// Note the bank is levelled to -25 rather than the -21 the sustained machines
// use, and that is not a machine that is quiet: a cloud's crest factor is
// about twenty decibels where a pad's is twelve, so at -21 loud its peaks sit
// on zero dBFS with nothing left for a second note. Four decibels down the
// meter buys back the headroom the peaks need. Levelling to a loudness target
// *decides* the peaks; there is no setting of this constant that avoids it.
// What the grain sum actually reaches before the output stage, and so what
// the bit crusher should treat as full scale.
constexpr float kCrushNominal = 0.3f;
constexpr float kHouse = 0.81f; // set from Init on the musical seed, which is louder than the break was

constexpr float kTwoPi = 6.28318530718f;
float mtof(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
} // namespace

Pollen::Pollen() { initParams(); }

const ParamDef *Pollen::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"source", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""}, // sample, live
        {"ingain", 0.0f, 4.0f, 1.0f, Curve::Linear, 0, ""},
        {"buffer", 0.25f, 8.0f, 4.0f, Curve::Exponential, 0, "s"},
        {"freeze", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"capture", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        // Capped below one in the table rather than in code: no automation
        // curve can then ask for a loop that grows.
        {"feedback", 0.0f, 0.95f, 0.0f, Curve::Linear, 0, ""},
        {"position", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"scan", -2.0f, 2.0f, 0.0f, Curve::Linear, 0, ""},
        {"spray", 0.0f, 1.0f, 0.1f, Curve::Linear, 0, ""},
        {"snap", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"reverse", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},  // a probability, not a switch
        {"size", 1.0f, 500.0f, 120.0f, Curve::Exponential, 0, "ms"},
        {"sizespread", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        // 45 a second at 120 ms is five and a half grains sounding at once.
        // At 24 and 90 it was two, and two grains of a pitched source taken
        // from unrelated points comb-filter against each other as they fade
        // in and out - the level shakes, and Dan heard the default patch as a
        // "crackly wind storm". Below about five it is a fault; above, a
        // cloud. The cure is overlap: cutting the spray instead made it
        // measurably worse.
        {"density", 0.5f, 200.0f, 45.0f, Curve::Exponential, 0, "/s"},
        {"jitter", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"window", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"skew", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"pitch", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, ""},
        {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        // At zero the cloud ignores the keyboard, which is what a live
        // processor wants: play it and the buffer is not transposed.
        {"keytrack", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"spread", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, ""},
        {"scatter", 0.0f, 4.0f, 0.0f, Curve::Stepped, 5, ""}, // free 8ve 5th triad scale
        {"scale", 0.0f, 32.0f, 0.0f, Curve::Stepped, 33, ""},
        {"key", 0.0f, 11.0f, 0.0f, Curve::Stepped, 12, ""},
        {"bloom", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"drift", 0.0f, 1.0f, 0.25f, Curve::Linear, 0, ""},
        {"mutate", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"generations", 1.0f, 6.0f, 3.0f, Curve::Stepped, 6, ""},
        {"panspread", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"width", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"bits", 1.0f, 16.0f, 16.0f, Curve::Stepped, 16, ""},
        {"crush", 1.0f, 32.0f, 1.0f, Curve::Exponential, 0, ""},
        {"wobble", 0.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"wobblerate", 0.05f, 12.0f, 0.7f, Curve::Exponential, 0, "Hz"},
        {"cutoff", 40.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
        {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"filtertype", 0.0f, 11.0f, 1.0f, Curve::Stepped, 12, ""},
        {"ampattack", 0.001f, 8.0f, 0.05f, Curve::Exponential, 0, "s"},
        {"ampdecay", 0.005f, 8.0f, 0.5f, Curve::Exponential, 0, "s"},
        {"ampsustain", 0.0f, 1.0f, 0.9f, Curve::Linear, 0, ""},
        {"amprelease", 0.005f, 12.0f, 0.6f, Curve::Exponential, 0, "s"},
        {"mono", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"glide", 0.0f, 2.0f, 0.0f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"velocity", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"dry", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

float Pollen::rawOf(int32_t p) const {
    int32_t n = 0;
    const ParamDef *defs = paramDefs(n);
    return defs[p].map(params_.normalized(p));
}

void Pollen::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    ringCapacity = sr * kLiveSeconds;
    ringL.assign(static_cast<size_t>(ringCapacity), 0.0f);
    ringR.assign(static_cast<size_t>(ringCapacity), 0.0f);
    liveLength = ringCapacity;
    // Four window shapes, once, into tables: a cloud of ninety-six grains
    // cannot afford a cos() each.
    for (int shape = 0; shape < WindowCount; ++shape) {
        window[shape].resize(kWindowSize + 1);
        for (int i = 0; i <= kWindowSize; ++i) {
            const float t = static_cast<float>(i) / kWindowSize;
            float w = 0.0f;
            switch (shape) {
            case Hann: w = 0.5f - 0.5f * std::cos(t * kTwoPi); break;
            case Tukey: { // flat in the middle, so a long grain keeps its body
                const float edge = 0.25f;
                w = t < edge ? 0.5f - 0.5f * std::cos(t / edge * 3.14159265f)
                             : (t > 1.0f - edge ? 0.5f - 0.5f * std::cos((1.0f - t) / edge * 3.14159265f) : 1.0f);
                break;
            }
            case Percussive: w = std::exp(-5.0f * t) * (1.0f - std::exp(-60.0f * t)); break;
            case Reverse: w = std::exp(-5.0f * (1.0f - t)) * (1.0f - std::exp(-60.0f * (1.0f - t))); break;
            default: break;
            }
            window[shape][static_cast<size_t>(i)] = w;
        }
    }
    filterL.setSampleRate(sampleRate);
    filterR.setSampleRate(sampleRate);
    finder.reset(sampleRate);
    for (auto &v : voices) v.amp.setSampleRate(sampleRate);
    reset();
}

void Pollen::reset() {
    for (auto &g : grains) g.active = false;
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.living = 0;
        v.amp.kill();
    }
    filterL.reset();
    filterR.reset();
    feedbackL = feedbackR = 0.0f;
    std::fill(ringL.begin(), ringL.end(), 0.0f);
    std::fill(ringR.begin(), ringR.end(), 0.0f);
    writePos = 0;
    liveOnsetCount = liveOnsetNext = 0;
    captureLeft = 0;
    finder.reset(sampleRate);
}

void *Pollen::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    // Grains hold positions into the old source, so they stop with it.
    for (auto &g : grains) g.active = false;
    for (auto &v : voices) v.living = 0;
    void *old = const_cast<Take *>(source);
    source = static_cast<const Take *>(object);
    return old;
}

Pollen::Voice *Pollen::allocate() {
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

void Pollen::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = steppedTargetOf(Mono) != 0 ? &voices[0] : allocate();
    if (v == nullptr) return;
    // Cut the grains still in the air loose from this voice before the new
    // note takes it over.
    //
    // They read their voice's envelope live - that is what stops a long grain
    // outliving the release it was supposed to end under - but a voice that
    // has been handed to a new note carries a *different* envelope, and a
    // grain born under the old note would step onto it. With grains up to
    // half a second long and eight voices under a melody, that is every
    // reuse. Orphaned grains keep the level they had and ride out their own
    // window, which is what they did before the envelope followed them.
    {
        const int32_t idx = static_cast<int32_t>(v - voices);
        for (auto &g : grains) {
            if (!g.active || g.voice != idx) continue;
            g.gainL *= v->envNow;
            g.gainR *= v->envNow;
            g.voice = -1;
        }
        v->living = 0;
    }
    const float glide = targetOf(Glide);
    const bool gliding = glide > 0.001f && v->used;
    v->glideFrom = gliding ? v->freq : mtof(static_cast<float>(note));
    v->glidePos = gliding ? 0.0f : 1.0f;
    v->freq = v->glideFrom;
    v->used = true;
    v->gate = true;
    v->note = note;
    v->bend = 0.0f;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->age = ++ageCounter;
    v->timer = 0.0f; // the first grain lands at once, not a period later
    v->playhead = 0.0;
    v->scanOffset = 0.0;
    v->amp.retrigger();
}

void Pollen::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Pollen::allNotesOff() {
    for (auto &v : voices) {
        if (!v.used) continue;
        v.gate = false;
        v.amp.release();
    }
}

void Pollen::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Pollen::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }
void Pollen::onBlock(int64_t, int64_t, float) {}

float Pollen::windowAt(int32_t shape, float phase, float skew, float skewK) const {
    // Skew bends time inside the window: negative puts the peak early,
    // positive late, without needing a table per setting. The power is the
    // grain's own, worked out when it was born - it was a `pow` per grain per
    // sample for a number that never changes over the grain's life.
    float t = std::clamp(phase, 0.0f, 1.0f);
    if (skew > 0.001f || skew < -0.001f) t = std::pow(t, skewK);
    const float x = t * kWindowSize;
    const int32_t i = std::min(static_cast<int32_t>(x), kWindowSize - 1);
    const float f = x - static_cast<float>(i);
    const float *w = window[std::clamp(shape, 0, WindowCount - 1)].data();
    return w[i] + (w[i + 1] - w[i]) * f;
}

void Pollen::noteBend(uint8_t note, float semitones) {
    if (Voice *v = voiceForNote(voices, note)) v->bend = semitones;
}

float Pollen::scatterSemis(float amount, uint32_t &state) const {
    state = state * 1664525u + 1013904223u;
    const float r = static_cast<float>(state >> 8) * (1.0f / 16777216.0f);
    const int32_t mode = steppedOf(ScatterMode);
    if (amount <= 0.001f) return 0.0f;
    switch (mode) {
    case Octaves: {
        const int32_t steps = std::max(1, static_cast<int32_t>(amount / 12.0f + 0.5f));
        return 12.0f * static_cast<float>(static_cast<int32_t>(r * (steps * 2 + 1)) - steps);
    }
    case Fifths: {
        static const float kFifths[5] = {-12.0f, -5.0f, 0.0f, 7.0f, 12.0f};
        const int32_t n = std::clamp(static_cast<int32_t>(amount / 6.0f) + 2, 1, 5);
        return kFifths[static_cast<int32_t>(r * n) % 5];
    }
    case Triad: {
        static const float kTriad[6] = {0.0f, 4.0f, 7.0f, 12.0f, 16.0f, 19.0f};
        const int32_t n = std::clamp(static_cast<int32_t>(amount / 4.0f) + 1, 1, 6);
        return kTriad[static_cast<int32_t>(r * n) % 6];
    }
    case InScale: {
        // The same thirty-three the modifiers use, so a cloud can be told to
        // stay in the song's key.
        const music::ScaleDef &scale = music::kScales[std::clamp(steppedOf(ScaleIndex), 0, music::kScaleCount - 1)];
        const int32_t span = std::max(1, static_cast<int32_t>(amount / 12.0f * scale.count + 0.5f));
        const int32_t degree = static_cast<int32_t>(r * (span * 2 + 1)) - span;
        const int32_t octave = static_cast<int32_t>(std::floor(static_cast<float>(degree) / scale.count));
        int32_t index = degree - octave * scale.count;
        if (index < 0) index += scale.count;
        return static_cast<float>(scale.intervals[index] + 12 * octave + steppedOf(Key));
    }
    default:
        return (r * 2.0f - 1.0f) * amount;
    }
}

View Pollen::resolveView(int32_t mode, int32_t liveLen) const {
    View v;
    if (mode == FromLive) {
        v.l = ringL.data();
        v.r = ringR.data();
        v.frames = liveLen;
        v.onsets = liveOnsets;
        v.onsetCount = liveOnsetCount;
    } else if (source != nullptr && source->frames > 1) {
        v.l = source->left.data();
        v.r = source->right.empty() ? source->left.data() : source->right.data();
        v.frames = source->frames;
        v.onsets = source->onsets.empty() ? nullptr : source->onsets.data();
        v.onsetCount = static_cast<int32_t>(source->onsets.size());
    }
    return v;
}

int32_t Pollen::takeGrain() {
    for (int32_t i = 0; i < kGrains; ++i) {
        const int32_t at = (grainCursor + i) % kGrains;
        if (!grains[at].active) {
            grainCursor = (at + 1) % kGrains;
            return at;
        }
    }
    // Nothing free: take the one furthest through its own window, whose
    // death is the least audible thing available.
    int32_t oldest = 0;
    float most = -1.0f;
    for (int32_t i = 0; i < kGrains; ++i) {
        const float t = static_cast<float>(grains[i].age) / static_cast<float>(std::max(1, grains[i].length));
        if (t > most) { most = t; oldest = i; }
    }
    if (grains[oldest].voice >= 0 && voices[grains[oldest].voice].living > 0) --voices[grains[oldest].voice].living;
    return oldest;
}

void Pollen::spawn(Voice &v, int32_t voiceIndex, const View &view, float env) {
    if (!view.usable()) return;
    const int32_t slot = takeGrain();
    Grain &g = grains[slot];
    const bool live = steppedOf(SourceMode) == FromLive;

    const float sizeMs = paramOf(Size) * (1.0f + (nextRandom() * 2.0f - 1.0f) * paramOf(SizeSpread));
    const float spray = paramOf(Spray);
    const float snap = paramOf(Snap);
    const float span = static_cast<float>(view.frames);

    float pos = static_cast<float>(v.playhead) + nextRandom() * 0.0f;
    pos += (nextRandom() * 2.0f - 1.0f) * spray * span * 0.5f;
    while (pos < 0.0f) pos += span;
    while (pos >= span) pos -= span;
    if (snap > 0.001f && view.onsetCount > 0) {
        const float onset = view.nearestOnset(pos);
        pos += (onset - pos) * snap;
    }

    const float semis = paramOf(Pitch) + paramOf(Fine) * 0.01f + paramOf(Transpose) + 12.0f * paramOf(Octave) +
                        bend * paramOf(BendRange) + v.bend;
    uint32_t state = rng;
    const float scatter = scatterSemis(paramOf(Spread), state);
    rng = state;
    // Key tracking: at 1 the buffer transposes with the keyboard, at 0 it
    // plays at its own speed whatever you press - a live processor played
    // from the keys should not detune what it is processing.
    const float track = paramOf(KeyTrack);
    const float ratio = 1.0f + (v.freq / 261.626f - 1.0f) * track;
    const float inc = static_cast<double>(ratio) * std::pow(2.0f, (semis + scatter) / 12.0f);

    // Live, the write head is coming: a grain the head catches mid-window
    // is spliced onto audio from the previous lap, which ticks. Shorten it
    // so it finishes first, and keep a guard behind the head so "position
    // zero" means as recent as is safe rather than right on the seam.
    int32_t length = std::max(8, static_cast<int32_t>(sizeMs * 0.001f * sampleRate));
    if (live) {
        float behind = static_cast<float>(writePos) - pos;
        while (behind < 0.0f) behind += span;
        const int32_t reach = static_cast<int32_t>(span - behind);
        if (reach > 0 && reach < length) length = std::max(8, reach);
    }

    g.active = true;
    g.voice = voiceIndex;
    g.pos = pos;
    g.inc = nextRandom() < paramOf(ReverseProb) ? -inc : inc;
    g.age = 0;
    g.length = length;
    g.skew = paramOf(Skew);
    g.skewK = std::pow(2.0f, -g.skew * 2.0f);
    g.generation = 0;
    const float pan = (nextRandom() * 2.0f - 1.0f) * paramOf(PanSpread);
    const float angle = (std::clamp(pan, -1.0f, 1.0f) + 1.0f) * 0.25f * 3.14159265f;
    // Velocity only. The amplitude envelope is *not* baked in here.
    //
    // It used to be, and a grain then carried the envelope's value at the
    // moment of its birth for the whole of its life - so a 440 ms grain born
    // just before the key came up went on at full level for 440 ms after it,
    // and the tail could get *louder* after the release than during it. The
    // bank_test runaway check caught Boulders doing exactly that: 14 dB up,
    // a second and a half after the last note ended. A release that a grain
    // can outlive is not a release.
    const float level = 1.0f - paramOf(VelocityAmount) + paramOf(VelocityAmount) * v.velocity;
    g.gainL = std::cos(angle) * level;
    g.gainR = std::sin(angle) * level;
    ++v.living;
    ++births;
}

void Pollen::pollinate(const Grain &parent, const View &view) {
    if (!view.usable()) return;
    const int32_t generations = steppedOf(Generations);
    if (parent.generation >= generations) return;
    if (nextRandom() >= paramOf(Bloom)) return;
    // The clock always wins over the lineage: children only take a slot when
    // there is room to spare, so a new note is never starved by a bloom.
    int32_t free = 0;
    for (const auto &g : grains) if (!g.active) ++free;
    if (free <= 8) return;

    const int32_t slot = takeGrain();
    Grain &g = grains[slot];
    const float span = static_cast<float>(view.frames);
    const float drift = paramOf(Drift), mutate = paramOf(Mutate);

    g = parent;
    g.active = true;
    g.age = 0;
    g.generation = parent.generation + 1;
    g.pos = parent.pos + (nextRandom() * 2.0f - 1.0f) * drift * span * 0.25f;
    while (g.pos < 0.0) g.pos += span;
    while (g.pos >= span) g.pos -= span;
    g.inc = parent.inc * std::pow(2.0f, (nextRandom() * 2.0f - 1.0f) * mutate * 12.0f / 12.0f);
    g.length = std::max(8, static_cast<int32_t>(parent.length * (1.0f + (nextRandom() * 2.0f - 1.0f) * mutate * 0.5f)));
    // Each generation a little quieter, so a cloud settles instead of piling
    // up, and a wide pan gets wider down the line.
    const float fade = 0.72f;
    const float widen = 1.0f + mutate * (nextRandom() * 2.0f - 1.0f) * 0.5f;
    g.gainL = parent.gainL * fade * std::clamp(widen, 0.2f, 1.8f);
    g.gainR = parent.gainR * fade * std::clamp(2.0f - widen, 0.2f, 1.8f);
    if (g.voice >= 0) ++voices[g.voice].living;
    ++births;
}

bool Pollen::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;

    const int32_t mode = steppedOf(SourceMode);
    const float dt = 1.0f / sampleRate;

    // --- the live ring: what is coming in, and what we made last block ----
    const int32_t wantLen = std::clamp(static_cast<int32_t>(paramOf(BufferSeconds) * sampleRate), 4800, ringCapacity);
    if (wantLen != liveLength) {
        liveLength = wantLen;
        if (writePos >= liveLength) writePos = 0;
    }
    const bool frozen = rawOf(Freeze) >= 0.5f;
    const bool captureNow = rawOf(Capture) >= 0.5f;
    if (captureNow && !lastCapture) captureLeft = liveLength; // the rising edge takes a whole buffer
    lastCapture = captureNow;

    const InputBus &bus = InputBus::get();
    const float *in = bus.live() ? bus.block() : nullptr;
    const float inGain = paramOf(InGain);
    const float feedback = paramOf(Feedback);
    const bool writing = (!frozen || captureLeft > 0) && (in != nullptr || feedback > 0.0001f);

    // --- everything the grains need, once ---------------------------------
    const View view = resolveView(mode, liveLength);
    const float density = paramOf(Density);
    const float jitter = paramOf(Jitter);
    const float scan = paramOf(Scan);
    const int32_t shape = steppedOf(WindowShape);
    const float position = paramOf(Position);
    const float bloom = paramOf(Bloom);
    const float wobbleCents = paramOf(Wobble);
    wobblePhase += paramOf(WobbleRate) * dt * static_cast<float>(frames);
    while (wobblePhase >= 1.0f) wobblePhase -= 1.0f;
    const float wobble = std::pow(2.0f, std::sin(wobblePhase * kTwoPi) * wobbleCents / 1200.0f);

    int32_t active = 0;
    for (const auto &v : voices) if (v.used) ++active;
    const int32_t quota = active > 0 ? std::max(4, kGrains / active) : kGrains;

    const float ampA = paramOf(AmpAttack), ampD = paramOf(AmpDecay);
    const float ampS = paramOf(AmpSustain), ampR = paramOf(AmpRelease);
    const float glide = paramOf(Glide);
    for (auto &v : voices) {
        v.amp.set(0.0f, ampA, ampD, ampS, ampR, false);
        if (v.glidePos < 1.0f) {
            v.glidePos = std::min(1.0f, v.glidePos + (glide > 0.001f ? dt * frames / glide : 1.0f));
            v.freq = v.glideFrom + (mtof(static_cast<float>(v.note)) - v.glideFrom) * v.glidePos;
        } else {
            v.freq = mtof(static_cast<float>(v.note));
        }
    }

    filterL.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType), dsp::MultiFilter::Clean, 0.0f);
    filterR.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType), dsp::MultiFilter::Clean, 0.0f);

    const int32_t bits = steppedOf(Bits);
    const float crush = paramOf(Crush);
    const float width = paramOf(Width), drive = paramOf(Drive), volume = paramOf(Volume);
    const float dry = paramOf(Dry);
    const float panKnob = paramOf(Pan);
    const float panL = std::cos((panKnob + 1.0f) * 0.25f * 3.14159265f);
    const float panR = std::sin((panKnob + 1.0f) * 0.25f * 3.14159265f);
    const float span = static_cast<float>(std::max(1, view.frames));
    // Live, the write head is a seam: on one side is this lap, on the other
    // is the last one. Position zero means "as recent as is safe", which is
    // one grain's length behind the head, not on top of it.
    const float guard = mode == FromLive
                            ? std::min(span * 0.25f, paramOf(Size) * 0.001f * sampleRate * (1.0f + paramOf(SizeSpread)) + 480.0f)
                            : 0.0f;

    for (int32_t i = 0; i < frames; ++i) {
        // Write first, so a grain can read the sample that just arrived.
        if (writing) {
            const float dryL = in != nullptr ? in[static_cast<size_t>(i) * 2] * inGain : 0.0f;
            const float dryR = in != nullptr ? in[static_cast<size_t>(i) * 2 + 1] * inGain : 0.0f;
            // Feedback, through a DC blocker: a granular loop integrates any
            // offset, and an offset in a ring stays there until a reset.
            const float fbl = feedbackL * feedback;
            const float fbr = feedbackR * feedback;
            const float hpL = fbl - dcInL + 0.9985f * dcOutL;
            const float hpR = fbr - dcInR + 0.9985f * dcOutR;
            dcInL = fbl; dcOutL = hpL;
            dcInR = fbr; dcOutR = hpR;
            float wl = dryL + dsp::fastTanh(hpL);
            float wr = dryR + dsp::fastTanh(hpR);
            // A NaN in the ring would sit there through every parameter
            // change and survive everything but a reset, so it is refused at
            // the door rather than chased later.
            if (!std::isfinite(wl)) wl = 0.0f;
            if (!std::isfinite(wr)) wr = 0.0f;
            wl = std::clamp(wl, -2.0f, 2.0f);
            wr = std::clamp(wr, -2.0f, 2.0f);
            ringL[static_cast<size_t>(writePos)] = wl;
            ringR[static_cast<size_t>(writePos)] = wr;
            // Onsets are found in what came *in*, not in what came back: a
            // detector fed its own feedback finds its own echoes and the
            // cloud snaps to a metronome of its own making.
            if (finder.push(dryL, dryR)) {
                liveOnsets[liveOnsetNext] = writePos;
                liveOnsetNext = (liveOnsetNext + 1) % kLiveOnsets;
                if (liveOnsetCount < kLiveOnsets) ++liveOnsetCount;
            }
            if (++writePos >= liveLength) writePos = 0;
            if (captureLeft > 0) --captureLeft;
        }

        // Voices: envelopes, playheads, births.
        for (int32_t vi = 0; vi < kVoices; ++vi) {
            Voice &v = voices[vi];
            if (!v.used) continue;
            const float env = v.amp.next();
            v.envNow = env;   // the grains in the pool read this, not their birth value
            if (env <= 0.0000005f && !v.gate) {
                v.used = false;
                continue;
            }
            // Where this voice is reading. For the live ring, position walks
            // back from the write head - "now" is zero and the past is to
            // the right, which is how a pedal behaves.
            const double base = mode == FromLive
                                    ? static_cast<double>(writePos) - guard - static_cast<double>(position) * (span - guard)
                                    : static_cast<double>(position) * span;
            v.playhead = base + v.scanOffset;
            v.scanOffset += scan; // scan 1 is real time, 0 holds, negative reverses
            if (v.scanOffset > span) v.scanOffset -= span;
            if (v.scanOffset < -span) v.scanOffset += span;
            while (v.playhead < 0.0) v.playhead += span;
            while (v.playhead >= span) v.playhead -= span;

            v.timer -= 1.0f;
            if (v.timer <= 0.0f) {
                const float period = sampleRate / std::max(0.5f, density);
                v.timer = period * (1.0f + (nextRandom() * 2.0f - 1.0f) * jitter * 0.9f);
                // A voice's first grain is never refused: a dense cloud
                // that has been held down must not make a new key silent.
                if (v.living < quota || v.living == 0) spawn(v, vi, view, env);
            }
        }

        // The cloud itself: one pass over the pool.
        float l = 0.0f, r = 0.0f;
        if (view.usable()) {
            for (auto &g : grains) {
                if (!g.active) continue;
                const float phase = static_cast<float>(g.age) / static_cast<float>(g.length);
                const float w = windowAt(shape, phase, g.skew, g.skewK);
                double p = g.pos;
                if (p < 0.0) p += span;
                if (p >= span) p -= span;
                const int32_t idx = static_cast<int32_t>(p);
                const int32_t next = idx + 1 >= view.frames ? 0 : idx + 1;
                const float f = static_cast<float>(p - idx);
                const float sl = view.l[idx] + (view.l[next] - view.l[idx]) * f;
                const float sr = view.r[idx] + (view.r[next] - view.r[idx]) * f;
                const float ge = g.voice >= 0 ? voices[g.voice].envNow : 1.0f;
                l += sl * w * g.gainL * ge;
                r += sr * w * g.gainR * ge;
                g.pos = p + g.inc * wobble;
                if (++g.age >= g.length) {
                    g.active = false;
                    if (g.voice >= 0 && voices[g.voice].living > 0) --voices[g.voice].living;
                    if (bloom > 0.0001f) pollinate(g, view);
                }
            }
        }

        // Width, then the machine's own lo-fi, then the filter.
        const float mid = (l + r) * 0.5f, side = (l - r) * 0.5f * width;
        l = mid + side;
        r = mid - side;
        if (bits < 16) {
            // Quantise against what the cloud reaches, not against full scale.
            //
            // The signal arriving here peaks near a third, so rounding against
            // 1.0 gave "four bits" *two* usable levels - and an attack then
            // rounds to nothing at all until it crosses half a step, where it
            // snaps to a whole one. That snap is a click, it sits exactly on
            // the note's start, and it is what Dan heard as "a little pop to
            // the start of every or almost every sample": the only two patches
            // measuring a jump of ten thousand times over the preceding
            // twenty milliseconds were Crushed and Telephone, the only two
            // that set `bits`. Downsampled, which crushes the *rate*, was
            // clean - so it was the quantiser, not the lo-fi in general.
            //
            // The same lesson as the saturator, one stage along: normalise on
            // a nominal level, never on a point the signal does not reach.
            const float step = kCrushNominal / static_cast<float>(1 << bits);
            // A step of dither, so the first crossing dissolves instead of
            // clicking - but only while the cloud is sounding. A machine that
            // hisses into its own silence is a worse fault than the one being
            // fixed.
            const float d = active > 0 ? step : 0.0f;
            l = std::round((l + (nextRandom() - 0.5f) * d) / step) * step;
            r = std::round((r + (nextRandom() - 0.5f) * d) / step) * step;
        }
        if (crush > 1.001f) {
            crushAcc += 1.0f / crush;
            if (crushAcc >= 1.0f) { crushAcc -= 1.0f; heldL = l; heldR = r; }
            l = heldL;
            r = heldR;
        }
        l = filterL.process(l);
        r = filterR.process(r);
        if (drive > 0.0001f) {
            // Normalised on the nominal level, not on the ceiling.
            //
            // This was `tanh(x * k) / sqrt(k)`, which is not a drive at all:
            // sqrt(k) grows faster than the tanh recovers, so turning the
            // knob up made the machine quieter and the only way to hear the
            // dirt was to lose the level. The same fault was fixed in Hexbeat
            // and in Genesis this round; Pollen still had it, and it surfaced
            // as Telephone sitting ten decibels under the bank with its
            // volume knob already against the stop - there was no make-up
            // gain to be had from the one stage that should have supplied it.
            //
            // Divide by the drive's own response at the nominal level, so a
            // signal of that size comes out the size it went in and the knob
            // changes the shape rather than the volume.
            const float k = 1.0f + drive * 10.0f;
            const float norm = kCrushNominal / dsp::fastTanh(kCrushNominal * k);
            l = dsp::fastTanh(l * k) * norm;
            r = dsp::fastTanh(r * k) * norm;
        }
        l *= volume * kHouse;
        r *= volume * kHouse;
        feedbackL = l;
        feedbackR = r;
        // The input, passed through: a processor that cannot be heard
        // alongside what it is processing is hard to set up.
        if (dry > 0.0001f && in != nullptr) {
            l += in[static_cast<size_t>(i) * 2] * inGain * dry;
            r += in[static_cast<size_t>(i) * 2 + 1] * inGain * dry;
        }
        L[i] = l * panL * 1.4142f;
        R[i] = r * panR * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
