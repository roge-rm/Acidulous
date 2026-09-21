// The amp, and the cabinet that is most of it.
//
// Nearly everything worth asserting here is **pure arithmetic** - a filter
// chain's magnitude, evaluated through `Biquad::at`, with no audio rendered and
// no FFT. That is the point of lifting `at` out of Timber: a claim about a
// cabinet's response should cost microseconds, so it can be made across a grid
// of settings rather than at one.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/effect/amp/Cabinet.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-52s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;
double dB(double v) { return 20.0 * std::log10(v + 1e-20); }

/** Where the response has fallen 6 dB below its own midband, going down. */
float lowCornerOf(effect::amp::Cabinet &cab) {
    const float ref = cab.magnitudeAt(400.0f);
    for (int32_t i = 0; i < 400; ++i) {
        const float hz = 30.0f * std::pow(2.0f, static_cast<float>(i) * 0.01f);
        if (hz > 400.0f) break;
        if (dB(cab.magnitudeAt(hz)) - dB(ref) > -6.0) return hz;
    }
    return 400.0f;
}

/** And where it falls away at the top. */
float topCornerOf(effect::amp::Cabinet &cab) {
    const float ref = cab.magnitudeAt(500.0f);
    for (int32_t i = 400; i >= 0; --i) {
        const float hz = 800.0f * std::pow(2.0f, static_cast<float>(i) * 0.01f);
        if (hz > 20000.0f) continue;
        if (dB(cab.magnitudeAt(hz)) - dB(ref) > -6.0) return hz;
    }
    return 800.0f;
}

/** The pink-weighted centroid, in octaves above 80 Hz. */
double centroidOf(effect::amp::Cabinet &cab) {
    double num = 0.0, den = 0.0;
    for (int32_t i = 0; i < 40; ++i) {
        const double oct = static_cast<double>(i) * 0.2;
        const float hz = 80.0f * static_cast<float>(std::pow(2.0, oct));
        const double g = cab.magnitudeAt(hz);
        num += oct * g * g;
        den += g * g;
    }
    return den > 0.0 ? num / den : 0.0;
}

// --- The claims ------------------------------------------------------------------

/**
 * A cabinet you can resize, and not a tone control with extra steps.
 *
 * This is the whole reason the cab is modelled rather than convolved, and the
 * regression it invites is scaling every filter by the same factor - which
 * looks tidier and turns `size` into "dark at one end, thin at the other".
 *
 * Two numbers encode the claim: the **low corner moves a lot** because the box
 * decides it, and the **top does not** because the cone does, and a cone is not
 * a box.
 */
void sizeIsACabinetNotAToneControl() {
    printf("- size is a cabinet you can resize\n");
    effect::amp::Cabinet small, big;
    small.prepare(kRate);
    big.prepare(kRate);
    small.set(0.0f, 0.5f, 0.0f, 0.0f, 0.0f);
    big.set(1.0f, 0.5f, 0.0f, 0.0f, 0.0f);

    const float lowSmall = lowCornerOf(small), lowBig = lowCornerOf(big);
    const double octaves = std::log2(lowSmall / lowBig);
    ok("the low corner moves the best part of two octaves", octaves > 1.7,
       std::to_string(lowSmall) + " Hz down to " + std::to_string(lowBig) + " Hz, " +
           std::to_string(octaves) + " octaves");

    // **The top corner, not a centroid.** A centroid over the whole band moves
    // when the *bass* moves, so it cannot tell "the cab got bigger" from "the
    // cab got darker" - which is the one distinction this test exists to make.
    //
    // The bar is an octave. The regression this guards against is scaling
    // every filter by the same factor, which would move the top by the same
    // 2.2 octaves as the bottom and turn `size` into a tone control; what the
    // fractional exponents buy is that the top moves about half as far as the
    // bottom, because breakup belongs to the cone and the corner belongs to
    // the box.
    const float topSmall = topCornerOf(small), topBig = topCornerOf(big);
    const double moved = std::log2(topSmall / topBig);
    ok("and the top end moves about half as far", std::fabs(moved) < 1.1 && moved < octaves * 0.6,
       std::to_string(topSmall) + " Hz to " + std::to_string(topBig) + " Hz, " +
           std::to_string(moved) + " octaves against " + std::to_string(octaves));
}

