#include "TakeScore.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include <engine/core/Tuner.h>
#include <engine/dsp/Biquad.h>

namespace acidulous::audio::takescore {
namespace {

constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

/** Loudness is measured in blocks of 20 ms. */
constexpr int32_t kBlock = 960;
/** Pitch is read every 30 ms, over 85 ms windows. */
constexpr int32_t kPitchHop = 1440;
constexpr int32_t kPitchWindow = 4096;
/** A voice or an instrument, not the bottom of a bass: shorter windows read it. */
constexpr float kPitchLowHz = 48.0f;
/** A clipped peak: this many samples in a row at full scale, or this many identical ones up high. */
constexpr int32_t kClipRun = 3;
constexpr int32_t kFlatRun = 6;
/** Hits further apart than this don't count as the same hit. */
constexpr float kSameHitMs = 60.0f;

/** Take [t] over region frames [from, to) as mono, silent where it has no audio. */
std::vector<float> monoOf(const TakeAudio &t, int32_t from, int32_t to) {
    std::vector<float> out(static_cast<size_t>(std::max(0, to - from)), 0.0f);
    if (t.source == nullptr) return out;
    const int32_t a = std::max(from, t.start);
    const int32_t b = std::min(to, t.end);
    for (int32_t at = a; at < b; ++at) {
        const int64_t s = static_cast<int64_t>(t.offset) + at;
        if (s < 0 || s >= t.source->frames) continue;
        out[static_cast<size_t>(at - from)] =
            0.5f * (static_cast<float>(t.source->lp[static_cast<size_t>(s)]) +
                    static_cast<float>(t.source->rp[static_cast<size_t>(s)])) * (1.0f / 32768.0f);
    }
    return out;
}

/** Rumble out, so a low hum is neither a hit nor a level. */
void highPass(std::vector<float> &x, float hz, float sampleRate) {
    dsp::Biquad hp;
    hp.highpass(hz, 0.7071f, sampleRate);
    for (float &v : x) v = hp.process(v);
}

float dbOf(double meanSquare) { return 10.0f * static_cast<float>(std::log10(meanSquare + 1e-12)); }

/** Each 20 ms block's level in dB, over [from, to) of [x]. */
std::vector<float> blockLevels(const std::vector<float> &x, int32_t from, int32_t to) {
    std::vector<float> out;
    for (int32_t a = from; a + kBlock <= to; a += kBlock) {
        double e = 0.0;
        for (int32_t i = a; i < a + kBlock; ++i) e += static_cast<double>(x[static_cast<size_t>(i)]) * x[static_cast<size_t>(i)];
        out.push_back(dbOf(e / kBlock));
    }
    return out;
}

float percentile(std::vector<float> v, float p) {
    if (v.empty()) return kNaN;
    std::sort(v.begin(), v.end());
    const auto i = static_cast<size_t>(std::clamp(p * static_cast<float>(v.size() - 1), 0.0f, static_cast<float>(v.size() - 1)));
    return v[i];
}

float median(std::vector<float> v) { return percentile(std::move(v), 0.5f); }

/** Clipped peaks in take [t] over region frames [from, to). */
int32_t clipsIn(const TakeAudio &t, int32_t from, int32_t to) {
    if (t.source == nullptr) return 0;
    int32_t clips = 0;
    const int32_t a = std::max(from, t.start);
    const int32_t b = std::min(to, t.end);
    const int16_t *planes[2] = {t.source->lp, t.source->stereo ? t.source->rp : nullptr};
    for (const int16_t *p : planes) {
        if (p == nullptr) continue;
        int32_t full = 0, flat = 0;
        int16_t last = 0;
        for (int32_t at = a; at < b; ++at) {
            const int64_t s = static_cast<int64_t>(t.offset) + at;
            if (s < 0 || s >= t.source->frames) { full = flat = 0; continue; }
            const int16_t v = p[static_cast<size_t>(s)];
            const int32_t mag = v < 0 ? -static_cast<int32_t>(v) : v;
            full = mag >= 32440 ? full + 1 : 0;
            flat = (v == last && mag >= 9830) ? flat + 1 : 0;
            last = v;
            // Count a run once, as it reaches the length.
            if (full == kClipRun || flat == kFlatRun - 1) ++clips;
        }
    }
    return clips;
}

/** The gap from [cents] to the nearest semitone of A 440, 0 to 50. */
float offPitch(float hz) {
    const float cents = 1200.0f * std::log2(hz / 440.0f);
    return std::fabs(cents - 100.0f * std::round(cents / 100.0f));
}

/** What one take showed in one phrase, before the takes are compared. */
struct Reading {
    bool present = false;
    float level = kNaN;
    float snr = kNaN;
    int32_t clips = 0;
    int32_t sounding = 0;
    int32_t voiced = 0;
    double centsOff = 0.0;
    std::vector<int32_t> hits; // region frames
};

} // namespace

std::vector<float> onsetStrength(const float *x, int32_t frames, float sampleRate) {
    const int32_t hops = frames / kHop;
    std::vector<float> db(static_cast<size_t>(std::max(0, hops)), -120.0f);
    if (hops <= 0) return {};
    dsp::Biquad hp;
    hp.highpass(100.0f, 0.7071f, sampleRate);
    std::vector<float> f(static_cast<size_t>(frames));
    for (int32_t i = 0; i < frames; ++i) f[static_cast<size_t>(i)] = hp.process(x[i]);
    // Energy over 5 ms centred on each hop.
    constexpr int32_t kHalf = 120;
    float peak = -120.0f;
    for (int32_t h = 0; h < hops; ++h) {
        const int32_t c = h * kHop;
        double e = 0.0;
        int32_t n = 0;
        for (int32_t i = std::max(0, c - kHalf); i < std::min(frames, c + kHalf); ++i) {
            e += static_cast<double>(f[static_cast<size_t>(i)]) * f[static_cast<size_t>(i)];
            ++n;
        }
        db[static_cast<size_t>(h)] = dbOf(n > 0 ? e / n : 0.0);
        peak = std::max(peak, db[static_cast<size_t>(h)]);
    }
    // A rise over 2 ms, only where it's within 45 dB of the loudest, so
    // the floor's flicker isn't a hit.
    std::vector<float> s(static_cast<size_t>(hops), 0.0f);
    for (int32_t h = 2; h < hops; ++h) {
        const float d = db[static_cast<size_t>(h)] - db[static_cast<size_t>(h - 2)];
        if (d > 0.0f && db[static_cast<size_t>(h)] > peak - 45.0f) s[static_cast<size_t>(h)] = d;
    }
    return s;
}

std::vector<int32_t> hitsOf(const std::vector<float> &s) {
    const auto n = static_cast<int32_t>(s.size());
    // Summed over 10 ms, about twice the rise over that time.
    std::vector<float> rise(static_cast<size_t>(n), 0.0f);
    float run = 0.0f;
    for (int32_t h = 0; h < n; ++h) {
        run += s[static_cast<size_t>(h)];
        if (h >= 10) run -= s[static_cast<size_t>(h - 10)];
        rise[static_cast<size_t>(h)] = run * 0.5f;
    }
    std::vector<int32_t> hits;
    constexpr int32_t kApart = 30; // hops: one hit per 30 ms either way
    for (int32_t h = 0; h < n; ++h) {
        const float r = rise[static_cast<size_t>(h)];
        if (r < 9.0f) continue;
        bool top = true;
        for (int32_t k = std::max(0, h - kApart); k < std::min(n, h + kApart + 1) && top; ++k) {
            const float o = rise[static_cast<size_t>(k)];
            if (o > r || (o == r && k < h)) top = false;
        }
        if (!top) continue;
        // The steepest point of the rise is the hit.
        int32_t best = h;
        for (int32_t k = std::max(0, h - 9); k <= h; ++k) {
            if (s[static_cast<size_t>(k)] > s[static_cast<size_t>(best)]) best = k;
        }
        hits.push_back(best * kHop);
    }
    return hits;
}

float lagOf(const float *lead, const float *dbl, int32_t frames, int32_t maxLag, float sampleRate) {
    const std::vector<int32_t> ha = hitsOf(onsetStrength(lead, frames, sampleRate));
    const std::vector<int32_t> hb = hitsOf(onsetStrength(dbl, frames, sampleRate));
    if (ha.empty() || hb.empty()) return kNaN;
    // Every gap between a hit of the double and a lead hit near it votes
    // for that gap, in 1 ms bins. The gap most hits agree on to within
    // 5 ms wins (a tie goes to the smaller), then the middle of the gaps
    // that agreed. Hits are sorted, so each looks only at its neighbours.
    const int32_t bins = maxLag / kHop;
    constexpr int32_t kAgreeBins = 5;
    std::vector<int32_t> votes(static_cast<size_t>(2 * bins + 1), 0);
    for (const int32_t h : hb) {
        auto it = std::lower_bound(ha.begin(), ha.end(), h - maxLag);
        for (; it != ha.end() && *it <= h + maxLag; ++it) {
            const int32_t bin = static_cast<int32_t>(std::lround(static_cast<float>(h - *it) / kHop));
            if (bin >= -bins && bin <= bins) ++votes[static_cast<size_t>(bin + bins)];
        }
    }
    int32_t best = 0, bestVotes = -1;
    for (int32_t b = -bins; b <= bins; ++b) {
        int32_t v = 0;
        for (int32_t k = std::max(-bins, b - kAgreeBins); k <= std::min(bins, b + kAgreeBins); ++k) v += votes[static_cast<size_t>(k + bins)];
        if (v > bestVotes || (v == bestVotes && std::abs(b) < std::abs(best))) {
            bestVotes = v;
            best = b;
        }
    }
    const float lag = static_cast<float>(best * kHop);
    constexpr float kAgree = static_cast<float>(kAgreeBins * kHop);
    std::vector<float> agreed;
    for (const int32_t h : hb) {
        float near = kAgree + 1.0f, gap = 0.0f;
        auto it = std::lower_bound(ha.begin(), ha.end(), h - maxLag - static_cast<int32_t>(kAgree));
        for (; it != ha.end() && *it <= h + maxLag + static_cast<int32_t>(kAgree); ++it) {
            const float d = std::fabs(static_cast<float>(h - *it) - lag);
            if (d < near) { near = d; gap = static_cast<float>(h - *it); }
        }
        if (near <= kAgree) agreed.push_back(gap);
    }
    // Most of the hits have to agree, and at least two unless there's only one.
    const auto fewer = static_cast<int32_t>(std::min(ha.size(), hb.size()));
    const auto n = static_cast<int32_t>(agreed.size());
    if (n < std::max(fewer > 1 ? 2 : 1, (fewer + 1) / 2)) return kNaN;
    const float out = median(agreed);
    return std::fabs(out) > static_cast<float>(maxLag) ? kNaN : out;
}

std::vector<Score> scoreStack(const Stack &st) {
    const auto takes = static_cast<int32_t>(st.takes.size());
    const int32_t phrases = std::max(0, static_cast<int32_t>(st.cuts.size()) - 1);
    std::vector<Score> out(static_cast<size_t>(takes * phrases));
    if (takes == 0 || phrases == 0) return out;
    const int32_t total = st.cuts.back();
    const float rate = st.sampleRate;

    // Each take whole, filtered once, and its floor: the quietest tenth of its blocks.
    std::vector<std::vector<float>> audio(static_cast<size_t>(takes));
    std::vector<float> floors(static_cast<size_t>(takes), kNaN);
    for (int32_t k = 0; k < takes; ++k) {
        const TakeAudio &t = st.takes[static_cast<size_t>(k)];
        audio[static_cast<size_t>(k)] = monoOf(t, 0, total);
        highPass(audio[static_cast<size_t>(k)], 60.0f, rate);
        floors[static_cast<size_t>(k)] =
            percentile(blockLevels(audio[static_cast<size_t>(k)], std::clamp(t.start, 0, total), std::clamp(t.end, 0, total)), 0.1f);
    }

    std::vector<Reading> rd(static_cast<size_t>(takes));
    std::vector<float> window(static_cast<size_t>(kPitchWindow));
    for (int32_t ph = 0; ph < phrases; ++ph) {
        const int32_t a = st.cuts[static_cast<size_t>(ph)];
        const int32_t b = st.cuts[static_cast<size_t>(ph + 1)];
        if (b <= a) continue;
        for (int32_t k = 0; k < takes; ++k) {
            const TakeAudio &t = st.takes[static_cast<size_t>(k)];
            const std::vector<float> &x = audio[static_cast<size_t>(k)];
            Reading &r = rd[static_cast<size_t>(k)];
            r = Reading{};
            const int32_t ca = std::max(a, t.start), cb = std::min(b, t.end);
            r.present = t.source != nullptr && cb - ca >= (b - a) / 2;
            if (!r.present) continue;
            const std::vector<float> levels = blockLevels(x, ca, cb);
            r.level = percentile(levels, 0.9f);
            r.snr = r.level - floors[static_cast<size_t>(k)];
            r.clips = clipsIn(t, ca, cb);

            // Pitch every 30 ms where the take is sounding.
            const float floorDb = floors[static_cast<size_t>(k)];
            for (int32_t c = ca + kPitchWindow / 2; c + kPitchWindow / 2 <= cb; c += kPitchHop) {
                double e = 0.0;
                for (int32_t i = c - kBlock / 2; i < c + kBlock / 2; ++i) e += static_cast<double>(x[static_cast<size_t>(i)]) * x[static_cast<size_t>(i)];
                const float db = dbOf(e / kBlock);
                if (db < floorDb + 12.0f || db < r.level - 30.0f) continue;
                ++r.sounding;
                std::copy(x.begin() + (c - kPitchWindow / 2), x.begin() + (c + kPitchWindow / 2), window.begin());
                const float hz = PitchFinder::find(window.data(), kPitchWindow, rate, nullptr, kPitchLowHz, PitchFinder::kMaxHz);
                if (hz <= 0.0f) continue;
                ++r.voiced;
                r.centsOff += offPitch(hz);
            }

            // The hits, in region frames.
            const std::vector<float> s = onsetStrength(x.data() + ca, cb - ca, rate);
            for (const int32_t h : hitsOf(s)) r.hits.push_back(ca + h);
        }

        // Is the phrase pitched? Most takes that sound in it have to say so.
        std::vector<float> voicedShare;
        float loudest = -1e9f;
        for (const Reading &r : rd) {
            if (!r.present) continue;
            loudest = std::max(loudest, r.level);
            if (r.sounding > 0) voicedShare.push_back(static_cast<float>(r.voiced) / static_cast<float>(r.sounding));
        }
        const bool pitched = !voicedShare.empty() && median(voicedShare) >= 0.4f;

        for (int32_t k = 0; k < takes; ++k) {
            const Reading &r = rd[static_cast<size_t>(k)];
            Score &s = out[static_cast<size_t>(k * phrases + ph)];
            if (!r.present) {
                s = {kNaN, kNaN, kNaN, 0.0f, kNaN};
                continue;
            }
            s.clips = static_cast<float>(r.clips);
            s.snrDb = r.snr;
            s.pitchCents = !pitched ? kNaN
                           : r.voiced > 0 ? static_cast<float>(r.centsOff / r.voiced)
                                          : (r.sounding > 0 ? kWorstCents : kNaN);
            // Each hit against the grid and against the nearest hit of any
            // other take, whichever it's closer to: in time with the song or
            // with the other takes are both in time.
            double err = 0.0;
            int32_t counted = 0;
            for (const int32_t h : r.hits) {
                float best = kSameHitMs;
                bool any = false;
                if (st.gridStep > 0.0) {
                    const double d = std::fmod(static_cast<double>(h) - st.gridPhase, st.gridStep);
                    const double m = std::fabs(d) < st.gridStep * 0.5 ? std::fabs(d) : st.gridStep - std::fabs(d);
                    best = std::min(best, static_cast<float>(m * 1000.0 / rate));
                    any = true;
                }
                for (int32_t o = 0; o < takes; ++o) {
                    if (o == k || !rd[static_cast<size_t>(o)].present) continue;
                    for (const int32_t g : rd[static_cast<size_t>(o)].hits) {
                        best = std::min(best, std::fabs(static_cast<float>(g - h)) * 1000.0f / rate);
                        any = true;
                    }
                }
                if (!any) continue;
                err += best;
                ++counted;
            }
            s.timingMs = counted > 0 ? static_cast<float>(err / counted) : kNaN;

            float bad = 0.0f, weight = 0.0f;
            if (!std::isnan(s.pitchCents)) {
                bad += kPitchWeight * std::min(1.0f, s.pitchCents / kWorstCents);
                weight += kPitchWeight;
            }
            if (!std::isnan(s.timingMs)) {
                bad += kTimingWeight * std::min(1.0f, s.timingMs / kWorstMs);
                weight += kTimingWeight;
            }
            bad += kClipWeight * std::min(1.0f, s.clips / kWorstClips);
            weight += kClipWeight;
            const float noise = std::isnan(s.snrDb) ? 1.0f : std::clamp((kCleanDb - s.snrDb) / 25.0f, 0.0f, 1.0f);
            bad += kNoiseWeight * noise;
            weight += kNoiseWeight;
            s.score = 100.0f * (1.0f - bad / weight);
            // A take that's 20 dB under the loudest here barely sings the phrase.
            if (r.level < loudest - 20.0f) s.score = 0.0f;
        }
    }
    return out;
}

std::vector<float> lineUp(const Reel::Region &lead, const Reel::Region &dbl, const std::vector<int32_t> &cuts,
                          int32_t maxShift, float sampleRate) {
    std::vector<float> out;
    const int32_t margin = maxShift + 480;
    for (size_t i = 0; i + 1 < cuts.size(); ++i) {
        const int32_t a = cuts[i] - margin;
        const int32_t b = cuts[i + 1] + margin;
        if (b - a <= 0) {
            out.push_back(kNaN);
            continue;
        }
        std::vector<float> l(static_cast<size_t>(b - a)), d(static_cast<size_t>(b - a));
        int32_t hl = 0, hd = 0;
        for (int32_t at = a; at < b; ++at) {
            float x = 0.0f, y = 0.0f;
            if (at >= 0 && at < lead.frames) lead.soundAt(at, hl, x, y);
            l[static_cast<size_t>(at - a)] = 0.5f * (x + y);
            x = y = 0.0f;
            if (at >= 0 && at < dbl.frames) dbl.soundAt(at, hd, x, y);
            d[static_cast<size_t>(at - a)] = 0.5f * (x + y);
        }
        const float lag = lagOf(l.data(), d.data(), b - a, maxShift, sampleRate);
        out.push_back(std::isnan(lag) || std::fabs(lag) > static_cast<float>(maxShift) ? kNaN : -lag);
    }
    return out;
}

} // namespace acidulous::audio::takescore
