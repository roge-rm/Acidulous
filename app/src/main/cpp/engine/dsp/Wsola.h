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

class Wsola {
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
        out.resize(static_cast<size_t>(kWindow + kHop));
        reset();
    }

    /** Start again at [from] frames into the source. */
    void seek(int64_t from) {
        reset();
        readPos = static_cast<double>(from);
        anchor = from;
    }

    void reset() {
        for (auto &v : out) v = 0.0f;
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
    int32_t fill(float *dst, int32_t n, const int16_t *src, int64_t first, int64_t last, float rate) {
        if (src == nullptr || window == nullptr || last - first < kWindow) return 0;
        int32_t made = 0;
        while (made < n) {
            if (taken >= have) {
                if (!lay(src, first, last, rate)) break;
            }
            const int32_t k = have - taken < n - made ? have - taken : n - made;
            for (int32_t i = 0; i < k; ++i) {
                dst[made + i] = out[static_cast<size_t>(taken + i)];
            }
            made += k;
            taken += k;
        }
        return made;
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
    bool lay(const int16_t *src, int64_t first, int64_t last, float rate) {
        // Shift the overlap down: what has been consumed goes, what has been
        // written into the tail becomes the head of the next window.
        for (int32_t i = 0; i < kWindow - kHop; ++i) {
            out[static_cast<size_t>(i)] = out[static_cast<size_t>(i + kHop)];
        }
        for (int32_t i = kWindow - kHop; i < kWindow + kHop; ++i) out[static_cast<size_t>(i)] = 0.0f;
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
        if (primed) want = bestJoin(src, first, last, want);
        if (want < first) want = first;
        if (want + kWindow > last) return false;

        for (int32_t i = 0; i < kWindow; ++i) {
            const float v = static_cast<float>(src[want + i]) * (1.0f / 32768.0f);
            out[static_cast<size_t>(i)] += v * window[i];
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
    int64_t bestJoin(const int16_t *src, int64_t first, int64_t last, int64_t want) const {
        const int32_t overlap = kWindow - kHop;
        int64_t best = want;
        float bestScore = -1e30f;
        for (int32_t d = -kSearch; d <= kSearch; d += 4) {
            const int64_t at = want + d;
            if (at < first || at + kWindow > last) continue;
            float score = 0.0f;
            for (int32_t i = 0; i < overlap; i += 4) {
                score += out[static_cast<size_t>(i)] * static_cast<float>(src[at + i]);
            }
            if (score > bestScore) {
                bestScore = score;
                best = at;
            }
        }
        return best;
    }

    const float *window = nullptr;
    std::vector<float> out;
    int32_t have = 0, taken = 0;
    double readPos = 0.0;
    int64_t anchor = 0;
    bool primed = false;
};

} // namespace acidulous::dsp
