// Tests the character effects: Smash, Acid, Mouth, Tape, Slicer, Horn,
// Spectral and Formula. Each is rendered and measured for the thing it's
// for, since none of it shows in a frequency response.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <engine/effect/Effect.h>
#include <engine/effect/EffectRegistry.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-60s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;
constexpr int32_t kBlock = 64;
/** Ticks a beat, as the engine counts them. */
constexpr int64_t kTicksPerBeat = 240;

std::string num(double v) { char b[32]; snprintf(b, sizeof b, "%.3f", v); return b; }
double dB(double v) { return 20.0 * std::log10(v + 1e-20); }

/** An effect by name, prepared, with the given parameters in their own units. */
std::unique_ptr<Effect> make(const char *type, std::initializer_list<std::pair<const char *, float>> set = {}) {
    std::unique_ptr<Effect> fx(EffectRegistry::create(type));
    if (!fx) { printf("  FAIL no effect called %s\n", type); ++failures; return nullptr; }
    fx->prepare(static_cast<int32_t>(kRate));
    for (const auto &[name, value] : set) {
        const int32_t i = fx->params().indexOf(name);
        if (i < 0) { printf("  FAIL %s has no parameter %s\n", type, name); ++failures; continue; }
        fx->params().set(i, fx->params().def(i).unmap(value));
    }
    fx->params().jumpAll();
    return fx;
}

void set(Effect &fx, const char *name, float value) {
    const int32_t i = fx.params().indexOf(name);
    if (i < 0) { printf("  FAIL no parameter %s\n", name); ++failures; return; }
    fx.params().set(i, fx.params().def(i).unmap(value));
    fx.params().jumpAll();
}

struct Stereo { std::vector<float> l, r; };

/** Runs a mono signal through in blocks at [bpm], the transport counting from 0. */
Stereo run(Effect &fx, const std::vector<float> &in, float bpm = 120.0f, size_t from = 0) {
    Stereo s{in, in};
    const double ticksPerSample = kTicksPerBeat * bpm / 60.0 / kRate;
    for (size_t at = 0; at < in.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, in.size() - at));
        const auto t0 = static_cast<int64_t>((from + at) * ticksPerSample);
        const auto t1 = static_cast<int64_t>((from + at + n) * ticksPerSample);
        fx.onBlock(t0, t1, bpm);
        fx.run(s.l.data() + at, s.r.data() + at, n, false);
    }
    return s;
}

std::vector<float> tone(float hz, float amp, size_t n) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; ++i) x[i] = amp * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    return x;
}

std::vector<float> saw(float hz, float amp, size_t n) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; ++i) x[i] = amp * (2.0f * std::fmod(hz * static_cast<float>(i) / kRate, 1.0f) - 1.0f);
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

double worst(const std::vector<float> &a, const std::vector<float> &b) {
    double w = 0.0;
    for (size_t i = 0; i < std::min(a.size(), b.size()); ++i) w = std::max(w, double(std::fabs(a[i] - b[i])));
    return w;
}

/** Every effect, driven hard with every knob at its top for five seconds, stays finite and bounded. */
void staysBounded(const char *type) {
    auto fx = make(type);
    if (!fx) return;
    for (int32_t i = 0; i < fx->params().size(); ++i) {
        if (std::strcmp(fx->params().def(i).name, "gain") == 0) continue;
        fx->params().set(i, 1.0f);
    }
    fx->params().jumpAll();
    const auto out = run(*fx, noise(0.9f, 240000));
    ok(std::string(type) + ": every knob up, it stays finite and bounded", finite(out) && peak(out.l) < 8.0,
       "peak " + num(peak(out.l)));
}

// --- Smash ----------------------------------------------------------------------

