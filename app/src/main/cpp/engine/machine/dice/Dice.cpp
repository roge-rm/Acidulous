#include "Dice.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

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
    for (auto &v : voices) v.filter.setSampleRate(sampleRate);
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
    return &voices[0]; // a slicer steals rather than refuses: the beat comes first
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
    if (chance(paramOf(Drop))) return; // the roll said nothing

    int32_t slice = asked;
    if (chance(paramOf(Swap))) slice = static_cast<int32_t>(uniform() * slices) % slices;

    const bool sliceReversed = static_cast<int32_t>(sliceParam(slice, Direction) + 0.5f) != 0;
    const bool reversed = sliceReversed != chance(paramOf(Reverse));

    float semis = paramOf(RootPitch) + paramOf(Fine) * 0.01f + sliceParam(slice, Pitch);
    if (chance(paramOf(Jump))) {
        const float range = paramOf(JumpRange);
        semis += (uniform() < 0.5f ? -1.0f : 1.0f) * std::round(uniform() * range);
    }

    int32_t repeats = 0;
    if (chance(paramOf(Stutter))) repeats = steppedOf(StutterDiv);

    Voice *v = allocate();
    v->used = true;
    v->note = note;
    v->slice = slice;
    v->start = bounds[slice];
    v->end = bounds[slice + 1];
    if (v->end <= v->start) v->end = std::min(take->frames, v->start + 64);
    v->inc = std::pow(2.0f, semis / 12.0f) * paramOf(Rate) * (reversed ? -1.0 : 1.0);
    v->pos = reversed ? v->end - 1 : v->start;
    const int32_t span = v->end - v->start;
    v->repeatLen = repeats > 0 ? std::max(64, span / repeats) : span;
    v->repeats = repeats;
    v->left = static_cast<int32_t>(v->repeatLen / std::max(0.05, std::fabs(v->inc)));

    const float accent = paramOf(Accent);
    const float vel = 1.0f - accent + accent * static_cast<float>(velocity) / 127.0f;
    const float pan = std::clamp(sliceParam(slice, Pan), -1.0f, 1.0f);
    const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
    const float level = sliceParam(slice, Level) * vel;
    v->gainL = std::cos(angle) * level * 1.4142f;
    v->gainR = std::sin(angle) * level * 1.4142f;
    v->env = 1.0f;
    // Gate at one lets a slice run to its own end; below that it is cut
    // short, which is what makes a loop breathe rather than smear.
    const float decay = sliceParam(slice, Decay) * std::max(0.02f, paramOf(Gate));
    v->envCoeff = 1.0f - std::exp(-1.0f / (decay * sampleRate));
    v->filter.reset();
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
        for (int32_t i = 0; i < frames; ++i) {
            if (!v.used) break;
            const int32_t idx = std::clamp(static_cast<int32_t>(v.pos), 0, last);
            const int32_t next = std::min(idx + 1, last);
            const float f = static_cast<float>(v.pos - idx);
            const float sl = left[idx] + (left[next] - left[idx]) * f;
            const float sr = right[idx] + (right[next] - right[idx]) * f;
            const float e = v.env;
            v.env -= v.env * v.envCoeff;
            L[i] += v.filter.process(sl * e) * v.gainL;
            R[i] += sr * e * v.gainR;
            v.pos += v.inc;
            if (--v.left <= 0) {
                if (v.repeats > 1) {
                    // A stutter is the same piece again, not the next one.
                    --v.repeats;
                    v.pos = v.inc < 0 ? v.end - 1 : v.start;
                    v.left = static_cast<int32_t>(v.repeatLen / std::max(0.05, std::fabs(v.inc)));
                } else {
                    v.used = false;
                }
            }
            if (v.env < 1e-4f) v.used = false;
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float l = L[i] * volume, r = R[i] * volume;
        if (drive > 0.0001f) {
            const float k = 1.0f + drive * 10.0f;
            l = dsp::fastTanh(l * k) / std::sqrt(k);
            r = dsp::fastTanh(r * k) / std::sqrt(k);
        }
        L[i] = l * panL * 1.4142f;
        R[i] = r * panR * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
