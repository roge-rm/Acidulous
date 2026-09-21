#pragma once
#include <cmath>
#include <cstdint>

// Running a nonlinearity at twice the rate, so what it makes above Nyquist
// does not fold back into the music.
//
// **Why this exists when `Distortion` already oversamples.** That one is
// hand-rolled inline: upsampled by taking the midpoint of two samples and
// decimated with a single biquad. A linear-interpolated midpoint is
// convolution with a two-sample triangle, whose response passes the first
// image at about -13 dB - so the nonlinearity downstream intermodulates those
// images straight back down into the audible band, and the 2x buys far less
// than it looks like it buys. Good enough for a distortion pedal whose grit is
// part of its sound; not good enough for an amp, which has *two* nonlinear
// stages and a speaker filter after them that hides none of it, because folded
// harmonics land below the corner.
//
// A halfband FIR instead. Halfband because every even tap of it is zero except
// the middle one, which is what makes the polyphase form cost eight multiplies
// a sample each way rather than thirty-one.
//
// **2x and not 4x**, and that is a decision about the *nonlinearities*, not
// about the filter: a tanh family's harmonics fall away fast enough that what
// is left above 72 kHz is already far below anything audible. A hard clipper
// or a wavefolder would need 4x, which is why nothing inside the oversampled
// region of an amp is allowed to be one.
namespace acidulous::dsp {

class Oversampler {
  public:
    /** Taps at the doubled rate. 15 either side of the centre. */
    static constexpr int32_t kTaps = 31;
    static constexpr int32_t kHalf = kTaps / 2;
    /**
     * What a round trip costs, in samples at the **base** rate.
     *
     * Thirty at twice the rate, which is fifteen here. It is a constant rather
     * than something to measure because the dry path has to be delayed by
     * exactly this - see the note on `Amp` - and a latency that changes when a
     * quality setting is flipped is a click.
     */
    static constexpr int32_t kLatency = (kTaps - 1) / 2;

    void prepare() {
        // A windowed sinc at a quarter of the doubled rate. Blackman, which
        // gets a 31-tap halfband to about -74 dB in the stopband: the images
        // the nonlinearity would otherwise fold back arrive already dead.
        for (int32_t k = 0; k < kPairs; ++k) {
            const int32_t n = 2 * k + 1; // the odd taps are the only live ones
            const float ideal = std::sin(1.5707963f * static_cast<float>(n)) /
                                (3.14159265f * static_cast<float>(n));
            const float t = static_cast<float>(n + kHalf) / static_cast<float>(kTaps - 1);
            const float w = 0.42f - 0.5f * std::cos(6.2831853f * t) + 0.08f * std::cos(12.566371f * t);
            odd[k] = ideal * w;
        }
        reset();
    }

    void reset() {
        for (auto &v : hist) v = 0.0f;
        for (auto &v : down2) v = 0.0f;
        at = 0;
        atDown = 0;
    }

    /**
     * [n] frames in, 2n out.
     *
     * The even outputs are the input itself: with zeros stuffed between the
     * samples, only the centre tap reaches them, and a halfband's centre tap
     * is a half that the doubling cancels. So half the work is already done
     * and the odd outputs are the only ones that cost anything.
     */
    void up(const float *in, int32_t n, float *out) {
        for (int32_t i = 0; i < n; ++i) {
            hist[static_cast<size_t>(at)] = in[i];
            hist[static_cast<size_t>(at + kRing)] = in[i]; // a second copy, so no wrap in the loop
            at = at + 1 >= kRing ? 0 : at + 1;

            // The centre of the window, delayed so that both branches line up.
            out[i * 2] = tap(kPairs);
            float odds = 0.0f;
            for (int32_t k = 0; k < kPairs; ++k) {
                odds += odd[k] * (tap(kPairs + k) + tap(kPairs - 1 - k));
            }
            out[i * 2 + 1] = 2.0f * odds;
        }
    }

    /** 2n frames in, [n] out. The mirror of [up]. */
    void down(const float *in, int32_t n, float *out) {
        for (int32_t i = 0; i < n; ++i) {
            push(in[i * 2]);
            push(in[i * 2 + 1]);
            // Even taps first: only the centre, at a half.
            float sum = 0.5f * dtap(kHalf);
            for (int32_t k = 0; k < kPairs; ++k) {
                const int32_t j = 2 * k + 1;
                sum += odd[k] * (dtap(kHalf - j) + dtap(kHalf + j));
            }
            out[i] = sum;
        }
    }

  private:
    static constexpr int32_t kPairs = (kHalf + 1) / 2; // 8 live taps either side
    static constexpr int32_t kRing = 32;
    static constexpr int32_t kDownRing = 64;

    /** [back] samples before the write head, at the base rate. */
    float tap(int32_t back) const {
        return hist[static_cast<size_t>(at + kRing - 1 - back)];
    }
    void push(float v) {
        down2[static_cast<size_t>(atDown)] = v;
        down2[static_cast<size_t>(atDown + kDownRing)] = v;
        atDown = atDown + 1 >= kDownRing ? 0 : atDown + 1;
    }
    float dtap(int32_t back) const {
        return down2[static_cast<size_t>(atDown + kDownRing - 1 - back)];
    }

    float odd[kPairs] = {0.0f};
    float hist[kRing * 2] = {0.0f};
    float down2[kDownRing * 2] = {0.0f};
    int32_t at = 0, atDown = 0;
};

} // namespace acidulous::dsp