/** Every cabinet knob has to point one way, or nobody can aim it. */
void everyKnobIsMonotonic() {
    printf("- every cabinet control moves one way\n");
    struct Probe { const char *name; int which; float hz; bool up; };
    const Probe probes[] = {
        {"size", 0, 80.0f, true},    // bigger box, more bottom
        {"cone", 1, 2600.0f, true},  // harder cone, more breakup
        {"mic", 2, 5000.0f, false},  // off axis, darker
        // `room` is deliberately not here: it is three delayed taps, not a
        // filter, so it does not appear in the chain's magnitude at all. What
        // it does is measured where it happens, in the render.
    };
    for (const auto &p : probes) {
        double last = -1e9;
        bool mono = true;
        double first = 0.0, end = 0.0;
        for (int32_t i = 0; i <= 32; ++i) {
            const float v = static_cast<float>(i) / 32.0f;
            float k[5] = {0.5f, 0.5f, 0.0f, 0.0f, 0.0f};
            k[p.which] = v;
            effect::amp::Cabinet cab;
            cab.prepare(kRate);
            cab.set(k[0], k[1], k[2], k[3], k[4]);
            double g = dB(cab.magnitudeAt(p.hz));
            if (!p.up) g = -g;
            if (i == 0) first = g;
            end = g;
            // A little slack: the trim moves with the response and a knob may
            // pause without turning round.
            if (g < last - 0.35) mono = false;
            last = g;
        }
        ok((std::string(p.name) + " moves one way, and it moves").c_str(),
           mono && std::fabs(end - first) > 1.0,
           std::to_string(end - first) + " dB across the range");
    }
}

/**
 * A cabinet must not ring.
 *
 * Five peaks, and `cone` puts the top of their Q at 3.5. Two of them landing a
 * third apart would sum into something sharper than either, and the symptom is
 * a sproingy ping on transients - a snare turning into a boing.
 *
 * Measured from the poles rather than from audio, and **split at 800 Hz**: an
 * 110 Hz bump at Q 1.6 rings for 30 ms and that ringing *is* the thump, so one
 * flat threshold would fail a correct design.
 */
void theCabinetDoesNotRing() {
    printf("- the cabinet does not ring\n");
    double worstHigh = 0.0;
    std::string where;
    for (int32_t a = 0; a <= 4; ++a) {
        for (int32_t b = 0; b <= 4; ++b) {
            for (int32_t c = 0; c <= 2; ++c) {
                effect::amp::Cabinet cab;
                cab.prepare(kRate);
                cab.set(a / 4.0f, b / 4.0f, c / 2.0f, 0.5f, 0.0f);
                // Peaks 2, 3 and 4 are the cone, all above 800 Hz.
                for (int32_t k = 2; k < effect::amp::Cabinet::kPeaks; ++k) {
                    const double t = cab.ringSeconds(k);
                    if (t > worstHigh) {
                        worstHigh = t;
                        where = "size " + std::to_string(a / 4.0f) + " cone " + std::to_string(b / 4.0f);
                    }
                }
            }
        }
    }
    ok("no cone peak rings past twelve milliseconds", worstHigh < 0.012,
       std::to_string(worstHigh * 1000.0) + " ms at " + where);
}

/** And every setting has to sit at about the same level as every other. */
void theLevelHoldsAcrossTheGrid() {
    printf("- one cabinet is not louder than another\n");
    double lo = 1e9, hi = -1e9;
    for (int32_t a = 0; a <= 4; ++a) {
        for (int32_t b = 0; b <= 4; ++b) {
            for (int32_t c = 0; c <= 2; ++c) {
                effect::amp::Cabinet cab;
                cab.prepare(kRate);
                cab.set(a / 4.0f, b / 4.0f, c / 2.0f, 0.0f, 0.0f);
                // The same pink-weighted reading the trim is made from.
                double sum = 0.0;
                for (int32_t i = 0; i < 12; ++i) {
                    const float hz = 80.0f * std::pow(2.0f, static_cast<float>(i) * 0.55f);
                    // The trim is what keeps them together, so it is part of
                    // what is being measured.
                    const double g = cab.magnitudeAt(hz) * cab.outputTrim();
                    sum += g * g;
                }
                const double rms = dB(std::sqrt(sum / 12.0));
                lo = rms < lo ? rms : lo;
                hi = rms > hi ? rms : hi;
            }
        }
    }
    ok("the whole grid sits within three decibels", hi - lo < 3.0,
       std::to_string(hi - lo) + " dB spread");
}

} // namespace

int main() {
    printf("the amp's cabinet\n");
    sizeIsACabinetNotAToneControl();
    everyKnobIsMonotonic();
    theCabinetDoesNotRing();
    theLevelHoldsAcrossTheGrid();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
