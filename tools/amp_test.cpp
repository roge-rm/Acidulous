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
#include <engine/dsp/Oversampler.h>
#include <engine/effect/amp/Amp.h>
#include <engine/effect/amp/Stages.h>

using namespace acidulous;
using namespace acidulous::effect;

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

// --- The amp around it -----------------------------------------------------------

/** And `stack` chooses *how hard* they fight, not just where. */
void theStacksDifferInHowHardTheyFight(const double *depth) {
    ok("the British stack scoops harder than the modern one",
       depth[amp::Uk] > depth[amp::Modern] + 2.0,
       std::to_string(depth[amp::Uk]) + " dB against " + std::to_string(depth[amp::Modern]));
}

/**
 * The tone stack interacts, which is what tells it from an equaliser.
 *
 * Three independent shelves would leave the mid where it is when bass and
 * treble go up. A real passive stack scoops, because it can only attenuate -
 * and the scoop runs on the **product** of the two, so turning either one down
 * fills the mid back in.
 */
void theToneStackInteracts() {
    printf("- the tone stack fights with itself\n");
    double depth[amp::StackCount] = {0.0};
    for (int32_t k = 0; k < amp::StackCount; ++k) {
        const amp::Voicing v = amp::voicingOf(k);
        amp::ToneStack both, neither;
        both.prepare(kRate);
        neither.prepare(kRate);
        both.set(v, 1.0f, 0.0f, 1.0f);    // bass and treble up, mid down
        neither.set(v, 0.0f, 0.0f, 0.0f); // everything down

        const float midHz = v.midRef;
        const double scooped = dB(both.magnitudeAt(midHz)) - dB(both.magnitudeAt(100.0f));
        const double flat = dB(neither.magnitudeAt(midHz)) - dB(neither.magnitudeAt(100.0f));
        // Three decibels for all three, because **the modern voicing scoops
        // least on purpose** - a high-gain amp does its scooping with gain
        // structure and its stack is flatter. That the three differ is
        // asserted separately below; that they all scoop is the shared claim.
        ok((std::string("stack ") + std::to_string(k) + ": bass and treble up scoops the mid").c_str(),
           scooped < flat - 3.0,
           std::to_string(scooped) + " dB against " + std::to_string(flat));
        depth[k] = flat - scooped;
    }
    theStacksDifferInHowHardTheyFight(depth);
}

/** And it can never go unstable, whatever anybody automates it to. */
void theToneStackIsAlwaysStable() {
    printf("- no setting of it misbehaves\n");
    double loudest = -1e9;
    bool finite = true;
    for (int32_t k = 0; k < amp::StackCount; ++k) {
        const amp::Voicing v = amp::voicingOf(k);
        for (int32_t b = 0; b <= 4; ++b) {
            for (int32_t m = 0; m <= 4; ++m) {
                for (int32_t t = 0; t <= 4; ++t) {
                    amp::ToneStack s;
                    s.prepare(kRate);
                    s.set(v, b / 4.0f, m / 4.0f, t / 4.0f);
                    for (int32_t i = 0; i < 40; ++i) {
                        const float hz = 30.0f * std::pow(2.0f, static_cast<float>(i) * 0.25f);
                        if (hz > 20000.0f) break;
                        const double g = dB(s.magnitudeAt(hz));
                        if (!std::isfinite(g)) finite = false;
                        loudest = g > loudest ? g : loudest;
                    }
                }
            }
        }
    }
    ok("every setting is finite", finite);
    ok("and none of them gains more than fourteen decibels", loudest < 14.0,
       std::to_string(loudest) + " dB at the loudest");
}

/**
 * Sag droops and comes back, and does not oscillate.
 *
 * The failure it guards: detecting on the stage's *output* instead of its
 * input gives a limiter loop with a twelve millisecond attack and a loop gain
 * above one, which motorboats at thirty to eighty hertz - and gets blamed on
 * the cab.
 */
