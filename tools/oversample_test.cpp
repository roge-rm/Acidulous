// Does running a nonlinearity at twice the rate actually buy anything?
//
// The question is not academic: the engine already had an oversampled
// distortion whose upsampler was a linear-interpolated midpoint, and the point
// of this harness is to put a number on how much that buys against a proper
// halfband - because an amp has two nonlinear stages and a speaker filter after
// them that hides none of the folding.
//
// **The measurement.** A 7 kHz sine into a saturator. Its seventh harmonic is
// 49 kHz; at 48 kHz that folds to 1 kHz, and *nothing legitimate* puts energy
// at 1 kHz from a 7 kHz input. So the 1 kHz bin is aliasing and only aliasing.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/dsp/Biquad.h>
#include <engine/dsp/Math.h>
#include <engine/dsp/Oversampler.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-52s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int32_t kRate = 48000;
constexpr int32_t kN = 48000;

/** One bin of a DFT, which is all this needs. */
double goertzel(const std::vector<float> &x, double hz, int32_t from) {
    const double w = 2.0 * M_PI * hz / kRate;
    const double c = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int32_t i = from; i < static_cast<int32_t>(x.size()); ++i) {
        const double s = x[static_cast<size_t>(i)] + c * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    const double n = x.size() - from;
    return std::sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) * 2.0 / n;
}

double dB(double v) { return 20.0 * std::log10(v + 1e-20); }

/** The stage an amp's preamp is: a tanh hard enough to make harmonics. */
float saturate(float x) { return dsp::fastTanh(x * 6.0f); }

std::vector<float> input() {
    std::vector<float> x(static_cast<size_t>(kN));
    for (int32_t i = 0; i < kN; ++i) {
        x[static_cast<size_t>(i)] = 0.5f * std::sin(2.0 * M_PI * 7000.0 * i / kRate);
    }
    return x;
}

/** Straight through, at the base rate: every fold lands where it lands. */
std::vector<float> plain(const std::vector<float> &in) {
    std::vector<float> out(in.size());
    for (size_t i = 0; i < in.size(); ++i) out[i] = saturate(in[i]);
    return out;
}

/** Distortion's scheme: one linear-interpolated midpoint, a biquad to decimate. */
std::vector<float> linearTwice(const std::vector<float> &in) {
    std::vector<float> out(in.size());
    dsp::Biquad half;
    half.lowpass(19000.0f, 0.707f, kRate * 2.0f);
    float prev = 0.0f;
    for (size_t i = 0; i < in.size(); ++i) {
        const float mid = (prev + in[i]) * 0.5f;
        half.process(saturate(mid));
        out[i] = half.process(saturate(in[i]));
        prev = in[i];
    }
    return out;
}

/** A halfband either way, a block at a time, as the amp will use it. */
std::vector<float> halfband(const std::vector<float> &in) {
    dsp::Oversampler os;
    os.prepare();
    std::vector<float> out(in.size());
    float up[128], work[128];
    for (size_t at = 0; at < in.size(); at += 64) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(64, in.size() - at));
        os.up(in.data() + at, n, up);
        for (int32_t i = 0; i < n * 2; ++i) work[i] = saturate(up[i]);
        os.down(work, n, out.data() + at);
    }
    return out;
}

} // namespace

int main() {
    printf("oversampling: what it costs and what it buys\n");
    const auto in = input();

    // Past the filters' startup, so the measurement is of the steady state.
    const int32_t from = kRate / 4;
    const double a1 = goertzel(plain(in), 1000.0, from);
    const double a2 = goertzel(linearTwice(in), 1000.0, from);
    const double a4 = goertzel(halfband(in), 1000.0, from);

    printf("  7 kHz saturated, energy folded into 1 kHz:\n");
    printf("    at the base rate        %7.1f dB\n", dB(a1));
    printf("    2x, linear midpoint     %7.1f dB   (%.1f dB better)\n", dB(a2), dB(a1) - dB(a2));
    printf("    2x, 31-tap halfband     %7.1f dB   (%.1f dB better)\n", dB(a4), dB(a1) - dB(a4));

    ok("2x with a halfband kills the fold", dB(a1) - dB(a4) > 40.0,
       std::to_string(dB(a1) - dB(a4)) + " dB better");
    ok("and beats the linear midpoint by a wide margin", dB(a2) - dB(a4) > 20.0,
       std::to_string(dB(a2) - dB(a4)) + " dB better");

    // The other half of the claim: it must not damage what it passes.
    {
        std::vector<float> quiet(static_cast<size_t>(kN));
        for (int32_t i = 0; i < kN; ++i) {
            quiet[static_cast<size_t>(i)] = 0.1f * std::sin(2.0 * M_PI * 1000.0 * i / kRate);
        }
        dsp::Oversampler os;
        os.prepare();
        std::vector<float> out(quiet.size());
        float up[128];
        for (size_t at = 0; at < quiet.size(); at += 64) {
            const int32_t n = static_cast<int32_t>(std::min<size_t>(64, quiet.size() - at));
            os.up(quiet.data() + at, n, up);
            os.down(up, n, out.data() + at);
        }
        const double got = goertzel(out, 1000.0, from);
        const double want = goertzel(quiet, 1000.0, from);
        ok("a round trip with nothing in between is transparent",
           std::fabs(dB(got) - dB(want)) < 0.2,
           std::to_string(dB(got) - dB(want)) + " dB");

        // And the latency is what it says, because the dry path is delayed by
        // exactly this and being wrong makes `mix` a comb filter.
        double best = 1e9;
        int32_t bestAt = -1;
        for (int32_t d = 0; d <= 24; ++d) {
            double err = 0.0;
            for (int32_t i = 2000; i < 3000; ++i) {
                const double e = out[static_cast<size_t>(i)] - quiet[static_cast<size_t>(i - d)];
                err += e * e;
            }
            if (err < best) { best = err; bestAt = d; }
        }
        ok("the latency is the constant it advertises", bestAt == dsp::Oversampler::kLatency,
           "measured " + std::to_string(bestAt) + ", says " +
               std::to_string(dsp::Oversampler::kLatency));
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
