#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
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

    const int32_t at = centroidFrom >= 0
                           ? static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(centroidFrom), frames))
                           : static_cast<int32_t>(std::min<size_t>(frames > 8192 ? 4800 : 0, frames));
    m.centroidHz = centroid(mono, at);
    m.f0Hz = fundamental(mono, at);

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
    (void)noteForF0;
    return m;
}

} // namespace acidulous::audition