void smash() {
    printf("- smash\n");
    const auto music = noise(0.3f, 48000);
    {
        auto fx = make("Smash", {{"depth", 0.0f}});
        ok("depth 0 is the track untouched", worst(run(*fx, music).l, music) < 1e-6);
    }
    {
        auto fx = make("Smash", {{"up", 0.0f}, {"down", 0.0f}});
        ok("both ways off, the bands add back to the track", worst(run(*fx, music).l, music) < 1e-5);
    }
    {
        // A quiet tone under the threshold comes up toward it.
        const auto quiet = tone(1000.0f, 0.01f, 96000); // -40 dB
        auto fx = make("Smash", {{"threshold", -20.0f}});
        const double before = dB(rms(quiet, 48000, 96000)), after = dB(rms(run(*fx, quiet).l, 48000, 96000));
        ok("a quiet tone is pulled up toward the threshold", after > before + 10.0, num(before) + " -> " + num(after) + " dB");
    }
    {
        const auto loud = tone(1000.0f, 0.9f, 96000);
        auto fx = make("Smash", {{"threshold", -20.0f}});
        const double before = dB(rms(loud, 48000, 96000)), after = dB(rms(run(*fx, loud).l, 48000, 96000));
        ok("a loud tone is pushed down toward it", after < before - 10.0, num(before) + " -> " + num(after) + " dB");
    }
    {
        // Hiss far under the floor isn't pulled up.
        const auto hiss = noise(0.0003f, 96000); // about -75 dB
        auto fx = make("Smash");
        const double before = rms(hiss, 48000, 96000), after = rms(run(*fx, hiss).l, 48000, 96000);
        ok("hiss under the floor stays where it was", after < before * 1.5, num(dB(before)) + " -> " + num(dB(after)) + " dB");
    }
    {
        const auto bass = tone(80.0f, 0.2f, 96000);
        auto flat = make("Smash", {{"up", 0.0f}, {"down", 0.0f}});
        auto lifted = make("Smash", {{"up", 0.0f}, {"down", 0.0f}, {"low", 6.0f}});
        const double a = rms(run(*flat, bass).l, 48000, 96000), b = rms(run(*lifted, bass).l, 48000, 96000);
        ok("the low band's gain moves a bass note", dB(b / a) > 4.0 && dB(b / a) < 7.0, num(dB(b / a)) + " dB");
    }
    staysBounded("Smash");
}

// --- Acid -----------------------------------------------------------------------

/**
 * How bright [x] is between [from] and [to]: the level of a 110 Hz saw's
 * harmonics above 2 kHz against its first few, in dB.
 */
double brightness(const std::vector<float> &x, size_t from, size_t to) {
    auto power = [&](int h) {
        double re = 0.0, im = 0.0;
        for (size_t i = from; i < to && i < x.size(); ++i) {
            const double ph = 2.0 * M_PI * 110.0 * h * i / kRate;
            re += x[i] * std::cos(ph);
            im += x[i] * std::sin(ph);
        }
        return re * re + im * im;
    };
    double high = 0.0, low = 0.0;
    for (int h = 19; h <= 40; ++h) high += power(h);
    for (int h = 1; h <= 4; ++h) low += power(h);
    return 10.0 * std::log10((high + 1e-30) / (low + 1e-30));
}

void acid() {
    printf("- acid\n");
    const auto buzz = saw(110.0f, 0.4f, 96000);
    {
        auto fx = make("Acid", {{"mix", 0.0f}});
        ok("mix 0 is the track untouched", worst(run(*fx, buzz).l, buzz) < 1e-6);
    }
    {
        // On a pattern, every step sweeps the filter open and it falls back:
        // just after a step is brighter than just before the next.
        // Quiet and without drive, so the saturation between the stages
        // doesn't add back the highs the filter takes out.
        auto fx = make("Acid", {{"pattern", 2.0f}, {"decay", 60.0f}, {"env", 1.0f}, {"resonance", 0.3f}, {"drive", 0.0f}});
        const auto out = run(*fx, saw(110.0f, 0.05f, 96000), 120.0f).l; // a sixteenth is 6000 samples
        // Measured just as it opens, against just before the next step.
        const double open = brightness(out, 48000 + 100, 48000 + 1300);
        const double shut = brightness(out, 48000 + 4700, 48000 + 5900);
        ok("on a pattern, each step sweeps it open", open > shut + 6.0, num(shut) + " -> " + num(open) + " dB");
    }
    {
        // With no pattern, the track's own hits sweep it: a burst after silence.
        std::vector<float> hits(96000, 0.0f);
        for (size_t at : {12000u, 60000u}) for (size_t i = 0; i < 9000; ++i) hits[at + i] = 0.05f * (2.0f * std::fmod(110.0f * i / kRate, 1.0f) - 1.0f);
        auto fx = make("Acid", {{"pattern", 0.0f}, {"decay", 60.0f}, {"env", 1.0f}, {"resonance", 0.3f}, {"drive", 0.0f}});
        const auto out = run(*fx, hits).l;
        const double open = brightness(out, 60000 + 100, 60000 + 1300), shut = brightness(out, 60000 + 7600, 60000 + 8800);
        ok("with no pattern, a hit sweeps it open", open > shut + 6.0, num(shut) + " -> " + num(open) + " dB");
    }
    staysBounded("Acid");
}