void sagDroopsAndRecovers() {
    printf("- the supply sags under load and comes back\n");
    amp::PowerStage stage;
    stage.prepare(kRate);
    stage.set(1.0f, 1.0f, 1.0f);

    std::vector<float> rail;
    for (int32_t i = 0; i < static_cast<int32_t>(kRate * 2); ++i) {
        stage.process(0.4f * std::sin(2.0f * static_cast<float>(M_PI) * 220.0f * i / kRate));
        rail.push_back(stage.rail());
    }
    const double early = rail[static_cast<size_t>(kRate * 0.2f)];
    ok("it has drooped within two hundred milliseconds", dB(early) < -1.5,
       std::to_string(dB(early)) + " dB of rail");

    // Over the last second it must be steady: a rail that keeps moving is one
    // that is oscillating.
    double lo = 1e9, hi = -1e9;
    for (int32_t i = static_cast<int32_t>(kRate); i < static_cast<int32_t>(kRate * 2); ++i) {
        const double r = rail[static_cast<size_t>(i)];
        lo = r < lo ? r : lo;
        hi = r > hi ? r : hi;
    }
    ok("and then holds still rather than pumping", dB(hi) - dB(lo) < 0.5,
       std::to_string(dB(hi) - dB(lo)) + " dB of movement");

    // Quiet in, no sag.
    amp::PowerStage quiet;
    quiet.prepare(kRate);
    quiet.set(1.0f, 1.0f, 1.0f);
    for (int32_t i = 0; i < static_cast<int32_t>(kRate); ++i) {
        quiet.process(0.01f * std::sin(2.0f * static_cast<float>(M_PI) * 220.0f * i / kRate));
    }
    ok("a quiet passage does not sag at all", dB(quiet.rail()) > -0.3,
       std::to_string(dB(quiet.rail())) + " dB");
}

/**
 * The whole effect, end to end.
 *
 * Two claims a chain this long can break quietly: that `mix` at nought is the
 * input back again - which also proves the dry path is delayed by exactly the
 * oversampler's latency, because a dry path that is not would show up here as
 * a difference - and that it makes a sound at all.
 */
void theWholeAmp() {
    printf("- the amp, end to end\n");
    Amp fx;
    fx.prepare(static_cast<int32_t>(kRate));

    std::vector<float> in(1024);
    for (size_t i = 0; i < in.size(); ++i) {
        in[i] = 0.3f * std::sin(2.0 * M_PI * 220.0 * i / kRate) +
                0.1f * std::sin(2.0 * M_PI * 1870.0 * i / kRate);
    }

    // mix = 0: bit for bit what went in.
    {
        const int32_t mix = fx.params().indexOf("mix");
        fx.params().set(mix, 0.0f);
        fx.params().jumpAll();
        std::vector<float> l = in, r = in;
        for (size_t at = 0; at < in.size(); at += 64) {
            fx.run(l.data() + at, r.data() + at, 64, true);
        }
        // Bit for bit, **delayed by the oversampler's latency** - which is the
        // claim worth making, because it proves the dry path is compensated by
        // exactly that and not approximately.
        bool same = true;
        int32_t firstBad = -1;
        for (size_t i = dsp::Oversampler::kLatency; i < in.size(); ++i) {
            if (l[i] != in[i - dsp::Oversampler::kLatency]) {
                same = false;
                if (firstBad < 0) firstBad = static_cast<int32_t>(i);
            }
        }
        ok("mix at nought is bit for bit the input, delayed by the latency", same,
           firstBad < 0 ? "" : "first differs at " + std::to_string(firstBad));
    }

    // And with it up, something happens - and stays finite.
    {
        fx.reset();
        fx.params().set(fx.params().indexOf("mix"), 1.0f);
        fx.params().set(fx.params().indexOf("drive"), 0.8f);
        fx.params().jumpAll();
        std::vector<float> l = in, r = in;
        double peak = 0.0;
        bool finite = true;
        for (size_t at = 0; at < in.size(); at += 64) {
            fx.run(l.data() + at, r.data() + at, 64, true);
            for (int32_t i = 0; i < 64; ++i) {
                finite = finite && std::isfinite(l[at + i]);
                peak = std::fabs(l[at + i]) > peak ? std::fabs(l[at + i]) : peak;
            }
        }
        ok("driven, it makes a sound and stays finite", finite && peak > 0.01 && peak < 4.0,
           "peak " + std::to_string(peak));
    }
}

} // namespace

int main() {
    printf("the amp's cabinet\n");
    sizeIsACabinetNotAToneControl();
    everyKnobIsMonotonic();
    theCabinetDoesNotRing();
    theLevelHoldsAcrossTheGrid();
    theToneStackInteracts();
    theToneStackIsAlwaysStable();
    sagDroopsAndRecovers();
    theWholeAmp();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
