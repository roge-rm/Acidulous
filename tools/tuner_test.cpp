// Tests the tuner: does it name the right note, and the right number of cents.
//
// It needs to be right to a cent or two. The main fault to catch is reading
// an octave out: autocorrelation at twice the true period is nearly as strong
// as at the true one, and stronger on a string with a weak fundamental.
//
// None of the test signals are sines. A sine has no harmonics, decay,
// inharmonicity or noise, so a broken detector looks perfect on it.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/core/Tuner.h>

using namespace acidulous;
using namespace acidulous::audio;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-46s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;

float cents(float got, float want) { return 1200.0f * std::log2(got / want); }

struct Rng {
    uint32_t s = 0x7c1d9e3bu;
    float next() { s = s * 1664525u + 1013904223u; return static_cast<float>(s >> 8) / 8388608.0f - 1.0f; }
};

/**
 * A plucked string, deliberately awkward.
 *
 * [weakFundamental] is the bridge-pickup case, where the first harmonic is
 * buried under the second and third and a naive detector reports an octave
 * down.
 */
std::vector<float> pluck(float hz, float seconds, bool weakFundamental = false,
                         float noise = 0.0f, float startAt = 0.0f) {
    std::vector<float> x(static_cast<size_t>(seconds * kRate), 0.0f);
    Rng rng;
    for (size_t i = 0; i < x.size(); ++i) {
        const double t = static_cast<double>(i) / kRate - startAt;
        double v = 0.0;
        if (t >= 0.0) {
            for (int h = 1; h <= 10; ++h) {
                // Real strings are stiff, so the partials stretch above exact
                // multiples, by about this much on a wound string. A detector
                // that assumes exact multiples reads a steady note as drifting.
                const double stretch = std::sqrt(1.0 + 0.00012 * h * h);
                const double f = hz * h * stretch;
                if (f > 18000.0) break;
                double amp = 0.9 / h;
                if (weakFundamental && h == 1) amp *= 0.06;
                if (weakFundamental && h == 2) amp *= 1.8;
                v += amp * std::exp(-t * (1.1 + 0.45 * h)) * std::sin(2.0 * M_PI * f * t + 0.7 * h);
            }
        }
        x[i] = static_cast<float>(v * 0.3) + noise * rng.next();
    }
    return x;
}

void everyStringOnTheInstrument() {
    printf("- the open strings, and the ends of the range\n");
    struct { const char *name; float hz; } notes[] = {
        {"bass low B", 30.87f}, {"bass E", 41.20f},
        {"guitar low E", 82.41f}, {"A", 110.00f}, {"D", 146.83f},
        {"G", 196.00f}, {"B", 246.94f}, {"top E", 329.63f},
        {"A4", 440.00f}, {"E6, 24th fret", 1318.51f},
    };
    for (const auto &n : notes) {
        const auto x = pluck(n.hz, 0.6f);
        float clarity = 0.0f;
        const float got = PitchFinder::find(x.data(), static_cast<int32_t>(x.size()), kRate, &clarity);
        const float err = got > 0.0f ? cents(got, n.hz) : 1e9f;
        char label[64];
        snprintf(label, sizeof(label), "%s (%.2f Hz)", n.name, n.hz);
        ok(label, std::fabs(err) < 0.5f,
           got > 0.0f ? std::string("read ") + std::to_string(got) + " Hz, " + std::to_string(err) + " cents"
                      : "read nothing");
    }
}

void itSaysHowFarOut() {
    printf("- how far out, in cents\n");
    for (float want : {-48.0f, -14.0f, -3.0f, 3.0f, 14.0f, 48.0f}) {
        const float hz = 196.0f * std::pow(2.0f, want / 1200.0f); // a G string, mistuned
        const auto x = pluck(hz, 0.6f);
        const float got = PitchFinder::find(x.data(), static_cast<int32_t>(x.size()), kRate);
        const float err = got > 0.0f ? cents(got, 196.0f) - want : 1e9f;
        char label[64];
        snprintf(label, sizeof(label), "%+.0f cents reads as that", want);
        ok(label, std::fabs(err) < 0.5f, std::string("off by ") + std::to_string(err) + " cents");
    }
}

void noOctaveErrors() {
    printf("- the octave, which is the fault that matters\n");
    // A bridge pickup on a bass: the fundamental is 24 dB under the second
    // harmonic. Picking the strongest correlation would report an octave down.
    for (float hz : {41.20f, 82.41f, 110.0f, 220.0f}) {
        const auto x = pluck(hz, 0.6f, true);
        const float got = PitchFinder::find(x.data(), static_cast<int32_t>(x.size()), kRate);
        const float err = got > 0.0f ? cents(got, hz) : 1e9f;
        char label[64];
        snprintf(label, sizeof(label), "%.2f Hz with almost no fundamental", hz);
        ok(label, std::fabs(err) < 1.5f,
           got > 0.0f ? std::string("read ") + std::to_string(got) + " Hz (" + std::to_string(err / 1200.0f) + " octaves off)"
                      : "read nothing");
    }
}

