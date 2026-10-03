#pragma once
#include <cmath>
#include <engine/dsp/Math.h>
#include <engine/machine/hammer/KeyTables.h>

// One key of an instrument, as the strings and the hammer need it.
//
// The measured part comes from the anchors in KeyTables.h, read between them
// (on a log scale where a quantity is a ratio). What a recording can't show -
// the hammer's mass and felt, where it strikes, the strings' impedance, how
// many strings a key has - comes from the piano acoustics literature as
// smooth curves over the keyboard, and the measured brightness calibrates the
// felt against them (tools/hammer_test).
namespace acidulous::machine::hammer {
using dsp::clampf;

struct KeySpec {
    int key = 60;
    float B = 2.5e-4f, stretchCents = 0.0f;
    /** T60s, seconds, as in Anchor: prompt and after for the fundamental, partials 2-4 and 5-10. */
    float prompt1 = 8.0f, after1 = 30.0f, prompt3 = 5.0f, after3 = 20.0f, prompt7 = 3.0f, after7 = 10.0f;
    float kneeDb = 35.0f, wobbleDb = 3.0f, reachHz = 1000.0f, brightF0 = 1.5f;
    /** Strings to the note. */
    int strings = 3;
    /** How far apart the strings are tuned, in coupling widths (Course::Design::unison). */
    float unison = 1.0f;
    /** How unevenly the hammer meets the strings: what starts the aftersound. */
    float uneven = 0.3f;
    /** How much less than one B says the partials stretch high up: B / (1 + bend k) at partial k. */
    float bend = 0.0f;
    /** Where the hammer strikes, a fraction of the string from the agraffe. */
    float strike = 0.12f;
    /** The hammer: mass (kg), felt exponent, and contact time at 2 m/s on something rigid (s). */
    float mass = 8.7e-3f, exponent = 2.6f, contact = 2e-3f;
    /** The calibrated factor already in [contact]. */
    float contactAdjust = 1.0f;
    /** How loud the board's knock is under this key, against the others. */
    float knock = 1.0f;
    /** The key's output gain: what evens the keyboard out to the reference's shape. */
    float level = 1.0f;
    /** How much harder the felt is per doubling of the hammer's speed, as a power. */
    float hardening = 0.0f;
    /** The strings' wave impedance, kg/s. */
    float impedance = 2.2f;
    /** Whether it has a damper, and how fast the damper stops it (T60, s). */
    bool damper = true;
    float dampedT60 = 0.3f;
    /** Which part of the bridge it sits on: 0 bass, 1 tenor, 2 treble; and where it is across the stereo. */
    int zone = 1;
    float pan = 0.0f;
};

namespace detail {

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

/** [field] of [table] at [key], read between anchors; on a log scale if [log]. A gap takes its nearest neighbour. */
template <typename Field>
float between(const Anchor *table, int count, float key, Field field, bool log) {
    auto valid = [&](int i) { return field(table[i]) > kGap && (!log || field(table[i]) > 0.0f); };
    int lo = -1, hi = -1;
    for (int i = 0; i < count; ++i) {
        if (!valid(i)) continue;
        if (static_cast<float>(table[i].key) <= key) lo = i;
        if (static_cast<float>(table[i].key) >= key && hi < 0) hi = i;
    }
    if (lo < 0 && hi < 0) return 0.0f;
    if (lo < 0) return field(table[hi]);
    if (hi < 0 || hi == lo) return field(table[lo]);
    const float t = (key - static_cast<float>(table[lo].key)) / static_cast<float>(table[hi].key - table[lo].key);
    const float a = field(table[lo]), b = field(table[hi]);
    return log ? std::exp(lerp(std::log(a), std::log(b), t)) : lerp(a, b, t);
}

/** A factor from an Adjust table at [key], read between on a log scale. */
inline float betweenAdjust(const Adjust *table, int count, float key, float Adjust::*field) {
    if (key <= static_cast<float>(table[0].key)) return table[0].*field;
    for (int i = 1; i < count; ++i) {
        if (key <= static_cast<float>(table[i].key)) {
            const float t = (key - static_cast<float>(table[i - 1].key)) /
                            static_cast<float>(table[i].key - table[i - 1].key);
            return std::exp(lerp(std::log(table[i - 1].*field), std::log(table[i].*field), t));
        }
    }
    return table[count - 1].*field;
}

/** A straight line over the keyboard from [atA0] (key 21) to [atC8] (key 108), on a log scale if [log]. */
inline float across(float key, float atA0, float atC8, bool log) {
    const float t = clampf((key - 21.0f) / 87.0f, 0.0f, 1.0f);
    return log ? std::exp(lerp(std::log(atA0), std::log(atC8), t)) : lerp(atA0, atC8, t);
}

} // namespace detail

/** The grand's key [key] (fractional keys read between). */
inline KeySpec grandKey(float key) {
    using detail::across;
    using detail::between;
    using detail::betweenAdjust;
    constexpr int n = static_cast<int>(sizeof(kGrandA) / sizeof(kGrandA[0]));
    KeySpec s;
    s.key = static_cast<int>(std::lround(key));
    s.B = between(kGrandA, n, key, [](const Anchor &a) { return a.B; }, true);
    s.stretchCents = between(kGrandA, n, key, [](const Anchor &a) { return a.cents; }, false);
    s.prompt1 = between(kGrandA, n, key, [](const Anchor &a) { return a.prompt1; }, true);
    s.after1 = between(kGrandA, n, key, [](const Anchor &a) { return a.after1; }, true);
    s.prompt3 = between(kGrandA, n, key, [](const Anchor &a) { return a.prompt3; }, true);
    s.after3 = between(kGrandA, n, key, [](const Anchor &a) { return a.after3; }, true);
    s.prompt7 = between(kGrandA, n, key, [](const Anchor &a) { return a.prompt7; }, true);
    s.after7 = between(kGrandA, n, key, [](const Anchor &a) { return a.after7; }, true);
    // No partial 5-10 left to measure at the top: the ratios below it.
    if (key > 93.0f) {
        s.prompt7 = s.prompt3 * 0.47f;
        s.after7 = s.after3 * 0.4f;
    }
    s.kneeDb = between(kGrandA, n, key, [](const Anchor &a) { return a.kneeDb; }, false);
    s.wobbleDb = between(kGrandA, n, key, [](const Anchor &a) { return a.wobbleDb; }, false);
    s.reachHz = between(kGrandA, n, key, [](const Anchor &a) { return a.reachHz; }, true);
    s.brightF0 = between(kGrandA, n, key, [](const Anchor &a) { return a.brightF0; }, true);
    // What the model needs on top, to measure like the recordings.
    {
        constexpr int m = static_cast<int>(sizeof(kGrandAAdjust) / sizeof(kGrandAAdjust[0]));
        auto factor = [&](float Adjust::*field) {
            return betweenAdjust(kGrandAAdjust, m, key, field);
        };
        s.B *= factor(&Adjust::B);
        s.prompt1 *= factor(&Adjust::prompt1);
        s.after1 *= factor(&Adjust::after1);
        s.prompt3 *= factor(&Adjust::prompt3);
        s.after3 *= factor(&Adjust::after3);
        s.prompt7 *= factor(&Adjust::prompt7);
        s.after7 *= factor(&Adjust::after7);
        s.unison *= factor(&Adjust::unison);
        s.contactAdjust = factor(&Adjust::contact);
        s.level = factor(&Adjust::level);
        s.knock = factor(&Adjust::knock);
    }
    // A grand's lowest notes have one string, then two, then three.
    s.strings = key < 28.5f ? 1 : (key < 45.5f ? 2 : 3);
    // From the literature (hammer masses 11 to 6.5 g, felt exponents 2.3 to
    // 3, contact 4 to 0.6 ms at 2 m/s, the strike an eighth of the way along
    // and nearer the end at the top).
    // The lowest wound strings stretch less high up than their B says. In
    // the reference, the partials' spacing from 0.6 to 3 kHz is as
    // B / (1 + k/300) puts it at A0, B / (1 + k/250) at D#1 and
    // B / (1 + k/600) at A1; from C2 one B fits.
    s.bend = key < 27.0f ? detail::lerp(1.0f / 300.0f, 1.0f / 250.0f, clampf((key - 21.0f) / 6.0f, 0.0f, 1.0f))
           : key < 33.0f ? detail::lerp(1.0f / 250.0f, 1.0f / 600.0f, (key - 27.0f) / 6.0f)
           : key < 36.0f ? detail::lerp(1.0f / 600.0f, 0.0f, (key - 33.0f) / 3.0f) : 0.0f;
    // Where the hammer strikes, from the recordings' notches: an eighth of the
    // way along (0.12) up to D#4, then nearer the end, 0.09 by F#5; above
    // that, what the literature says.
    s.strike = key < 63.0f ? 0.12f
             : key < 78.0f ? detail::lerp(0.12f, 0.09f, (key - 63.0f) / 15.0f)
                           : detail::lerp(0.09f, 0.075f, clampf((key - 78.0f) / 30.0f, 0.0f, 1.0f));
    s.mass = across(key, 11.0e-3f, 6.5e-3f, true);
    s.exponent = across(key, 2.3f, 3.0f, false);
    // Calibrated to how the spectrum's shape changes with velocity in the
    // reference, band by band over the first 300 ms (tools/hammer_reference,
    // bands against the note's total): soft notes keep their upper partials
    // more than a stiffening felt alone would leave them.
    s.hardening = key < 72.0f ? 0.32f : detail::lerp(0.32f, 0.2f, clampf((key - 72.0f) / 36.0f, 0.0f, 1.0f));
    s.contact = across(key, 4.0e-3f, 0.6e-3f, true) * s.contactAdjust;
    // Wound bass strings are heavy: about 12 kg/s at A0, 2.2 at middle C, 1.5 at the top.
    s.impedance = key < 60.0f ? std::exp(detail::lerp(std::log(12.0f), std::log(2.2f), clampf((key - 21.0f) / 39.0f, 0.0f, 1.0f)))
                              : std::exp(detail::lerp(std::log(2.2f), std::log(1.5f), clampf((key - 60.0f) / 48.0f, 0.0f, 1.0f)));
    // No dampers on the top two octaves or so; slower ones in the bass.
    s.damper = key < 89.0f;
    s.dampedT60 = across(key, 1.2f, 0.08f, true);
    s.zone = key < 48.0f ? 0 : (key < 72.0f ? 1 : 2);
    // As the player hears it: bass on the left.
    s.pan = clampf((key - 64.0f) / 44.0f, -1.0f, 1.0f) * 0.6f;
    return s;
}

} // namespace acidulous::machine::hammer
