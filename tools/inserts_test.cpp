// Tests the three inserts built from the machines' parts: Rotary, Grain and
// Resonator. Rendered, since a speaker turning, a cloud holding on and a
// string ringing in key don't show in a frequency response.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/effect/FromMachines.h>

using namespace acidulous;
using namespace acidulous::effect;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-58s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;
constexpr int32_t kBlock = 64;

template <typename Fx>
void setUnit(Fx &fx, const char *name, float value) {
    const int32_t i = fx.params().indexOf(name);
    if (i < 0) { printf("  FAIL no parameter named %s\n", name); ++failures; return; }
    fx.params().set(i, fx.params().def(i).unmap(value));
}

struct Stereo { std::vector<float> l, r; };

template <typename Fx>
Stereo run(Fx &fx, const std::vector<float> &in, float bpm = 120.0f) {
    Stereo s{in, in};
    for (size_t at = 0; at < in.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, in.size() - at));
        fx.onBlock(0, 0, bpm);
        fx.run(s.l.data() + at, s.r.data() + at, n, false);
    }
    return s;
}

std::vector<float> tone(float hz, float amp, size_t n) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; ++i) x[i] = amp * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    return x;
}

std::vector<float> noise(float amp, size_t n, uint32_t seed = 1) {
    std::vector<float> x(n);
    for (auto &v : x) {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        v = amp * (static_cast<float>(seed & 0xffffff) / 8388608.0f - 1.0f);
    }
    return x;
}

double rms(const std::vector<float> &x, size_t from, size_t to) {
    double s = 0.0;
    to = std::min(to, x.size());
    for (size_t i = from; i < to; ++i) s += double(x[i]) * x[i];
    return std::sqrt(s / double(std::max<size_t>(1, to - from)));
}

double peak(const std::vector<float> &x) {
    double p = 0.0;
    for (float v : x) p = std::max(p, double(std::fabs(v)));
    return p;
}

bool finite(const Stereo &s) {
    for (size_t i = 0; i < s.l.size(); ++i) if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) return false;
    return true;
}

std::string num(double v) { char b[32]; snprintf(b, sizeof b, "%.3f", v); return b; }

// --- Rotary ----------------------------------------------------------------------

/** How much the left channel's level swings over [x], in dB, measured in 10 ms windows. */
double swing(const std::vector<float> &x, size_t from) {
    double lo = 1e9, hi = 0.0;
    for (size_t at = from; at + 480 <= x.size(); at += 480) {
        const double r = rms(x, at, at + 480);
        lo = std::min(lo, r);
        hi = std::max(hi, r);
    }
    return 20.0 * std::log10(hi / std::max(lo, 1e-9));
}

void rotaryTurns() {
    printf("- rotary\n");
    const auto in = tone(1000.0f, 0.3f, 96000);
    Rotary brake, fast;
    for (Rotary *fx : {&brake, &fast}) {
        fx->prepare(static_cast<int32_t>(kRate));
        setUnit(*fx, "ramp", 0.05f);
    }
    setUnit(brake, "speed", 0.0f);
    setUnit(fast, "speed", 2.0f);
    brake.params().jumpAll();
    fast.params().jumpAll();
    const auto still = run(brake, in), turning = run(fast, in);
    ok("braked, the level holds still", swing(still.l, 48000) < 1.0, num(swing(still.l, 48000)) + " dB");
    ok("fast, it swings", swing(turning.l, 48000) > 2.0, num(swing(turning.l, 48000)) + " dB");
    ok("the two sides move apart", rms(turning.l, 48000, 96000) > 0.0 &&
       std::fabs(turning.l[60000] - turning.r[60000]) + std::fabs(turning.l[70000] - turning.r[70000]) > 1e-4);
    Rotary dry;
    dry.prepare(static_cast<int32_t>(kRate));
    setUnit(dry, "mix", 0.0f);
    dry.params().jumpAll();
    const auto through = run(dry, in);
    double worst = 0.0;
    for (size_t i = 0; i < in.size(); ++i) worst = std::max(worst, double(std::fabs(through.l[i] - in[i])));
    ok("mix 0 is the input", worst < 1e-6);
}

// --- Grain -----------------------------------------------------------------------

