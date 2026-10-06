// Tests Swell, the upward compressor: that it's transparent at nothing, that
// its bands add back to the input, how far it brings a sound toward the
// ceiling, that it leaves sound under the floor alone, that the look-ahead
// keeps peaks at the ceiling, and that splitting brings up a quiet band on
// its own. All of it rendered.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/effect/Effects.h>

using namespace acidulous;
using namespace acidulous::effect;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-56s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;
constexpr int32_t kBlock = 64;
constexpr int kLatency = dsp::Swell::kLook;

double dB(double v) { return 20.0 * std::log10(v + 1e-20); }
std::string num(double v) { char b[32]; snprintf(b, sizeof b, "%.2f", v); return b; }

void setUnit(Swell &fx, const char *name, float value) {
    const int32_t i = fx.params().indexOf(name);
    if (i < 0) { printf("  FAIL no parameter named %s\n", name); ++failures; return; }
    fx.params().set(i, fx.params().def(i).unmap(value));
}

/** A Swell with everything named, so no test depends on a default. */
Swell make(float floor, float ceiling, float amount, float split, float releaseMs, float mix = 1.0f) {
    Swell fx;
    fx.prepare(static_cast<int32_t>(kRate));
    setUnit(fx, "floor", floor);
    setUnit(fx, "ceiling", ceiling);
    setUnit(fx, "amount", amount);
    setUnit(fx, "split", split);
    setUnit(fx, "release", releaseMs);
    setUnit(fx, "mix", mix);
    setUnit(fx, "gain", 0.0f);
    fx.params().jumpAll();
    return fx;
}

struct Stereo { std::vector<float> l, r; };

Stereo run(Swell &fx, const std::vector<float> &inL, const std::vector<float> &inR) {
    Stereo s{inL, inR};
    for (size_t at = 0; at < inL.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, inL.size() - at));
        fx.run(s.l.data() + at, s.r.data() + at, n, true);
    }
    return s;
}
std::vector<float> run(Swell &fx, const std::vector<float> &in) { return run(fx, in, in).l; }

std::vector<float> tone(float hz, float amp, float seconds) {
    std::vector<float> x(static_cast<size_t>(seconds * kRate));
    for (size_t i = 0; i < x.size(); ++i) x[i] = amp * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    return x;
}

std::vector<float> noise(float amp, size_t n, uint32_t seed) {
    std::vector<float> x(n);
    for (auto &v : x) {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        v = amp * (static_cast<float>(seed & 0xffffff) / 8388608.0f - 1.0f);
    }
    return x;
}

