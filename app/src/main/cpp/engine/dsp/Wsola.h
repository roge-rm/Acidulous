#pragma once
#include <cmath>
#include <cstdint>
#include <vector>

// Changing how long a take is without changing what it sings.
//
// **Why not the PSOLA already in the tree.** `engine/core/Utterance.h` finds a
// voice's glottal pulses and Molt lays them down at a new spacing, which is a
// better answer than this one *for a voice* - and it is a worse answer for a
// guitar, a room, a drum loop or a band, none of which have glottal pulses. It
// also costs a few hundred milliseconds of analysis for ten seconds of audio,
// which on a half-hour take is minutes. An audio track holds whatever somebody
// recorded, so what it needs is the algorithm that asks the material nothing.
//
// WSOLA: lay the output down in overlapping hops, and for each one **search
// the source near where the clock says, for the window that joins best onto
// what has already been written**. The join is what the search is for; without
// it, overlap-add at an arbitrary phase cancels the very frequencies it is
// carrying and the result warbles.
//
// Deliberately int16 and mono-at-a-time, because that is exactly how a reel
// holds a take (`engine/core/Reel.h`) and converting a half-hour file to float
// to stretch it would undo what the mapping was for.
namespace acidulous::dsp {

/**
 * How a sample type becomes a float, and nothing else.
 *
 * A reel is int16 because that is how a half-hour take is held. A frozen clip
 * is float **and stereo**, and float deliberately: the tap is pre-fader, so it
 * can sit above full scale - the demo's Hexbeat bar peaks at 1.84 - and
 * converting it to int16 to stretch it would clip exactly what the float
 * format is there to keep.
 */
template <class Sample> struct SampleScale;
template <> struct SampleScale<int16_t> {
    static float of(int16_t v) { return static_cast<float>(v) * (1.0f / 32768.0f); }
};
template <> struct SampleScale<float> {
    static float of(float v) { return v; }
};

/**
 * [Channels] is where a stereo stretch is won or lost.
 *
 * Two independent stretchers on a stereo pair each pick their own join, and
 * the offsets differ by up to half a hop - so the image wanders and anything
 * centred comes apart. **The search runs once, on the sum of the channels,
 * and the offset it finds is applied to all of them.** That is the whole of
 * what makes this stereo rather than two monos.
 */
template <class Sample, int32_t Channels> class Stretcher {
  public:
    /**
     * The hop, the overlap and how far the search may look.
     *
     * 30 ms of window is long enough to carry a low male voice's period four
     * times over - so the correlation has something periodic to lock to - and
     * short enough that a transient is smeared by less than a drummer would
     * notice. The search is half a hop either way, which covers a full period
     * of anything above 70 Hz.
     */
    static constexpr int32_t kWindow = 1440; // 30 ms at 48k
    static constexpr int32_t kHop = kWindow / 2;
    static constexpr int32_t kSearch = kHop / 2;

    /**
     * The Hann window, built once for the whole app.
     *
     * Every stretcher's window is the same numbers, and four lanes across
     * sixteen racks is sixty-four copies of an identical table - a third of a
     * megabyte of cosine nobody needs twice.
     */
    static const float *hann() {
        static const std::vector<float> w = [] {
            std::vector<float> v(static_cast<size_t>(kWindow));
            for (int32_t i = 0; i < kWindow; ++i) {
                // The halves sum to one at this overlap, so a rate of exactly
                // one with no search is the input back again.
                v[static_cast<size_t>(i)] = 0.5f - 0.5f * std::cos(6.2831853f * static_cast<float>(i) /
                                                                  static_cast<float>(kWindow));
            }
            return v;
        }();
        return w.data();
    }

    void prepare() {
        window = hann();
        for (auto &ch : out) ch.resize(static_cast<size_t>(kWindow + kHop));
        reset();
    }

    /** Start again at [from] frames into the source. */
    void seek(int64_t from) {
        reset();
        readPos = static_cast<double>(from);
        anchor = from;
    }

    void reset() {
        for (auto &ch : out) {
            for (auto &v : ch) v = 0.0f;
        }
        have = 0;
        taken = 0;
        readPos = 0.0;
        anchor = 0;
        primed = false;
    }

    /** How far into the source the next output sample comes from. */
    int64_t sourcePosition() const { return static_cast<int64_t>(readPos); }

    /**
     * Fill [n] output frames from [src], which holds [first, last) of a take.
     *
     * [rate] is how fast the source is consumed: 1.0 is real time, 2.0 plays
     * it in half the time **at the same pitch**. Returns how many frames were
     * written; short means the source ran out.
     */
    int32_t fill(float *const dst[Channels], const Sample *const src[Channels], int64_t first, int64_t last,
                 int32_t n, float rate) {
        if (window == nullptr || last - first < kWindow) return 0;
        for (int32_t ch = 0; ch < Channels; ++ch) {
            if (src[ch] == nullptr) return 0;
        }
        int32_t made = 0;
        while (made < n) {
            if (taken >= have) {
                if (!lay(src, first, last, rate)) break;
            }
            const int32_t k = have - taken < n - made ? have - taken : n - made;
            for (int32_t ch = 0; ch < Channels; ++ch) {
                for (int32_t i = 0; i < k; ++i) {
                    dst[ch][made + i] = out[ch][static_cast<size_t>(taken + i)];
                }
            }
            made += k;
            taken += k;
        }
        return made;
    }

    /** The one-channel call, which is how a reel asks and how it always asked. */
    int32_t fill(float *dst, int32_t n, const Sample *src, int64_t first, int64_t last, float rate) {
        static_assert(Channels == 1, "a stereo stretch needs both channels, or the image wanders");
        float *dsts[1] = {dst};
        const Sample *srcs[1] = {src};
        return fill(dsts, srcs, first, last, n, rate);
    }

  private:
    /**
     * One hop: find where the source joins best, overlap-add it, and shift.
     *
     * The first hop has nothing to join onto, so it is laid down where the
     * clock says and the search starts from the second - which is also why
     * `seek` must be called at a cycle boundary rather than the position
     * nudged, or the join is searched against audio from somewhere else.
     */
    bool lay(const Sample *const src[Channels], int64_t first, int64_t last, float rate) {
        // Shift the overlap down: what has been consumed goes, what has been
        // written into the tail becomes the head of the next window.
        for (int32_t ch = 0; ch < Channels; ++ch) {
            for (int32_t i = 0; i < kWindow - kHop; ++i) {
                out[ch][static_cast<size_t>(i)] = out[ch][static_cast<size_t>(i + kHop)];
            }
            for (int32_t i = kWindow - kHop; i < kWindow + kHop; ++i) out[ch][static_cast<size_t>(i)] = 0.0f;
        }
        have = kHop;
        taken = 0;

        // **The clock is not moved by the search.** This is the one mistake a
        // WSOLA can make that still sounds fine: feed the chosen offset back
        // into the read position and every hop's join nudges the next hop's
        // starting point, so the offsets accumulate and the rate is not the
        // rate. Measured here before it was fixed: asked for half speed, it
        // read a third. The search decides *which* samples are copied and
        // nothing about *where the clock is*.
        int64_t want = static_cast<int64_t>(readPos);
        if (primed) want = bestJoin(src, first, last, want, searchFor(rate));
        if (want < first) want = first;
        if (want + kWindow > last) return false;

        for (int32_t ch = 0; ch < Channels; ++ch) {
            for (int32_t i = 0; i < kWindow; ++i) {
                out[ch][static_cast<size_t>(i)] += SampleScale<Sample>::of(src[ch][want + i]) * window[i];
            }
        }
        // The *output* advances by a hop; the *source* advances by a hop times
        // the rate. That difference is the whole of the stretch.
        readPos += static_cast<double>(kHop) * static_cast<double>(rate);
        primed = true;
        return true;
    }

    /**
     * Cross-correlate what we are about to overlap against what is already
     * written, and take the offset that agrees best.
     *
     * Decimated by four: a join good to four samples is good to eighty
     * microseconds, which is far below where a phase error is audible, and it
     * is four times less work in the one loop here that is O(search x window).
     */
    /**
     * How far the search has to look, which depends on the rate.
     *
     * ±half a hop is what it takes to *acquire* alignment from nothing - a
     * full period of anything above 70 Hz. But after the first hop this is not
     * acquiring, it is **tracking**: the previous hop was already aligned, and
     * one hop of a rate r slips the source by `(r - 1) * kHop` against the
     * output. So the correction needed is that slip and not a whole period.
     *
     * At the 1.065 a demo tempo ramp asks for, that is 47 samples rather than
     * 360 - and the search is the whole cost of a hop. Measured on a phone
     * before this: 1.07 ms for one stretching rack, three of them in lockstep,
     * against a 1.33 ms block. A take at half speed still gets the full range,
     * because there the slip really is a period and more.
     */
    int32_t searchFor(float rate) const {
        const float slip = std::fabs(rate - 1.0f) * static_cast<float>(kHop);
        const int32_t want = static_cast<int32_t>(slip * 2.0f) + 32;
        return want > kSearch ? kSearch : want;
    }

    int64_t bestJoin(const Sample *const src[Channels], int64_t first, int64_t last, int64_t want,
                     int32_t reach) const {
        const int32_t overlap = kWindow - kHop;
        int64_t best = want;
        float bestScore = -1e30f;
        for (int32_t d = -reach; d <= reach; d += 4) {
            const int64_t at = want + d;
            if (at < first || at + kWindow > last) continue;
            float score = 0.0f;
            // On the sum of the channels, so every channel is laid at the one
            // offset. Scale is irrelevant here - this only ever picks a winner
            // - so the source is correlated raw and unconverted.
            for (int32_t i = 0; i < overlap; i += 4) {
                float written = 0.0f, coming = 0.0f;
                for (int32_t ch = 0; ch < Channels; ++ch) {
                    written += out[ch][static_cast<size_t>(i)];
                    coming += static_cast<float>(src[ch][at + i]);
                }
                score += written * coming;
            }
            if (score > bestScore) {
                bestScore = score;
                best = at;
            }
        }
        return best;
    }

    const float *window = nullptr;
    std::vector<float> out[Channels];
    int32_t have = 0, taken = 0;
    double readPos = 0.0;
    int64_t anchor = 0;
    bool primed = false;
};

/** A reel lane: one channel of int16, which is what Bias has always asked for. */
using Wsola = Stretcher<int16_t, 1>;

/**
 * A frozen clip: float, stereo, and stretched to follow a tempo it was not
 * rendered at.
 *
 * A freeze is tempo-bound because audio does not stretch - and it does, for
 * about nine microseconds a rack against a block's thirteen hundred. Measured
 * against the alternative it replaces: a scene with a smooth tempo change
 * cannot match any clip's rendered tempo while it is ramping, so every frozen
 * clip in it fell back to its machine for a bar - 87 us a rack for Trinity,
 * in the scene most likely to be why anything was frozen at all.
 */
using StereoStretch = Stretcher<float, 2>;

} // namespace acidulous::dsp
