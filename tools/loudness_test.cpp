// Loudness against the numbers the standard publishes.
//
// A meter that reads a figure nobody can check is a meter nobody should mix
// to, so these are the EBU's own test signals (Tech 3341) and what they must
// read, to a tenth of a LU.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/dsp/Loudness.h>

using acidulous::dsp::Loudness;

namespace {
int checks = 0, failures = 0;
constexpr float kSr = 48000.0f;

void ok(const char *what, bool cond, const std::string &detail) {
    ++checks;
    printf("  %s %-58s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

/** A stereo 1 kHz sine at [dbfs] for [seconds], into [m]; phase carried across calls. */
void sine(Loudness &m, float dbfs, float seconds, double &phase, float hz = 1000.0f, bool rightToo = true) {
    const float amp = std::pow(10.0f, dbfs / 20.0f);
    std::vector<float> L(64), R(64);
    const int64_t frames = static_cast<int64_t>(seconds * kSr);
    for (int64_t done = 0; done < frames; done += 64) {
        for (int i = 0; i < 64; ++i) {
            L[i] = amp * static_cast<float>(std::sin(phase));
            R[i] = rightToo ? L[i] : 0.0f;
            phase += 2.0 * 3.141592653589793 * hz / kSr;
        }
        m.process(L.data(), R.data(), 64);
    }
}

std::string lu(float v) { char b[32]; snprintf(b, sizeof b, "%.2f", v); return b; }
} // namespace

int main() {
    printf("\nloudness, against EBU Tech 3341\n\n");
    Loudness m;
    double ph = 0.0;

    m.prepare(kSr);
    sine(m, -23.0f, 20.0f, ph);
    ok("1 kHz stereo at -23 dBFS reads -23.0 LUFS integrated", std::fabs(m.integrated() + 23.0f) <= 0.1f, lu(m.integrated()));
    ok("and -23.0 momentary", std::fabs(m.momentary() + 23.0f) <= 0.1f, lu(m.momentary()));
    ok("and -23.0 short-term", std::fabs(m.shortTerm() + 23.0f) <= 0.1f, lu(m.shortTerm()));

    m.reset();
    sine(m, -33.0f, 20.0f, ph);
    ok("at -33 dBFS, -33.0", std::fabs(m.integrated() + 33.0f) <= 0.1f, lu(m.integrated()));

    // Case 3 of Tech 3341: quiet, loud, quiet - the quiet parts are more than
    // 10 LU under and the relative gate leaves them out.
    m.reset();
    sine(m, -36.0f, 10.0f, ph);
    sine(m, -23.0f, 60.0f, ph);
    sine(m, -36.0f, 10.0f, ph);
    ok("-36, -23, -36: the relative gate leaves the quiet out", std::fabs(m.integrated() + 23.0f) <= 0.1f, lu(m.integrated()));

    // Silence is under the absolute gate, so a pause does not drag it down.
    m.reset();
    sine(m, -23.0f, 10.0f, ph);
    sine(m, -200.0f, 10.0f, ph);
    ok("a pause after it does not pull the figure down", std::fabs(m.integrated() + 23.0f) <= 0.1f, lu(m.integrated()));

    // One channel is half the energy: 3 LU down.
    m.reset();
    sine(m, -23.0f, 10.0f, ph, 1000.0f, false);
    ok("the same sine in one channel is 3 LU quieter", std::fabs(m.integrated() + 26.01f) <= 0.1f, lu(m.integrated()));

    // True peak: a full-scale sine at a quarter of the rate, started at 45
    // degrees, is sampled at +-0.707 every time - a sample peak of -3 dB
    // for a waveform that reaches 0.
    m.reset();
    ph = 3.141592653589793 / 4.0;
    sine(m, 0.0f, 1.0f, ph, kSr / 4.0f);
    ok("a peak between the samples is found: ~0 dBTP", std::fabs(m.truePeakDb()) <= 0.3f, lu(m.truePeakDb()) + " dBTP");

    // And a meter that has heard nothing says so.
    m.reset();
    ok("silence reads as silence", m.integrated() <= Loudness::kSilent, lu(m.integrated()));

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
