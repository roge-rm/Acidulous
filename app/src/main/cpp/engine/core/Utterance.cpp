#include "Utterance.h"
#include <algorithm>
#include <cmath>

namespace acidulous::audio {

namespace {

/** Six to one: 48 kHz down to 8, which is where the pitch is looked for. */
constexpr int32_t kDecim = 6;

} // namespace

void PitchTrack::find(const std::vector<float> &mono, int32_t frames, float sampleRate) {
    hz.clear();
    clarity.clear();
    hopFrames = sampleRate * kHopMs * 0.001f;
    if (frames <= 0) return;

    // --- rumble, first ------------------------------------------------------
    //
    // Anything below the lowest pitch this can report is, by definition, not
    // a pitch. It is rumble, and in a forty millisecond window it is not even
    // a low note - it is a drift across the window, which correlates with
    // itself best at the *shortest* lag and less and less as the lag grows.
    // So a take with rumble under it produces a score curve that falls
    // monotonically from the minimum lag, with no peak at the true period
    // anywhere, and the tracker pegs at its own ceiling for every frame.
    //
    // Which is exactly what a real recording did. Dan's voice take - a phone
    // in a room, which is what this machine will always be fed - is dominated
    // below eighty hertz for much of its length, and every frame of it came
    // back as 889 Hz and voiced. Synthetic material has no rumble by
    // construction, so the analyser had never met the case: Molt worked
    // perfectly on a signal nobody will ever sing.
    //
    // Four poles at kMinHz, not one or two. Measured on that take: one pole
    // and two poles both leave it pegged at 800 Hz, four bring it to 116 Hz,
    // against a median fundamental of about 125 measured by other means. The
    // synthetic take reads 125 Hz throughout and is unmoved by any of it,
    // which is the point - this takes nothing away from material that was
    // already clean.
    std::vector<float> clean(mono.begin(), mono.begin() + frames);
    {
        const float a = std::exp(-2.0f * 3.14159265f * kMinHz / sampleRate);
        for (int pass = 0; pass < 4; ++pass) {
            float px = 0.0f, py = 0.0f;
            for (float &v : clean) {
                py = a * (py + v - px);
                px = v;
                v = py;
            }
        }
    }

    // --- decimate -----------------------------------------------------------
    const float lowRate = sampleRate / static_cast<float>(kDecim);
    const int32_t lowFrames = frames / kDecim;
    std::vector<float> low(static_cast<size_t>(std::max(1, lowFrames)), 0.0f);
    for (int32_t i = 0; i < lowFrames; ++i) {
        float sum = 0.0f;
        for (int32_t k = 0; k < kDecim; ++k) sum += clean[static_cast<size_t>(i * kDecim + k)];
        low[static_cast<size_t>(i)] = sum / static_cast<float>(kDecim);
    }

    const int32_t minLag = static_cast<int32_t>(lowRate / kMaxHz);
    const int32_t maxLag = static_cast<int32_t>(lowRate / kMinHz) + 1;
    const int32_t window = static_cast<int32_t>(lowRate * kWindowMs * 0.001f);
    const int32_t hop = std::max(1, static_cast<int32_t>(lowRate * kHopMs * 0.001f));
    const int32_t hops = frames > 0 ? (frames + static_cast<int32_t>(hopFrames) - 1) /
                                          static_cast<int32_t>(hopFrames)
                                    : 0;
    hz.assign(static_cast<size_t>(std::max(0, hops)), 0.0f);
    clarity.assign(static_cast<size_t>(std::max(0, hops)), 0.0f);
    std::vector<float> scores;

    for (int32_t h = 0; h < hops; ++h) {
        const int32_t at = h * hop;
        // A window that runs off the end is simply short; the normalisation
        // below divides by what was actually summed, so a half window still
        // gives an honest correlation rather than a quiet one.
        const int32_t len = std::min(window, lowFrames - at);
        if (len <= maxLag + 2) break;

        double power = 0.0;
        for (int32_t i = 0; i < len; ++i) {
            const double s = low[static_cast<size_t>(at + i)];
            power += s * s;
        }
        if (power < 1e-7) continue; // silence has no pitch

        scores.assign(static_cast<size_t>(maxLag - minLag + 1), 0.0f);
        float bestScore = 0.0f;
        for (int32_t lag = minLag; lag <= maxLag; ++lag) {
            double corr = 0.0, energy = 0.0;
            const int32_t n = len - lag;
            for (int32_t i = 0; i < n; ++i) {
                const double a = low[static_cast<size_t>(at + i)];
                const double b = low[static_cast<size_t>(at + i + lag)];
                corr += a * b;
                energy += b * b;
            }
            if (energy < 1e-9) continue;
            // Normalised against both halves, so a lag that merely points at
            // a louder stretch of audio does not win on volume.
            const float score = static_cast<float>(corr / std::sqrt(power * energy));
            scores[static_cast<size_t>(lag - minLag)] = score;
            bestScore = std::max(bestScore, score);
        }
        if (bestScore < kVoiced) continue;

        int32_t bestLag = 0;
        for (int32_t lag = minLag; lag <= maxLag; ++lag) {
            if (scores[static_cast<size_t>(lag - minLag)] >= bestScore) {
                bestLag = lag;
                break;
            }
        }
        if (bestLag <= 0) continue;

        // Halve while halving is just as good, and no further.
        //
        // A perfectly periodic voice correlates with itself two periods away
        // exactly as well as one, so which of the two wins is down to
        // rounding - and the answer comes out an octave low whenever the
        // second harmonic is the stronger, which for a voice with its
        // formants moved up is most of the time.
        //
        // Only exact sub-multiples of the winner are considered, and only at
        // ninety-five per cent of its score. Scanning every lag for the first
        // that is merely close instead lands on the first formant: a vowel at
        // 180 Hz rings at 700, which is periodic enough over a few cycles to
        // pass a looser test and is four times wrong.
        for (int32_t k = 0; k < 3; ++k) {
            const int32_t half = bestLag / 2;
            if (half - 1 < minLag) break;
            // Either side of the halving, because it rounds. A lag of 61 at
            // eight kHz is 131 Hz and its half is 30.5, so the integer 30 is
            // a fifth of a semitone out - which at this resolution costs
            // enough correlation to fail the test and leave the octave error
            // standing. The neighbours cost two more multiplications.
            float bestHalf = 0.0f;
            int32_t halfLag = half;
            for (int32_t l = half - 1; l <= half + 1 && l <= maxLag; ++l) {
                const float sc = scores[static_cast<size_t>(l - minLag)];
                if (sc > bestHalf) {
                    bestHalf = sc;
                    halfLag = l;
                }
            }
            if (bestHalf < bestScore * 0.95f) break;
            bestLag = halfLag;
        }

        // Parabolic interpolation over the three correlations around the peak
        // would need them kept; the cheaper refinement is to search the
        // original rate in a narrow band, which also undoes the decimation's
        // own quantisation - one low-rate frame is six real ones, and at 200
        // Hz that is a whole semitone.
        const float coarse = lowRate / static_cast<float>(bestLag);
        const int32_t fineCentre = static_cast<int32_t>(sampleRate / coarse);
        const int32_t fineFrom = std::max(2, fineCentre - kDecim);
        const int32_t fineTo = fineCentre + kDecim;
        const int32_t fineAt = at * kDecim;
        const int32_t fineLen = std::min(static_cast<int32_t>(sampleRate * kWindowMs * 0.001f),
                                         frames - fineAt);
        float fineBest = 0.0f;
        int32_t fineLag = fineCentre;
        if (fineLen > fineTo + 2) {
            for (int32_t lag = fineFrom; lag <= fineTo; ++lag) {
                double corr = 0.0, ea = 0.0, eb = 0.0;
                const int32_t n = fineLen - lag;
                for (int32_t i = 0; i < n; ++i) {
                    const double a = mono[static_cast<size_t>(fineAt + i)];
                    const double b = mono[static_cast<size_t>(fineAt + i + lag)];
                    corr += a * b;
                    ea += a * a;
                    eb += b * b;
                }
                if (ea < 1e-9 || eb < 1e-9) continue;
                const float score = static_cast<float>(corr / std::sqrt(ea * eb));
                if (score > fineBest) {
                    fineBest = score;
                    fineLag = lag;
                }
            }
        }
        hz[static_cast<size_t>(h)] = sampleRate / static_cast<float>(fineLag);
        clarity[static_cast<size_t>(h)] = std::max(bestScore, fineBest);
    }
}

float PitchTrack::periodAt(float pos, float sampleRate) const {
    if (hz.empty() || hopFrames <= 0.0f) return 0.0f;
    const float h = pos / hopFrames;
    const int32_t i = static_cast<int32_t>(h);
    if (i < 0) return hz[0] > 0.0f ? sampleRate / hz[0] : 0.0f;
    if (i >= static_cast<int32_t>(hz.size()) - 1) {
        const float last = hz.back();
        return last > 0.0f ? sampleRate / last : 0.0f;
    }
    const float a = hz[static_cast<size_t>(i)];
    const float b = hz[static_cast<size_t>(i + 1)];
    // An unvoiced neighbour is not averaged in - it would drag the period
    // toward nothing. Whichever end has a pitch speaks for both.
    if (a <= 0.0f && b <= 0.0f) return 0.0f;
    if (a <= 0.0f) return sampleRate / b;
    if (b <= 0.0f) return sampleRate / a;
    const float t = h - static_cast<float>(i);
    return sampleRate / (a + (b - a) * t);
}

void Utterance::analyse(float sampleRate) {
    epochs.clear();
    rootHz = 0.0f;
    frames = static_cast<int32_t>(mono.size());
    if (frames <= 1) return;

    PitchTrack track;
    track.find(mono, frames, sampleRate);

    // The median rather than the mean: a take ends on a sigh and starts on a
    // breath, and both are found as pitches somewhere absurd. The middle of
    // the sorted list does not care, and an average would be dragged.
    {
        std::vector<float> voiced;
        voiced.reserve(track.hz.size());
        for (float f : track.hz) {
            if (f > 0.0f) voiced.push_back(f);
        }
        if (!voiced.empty()) {
            std::nth_element(voiced.begin(), voiced.begin() + static_cast<long>(voiced.size() / 2),
                             voiced.end());
            rootHz = voiced[voiced.size() / 2];
        }
    }

    // Unvoiced marks go down every five milliseconds. Short enough that a
    // consonant is carried by several of them and its noise is not repeated
    // audibly, long enough that a second of hiss is two hundred grains and
    // not two thousand.
    const float unvoicedPeriod = sampleRate * 0.005f;

    float pos = 0.0f;
    while (pos < static_cast<float>(frames)) {
        const float period = track.periodAt(pos, sampleRate);
        Epoch e;
        e.voiced = period > 1.0f;
        e.period = e.voiced ? period : unvoicedPeriod;
        e.at = static_cast<int32_t>(pos);

        // A voiced mark is pulled onto the nearest peak of the waveform
        // within a quarter period. Overlap-add wants every grain cut at the
        // same point in the cycle; cut them at arbitrary phases instead and
        // the sum of two of them cancels as often as it adds, which is heard
        // as a hollow, phasey voice.
        if (e.voiced) {
            const int32_t reach = static_cast<int32_t>(period * 0.25f);
            const int32_t from = std::max(0, e.at - reach);
            const int32_t to = std::min(frames - 1, e.at + reach);
            int32_t peak = e.at;
            float best = -1.0f;
            for (int32_t i = from; i <= to; ++i) {
                const float v = std::abs(mono[static_cast<size_t>(i)]);
                if (v > best) {
                    best = v;
                    peak = i;
                }
            }
            e.at = peak;
        }

        // Monotonic and never on the spot: a repeated position would make a
        // grain of zero length and the reader would sit on it forever.
        if (!epochs.empty() && e.at <= epochs.back().at) e.at = epochs.back().at + 1;
        if (e.at >= frames) break;
        epochs.push_back(e);
        pos += e.period;
    }
}

int32_t Utterance::epochAt(float pos) const {
    if (epochs.empty()) return -1;
    const int32_t p = static_cast<int32_t>(pos);
    int32_t lo = 0, hi = static_cast<int32_t>(epochs.size()) - 1;
    if (p <= epochs[0].at) return 0;
    if (p >= epochs[static_cast<size_t>(hi)].at) return hi;
    while (lo < hi) {
        const int32_t mid = (lo + hi + 1) / 2;
        if (epochs[static_cast<size_t>(mid)].at <= p) lo = mid;
        else hi = mid - 1;
    }
    return lo;
}

} // namespace acidulous::audio