void itSaysNothingWhenThereIsNothing() {
    printf("- when there is no note\n");
    {
        std::vector<float> silence(24000, 0.0f);
        ok("silence names no note",
           PitchFinder::find(silence.data(), static_cast<int32_t>(silence.size()), kRate) == 0.0f);
    }
    {
        // Room noise, at a level the tuner would otherwise accept.
        std::vector<float> hiss(24000);
        Rng rng;
        for (auto &v : hiss) v = 0.05f * rng.next();
        const float got = PitchFinder::find(hiss.data(), static_cast<int32_t>(hiss.size()), kRate);
        ok("noise names no note", got == 0.0f,
           got > 0.0f ? std::string("named ") + std::to_string(got) + " Hz" : "");
    }
    {
        // A hand across muted strings: loud, and not periodic.
        std::vector<float> muted(24000);
        Rng rng;
        for (size_t i = 0; i < muted.size(); ++i) {
            const double t = static_cast<double>(i) / kRate;
            muted[i] = static_cast<float>(0.4 * std::exp(-t * 40.0)) * rng.next();
        }
        const float got = PitchFinder::find(muted.data(), static_cast<int32_t>(muted.size()), kRate);
        ok("a muted strum names no note", got == 0.0f,
           got > 0.0f ? std::string("named ") + std::to_string(got) + " Hz" : "");
    }
}

void itWorksOnADyingNote() {
    printf("- a note that is already dying\n");
    // Half a second after the pluck, when people actually look at a tuner:
    // the transient is gone and the string is quiet.
    const auto x = pluck(110.0f, 1.2f, false, 0.0008f);
    const int32_t from = static_cast<int32_t>(0.55f * kRate);
    const int32_t n = static_cast<int32_t>(x.size()) - from;
    float clarity = 0.0f;
    const float got = PitchFinder::find(x.data() + from, n, kRate, &clarity);
    const float err = got > 0.0f ? cents(got, 110.0f) : 1e9f;
    ok("still right half a second in", std::fabs(err) < 1.0f,
       std::string("read ") + std::to_string(got) + " Hz, " + std::to_string(err) + " cents, clarity " +
           std::to_string(clarity));
}

void theRingHandsOverAWindow() {
    printf("- the ring, filled a block at a time\n");
    Tuner tuner;
    tuner.setEnabled(true);
    const auto mono = pluck(146.83f, 1.0f);
    std::vector<float> block(128 * 2);
    for (size_t at = 0; at + 128 <= mono.size(); at += 128) {
        for (int32_t i = 0; i < 128; ++i) {
            block[static_cast<size_t>(i) * 2] = mono[at + i];
            block[static_cast<size_t>(i) * 2 + 1] = mono[at + i];
        }
        tuner.push(block.data(), 128);
    }
    const float got = tuner.analyse(kRate);
    ok("a D string pushed through the ring", got > 0.0f && std::fabs(cents(got, 146.83f)) < 1.0f,
       std::string("read ") + std::to_string(got) + " Hz");

    tuner.setEnabled(false);
    ok("switched off, it says nothing", tuner.analyse(kRate) == 0.0f && tuner.hz() == 0.0f);
}

void itCostsNothingUntilAsked() {
    printf("- off by default\n");
    Tuner tuner;
    ok("a new tuner is off", !tuner.isEnabled());
    std::vector<float> block(256 * 2, 0.5f);
    tuner.push(block.data(), 256);
    ok("and pushing into it while off does nothing", tuner.analyse(kRate) == 0.0f);
}

/**
 * What one reading costs.
 *
 * The low note is the slow case, where the coarse stage searches every lag
 * out to 444 and the fine stage has the longest periods. It's about 1.1 ms on
 * this machine at -O2.
 *
 * The harness builds with sanitisers at -O1, so the printed figure is several
 * times the real one. The ceiling only catches an order-of-magnitude
 * regression. The reading is polled off the drawing thread anyway.
 */
void itIsCheapEnoughToPoll() {
    printf("- the cost of one reading\n");
    const auto low = pluck(30.87f, 0.6f);
    const auto high = pluck(440.0f, 0.6f);
    for (const auto *x : {&low, &high}) {
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < 20; ++i) PitchFinder::find(x->data(), static_cast<int32_t>(x->size()), kRate);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 20.0;
        ok(x == &low ? "a low B stays well under the old cost" : "an A4 stays well under the old cost",
           ms < 4.0, std::string(std::to_string(ms)) + " ms a reading, sanitised");
    }
}

} // namespace

int main() {
    printf("the tuner\n");
    everyStringOnTheInstrument();
    itSaysHowFarOut();
    noOctaveErrors();
    itSaysNothingWhenThereIsNothing();
    itWorksOnADyingNote();
    theRingHandsOverAWindow();
    itCostsNothingUntilAsked();
    itIsCheapEnoughToPoll();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