// --- Mouth ------------------------------------------------------------------------

/** The level of [x]'s harmonics of 110 Hz from 2.2 to 3.3 kHz against all of it, in dB. */
double upperFormants(const std::vector<float> &x, size_t from) {
    double sum = 0.0, all = 0.0;
    for (size_t i = from; i < x.size(); ++i) all += double(x[i]) * x[i];
    for (int h = 20; h <= 30; ++h) {
        double re = 0.0, im = 0.0;
        for (size_t i = from; i < x.size(); ++i) {
            const double ph = 2.0 * M_PI * 110.0 * h * i / kRate;
            re += x[i] * std::cos(ph);
            im += x[i] * std::sin(ph);
        }
        sum += (re * re + im * im) * 2.0 / double(x.size() - from);
    }
    return 10.0 * std::log10(sum / (all + 1e-20));
}

void mouth() {
    printf("- mouth\n");
    const auto voice = saw(110.0f, 0.4f, 48000);
    auto oo = make("Mouth", {{"vowel", 0.0f}, {"depth", 0.0f}});
    auto ee = make("Mouth", {{"vowel", 1.0f}, {"depth", 0.0f}});
    const auto a = run(*oo, voice).l, b = run(*ee, voice).l;
    ok("ee is brighter than oo", upperFormants(b, 9600) > upperFormants(a, 9600) + 10.0,
       num(upperFormants(a, 9600)) + " -> " + num(upperFormants(b, 9600)) + " dB");
    ok("it comes out at about the level it went in", rms(a, 9600, 48000) > rms(voice, 0, 48000) * 0.1 &&
       rms(b, 9600, 48000) < rms(voice, 0, 48000) * 4.0);
    // Moved by its own level: a loud passage opens the mouth further than a quiet one.
    auto byLevel = make("Mouth", {{"vowel", 0.0f}, {"depth", 1.0f}, {"move", 1.0f}});
    auto quietVoice = saw(110.0f, 0.03f, 48000);
    const auto loud = run(*byLevel, voice).l;
    byLevel->reset();
    const auto soft = run(*byLevel, quietVoice).l;
    ok("moved by level, loud opens it further", upperFormants(loud, 9600) > upperFormants(soft, 9600) + 6.0,
       num(upperFormants(soft, 9600)) + " -> " + num(upperFormants(loud, 9600)) + " dB");
    staysBounded("Mouth");
}

// --- Tape --------------------------------------------------------------------------

/** The pitch of a tone in [x] between [from] and [to], Hz, from its zero crossings. */
double crossingsHz(const std::vector<float> &x, size_t from, size_t to) {
    int n = 0;
    for (size_t i = from + 1; i < to; ++i) if (x[i - 1] <= 0.0f && x[i] > 0.0f) ++n;
    return n * kRate / double(to - from);
}

