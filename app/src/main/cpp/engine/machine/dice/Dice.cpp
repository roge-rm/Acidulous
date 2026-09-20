#include "Dice.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

// What a sliced loop reaches before the output stage, and so what the drive
// stage should treat as its nominal level.
constexpr float kNominal = 0.3f;
// Where this machine's bank sits in the volume knob's travel, set from Init -
// which carries no `volume` line, and so is the only patch that says what the
// machine does at its defaults. Without one, Init measured 1.7 dB over the
// bank's line and Dust, which throws away level through a short gate and a
// low filter, ran out of knob 5.5 dB under it.
constexpr float kHouse = 0.82f;
// Sixty decibels, as a multiple of the time constant: a decay stated in
// seconds has to be the time to fall sixty, or the number is a fiction.
constexpr float kLn1000 = 6.907755f;
// A per-slice decay at or above the top of its range means no decay at all.
constexpr float kDecayOff = 4.0f;
// The ramps at a slice's edges, in frames at 48 kHz. Short enough that a
// kick still arrives as a kick, long enough that the boundary is not a step.
constexpr int32_t kFadeIn = 24;   // half a millisecond
constexpr int32_t kFadeOut = 96;  // two milliseconds

Dice::Dice() { initParams(); }

const ParamDef *Dice::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static bool built = false;
    static char names[kSlices * SliceParamCount][16];
    if (!built) {
        struct Spec { const char *name; float min, max, def; Curve curve; int32_t steps; const char *unit; };
        static const Spec kSlice[SliceParamCount] = {
            {"level", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""},
            {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"pitch", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, ""},
            {"decay", 0.01f, 4.0f, 4.0f, Curve::Exponential, 0, "s"},
            {"dir", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        };
        for (int32_t s = 0; s < kSlices; ++s) {
            for (int32_t i = 0; i < SliceParamCount; ++i) {
                const int32_t at = s * SliceParamCount + i;
                std::snprintf(names[at], sizeof(names[at]), "s%02d_%s", s, kSlice[i].name);
                defs[at] = {names[at], kSlice[i].min, kSlice[i].max, kSlice[i].def, kSlice[i].curve, kSlice[i].steps, kSlice[i].unit};
            }
        }
        defs[CutMode] = {"cut", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""}; // onsets, grid
        defs[SliceCount] = {"slices", 2.0f, 16.0f, 8.0f, Curve::Stepped, 15, ""};
        defs[Gate] = {"gate", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""};
        defs[Rate] = {"rate", 0.25f, 4.0f, 1.0f, Curve::Exponential, 0, ""};
        defs[RootPitch] = {"pitch", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Fine] = {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"};
        defs[Swap] = {"swap", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Reverse] = {"reverse", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Stutter] = {"stutter", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[StutterDiv] = {"stutterdiv", 2.0f, 8.0f, 4.0f, Curve::Stepped, 7, ""};
        defs[Drop] = {"drop", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Jump] = {"jump", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[JumpRange] = {"jumprange", 1.0f, 12.0f, 12.0f, Curve::Stepped, 12, ""};
        defs[Hold] = {"hold", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""};
        defs[Seed] = {"seed", 0.0f, 63.0f, 1.0f, Curve::Stepped, 64, ""};
        defs[Cutoff] = {"cutoff", 40.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"};
        defs[Resonance] = {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[FilterType] = {"filtertype", 0.0f, 11.0f, 1.0f, Curve::Stepped, 12, ""};
        defs[Drive] = {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Volume] = {"volume", 0.0f, 1.5f, 0.9f, Curve::Linear, 0, ""};
        defs[MasterPan] = {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        defs[Accent] = {"accent", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""};
        built = true;
    }
    count = Count;
    return defs;
}

void Dice::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.filter.setSampleRate(sampleRate);
        v.filterR.setSampleRate(sampleRate);
    }
    reset();
}

void Dice::reset() {
    for (auto &v : voices) {
        v.used = false;
        v.filter.reset();
    }
    triggers = 0;
}

void Dice::allNotesOff() {
    for (auto &v : voices) v.used = false;
}

void *Dice::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    for (auto &v : voices) v.used = false; // they hold positions into the old loop
    void *old = const_cast<audio::Take *>(take);
    take = static_cast<const audio::Take *>(object);
    builtFrames = -1; // recut against the new loop
    return old;
}

/**
 * Where the cuts fall. Either the loop's own transients - which is what you
 * want on a break, because that is where the hits are - or an even grid,
 * which is what you want when the loop is a pad or the detector finds
 * nothing useful.
 */
void Dice::recut() {
    slices = 0;
    if (take == nullptr || take->frames <= 1) return;
    const int32_t want = std::clamp(steppedOf(SliceCount), 2, kSlices);
    const int32_t mode = steppedOf(CutMode);
    if (mode == 0 && !take->onsets.empty()) {
        // Onsets, thinned evenly when there are more of them than slices:
        // taking the first N would slice the first bar and ignore the rest.
        const int32_t found = static_cast<int32_t>(take->onsets.size());
        const int32_t n = std::min(want, found);
        for (int32_t i = 0; i < n; ++i) {
            bounds[i] = take->onsets[static_cast<size_t>(static_cast<int64_t>(i) * found / n)];
        }
        slices = n;
    } else {
        for (int32_t i = 0; i < want; ++i) {
            bounds[i] = static_cast<int32_t>(static_cast<int64_t>(i) * take->frames / want);
        }
        slices = want;
    }
    bounds[slices] = take->frames;
    builtMode = mode;
    builtCount = want;
    builtFrames = take->frames;
}

Dice::Voice *Dice::allocate() {
    for (auto &v : voices) if (!v.used) return &v;
    // A slicer steals rather than refuses - the beat comes first - but it has
    // to steal the *oldest* slice, not `voices[0]`.
    //
    // Taking the first slot cut whichever slice happened to live there, dead,
    // at whatever amplitude it was passing through: on Held, where `hold`
    // makes every roll land in the same place each pass, that was a step of
    // 0.71 once a bar and a click reading three hundred thousand times the
    // surrounding slope. The oldest voice is the one nearest its own end, so
    // it is both the least missed and the quietest place to cut.
    Voice *oldest = &voices[0];
    for (auto &v : voices) if (v.age > oldest->age) oldest = &v;
    return oldest;
}

/**
 * The dice for one trigger. Held, the rolls come from the slice and the seed
 * alone, so every pass is identical and a take can be recorded; free, they
 * come from a counter that never repeats.
 */
uint32_t Dice::rollFor(int32_t slice) {
    if (steppedOf(Hold) != 0) {
        uint32_t h = static_cast<uint32_t>(steppedOf(Seed)) * 2654435761u + static_cast<uint32_t>(slice) * 40503u + 1u;
        h ^= h >> 13;
        h *= 0x5bd1e995u;
        h ^= h >> 15;
        return h;
    }
    rng = rng * 1664525u + 1013904223u;
    return rng;
}

void Dice::noteOn(uint8_t note, uint8_t velocity) {
    if (take == nullptr || slices <= 0) return;
    const int32_t asked = note - kBaseNote;
    if (asked < 0 || asked >= slices) return;

    uint32_t roll = rollFor(asked);
    auto chance = [&](float probability) {
        roll = roll * 1664525u + 1013904223u;
        return static_cast<float>(roll >> 8) * (1.0f / 16777216.0f) < probability;
    };
    auto uniform = [&]() {
        roll = roll * 1664525u + 1013904223u;
        return static_cast<float>(roll >> 8) * (1.0f / 16777216.0f);
    };

    ++triggers;
    if (chance(targetOf(Drop))) return; // the roll said nothing

    int32_t slice = asked;
    if (chance(targetOf(Swap))) slice = static_cast<int32_t>(uniform() * slices) % slices;

    const bool sliceReversed = static_cast<int32_t>(sliceParam(slice, Direction) + 0.5f) != 0;
    const bool reversed = sliceReversed != chance(targetOf(Reverse));

    float semis = targetOf(RootPitch) + targetOf(Fine) * 0.01f + sliceParam(slice, Pitch);
    if (chance(targetOf(Jump))) {
        const float range = targetOf(JumpRange);
        semis += (uniform() < 0.5f ? -1.0f : 1.0f) * std::round(uniform() * range);
    }

    int32_t repeats = 0;
    if (chance(targetOf(Stutter))) repeats = steppedTargetOf(StutterDiv);

    Voice *v = allocate();
    v->used = true;
    v->note = note;
    v->slice = slice;
    v->start = bounds[slice];
    v->end = bounds[slice + 1];
    if (v->end <= v->start) v->end = std::min(take->frames, v->start + 64);
    v->inc = std::pow(2.0f, semis / 12.0f) * targetOf(Rate) * (reversed ? -1.0 : 1.0);
    v->pos = reversed ? v->end - 1 : v->start;
    const int32_t span = v->end - v->start;
    v->repeatLen = repeats > 0 ? std::max(64, span / repeats) : span;
    v->repeats = repeats;
    v->left = static_cast<int32_t>(v->repeatLen / std::max(0.05, std::fabs(v->inc)));

    const float accent = targetOf(Accent);
    const float vel = 1.0f - accent + accent * static_cast<float>(velocity) / 127.0f;
    const float pan = std::clamp(sliceParam(slice, Pan), -1.0f, 1.0f);
    const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
    const float level = sliceParam(slice, Level) * vel;
    v->gainL = std::cos(angle) * level * 1.4142f;
    v->gainR = std::sin(angle) * level * 1.4142f;
    v->env = 1.0f;
    // Gate at one lets a slice run to its own end; below that it is cut
    // short, which is what makes a loop breathe rather than smear.
    //
    // The decay is the time to fall sixty decibels, not one time constant -
    // the same correction Hexbeat and Genesis needed, where a label in
    // seconds meant seven times what it said. The top of the range is the
    // exception and means *no* decay: a slicer playing a loop straight must
    // not fade every slice, and four seconds read honestly would take three
    // and a half decibels off a quarter-second slice.
    const float decay = sliceParam(slice, Decay) * std::max(0.02f, targetOf(Gate));
    v->envCoeff = decay >= kDecayOff ? 0.0f
                                     : 1.0f - std::exp(-kLn1000 / (decay * sampleRate));
    v->filter.reset();
    v->filterR.reset();
}

void Dice::noteOff(uint8_t) {} // a slice plays its length; it is not held

bool Dice::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    if (take == nullptr || take->frames <= 1) return true;

    if (builtFrames != take->frames || builtMode != steppedOf(CutMode) || builtCount != steppedOf(SliceCount)) {
        recut();
    }
    if (slices <= 0) return true;

    const float cutoff = paramOf(Cutoff), reso = paramOf(Resonance);
    const int32_t ftype = steppedOf(FilterType);
    const float drive = paramOf(Drive), volume = paramOf(Volume);
    const float masterPan = paramOf(MasterPan);
    const float panL = std::cos((masterPan + 1.0f) * 0.25f * 3.14159265f);
    const float panR = std::sin((masterPan + 1.0f) * 0.25f * 3.14159265f);
    const float *left = take->left.data();
    const float *right = take->right.empty() ? left : take->right.data();
    const int32_t last = take->frames - 1;

    for (auto &v : voices) {
        if (!v.used) continue;
        v.filter.set(cutoff, reso, ftype, dsp::MultiFilter::Clean, 0.0f);
        v.filterR.set(cutoff, reso, ftype, dsp::MultiFilter::Clean, 0.0f);
        for (int32_t i = 0; i < frames; ++i) {
            if (!v.used) break;
            const int32_t idx = std::clamp(static_cast<int32_t>(v.pos), 0, last);
            const int32_t next = std::min(idx + 1, last);
            const float f = static_cast<float>(v.pos - idx);
            const float sl = left[idx] + (left[next] - left[idx]) * f;
            const float sr = right[idx] + (right[next] - right[idx]) * f;
            // Ramped at both ends.
            //
            // A slice cut at onsets ends exactly where the next transient
            // begins, so its last sample is nowhere near zero and stopping
            // there is a step - the harness measured sixteen thousand times
            // the surrounding slope on Straight and three hundred thousand on
            // Held. The out ramp is the longer of the two because that is the
            // end that lands on a transient; the in ramp only has to cover a
            // grid cut landing mid-waveform, and any longer would eat the
            // attack that a slicer exists to deliver.
            const float rampIn = v.age < kFadeIn
                                     ? static_cast<float>(v.age) / static_cast<float>(kFadeIn)
                                     : 1.0f;
            const float rampOut = v.left < kFadeOut
                                      ? static_cast<float>(v.left) / static_cast<float>(kFadeOut)
                                      : 1.0f;
            const float e = v.env * rampIn * rampOut;
            ++v.age;
            v.env -= v.env * v.envCoeff;
            L[i] += v.filter.process(sl * e) * v.gainL;
            R[i] += v.filterR.process(sr * e) * v.gainR;
            v.pos += v.inc;
            if (--v.left <= 0) {
                if (v.repeats > 1) {
                    // A stutter is the same piece again, not the next one.
                    --v.repeats;
                    v.pos = v.inc < 0 ? v.end - 1 : v.start;
                    v.left = static_cast<int32_t>(v.repeatLen / std::max(0.05, std::fabs(v.inc)));
                    v.age = 0; // a repeat is a new start, and needs the same ramp
                } else {
                    v.used = false;
                }
            }
            if (v.env < 1e-4f) v.used = false;
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float l = L[i] * volume * kHouse, r = R[i] * volume * kHouse;
        if (drive > 0.0001f) {
            // Normalised on the nominal level, not on the ceiling. The fourth
            // machine to carry `tanh(x * k) / sqrt(k)`, after Hexbeat, Genesis
            // and Pollen: sqrt(k) grows faster than the tanh recovers, so the
            // knob bought dirt by spending level.
            const float k = 1.0f + drive * 10.0f;
            const float norm = kNominal / dsp::fastTanh(kNominal * k);
            l = dsp::fastTanh(l * k) * norm;
            r = dsp::fastTanh(r * k) * norm;
        }
        L[i] = l * panL * 1.4142f;
        R[i] = r * panR * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