void grainClouds() {
    printf("- grain\n");
    // Two seconds of a tone, then three of silence.
    auto in = tone(330.0f, 0.4f, 96000);
    in.resize(96000 + 144000, 0.0f);
    Grain plain, frozen;
    for (Grain *fx : {&plain, &frozen}) {
        fx->prepare(static_cast<int32_t>(kRate));
        setUnit(*fx, "mix", 1.0f);
        setUnit(*fx, "spray", 0.1f);
    }
    plain.params().jumpAll();
    const auto a = run(plain, in);
    ok("it makes a cloud of what it hears", rms(a.l, 48000, 96000) > 0.05, num(rms(a.l, 48000, 96000)));
    ok("...which dies once the sound has passed through it", rms(a.l, 230000, 240000) < 0.002, num(rms(a.l, 230000, 240000)));
    // Frozen after a second of listening: it keeps playing what it has.
    std::vector<float> first(in.begin(), in.begin() + 48000), rest(in.begin() + 48000, in.end());
    frozen.params().jumpAll();
    run(frozen, first);
    setUnit(frozen, "freeze", 1.0f);
    frozen.params().jumpAll();
    const auto held = run(frozen, rest);
    ok("frozen, it holds on long after the sound stops", rms(held.l, 170000, 190000) > 0.05, num(rms(held.l, 170000, 190000)));

    Grain wild;
    wild.prepare(static_cast<int32_t>(kRate));
    setUnit(wild, "feedback", 0.9f);
    setUnit(wild, "density", 100.0f);
    setUnit(wild, "size", 300.0f);
    setUnit(wild, "reverse", 0.5f);
    setUnit(wild, "scatter", 1.0f);
    setUnit(wild, "mix", 1.0f);
    wild.params().jumpAll();
    const auto loud = run(wild, noise(0.8f, 480000));
    ok("at full feedback it stays finite and bounded", finite(loud) && peak(loud.l) < 4.0, "peak " + num(peak(loud.l)));
    Grain dry;
    dry.prepare(static_cast<int32_t>(kRate));
    setUnit(dry, "mix", 0.0f);
    dry.params().jumpAll();
    const auto through = run(dry, in);
    double worst = 0.0;
    for (size_t i = 0; i < in.size(); ++i) worst = std::max(worst, double(std::fabs(through.l[i] - in[i])));
    ok("mix 0 is the input", worst < 1e-6);
}

// --- Resonator ---------------------------------------------------------------------

/** How long a resonator rings after a half-second tone at [hz] stops: its level 0.5 s later. */
double ringAfter(float hz) {
    Resonator fx;
    fx.prepare(static_cast<int32_t>(kRate));
    setUnit(fx, "key", 0.0f);   // C
    setUnit(fx, "scale", 0.0f); // major
    setUnit(fx, "mix", 1.0f);
    fx.params().jumpAll();
    auto in = tone(hz, 0.4f, 24000);
    in.resize(72000, 0.0f);
    const auto out = run(fx, in);
    return rms(out.l, 48000, 52800);
}

void resonatorRingsInKey() {
    printf("- resonator\n");
    const double inKey = ringAfter(261.63f);  // C4, a string of C major
    const double outOfKey = ringAfter(277.18f); // C#4, between two of them
    ok("a note in the key sets it ringing", inKey > 0.003, num(inKey));
    ok("...several times more than a note out of it", inKey > outOfKey * 3.0, num(inKey) + " vs " + num(outOfKey));

    Resonator longer, shorter;
    for (Resonator *fx : {&longer, &shorter}) {
        fx->prepare(static_cast<int32_t>(kRate));
        setUnit(*fx, "mix", 1.0f);
    }
    setUnit(longer, "decay", 8.0f);
    setUnit(shorter, "decay", 0.5f);
    longer.params().jumpAll();
    shorter.params().jumpAll();
    auto in = noise(0.3f, 4800);
    in.resize(96000, 0.0f);
    const auto a = run(longer, in), b = run(shorter, in);
    ok("a longer decay rings longer", rms(a.l, 72000, 96000) > rms(b.l, 72000, 96000) * 10.0,
       num(rms(a.l, 72000, 96000)) + " vs " + num(rms(b.l, 72000, 96000)));

    Resonator metal;
    metal.prepare(static_cast<int32_t>(kRate));
    setUnit(metal, "metal", 1.0f);
    setUnit(metal, "decay", 12.0f);
    setUnit(metal, "strings", 16.0f);
    setUnit(metal, "mix", 1.0f);
    metal.params().jumpAll();
    const auto loud = run(metal, noise(0.9f, 240000));
    ok("driven hard for five seconds it stays bounded", finite(loud) && peak(loud.l) < 4.0, "peak " + num(peak(loud.l)));
}

} // namespace

int main() {
    printf("inserts from the machines\n");
    rotaryTurns();
    grainClouds();
    resonatorRingsInKey();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