void tape() {
    printf("- tape\n");
    const auto a440 = tone(440.0f, 0.3f, 192000);
    {
        auto fx = make("Tape", {{"mix", 0.0f}});
        ok("mix 0 is the track untouched", worst(run(*fx, a440).l, a440) < 1e-6);
    }
    {
        // Wow bends the pitch: measured over short windows it wanders.
        auto fx = make("Tape", {{"wow", 1.0f}, {"flutter", 0.0f}, {"hiss", 0.0f}});
        const auto out = run(*fx, a440).l;
        double lo = 1e9, hi = 0.0;
        for (size_t at = 48000; at + 9600 <= out.size(); at += 4800) {
            const double hz = crossingsHz(out, at, at + 9600);
            lo = std::min(lo, hz);
            hi = std::max(hi, hz);
        }
        ok("wow makes the pitch wander", hi - lo > 2.0, num(lo) + " to " + num(hi) + " Hz");
        auto still = make("Tape", {{"wow", 0.0f}, {"flutter", 0.0f}, {"hiss", 0.0f}});
        const auto steady = run(*still, a440).l;
        lo = 1e9; hi = 0.0;
        for (size_t at = 48000; at + 9600 <= steady.size(); at += 4800) {
            const double hz = crossingsHz(steady, at, at + 9600);
            lo = std::min(lo, hz);
            hi = std::max(hi, hz);
        }
        ok("...and without it, it holds", hi - lo < 0.5, num(lo) + " to " + num(hi) + " Hz");
    }
    {
        // Stop: a second into it the tape has slowed to silence.
        auto fx = make("Tape", {{"stoptime", 0.5f}, {"hiss", 0.0f}, {"wow", 0.0f}, {"flutter", 0.0f}});
        std::vector<float> first(a440.begin(), a440.begin() + 48000), rest(a440.begin() + 48000, a440.end());
        const auto before = run(*fx, first).l;
        set(*fx, "stop", 1.0f);
        const auto stopped = run(*fx, rest).l;
        ok("stopping, the pitch falls", crossingsHz(stopped, 6000, 12000) < 400.0, num(crossingsHz(stopped, 6000, 12000)) + " Hz");
        ok("...and it goes silent", rms(stopped, 48000, 72000) < 1e-3, num(rms(stopped, 48000, 72000)));
        set(*fx, "stop", 0.0f);
        const auto again = run(*fx, a440).l;
        ok("off again, it's back at speed", std::fabs(crossingsHz(again, 4800, 48000) - 440.0) < 2.0, num(crossingsHz(again, 4800, 48000)) + " Hz");
        (void)before;
    }
    staysBounded("Tape");
}

// --- Slicer -------------------------------------------------------------------------

void slicer() {
    printf("- slicer\n");
    // A ramp, so where in the track the output comes from can be read off it.
    std::vector<float> ramp(96000);
    for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 96000.0f;
    {
        auto fx = make("Slicer", {{"chance", 0.0f}});
        ok("chance 0 is the track untouched", worst(run(*fx, ramp).l, ramp) < 1e-6);
    }
    {
        // Every slice repeats the one before it. At 120 bpm a sixteenth is 6000
        // samples, so mid-slice the output is the track 6000 samples ago.
        auto fx = make("Slicer", {{"chance", 1.0f}, {"repeat", 1.0f}, {"reverse", 0.0f}, {"drop", 0.0f}});
        const auto out = run(*fx, ramp, 120.0f).l;
        const size_t at = 6000 * 5 + 3000;
        ok("a repeat plays the slice before, from its start", std::fabs(out[at] - ramp[at - 6000]) < 1e-3,
           num(out[at] * 96000.0) + " vs " + num((at - 6000.0)));
    }
    {
        auto fx = make("Slicer", {{"chance", 1.0f}, {"repeat", 0.0f}, {"reverse", 1.0f}, {"drop", 0.0f}});
        const auto out = run(*fx, ramp, 120.0f).l;
        const size_t at = 6000 * 5 + 1000;
        ok("backwards runs down where the track runs up", out[at + 100] < out[at], num(out[at] * 96000.0) + " then " + num(out[at + 100] * 96000.0));
    }
    {
        auto fx = make("Slicer", {{"chance", 1.0f}, {"repeat", 0.0f}, {"reverse", 0.0f}, {"drop", 1.0f}});
        const auto out = run(*fx, ramp, 120.0f).l;
        ok("a dropped slice is silent", std::fabs(out[6000 * 5 + 3000]) < 1e-6);
    }
    {
        // The same slices change every time: two runs from the top agree.
        auto a = make("Slicer", {{"chance", 0.5f}, {"seed", 3.0f}}), b = make("Slicer", {{"chance", 0.5f}, {"seed", 3.0f}});
        const auto x = noise(0.3f, 96000);
        ok("the same seed makes the same choices", worst(run(*a, x).l, run(*b, x).l) < 1e-9);
    }
    staysBounded("Slicer");
}

} // namespace

int main() {
    printf("character effects\n");
    smash();
    acid();
    mouth();
    tape();
    slicer();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
