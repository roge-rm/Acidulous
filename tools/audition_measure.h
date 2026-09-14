#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include <engine/dsp/Fft.h>

// What a rendered buffer measures.
//
// Eight numbers, and the reason there are eight rather than thirty is that
// each one changes a decision while a patch is being voiced. Total harmonic
// distortion, spectral flatness, roughness and every loudness model past RMS
// were all considered and left out: none of them would have moved a knob, and
// each is a thing to keep working.
//
// The two FFT helpers came out of tools/molt_test.cpp, which had them first
// and now includes them from here, so there is one copy of each.

namespace acidulous::audition {

constexpr float kSr = 48000.0f;

inline float dB(float linear) {
    return linear > 1e-9f ? 20.0f * std::log10(linear) : -200.0f;
}

/**
 * Where the energy sits, in Hz: the magnitude-weighted mean over a band.
 *
 * The brightness number. Read down a bank's column it says, at a glance,
 * that every patch in it is the same colour - which is the fault a bank is
 * most likely to have and the one hardest to hear patch by patch.
 *
 * Band-limited because the ends are noise: below 200 Hz a lone fundamental
 * drags the mean down regardless of timbre, and above 8 kHz there is hiss
 * and nothing a listener would call brightness.
 */
inline float centroid(const std::vector<float> &mono, int32_t from, float lo = 200.0f, float hi = 8000.0f) {
    constexpr int32_t kN = 8192;
    static const dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from) + static_cast<size_t>(i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < mono.size() ? mono[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    double num = 0.0, den = 0.0;
    for (int32_t k = 1; k < kN / 2; ++k) {
        const float hz = static_cast<float>(k) * kSr / static_cast<float>(kN);
        if (hz < lo || hz > hi) continue;
        const double m = std::sqrt(static_cast<double>(re[static_cast<size_t>(k)]) * re[static_cast<size_t>(k)] +
                                   static_cast<double>(im[static_cast<size_t>(k)]) * im[static_cast<size_t>(k)]);
        num += m * hz;
        den += m;
    }
    return den > 0.0 ? static_cast<float>(num / den) : 0.0f;
}

/** How much of [hz] is in there, for asking whether a chord has both notes. */
inline float magnitudeAt(const std::vector<float> &mono, int32_t from, float hz) {
    constexpr int32_t kN = 8192;
    static const dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from) + static_cast<size_t>(i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < mono.size() ? mono[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    const int32_t k = static_cast<int32_t>(hz * static_cast<float>(kN) / kSr + 0.5f);
    float best = 0.0f;
    for (int32_t j = k - 2; j <= k + 2; ++j) {
        if (j < 1 || j >= kN / 2) continue;
        best = std::max(best, std::sqrt(re[static_cast<size_t>(j)] * re[static_cast<size_t>(j)] +
                                        im[static_cast<size_t>(j)] * im[static_cast<size_t>(j)]));
    }
    return best;
}

/**
 * One windowed spectrum, kept so the measures that ask about a *known* note
 * can share a transform and a vocabulary.
 *
 * Everything below it is anchored: the harness always knows which note it
 * asked for, so it never has to guess one. A blind detector has to choose an
 * octave and can choose wrong - it did, on every string picked near its
 * bridge, where the fundamental sits eleven decibels under the fifth
 * harmonic. Anchored, there is no choice to get wrong.
 */
struct Spectrum {
    static constexpr int32_t kBins = 4096; // 8192-point transform
    std::vector<float> mag;
    float binHz = 1.0f;
    double totalSq = 0.0;

    /** The strongest peak within [cents] of [hz], placed between bins, and
     *  how big it is. Returns 0 Hz when there is nothing there. */
    void peakNear(float hz, float cents, float &atHz, float &size) const {
        atHz = 0.0f;
        size = 0.0f;
        if (!(hz > 0.0f)) return;
        const float span = hz * (std::pow(2.0f, cents / 1200.0f) - 1.0f);
        const int32_t lo = std::max(1, static_cast<int32_t>((hz - span) / binHz));
        const int32_t hi = std::min(kBins - 2, static_cast<int32_t>((hz + span) / binHz) + 1);
        int32_t best = -1;
        for (int32_t k = lo; k <= hi; ++k) {
            if (best < 0 || mag[static_cast<size_t>(k)] > mag[static_cast<size_t>(best)]) best = k;
        }
        if (best < 1) return;
        const float a = mag[static_cast<size_t>(best - 1)], b = mag[static_cast<size_t>(best)],
                    c = mag[static_cast<size_t>(best + 1)];
        const float den = a - 2.0f * b + c;
        float shift = den != 0.0f ? 0.5f * (a - c) / den : 0.0f;
        // A parabola through three nearly equal bins is nearly a straight
        // line, and its vertex can be a long way outside them. Unclamped,
        // that turned a +-60 cent search window into readings of +-600 on any
        // patch flat enough to have no clear peak.
        if (shift > 0.5f) shift = 0.5f;
        else if (shift < -0.5f) shift = -0.5f;
        atHz = (static_cast<float>(best) + shift) * binHz;
        size = b;
    }

    /** The energy under [hz], as a fraction of all of it. */
    float fractionBelow(float hz) const {
        if (totalSq <= 0.0) return 0.0f;
        double sum = 0.0;
        const int32_t to = std::min(kBins, static_cast<int32_t>(hz / binHz) + 1);
        for (int32_t k = 1; k < to; ++k) sum += static_cast<double>(mag[static_cast<size_t>(k)]) * mag[static_cast<size_t>(k)];
        return static_cast<float>(sum / totalSq);
    }
};

inline Spectrum spectrumAt(const std::vector<float> &mono, int32_t from) {
    constexpr int32_t kN = Spectrum::kBins * 2;
    static const dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from) + static_cast<size_t>(i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < mono.size() ? mono[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    Spectrum sp;
    sp.binHz = kSr / static_cast<float>(kN);
    sp.mag.assign(static_cast<size_t>(Spectrum::kBins), 0.0f);
    for (int32_t k = 0; k < Spectrum::kBins; ++k) {
        const float m = std::sqrt(re[static_cast<size_t>(k)] * re[static_cast<size_t>(k)] +
                                  im[static_cast<size_t>(k)] * im[static_cast<size_t>(k)]);
        sp.mag[static_cast<size_t>(k)] = m;
        if (k >= 1) sp.totalSq += static_cast<double>(m) * m;
    }
    return sp;
}

/**
 * The fundamental, by the loudest spectral peak with its neighbours used to
 * put it between bins.
 *
 * Not `PitchTrack`, which the engine already has: that is an autocorrelation
 * tracker written for a voice and it looks between 70 and 800 Hz, so it
 * cannot see a lead two octaves above middle C - which is most of what there
 * is to measure here. Parabolic interpolation over three bins gets a 5.9 Hz
 * grid down to well under a cent, which is what makes "is this oscillator an
 * octave out" answerable.
 */
inline float fundamental(const std::vector<float> &mono, int32_t from, float lo = 20.0f, float hi = 5000.0f) {
    constexpr int32_t kN = 8192;
    static const dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from) + static_cast<size_t>(i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < mono.size() ? mono[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    std::vector<float> mag(static_cast<size_t>(kN / 2), 0.0f);
    for (int32_t k = 0; k < kN / 2; ++k) {
        mag[static_cast<size_t>(k)] = std::sqrt(re[static_cast<size_t>(k)] * re[static_cast<size_t>(k)] +
                                                im[static_cast<size_t>(k)] * im[static_cast<size_t>(k)]);
    }
    const int32_t kLo = std::max(1, static_cast<int32_t>(lo * kN / kSr));
    const int32_t kHi = std::min(kN / 2 - 2, static_cast<int32_t>(hi * kN / kSr));
    int32_t best = 0;
    for (int32_t k = kLo; k <= kHi; ++k) {
        if (mag[static_cast<size_t>(k)] > mag[static_cast<size_t>(best)]) best = k;
    }
    if (best < 1) return 0.0f;
    // The loudest partial is not always the first one. Walk down to the
    // lowest peak that is a near-integer division of it and still carries a
    // tenth of its energy: a filtered saw whose second harmonic is loudest
    // is still playing the note underneath it.
    for (int32_t div = 8; div >= 2; --div) {
        const int32_t k = best / div;
        if (k < kLo) continue;
        float around = 0.0f;
        for (int32_t j = k - 1; j <= k + 1; ++j) {
            if (j >= 1 && j < kN / 2) around = std::max(around, mag[static_cast<size_t>(j)]);
        }
        if (around > 0.1f * mag[static_cast<size_t>(best)]) {
            best = k;
            break;
        }
    }
    const float a = mag[static_cast<size_t>(best - 1)];
    const float b = mag[static_cast<size_t>(best)];
    const float c = mag[static_cast<size_t>(best + 1)];
    const float denom = a - 2.0f * b + c;
    const float shift = denom != 0.0f ? 0.5f * (a - c) / denom : 0.0f;
    return (static_cast<float>(best) + shift) * kSr / static_cast<float>(kN);
}

inline float cents(float got, float want) {
    return (got > 0.0f && want > 0.0f) ? 1200.0f * std::log2(got / want) : 0.0f;
}

inline float midiToHz(int note) {
    return 440.0f * std::pow(2.0f, (static_cast<float>(note) - 69.0f) / 12.0f);
}

/** Everything measured about one rendered take. */
struct Measured {
    float peak = 0.0f;      // linear
    float peakDb = -200.0f;
    int64_t overs = 0;      // samples past +/-1.0
    float rmsDb = -200.0f;  // over the sounding part only
    float crestDb = 0.0f;   // peak - rms: transients, or a wall
    float centroidHz = 0.0f;
    float f0Hz = 0.0f;
    // The even harmonics against the odd ones, in decibels. A centroid
    // cannot tell a hollow tone from a full one of the same brightness -
    // a clarinet and a saxophone levelled to the same loudness read within
    // a percent of each other - and hollow against full is the largest
    // single fact about a wind instrument. Negative is hollow.
    float evenOddDb = 0.0f;
    float speaksMs = 0.0f;    // note-on to half the level it settles at
    // How much brighter the attack is than the tone. A chiff, not a click: a
    // flute has one on purpose and so does a plucked string, whose attack
    // really is twenty times the edge of its own tail. See clickRatio for
    // the fault this column was being read as.
    float onsetEdge = 1.0f;
    // --- anchored to the note the harness asked for ------------------------
    //
    // A blind pitch detector has to choose an octave and can choose wrong.
    // These never choose: they look where the note should be.
    float tuneCents = 0.0f;   // the note's own deviation, from its low partials
    bool tuned = false;       // ...and whether there was enough there to say
    float partialRatio = 0.0f;// the loudest partial over the note: 2.0 is an octave up
    // The note's own fundamental against the loudest partial, in decibels.
    // The difference between a string whose fundamental is merely quiet -
    // which is what picking near a bridge does, and is a tone colour - and a
    // saxophone that is sounding its octave and has nothing at the note at
    // all, which is a fault. `partialRatio` alone cannot tell those apart.
    float fundamentalDb = -200.0f;
    float harmonicity = 0.0f; // how much of the energy stands on the note's series
    float lowDb = -200.0f;    // energy under half the note, against all of it
    float ringSeconds = 0.0f; // the fundamental's own fall to -60 dB; 0 if it holds
    // A click is a *discontinuity*, which is what the second difference sees,
    // against the largest the sound makes for itself just afterwards. Scale
    // free and frequency free, so a bright attack does not read as a fault.
    float clickRatio = 0.0f;
    // What the ear would call the level of one note: the loudest four hundred
    // milliseconds of it. Neither peak nor rms levels a bank holding both a
    // pluck and a bowed note; this does.
    float loudnessDb = -200.0f;
    float tailSeconds = 0.0f; // note-off to -60 dB
    bool tailRanOut = false;  // it was still going when the render stopped
    float monoLossDb = 0.0f;  // how much is lost by summing to mono
    float dcDb = -200.0f;
    bool finite = true;
};

/**
 * [stereo] is interleaved. [offAt] is the frame the last note was released,
 * which is where the tail is measured from; pass the end for a phrase that
 * never lets go.
 */
/**
 * [offAt] is the frame the last note was released - or, for an effect, the
 * frame its source fell silent - which is where the tail is measured from.
 * [centroidFrom] is where the brightness is taken; the default is a tenth of
 * a second in, past the attack, which is where a machine's tone lives. An
 * effect wants it in the tail instead, where the sound is all wet and the
 * difference between one preset and the next is actually visible.
 */
inline Measured measure(const std::vector<float> &stereo, int64_t offAt, int noteForF0,
                        int64_t centroidFrom = -1) {
    Measured m;
    const size_t frames = stereo.size() / 2;
    if (frames == 0) return m;

    std::vector<float> mono(frames, 0.0f);
    double sumSq = 0.0, sumL = 0.0, sumR = 0.0, dc = 0.0;
    double monoSq = 0.0;
    for (size_t i = 0; i < frames; ++i) {
        const float l = stereo[i * 2], r = stereo[i * 2 + 1];
        if (!std::isfinite(l) || !std::isfinite(r)) m.finite = false;
        mono[i] = 0.5f * (l + r);
        m.peak = std::max(m.peak, std::max(std::abs(l), std::abs(r)));
        if (std::abs(l) > 1.0f) ++m.overs;
        if (std::abs(r) > 1.0f) ++m.overs;
        sumL += static_cast<double>(l) * l;
        sumR += static_cast<double>(r) * r;
        dc += l + r;
        monoSq += static_cast<double>(mono[i]) * mono[i];
    }
    sumSq = sumL + sumR;
    m.peakDb = dB(m.peak);
    m.dcDb = dB(static_cast<float>(std::abs(dc) / static_cast<double>(frames * 2)));

    // RMS over the sounding part: everything at or above -60 dB of the peak.
    // Measured over the whole buffer instead, a patch with a four-second tail
    // reads quieter than an identical one with a short release, and the
    // column that exists to be flattened would be measuring release time.
    const float gate = m.peak * 0.001f;
    double soundSq = 0.0;
    size_t sounding = 0;
    for (size_t i = 0; i < frames; ++i) {
        if (std::abs(mono[i]) < gate) continue;
        soundSq += static_cast<double>(stereo[i * 2]) * stereo[i * 2] +
                   static_cast<double>(stereo[i * 2 + 1]) * stereo[i * 2 + 1];
        ++sounding;
    }
    if (sounding > 0) m.rmsDb = dB(static_cast<float>(std::sqrt(soundSq / (static_cast<double>(sounding) * 2.0))));
    m.crestDb = m.peakDb - m.rmsDb;

    // Mono-sum loss. This app is full of unison, rotary and chorus, and a
    // patch that cancels itself on a phone speaker is a real fault that one
    // listen on headphones will never find.
    if (sumSq > 0.0) {
        const float stereoRms = static_cast<float>(std::sqrt(sumSq / (static_cast<double>(frames) * 2.0)));
        const float monoRms = static_cast<float>(std::sqrt(monoSq / static_cast<double>(frames)));
        m.monoLossDb = dB(stereoRms) - dB(monoRms);
    }

    // Brightness is averaged over the whole sounding part rather than read
    // from one window, and that is not a refinement - it is the difference
    // between a number and a wrong number. Read at a fixed point, a drum
    // kit's brightness was its kick's, because the kit is played one voice at
    // a time and the first one is the kick; every kit in the bank reported
    // the same figure and they looked identical. Anything that evolves - a
    // pad, a sweep, a slicer - had the same problem more quietly.
    const int32_t start = centroidFrom >= 0
                              ? static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(centroidFrom), frames))
                              : static_cast<int32_t>(std::min<size_t>(frames > 8192 ? 4800 : 0, frames));
    double num = 0.0, den = 0.0;
    const auto span = static_cast<int32_t>(frames) - start;
    const int32_t windows = span > 8192 * 2 ? std::min(6, span / 8192) : 1;
    for (int32_t w = 0; w < windows; ++w) {
        const int32_t at = start + (windows > 1 ? w * (span - 8192) / (windows - 1) : 0);
        // Weighted by how loud that window is, so silence between two hits
        // does not drag the answer towards whatever noise is in it.
        float loud = 0.0f;
        for (int32_t i = at; i < at + 8192 && i < static_cast<int32_t>(frames); ++i) {
            loud = std::max(loud, std::abs(mono[static_cast<size_t>(i)]));
        }
        if (loud < m.peak * 0.02f) continue;
        num += static_cast<double>(loud) * centroid(mono, at);
        den += loud;
    }
    m.centroidHz = den > 0.0 ? static_cast<float>(num / den) : centroid(mono, start);
    m.f0Hz = fundamental(mono, start);
    if (m.f0Hz > 20.0f) {
        double even = 1e-20, odd = 1e-20;
        for (int h = 1; h <= 12; ++h) {
            const float f = m.f0Hz * static_cast<float>(h);
            if (f > kSr * 0.45f) break;
            const double a = magnitudeAt(mono, start, f);
            (h % 2 == 0 ? even : odd) += a * a;
        }
        m.evenOddDb = static_cast<float>(10.0 * std::log10(even / odd));
    }

    // How long before you hear it.
    //
    // Measured against the level the note *settles* at, not against its peak,
    // because a sharp onset transient is the peak and would report every
    // patch as instant. A waveguide is the reason this is worth a column: a
    // big tube takes hundreds of milliseconds to build a standing wave, so a
    // patch can be perfectly good on a held note and produce almost nothing
    // in a phrase - which is exactly what Brazen's low brass was doing, and
    // what no other number here could see.
    {
        const size_t off = std::min(static_cast<size_t>(std::max<int64_t>(0, offAt)), frames);
        const size_t step10 = static_cast<size_t>(kSr) / 100;
        std::vector<float> env;
        for (size_t i = 0; i + step10 <= off; i += step10) {
            float loud = 0.0f;
            for (size_t j = i; j < i + step10; ++j) loud = std::max(loud, std::abs(mono[j]));
            env.push_back(loud);
        }
        if (env.size() > 4) {
            // The settled level: the mean of the last third before the release.
            const size_t from = env.size() * 2 / 3;
            double sum = 0.0;
            for (size_t i = from; i < env.size(); ++i) sum += env[i];
            const auto settled = static_cast<float>(sum / static_cast<double>(env.size() - from));
            if (settled > 0.0f) {
                for (size_t i = 0; i < env.size(); ++i) {
                    if (env[i] >= 0.5f * settled) {
                        m.speaksMs = static_cast<float>(i) * 10.0f;
                        break;
                    }
                }
            }
        }
    }

    // How much brighter the attack is than the note it turns into.
    //
    // A click is not an overshoot - it does not have to be louder than the
    // tone, and Brazen's low brass clicked while measuring well under it. It
    // is a burst of high frequency the body of the sound never has, so what
    // separates the two is a *ratio of edge to level*, onset against settled.
    // Around 1 is a note; above 2 or 3 there is a click on the front of it,
    // and the darker the instrument the more it sticks out.
    //
    // The first difference is a +6 dB/octave tilt, which is enough of a
    // high-pass to ask this question and costs nothing.
    {
        auto edge = [&](size_t from, size_t to) {
            if (to <= from + 1 || to > frames) return 0.0f;
            double d2 = 0.0, s2 = 0.0;
            for (size_t i = from + 1; i < to; ++i) {
                const double d = static_cast<double>(mono[i]) - mono[i - 1];
                d2 += d * d;
                s2 += static_cast<double>(mono[i]) * mono[i];
            }
            const auto n = static_cast<double>(to - from - 1);
            const double lvl = std::sqrt(s2 / n);
            return lvl > 1e-9 ? static_cast<float>(std::sqrt(d2 / n) / lvl) : 0.0f;
        };
        const auto twenty = static_cast<size_t>(kSr * 0.02f);
        const size_t off = std::min(static_cast<size_t>(std::max<int64_t>(0, offAt)), frames);
        // Only where there is a tone to compare the attack with. A plucked or
        // struck sound has decayed to nothing by the end of a two-second note,
        // and dividing by that reports every short patch as a click - which
        // is not a fault, it is what percussive means.
        float late = 0.0f;
        for (size_t i = off - off / 4; i < off && i < frames; ++i) late = std::max(late, std::abs(mono[i]));
        if (off > twenty * 4 && late > m.peak * 0.05f) {
            const float onset = edge(0, twenty);
            const float tone = edge(off - off / 4, off);
            if (tone > 1e-9f) m.onsetEdge = onset / tone;
        }
    }

    // The tail: from the release to 60 dB below the peak, in 10 ms steps, and
    // it is the LAST step above the floor that counts rather than the first
    // below it - a release that dips and comes back has not ended.
    const size_t off = std::min(static_cast<size_t>(std::max<int64_t>(0, offAt)), frames);
    const float floorAt = m.peak * 0.001f;
    const size_t step = static_cast<size_t>(kSr) / 100;
    size_t last = off;
    for (size_t i = off; i + step <= frames; i += step) {
        float loudest = 0.0f;
        for (size_t j = i; j < i + step; ++j) loudest = std::max(loudest, std::abs(mono[j]));
        if (loudest >= floorAt) last = i + step;
    }
    m.tailSeconds = static_cast<float>(last - off) / kSr;
    m.tailRanOut = last + step > frames;
    // --- the loudest four hundred milliseconds, weighted for the ear --------
    //
    // Unweighted, this counted sixty-five hertz at its full size, and sixty-
    // five hertz is not heard at its full size by anybody or reproduced at it
    // by most speakers. Six FM basses that measured within eight tenths of a
    // decibel of each other spanned four once the weighting was on, and the
    // one Dan reported as "too quiet to really hear" was the quietest of the
    // six - which the flat number had no way to say.
    //
    // The two stages are the broadcast loudness ones: a high shelf that lifts
    // everything above about a kilohertz, standing in for the head and the
    // outer ear, and a high-pass near forty hertz for what is felt rather
    // than heard. They are the standard's coefficients at this sample rate,
    // which is the whole reason to use them - a weighting invented here would
    // be a preference, and this is a measurement.
    {
        static_assert(static_cast<int>(kSr) == 48000, "the weighting coefficients are 48 kHz ones");
        const double b1[3] = {1.53512485958697, -2.69169618940638, 1.19839281085285};
        const double a1[3] = {1.0, -1.69065929318241, 0.73248077421585};
        const double b2[3] = {1.0, -2.0, 1.0};
        const double a2[3] = {1.0, -1.99004745483398, 0.99007225036621};
        std::vector<float> k(frames, 0.0f);
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (size_t i = 0; i < frames; ++i) {
            const double x = mono[i];
            const double y = b1[0] * x + b1[1] * x1 + b1[2] * x2 - a1[1] * y1 - a1[2] * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            k[i] = static_cast<float>(y);
        }
        x1 = x2 = y1 = y2 = 0;
        for (size_t i = 0; i < frames; ++i) {
            const double x = k[i];
            const double y = b2[0] * x + b2[1] * x1 + b2[2] * x2 - a2[1] * y1 - a2[2] * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            k[i] = static_cast<float>(y);
        }
        const auto win = static_cast<size_t>(kSr * 0.4f);
        const auto hop = static_cast<size_t>(kSr * 0.05f);
        double best = 0.0;
        if (frames >= win) {
            for (size_t at = 0; at + win <= frames; at += hop) {
                double sq = 0.0;
                for (size_t i = at; i < at + win; ++i) sq += static_cast<double>(k[i]) * k[i];
                best = std::max(best, sq / static_cast<double>(win));
            }
        } else {
            double sq = 0.0;
            for (size_t i = 0; i < frames; ++i) sq += static_cast<double>(k[i]) * k[i];
            best = frames > 0 ? sq / static_cast<double>(frames) : 0.0;
        }
        m.loudnessDb = dB(static_cast<float>(std::sqrt(best)));
    }

    // --- a click, which is a corner in the waveform and not a bright attack --
    {
        const auto twoMs = static_cast<size_t>(kSr * 0.002f);
        const auto fifty = static_cast<size_t>(kSr * 0.05f);
        auto worstBend = [&](size_t from, size_t to) {
            float worst = 0.0f;
            for (size_t i = std::max<size_t>(from, 2); i < to && i < frames; ++i) {
                worst = std::max(worst, std::abs(mono[i] - 2.0f * mono[i - 1] + mono[i - 2]));
            }
            return worst;
        };
        // Where the note actually starts, rather than where the buffer does.
        // A render has a block or two of lead-in and some machines take a
        // moment; measured from frame zero this read nothing at all for every
        // patch in a bank, which is the shape a broken measure has.
        size_t onset = 0;
        while (onset < frames && std::abs(mono[onset]) < m.peak * 0.01f) ++onset;
        if (onset < frames) {
            const float at = worstBend(onset, onset + twoMs);
            const float after = worstBend(onset + twoMs, onset + twoMs + fifty);
            m.clickRatio = after > 1e-9f ? at / after : 0.0f;
        }
    }

    // --- everything that needs to know which note was asked for -------------
    if (noteForF0 > 0) {
        const float want = midiToHz(noteForF0);
        const Spectrum sp = spectrumAt(mono, start);
        // Tuning, from the low partials. If the note is flat by x cents then
        // so is every partial of it; a stiff string's partials run sharper as
        // they go up, so the low ones are weighted most and the top ones are
        // there only to speak for a fundamental too quiet to be heard from.
        double num = 0.0, den = 0.0;
        for (int h = 1; h <= 6; ++h) {
            const float target = want * static_cast<float>(h);
            if (target > kSr * 0.45f) break;
            // Below about eight bins the transform cannot resolve sixty cents
            // at all - at 65 Hz one bin *is* eighty cents - so a reading there
            // is the grid speaking, not the note. The upper partials of a flat
            // note are flat by the same amount and are resolved far better, so
            // the answer comes from them instead.
            if (target < 8.0f * sp.binHz) continue;
            float atHz = 0.0f, size = 0.0f;
            sp.peakNear(target, 60.0f, atHz, size);
            if (atHz <= 0.0f) continue;
            const double weight = static_cast<double>(size) / std::sqrt(static_cast<double>(h));
            num += weight * 1200.0 * std::log2(static_cast<double>(atHz) / target);
            den += weight;
        }
        if (den > 0.0) {
            m.tuneCents = static_cast<float>(num / den);
            m.tuned = true;
        }
        // Which partial is actually the loudest. This is the column that says
        // a saxophone is overblowing: "2.0" rather than "+1200 cents".
        int32_t loudest = 1;
        const int32_t kLo = std::max(1, static_cast<int32_t>(20.0f / sp.binHz));
        const int32_t kHi = std::min(Spectrum::kBins - 2, static_cast<int32_t>(6000.0f / sp.binHz));
        for (int32_t k = kLo; k <= kHi; ++k) {
            if (sp.mag[static_cast<size_t>(k)] > sp.mag[static_cast<size_t>(loudest)]) loudest = k;
        }
        m.partialRatio = static_cast<float>(loudest) * sp.binHz / want;
        {
            float atHz = 0.0f, size = 0.0f;
            sp.peakNear(want, 60.0f, atHz, size);
            const float loudMag = sp.mag[static_cast<size_t>(loudest)];
            m.fundamentalDb = loudMag > 1e-12f ? dB(size / loudMag) : -200.0f;
        }
        // How much of the sound stands on the note's own harmonic series. One
        // number that tells a note on the wrong partial (harmonic, but not
        // this note's) from a note that is not a note at all.
        double onSeries = 0.0;
        for (int h = 1; h <= 20; ++h) {
            const float target = want * static_cast<float>(h);
            if (target > kSr * 0.45f) break;
            float atHz = 0.0f, size = 0.0f;
            sp.peakNear(target, 45.0f, atHz, size);
            onSeries += static_cast<double>(size) * size;
        }
        m.harmonicity = sp.totalSq > 0.0 ? static_cast<float>(std::min(1.0, onSeries / sp.totalSq)) : 0.0f;
        // Anything under half the note has no business being there, and one
        // number for it finds a DC pedestal and a sub-audio wander alike.
        m.lowDb = dB(std::sqrt(sp.fractionBelow(want * 0.5f)));

        // --- how long the note's own fundamental takes to go --------------
        //
        // Beat the note down to nothing and watch what is left: what remains
        // is that partial's own envelope and nothing else's. Fitted over the
        // first twenty decibels it falls, while the note is still held.
        {
            const double w = 2.0 * M_PI * static_cast<double>(want) / kSr;
            double cr = 1.0, ci = 0.0;
            const double rot = std::cos(w), rots = -std::sin(w);
            const auto smooth = static_cast<size_t>(kSr * 0.02f);
            std::vector<float> env;
            env.reserve(frames / smooth + 2);
            double sr2 = 0.0, si2 = 0.0;
            size_t n = 0;
            const size_t until = std::min(frames, static_cast<size_t>(std::max<int64_t>(0, offAt)));
            for (size_t i = 0; i < until; ++i) {
                sr2 += static_cast<double>(mono[i]) * cr;
                si2 += static_cast<double>(mono[i]) * ci;
                const double nr = cr * rot - ci * rots, ni = cr * rots + ci * rot;
                cr = nr; ci = ni;
                if (++n == smooth) {
                    env.push_back(static_cast<float>(std::sqrt(sr2 * sr2 + si2 * si2) / smooth));
                    sr2 = si2 = 0.0;
                    n = 0;
                }
            }
            if (env.size() > 6) {
                size_t pk = 0;
                for (size_t i = 0; i < env.size(); ++i) if (env[i] > env[pk]) pk = i;
                const float top = dB(env[pk]);
                size_t end = pk;
                while (end < env.size() && dB(env[end]) > top - 20.0f) ++end;
                if (end - pk >= 4) {
                    // Least squares on the decibels against time.
                    double sx = 0, sy = 0, sxx = 0, sxy = 0;
                    const double dt = static_cast<double>(smooth) / kSr;
                    for (size_t i = pk; i < end; ++i) {
                        const double x = static_cast<double>(i - pk) * dt, y = dB(env[i]);
                        sx += x; sy += y; sxx += x * x; sxy += x * y;
                    }
                    const auto cnt = static_cast<double>(end - pk);
                    const double denom = cnt * sxx - sx * sx;
                    const double slope = denom != 0.0 ? (cnt * sxy - sx * sy) / denom : 0.0;
                    if (slope < -1.0) m.ringSeconds = static_cast<float>(-60.0 / slope);
                }
            }
        }
    }
    return m;
}

// --- Pitch against time ------------------------------------------------------

/** One reading of the tracker: when, what pitch, how loud, and whether the
 *  three periods it was taken from agreed with each other. */
struct TrackPoint {
    float ms = 0.0f;
    float hz = 0.0f;   // 0: nothing periodic there yet
    float db = -200.0f;
    bool sure = false; // the periods either side agreed within a tenth
};

/**
 * A two-pole low-pass run forward and then backward, so it has no phase at
 * all - the crossings of what comes out are where the crossings of the
 * fundamental are, not where a filter's lag put them. Three times over,
 * which is twelve poles: fifty decibels down an octave up, so a second
 * harmonic louder than the note - which is what a clarinet radiates - does
 * not put crossings of its own between the real ones.
 */
inline void isolateFundamental(std::vector<float> &x, float hz) {
    const double w0 = 2.0 * M_PI * static_cast<double>(hz) / kSr;
    const double alpha = std::sin(w0) / (2.0 * 0.70710678);
    const double c = std::cos(w0);
    const double a0 = 1.0 + alpha;
    const double b0 = (1.0 - c) * 0.5 / a0, b1 = (1.0 - c) / a0, b2 = b0;
    const double a1 = -2.0 * c / a0, a2 = (1.0 - alpha) / a0;
    auto run = [&](bool forward) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        const auto n = static_cast<int64_t>(x.size());
        for (int64_t k = 0; k < n; ++k) {
            const auto i = static_cast<size_t>(forward ? k : n - 1 - k);
            const double in = x[i];
            const double out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in;
            y2 = y1; y1 = out;
            x[i] = static_cast<float>(out);
        }
    };
    for (int pass = 0; pass < 3; ++pass) { run(true); run(false); }
}

/**
 * Pitch and level at each of [atMs], from the start of [mono].
 *
 * Period by period rather than by any window: the fundamental is isolated
 * (see above), every positive-going crossing is placed between its two
 * samples by a straight line, and the pitch at a moment is the median of
 * the period it is in and the two beside it. A window of any length
 * averages over the very thing an onset measurement is looking at - the
 * scratch script this replaces autocorrelated twenty-five milliseconds,
 * which at a bassoon's pitch is under three cycles, and it disagreed with
 * the settled measurement by forty cents. This one is checked against a
 * tone of known pitch (trackSelfTest) before it is believed about anything.
 *
 * [hz] is the pitch the note settles at, from fundamental(): the isolation
 * is cut a third above it. Level is RMS over one settled period of the
 * *unfiltered* signal, so it is the note's level and not the fundamental's.
 */
inline std::vector<TrackPoint> pitchTrack(const std::vector<float> &mono, float hz,
                                          const std::vector<float> &atMs) {
    std::vector<TrackPoint> out;
    if (hz <= 0.0f || mono.empty()) return out;
    float lastMs = 0.0f;
    for (float ms : atMs) lastMs = std::max(lastMs, ms);
    const size_t span = std::min(mono.size(), static_cast<size_t>(kSr * (lastMs / 1000.0f + 0.1f)));
    const auto period = static_cast<size_t>(kSr / hz + 0.5f);

    // The level, as RMS over one period centred on each sample, from a
    // running sum. It is the dB column, and it is also divided out of the
    // signal before the filter sees it: a filter whose corner is near the
    // note reads a *growing* note flat - the sidebands the growth puts
    // above the note are cut and the ones below are not, and the crossings
    // drift late. Measured on a thirty-millisecond ramp, eleven cents. The
    // shape of the envelope is the last thing an onset measurement can
    // afford to have in its pitch, so it is taken out first.
    std::vector<double> sum2(span + 1, 0.0);
    for (size_t i = 0; i < span; ++i) sum2[i + 1] = sum2[i] + static_cast<double>(mono[i]) * mono[i];
    auto levelAt = [&](double at) {
        const auto lo = static_cast<size_t>(std::max(0.0, at - static_cast<double>(period) / 2.0));
        const size_t hi = std::min(span, lo + period);
        return hi > lo ? std::sqrt((sum2[hi] - sum2[lo]) / static_cast<double>(hi - lo)) : 0.0;
    };
    float peak = 0.0f;
    for (size_t i = 0; i < span; ++i) peak = std::max(peak, std::abs(mono[i]));
    const double floor = static_cast<double>(peak) * 1e-4;
    std::vector<float> x(span, 0.0f);
    for (size_t i = 0; i < span; ++i) {
        const double lvl = levelAt(static_cast<double>(i));
        x[i] = lvl > floor ? static_cast<float>(mono[i] / lvl) : 0.0f;
    }
    isolateFundamental(x, hz * 1.3f);

    std::vector<double> cross; // sample positions, fractional
    for (size_t i = 0; i + 1 < x.size(); ++i) {
        if (x[i] <= 0.0f && x[i + 1] > 0.0f) {
            const double d = static_cast<double>(x[i + 1]) - x[i];
            cross.push_back(static_cast<double>(i) + (d > 0.0 ? -static_cast<double>(x[i]) / d : 0.0));
        }
    }
    // Each reading is three cycles: the time between crossings three apart,
    // over three. One cycle on its own is placed by two crossings and the
    // hiss on a real note moves each by a fraction of a sample, which at
    // two hundred hertz is three cents of jitter; three cycles is a third
    // of that, and for a note on its way somewhere the mean of three is
    // exactly the pitch at their middle. So the earliest a reading exists
    // is three cycles in, and the front of a note reads '-' before that.
    struct Span { double centre, period; bool sure; };
    std::vector<Span> spans;
    for (size_t a = 0; a + 3 < cross.size(); ++a) {
        Span s;
        s.centre = 0.5 * (cross[a] + cross[a + 3]);
        s.period = (cross[a + 3] - cross[a]) / 3.0;
        double lo = s.period, hi = s.period;
        for (size_t j = a; j < a + 3; ++j) {
            lo = std::min(lo, cross[j + 1] - cross[j]);
            hi = std::max(hi, cross[j + 1] - cross[j]);
        }
        s.sure = hi - lo < 0.1 * s.period;
        spans.push_back(s);
    }
    for (float ms : atMs) {
        TrackPoint p;
        p.ms = ms;
        const double at = static_cast<double>(ms) * kSr / 1000.0;
        p.db = dB(static_cast<float>(levelAt(at)));
        // Read between the two spans whose centres bracket the moment, so a
        // glide reads where it is and not where the nearest whole reading
        // happened to be - at a slow glide those differ by cents.
        if (!spans.empty() && p.db > -90.0f && at >= spans.front().centre &&
            at <= spans.back().centre + spans.back().period) {
            size_t k = 0;
            while (k + 1 < spans.size() && spans[k + 1].centre <= at) ++k;
            double per = spans[k].period;
            bool sure = spans[k].sure;
            if (k + 1 < spans.size()) {
                const double t = (at - spans[k].centre) / (spans[k + 1].centre - spans[k].centre);
                per += (spans[k + 1].period - per) * std::min(1.0, std::max(0.0, t));
                sure = sure && spans[k + 1].sure;
            }
            if (per > 0.0) {
                p.hz = static_cast<float>(kSr / per);
                p.sure = sure;
            }
        }
        out.push_back(p);
    }
    return out;
}

/**
 * The tracker against tones whose pitch is known, in cents of worst error.
 *
 * Two tones. One steps from 200 to 210 Hz at a tenth of a second, with a
 * second harmonic six decibels *louder* than the fundamental - the reading
 * has to be right within two periods of the step. The other climbs from 50
 * to 55 Hz over a second, which at the bottom of the bassoon is where the
 * old measurement was forty cents out. Both carry a hiss at -30 dB and a
 * thirty-millisecond fade in, because a real note has those.
 *
 * A tool that cannot pass this says nothing about an instrument. Returns
 * the worst error past the first two periods, in cents; the caller decides
 * what is close enough.
 */
inline float trackSelfTest(bool verbose = false) {
    float worst = 0.0f;
    uint32_t rng = 0x9e3779b9u;
    auto noise = [&]() {
        rng = rng * 1664525u + 1013904223u;
        return static_cast<float>(rng >> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f;
    };
    struct Tone { const char *name; float f0, f1; float switchAt, glideOver; float second; };
    const Tone tones[] = {
        {"200 -> 210 Hz step, second harmonic +6 dB", 200.0f, 210.0f, 0.1f, 0.0f, 2.0f},
        {"50 -> 55 Hz over a second", 50.0f, 55.0f, 0.0f, 1.0f, 0.5f},
    };
    for (const Tone &t : tones) {
        const auto frames = static_cast<size_t>(kSr * 1.3f);
        std::vector<float> mono(frames, 0.0f);
        std::vector<float> want(frames, 0.0f);
        double phase = 0.0;
        for (size_t i = 0; i < frames; ++i) {
            const float s = static_cast<float>(i) / kSr;
            float hz;
            if (t.glideOver > 0.0f) hz = t.f0 + (t.f1 - t.f0) * std::min(1.0f, s / t.glideOver);
            else hz = s < t.switchAt ? t.f0 : t.f1;
            want[i] = hz;
            phase += hz / kSr;
            const float fade = std::min(1.0f, s / 0.03f);
            mono[i] = fade * (0.5f * static_cast<float>(std::sin(2.0 * M_PI * phase)) +
                              0.5f * t.second * static_cast<float>(std::sin(4.0 * M_PI * phase + 0.7)) +
                              0.016f * noise());
        }
        std::vector<float> at;
        for (float ms = 5.0f; ms <= 1200.0f; ms += 5.0f) at.push_back(ms);
        const float settled = t.f1;
        const std::vector<TrackPoint> got = pitchTrack(mono, settled, at);
        if (verbose) std::printf("  %s\n", t.name);
        for (const TrackPoint &p : got) {
            const auto i = static_cast<size_t>(p.ms * kSr / 1000.0f);
            // Judged once the fade is over and three cycles are in, and not
            // across the step: a reading that straddles it is a mean of both.
            const float threePeriods = 3000.0f / t.f0;
            const bool judged = p.ms > 30.0f + threePeriods &&
                                (t.glideOver > 0.0f || std::fabs(p.ms - t.switchAt * 1000.0f) > threePeriods);
            const float err = p.hz > 0.0f ? cents(p.hz, want[i]) : 1200.0f;
            if (judged) worst = std::max(worst, std::fabs(err));
            if (verbose && (static_cast<int>(p.ms) % 25 == 0 || (judged && std::fabs(err) > 2.0f))) {
                std::printf("    %6.0f ms  want %7.2f  got %7.2f  %+6.1f cents%s%s\n", static_cast<double>(p.ms),
                            static_cast<double>(want[i]), static_cast<double>(p.hz), static_cast<double>(err),
                            p.sure ? "" : "  ?", judged ? "" : "  (not judged)");
            }
        }
    }
    return worst;
}

} // namespace acidulous::audition