float peakOver(const std::vector<float> &x, size_t from, size_t to) {
    float p = 0.0f;
    for (size_t i = from; i < to && i < x.size(); ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

/** How loud one frequency is in a signal, by correlating against it. */
double levelAt(const std::vector<float> &x, float hz, size_t from) {
    double re = 0.0, im = 0.0;
    for (size_t i = from; i < x.size(); ++i) {
        const double ph = 2.0 * M_PI * hz * i / kRate;
        re += x[i] * std::cos(ph);
        im += x[i] * std::sin(ph);
    }
    const double n = static_cast<double>(x.size() - from);
    return 2.0 * std::sqrt(re * re + im * im) / n;
}

double worstDifference(const std::vector<float> &out, const std::vector<float> &in, int delay) {
    double worst = 0.0;
    for (size_t i = delay; i < out.size(); ++i) worst = std::max(worst, std::fabs(double(out[i]) - in[i - delay]));
    return worst;
}

// --- the numbers ------------------------------------------------------------

void nothingIsNothing() {
    printf("- at nothing, it's a short delay\n");
    const auto l = noise(0.5f, 24000, 1), r = noise(0.5f, 24000, 2);
    {
        Swell fx = make(-40.0f, -3.0f, 0.0f, 1.0f, 150.0f);
        const auto out = run(fx, l, r);
        const double d = std::max(worstDifference(out.l, l, kLatency), worstDifference(out.r, r, kLatency));
        ok("amount 0: the input, eight samples late", d < 1e-5, "worst " + std::to_string(d));
    }
    {
        // Split into three bands and put back together: the bands sum to the input.
        Swell fx = make(-40.0f, -3.0f, 0.0f, 1.0f, 150.0f);
        const auto out = run(fx, l);
        ok("three bands add back to the input exactly", worstDifference(out, l, kLatency) < 1e-5);
    }
    {
        Swell fx = make(-40.0f, -3.0f, 1.0f, 1.0f, 150.0f, 0.0f);
        const auto out = run(fx, l);
        ok("mix 0: the dry signal, lined up", worstDifference(out, l, kLatency) < 1e-6);
    }
    {
        Swell fx = make(-40.0f, -3.0f, 0.0f, 0.0f, 150.0f);
        std::vector<float> click(64, 0.0f);
        click[0] = 1.0f;
        const auto out = run(fx, click);
        int at = -1;
        for (int i = 0; i < 64; ++i) if (std::fabs(out[i]) > 0.5f) { at = i; break; }
        ok("the latency is eight samples", at == kLatency, "click came out at " + std::to_string(at));
    }
}

void itPullsTowardTheCeiling() {
    printf("- above the floor, toward the ceiling\n");
    const auto quiet = tone(440.0f, static_cast<float>(std::pow(10.0, -30.0 / 20.0)), 1.5f); // -30 dB peak
    {
        Swell fx = make(-50.0f, -3.0f, 1.0f, 0.0f, 1000.0f);
        const double out = dB(peakOver(run(fx, quiet), 48000, 72000));
        ok("all the way: a -30 dB tone comes up to the ceiling", std::fabs(out + 3.0) < 1.0, num(out) + " dB");
    }
    {
        Swell fx = make(-50.0f, -3.0f, 0.5f, 0.0f, 1000.0f);
        const double out = dB(peakOver(run(fx, quiet), 48000, 72000));
        ok("half way: it comes halfway, in dB", std::fabs(out + 16.5) < 1.0, num(out) + " dB");
    }
    {
        const auto loud = tone(440.0f, 1.0f, 1.5f);
        Swell fx = make(-50.0f, -6.0f, 1.0f, 0.0f, 1000.0f);
        const double out = dB(peakOver(run(fx, loud), 48000, 72000));
        ok("a tone over the ceiling comes down to it", std::fabs(out + 6.0) < 1.0, num(out) + " dB");
    }
}

void underTheFloorIsLeftAlone() {
    printf("- under the floor, nothing\n");
    const auto soft = tone(440.0f, static_cast<float>(std::pow(10.0, -70.0 / 20.0)), 1.0f);
    for (float split : {0.0f, 1.0f}) {
        Swell fx = make(-40.0f, -3.0f, 1.0f, split, 150.0f);
        const auto out = run(fx, soft);
        const double d = worstDifference(out, soft, kLatency);
        ok(split == 0.0f ? "a tone well under the floor is untouched" : "...and split into bands, still untouched",
           d < 1e-7, "worst " + std::to_string(d));
    }
    {
        Swell fx = make(-40.0f, -3.0f, 1.0f, 1.0f, 150.0f);
        const auto out = run(fx, std::vector<float>(24000, 0.0f));
        ok("silence stays silent", peakOver(out, 0, out.size()) == 0.0f);
    }
}

void peaksAreCaughtBeforeTheyArrive() {
    printf("- the look-ahead\n");
    // Quiet, then suddenly loud: the gain built up for the quiet part must be
    // gone before the loud part comes out.
    auto x = tone(440.0f, 0.01f, 1.0f);
    const auto loud = tone(440.0f, 1.0f, 0.5f);
    x.insert(x.end(), loud.begin(), loud.end());
    Swell fx = make(-60.0f, -3.0f, 1.0f, 0.0f, 2000.0f);
    const auto out = run(fx, x);
    const double over = dB(peakOver(out, 48000, out.size()));
    ok("a sudden peak after a quiet part stays near the ceiling", over < -3.0 + 1.5, num(over) + " dB");
}

void splitBringsUpAQuietBand() {
    printf("- split\n");
    // A loud low tone with a quiet high one. One band: the low tone sets the
    // gain and the high one stays where it was relative to it. Three bands:
    // the high one is brought up on its own.
    auto low = tone(100.0f, 0.5f, 1.5f), high = tone(8000.0f, 0.01f, 1.5f);
    std::vector<float> x(low.size());
    for (size_t i = 0; i < x.size(); ++i) x[i] = low[i] + high[i];
    Swell one = make(-60.0f, -3.0f, 1.0f, 0.0f, 500.0f);
    Swell three = make(-60.0f, -3.0f, 1.0f, 1.0f, 500.0f);
    const double a = dB(levelAt(run(one, x), 8000.0f, 48000));
    const double b = dB(levelAt(run(three, x), 8000.0f, 48000));
    ok("three bands bring the quiet high tone up", b > a + 6.0, num(a) + " -> " + num(b) + " dB");
    ok("...but no further than the spread allows", b < a + 9.0 + 1.0, num(b - a) + " dB more");
}

void resetForgets() {
    printf("- reset\n");
    Swell fx = make(-60.0f, -3.0f, 1.0f, 1.0f, 2000.0f);
    run(fx, tone(440.0f, 0.001f, 0.5f)); // builds up a big boost
    fx.reset();
    std::vector<float> click(64, 0.0f);
    click[0] = 0.5f;
    const auto out = run(fx, click);
    ok("after reset nothing is left in the delay", std::fabs(out[0]) < 1e-9f);
}

} // namespace

int main() {
    printf("swell\n");
    nothingIsNothing();
    itPullsTowardTheCeiling();
    underTheFloorIsLeftAlone();
    peaksAreCaughtBeforeTheyArrive();
    splitBringsUpAQuietBand();
    resetForgets();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
