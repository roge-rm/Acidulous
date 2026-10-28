// Tests the character effects: Smash, Acid, Mouth, Tape, Slicer, Horn,
// Spectral, Formula, Transient, Midside, Deesser, the Delay's modes and the
// Reverb's models. Each is rendered and measured for the thing it's for,
// since none of it shows in a frequency response.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Settings.h>
#include <engine/effect/Effect.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Magneto.h>
#include <engine/effect/Reverb.h>
#include <engine/effect/Spectral.h>
#include <engine/machine/formulate/Expr.h>

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

// --- Magneto --------------------------------------------------------------------

/** Runs left and right through, both live. */
Stereo runStereo(Effect &fx, const std::vector<float> &l, const std::vector<float> &r) {
    Stereo s{l, r};
    for (size_t at = 0; at < l.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, l.size() - at));
        fx.run(s.l.data() + at, s.r.data() + at, n, true);
    }
    return s;
}

/** The strength of one frequency over [from, to), as an amplitude. */
double goertzel(const std::vector<float> &x, double hz, size_t from, size_t to) {
    const double w = 2.0 * M_PI * hz / kRate, c = 2.0 * std::cos(w);
    double a = 0.0, b = 0.0;
    for (size_t i = from; i < to; ++i) {
        const double v = x[i] + c * a - b;
        b = a;
        a = v;
    }
    return 2.0 * std::sqrt(std::max(0.0, a * a + b * b - c * a * b)) / double(to - from);
}

/** Some music: a chord, a bass, a kick and hats. */
std::vector<float> music(size_t n, uint32_t seed) {
    std::vector<float> x(n, 0.0f);
    const auto hats = noise(1.0f, n, seed);
    float last = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        const double t = double(i) / kRate;
        for (double hz : {220.0, 277.18, 329.63}) x[i] += 0.1f * static_cast<float>(2.0 * std::fmod(hz * t, 1.0) - 1.0);
        x[i] += 0.15f * static_cast<float>(std::sin(2.0 * M_PI * 55.0 * t));
        const double beat = std::fmod(t, 0.5), off = std::fmod(t + 0.25, 0.5);
        x[i] += 0.4f * static_cast<float>(std::sin(2.0 * M_PI * 60.0 * beat) * std::exp(-beat / 0.08));
        const float bright = hats[i] - last; // the noise's top end
        last = hats[i];
        x[i] += 0.15f * bright * static_cast<float>(std::exp(-off / 0.02));
    }
    return x;
}

/** A one-pole low pass at [hz], run along [x]. */
std::vector<float> lowPass(const std::vector<float> &x, double hz) {
    const float a = static_cast<float>(std::exp(-2.0 * M_PI * hz / kRate));
    std::vector<float> y(x.size());
    float s = 0.0f;
    for (size_t i = 0; i < x.size(); ++i) y[i] = s = x[i] + a * (s - x[i]);
    return y;
}

/**
 * How far the error stands under the signal, dB, against the input held back
 * by [delay]. Taken below about 10 kHz, so the coding shows rather than
 * where each format cuts its top.
 */
double damage(const Stereo &out, const std::vector<float> &l, const std::vector<float> &r, int32_t delay) {
    double e = 0.0, s = 0.0;
    for (int c = 0; c < 2; ++c) {
        const auto &in = c == 0 ? l : r;
        const auto &wet = c == 0 ? out.l : out.r;
        std::vector<float> err(in.size(), 0.0f);
        for (size_t i = 0; i + delay < wet.size(); ++i) err[i] = wet[i + delay] - in[i];
        const auto le = lowPass(lowPass(err, 10000.0), 10000.0), li = lowPass(lowPass(in, 10000.0), 10000.0);
        for (size_t i = 48000; i + delay < wet.size(); ++i) {
            e += double(le[i]) * le[i];
            s += double(li[i]) * li[i];
        }
    }
    return 10.0 * std::log10(e / s);
}

/** Side over middle between [lo] and [hi] Hz, dB, summed over many single frequencies. */
double widthBetween(const Stereo &out, size_t from, double lo, double hi) {
    std::vector<float> m(out.l.size()), d(out.l.size());
    for (size_t i = 0; i < m.size(); ++i) {
        m[i] = out.l[i] + out.r[i];
        d[i] = out.l[i] - out.r[i];
    }
    double side = 0.0, mid = 0.0;
    for (double hz = lo; hz <= hi; hz += 37.0) {
        mid += std::pow(goertzel(m, hz, from, m.size()), 2.0);
        side += std::pow(goertzel(d, hz, from, d.size()), 2.0);
    }
    return 10.0 * std::log10(side / mid);
}

void magneto() {
    printf("- magneto\n");
    const char *names[] = {"SP", "LP2", "LP4", "HQ", "XLP"};
    auto latencyOf = [](Effect &fx) { return dynamic_cast<effect::Magneto &>(fx).latency(); };
    const size_t n = 240000;
    // Wide: the right is the left 37 ms later, with hats of its own.
    const auto l = music(n, 3), other = music(n, 7);
    std::vector<float> r(n, 0.0f);
    for (size_t i = 1776; i < n; ++i) r[i] = 0.5f * (l[i - 1776] + other[i]);
    {
        auto fx = make("Magneto", {{"mix", 0.0f}, {"dubs", 3.0f}});
        const auto out = runStereo(*fx, l, r);
        const int32_t d = latencyOf(*fx);
        std::vector<float> held(n, 0.0f);
        for (size_t i = d; i < n; ++i) held[i] = l[i - d];
        ok("mix 0 is the track, held back as far as the wet runs", worst(std::vector<float>(out.l.begin() + 4800, out.l.end()), std::vector<float>(held.begin() + 4800, held.end())) < 1e-6,
           "latency " + std::to_string(d));
        ok("...four dubs run four frames behind", d == 4 * 1024, std::to_string(d));
    }
    double hurt[5];
    for (int m = 0; m < 5; ++m) {
        auto fx = make("Magneto", {{"mode", float(m)}});
        const auto wet = runStereo(*fx, l, r);
        hurt[m] = damage(wet, l, r, latencyOf(*fx));
        // A bass note and a high one: the bass comes through whole, the top is cut.
        auto bass = tone(100.0f, 0.3f, 96000), top = tone(18500.0f, 0.1f, 96000);
        std::vector<float> both(96000);
        for (size_t i = 0; i < both.size(); ++i) both[i] = bass[i] + top[i];
        auto fx2 = make("Magneto", {{"mode", float(m)}});
        const auto out = runStereo(*fx2, both, both).l;
        const double low = dB(goertzel(out, 100.0, 48000, 96000) / 0.3), high = dB(goertzel(out, 18500.0, 48000, 96000) / 0.1);
        // SP keeps its top, as a recorder's own encoding does; the rest cut it.
        const bool keepsTop = m == 0;
        ok(std::string(names[m]) + (keepsTop ? ": the bass comes through whole and 18.5 kHz stays" : ": the bass comes through whole and 18.5 kHz is cut"),
           std::fabs(low) < 0.5 && (keepsTop ? high > -3.0 : high < -30.0), num(low) + " / " + num(high) + " dB");
    }
    // As the real copies measure: HQ the cleanest, SP and LP2 close (a
    // recorder's own SP is grainier than its age suggests), LP4 the worst.
    ok("the damage goes HQ least, SP and LP2 close, LP4 more than LP2",
       hurt[3] < std::min({hurt[0], hurt[1], hurt[2], hurt[4]}) && hurt[2] > hurt[1] + 2.0 && std::fabs(hurt[0] - hurt[1]) < 6.0,
       "SP " + num(hurt[0]) + " LP2 " + num(hurt[1]) + " LP4 " + num(hurt[2]) + " HQ " + num(hurt[3]) + " XLP " + num(hurt[4]));
    {
        auto one = make("Magneto", {{"mode", 2.0f}}), four = make("Magneto", {{"mode", 2.0f}, {"dubs", 3.0f}});
        const auto wetOne = runStereo(*one, l, r), wetFour = runStereo(*four, l, r);
        const double a = damage(wetOne, l, r, latencyOf(*one)), b = damage(wetFour, l, r, latencyOf(*four));
        ok("each dub loses a little more", b > a + 1.0, num(a) + " to " + num(b) + " dB");
    }
    {
        // LP4 throws the side away in the upper mids, as the real one does; LP2 keeps it.
        auto lp2 = make("Magneto", {{"mode", 1.0f}}), lp4 = make("Magneto", {{"mode", 2.0f}});
        const double wide = widthBetween(runStereo(*lp2, l, r), 48000, 3200.0, 4600.0);
        const double narrow = widthBetween(runStereo(*lp4, l, r), 48000, 3200.0, 4600.0);
        ok("LP4 narrows the upper mids, LP2 doesn't", narrow < wide - 20.0, num(wide) + " / " + num(narrow) + " dB");
    }
    {
        auto fx = make("Magneto", {{"mode", 2.0f}, {"dubs", 3.0f}});
        const std::vector<float> zero(96000, 0.0f);
        const auto out = runStereo(*fx, zero, zero);
        ok("silence stays silent", peak(out.l) == 0.0 && peak(out.r) == 0.0);
    }
    staysBounded("Magneto");
}

// --- Horn -----------------------------------------------------------------------

/** The pitch of [x] over [from, to), Hz, by where it best matches itself one period on. */
double pitchHz(const std::vector<float> &x, size_t from, size_t to) {
    double best = 0.0;
    int bestLag = 0;
    for (int lag = static_cast<int>(kRate / 1200.0f); lag < static_cast<int>(kRate / 50.0f); ++lag) {
        double c = 0.0, e1 = 0.0, e2 = 0.0;
        for (size_t i = from; i + lag < to; ++i) {
            c += double(x[i]) * x[i + lag];
            e1 += double(x[i]) * x[i];
            e2 += double(x[i + lag]) * x[i + lag];
        }
        const double r = c / std::sqrt(e1 * e2 + 1e-30);
        // The first strong match, so a period isn't mistaken for two.
        if (r > best + 0.02) { best = r; bestLag = lag; }
        if (best > 0.9 && r < best - 0.2) break;
    }
    return bestLag > 0 ? kRate / bestLag : 0.0;
}

void horn() {
    printf("- horn\n");
    const char *kinds[] = {"brass", "clarinet", "oboe", "flute"};
    const auto a220 = tone(220.0f, 0.3f, 144000);
    for (int k = 0; k < 4; ++k) {
        auto fx = make("Horn", {{"kind", float(k)}, {"air", 0.0f}});
        const auto out = run(*fx, a220).l;
        const double hz = pitchHz(out, 96000, 104000);
        const double level = dB(rms(out, 96000, 144000) / rms(a220, 96000, 144000));
        ok(std::string(kinds[k]) + " plays the note the track plays, about as loud", std::fabs(hz - 220.0) < 220.0 * 0.015 && std::fabs(level) < 4.0,
           num(hz) + " Hz, " + num(level) + " dB");
    }
    {
        auto fx = make("Horn", {{"octave", 1.0f}, {"air", 0.0f}});
        const auto out = run(*fx, a220).l;
        const double hz = pitchHz(out, 96000, 104000);
        ok("an octave up, it plays an octave up", std::fabs(hz - 440.0) < 440.0 * 0.015, num(hz) + " Hz");
    }
    {
        // The track moves up a fifth: the horn follows.
        auto fx = make("Horn", {{"air", 0.0f}, {"glide", 10.0f}});
        std::vector<float> line = a220;
        const auto e330 = tone(330.0f, 0.3f, 96000);
        line.insert(line.end(), e330.begin(), e330.end());
        const auto out = run(*fx, line).l;
        const double hz = pitchHz(out, 144000 + 48000, 144000 + 56000);
        ok("it follows the track to a new note", std::fabs(hz - 330.0) < 330.0 * 0.015, num(hz) + " Hz");
    }
    {
        auto fx = make("Horn");
        std::vector<float> line = a220;
        line.resize(line.size() + 96000, 0.0f);
        const auto out = run(*fx, line).l;
        ok("when the track stops, so does the horn", rms(out, 144000 + 48000, 144000 + 96000) < 1e-3, num(rms(out, 144000 + 48000, 144000 + 96000)));
    }
    {
        auto fx = make("Horn", {{"mix", 0.0f}});
        ok("mix 0 is the track untouched", worst(run(*fx, a220).l, a220) < 1e-6);
    }
    {
        // The generic check feeds it noise, which it ignores; a note with every knob up.
        auto fx = make("Horn");
        for (int32_t i = 0; i < fx->params().size(); ++i) {
            if (std::strcmp(fx->params().def(i).name, "gain") != 0) fx->params().set(i, 1.0f);
        }
        fx->params().jumpAll();
        const auto out = run(*fx, tone(220.0f, 0.9f, 144000));
        ok("every knob up on a loud note, it stays finite and bounded", finite(out) && peak(out.l) < 8.0, "peak " + num(peak(out.l)));
    }
    staysBounded("Horn");
}

// --- Spectral -------------------------------------------------------------------

void spectral() {
    printf("- spectral\n");
    auto latencyOf = [](Effect &fx) { return dynamic_cast<effect::Spectral &>(fx).latency(); };
    const size_t n = 144000;
    const auto l = music(n, 5);
    {
        // With nothing turned, it takes the track apart and puts it back as it was.
        auto fx = make("Spectral");
        const auto out = run(*fx, l).l;
        const int32_t d = latencyOf(*fx);
        double e = 0.0, s = 0.0;
        for (size_t i = 48000; i + d < n; ++i) {
            const double v = out[i + d] - l[i];
            e += v * v;
            s += double(l[i]) * l[i];
        }
        ok("with nothing turned, the track comes back as it went in", 10.0 * std::log10(e / s) < -60.0, num(10.0 * std::log10(e / s)) + " dB, latency " + std::to_string(d));
    }
    {
        auto fx = make("Spectral", {{"mix", 0.0f}, {"blur", 0.7f}});
        const auto out = run(*fx, l).l;
        const int32_t d = latencyOf(*fx);
        std::vector<float> held(n, 0.0f);
        for (size_t i = d; i < n; ++i) held[i] = l[i - d];
        ok("mix 0 is the track, held back as far as the wet runs", worst(std::vector<float>(out.begin() + 4800, out.end()), std::vector<float>(held.begin() + 4800, held.end())) < 1e-6);
    }
    const auto a440 = tone(440.0f, 0.3f, 48000);
    std::vector<float> burst = a440;
    burst.resize(144000, 0.0f);
    {
        // Frozen while the note plays, it goes on after the note stops.
        auto fx = make("Spectral");
        std::vector<float> first(burst.begin(), burst.begin() + 40000), rest(burst.begin() + 40000, burst.end());
        const auto before = run(*fx, first).l;
        set(*fx, "freeze", 1.0f);
        const auto after = run(*fx, rest).l;
        const double held = rms(after, 48000, 96000);
        ok("freeze holds the sound after the track stops", held > 0.1 && std::fabs(pitchHz(after, 48000, 52000) - 440.0) < 10.0,
           "rms " + num(held) + ", " + num(pitchHz(after, 48000, 52000)) + " Hz");
        set(*fx, "freeze", 0.0f);
        const auto thawed = run(*fx, std::vector<float>(48000, 0.0f)).l;
        ok("...and lets go when it's off", rms(thawed, 12000, 48000) < 1e-3, num(rms(thawed, 12000, 48000)));
        (void)before;
    }
    {
        auto dry = make("Spectral"), blurred = make("Spectral", {{"blur", 0.9f}});
        const auto a = run(*dry, burst).l, b = run(*blurred, burst).l;
        const double ta = rms(a, 48000 + 12000, 48000 + 24000), tb = rms(b, 48000 + 12000, 48000 + 24000);
        ok("blur lets a note fade instead of stop", tb > 0.02 && tb > ta * 10.0, num(ta) + " to " + num(tb));
    }
    {
        // A tone in noise: peaks keeps the tone and drops the noise.
        auto mixed = tone(1000.0f, 0.2f, 96000);
        const auto hiss = noise(0.1f, 96000, 9);
        for (size_t i = 0; i < mixed.size(); ++i) mixed[i] += hiss[i];
        auto fx = make("Spectral", {{"peaks", 0.7f}});
        const auto out = run(*fx, mixed).l;
        const double toneIn = goertzel(mixed, 1000.0, 24000, 96000), toneOut = goertzel(out, 1000.0, 24000, 96000);
        const double share = toneOut * toneOut / 2.0 / std::pow(rms(out, 24000, 96000), 2.0);
        const double shareIn = toneIn * toneIn / 2.0 / std::pow(rms(mixed, 24000, 96000), 2.0);
        ok("peaks keeps the strongest frequencies", 1.0 - share < (1.0 - shareIn) * 0.1, num(1.0 - shareIn) + " to " + num(1.0 - share) + " of the energy not the tone");
    }
    {
        auto dark = make("Spectral", {{"tilt", -12.0f}}), bright = make("Spectral", {{"tilt", 12.0f}});
        const auto w = noise(0.2f, 96000, 4);
        const auto a = run(*dark, w).l, b = run(*bright, w).l;
        const double ra = goertzel(a, 8000.0, 24000, 96000) / goertzel(a, 250.0, 24000, 96000);
        const double rb = goertzel(b, 8000.0, 24000, 96000) / goertzel(b, 250.0, 24000, 96000);
        ok("tilt leans it brighter or darker", dB(rb) > dB(ra) + 30.0, num(dB(ra)) + " / " + num(dB(rb)) + " dB");
    }
    {
        auto fx = make("Spectral", {{"robot", 1.0f}});
        const auto out = run(*fx, music(n, 2)).l;
        ok("robot makes a sound of its own", rms(out, 48000, n) > 0.01 && finite(Stereo{out, out}), num(rms(out, 48000, n)));
    }
    staysBounded("Spectral");
}

// --- Formula --------------------------------------------------------------------

/** Hands a Formula effect its formula, as the app does; false if it won't parse. */
bool giveFormula(Effect &fx, const char *text, std::string *error = nullptr) {
    auto *expr = new machine::formulate::Expr();
    std::string why;
    if (*text != '\0' && !machine::formulate::Expr::parse(text, *expr, why)) {
        delete expr;
        if (error) *error = why;
        return false;
    }
    delete static_cast<machine::formulate::Expr *>(fx.swapObject(0, expr));
    return true;
}

void formula() {
    printf("- formula\n");
    const auto l = music(96000, 8);
    {
        auto fx = make("Formula");
        ok("with no formula the track passes untouched", worst(run(*fx, l).l, l) < 1e-7);
    }
    {
        auto fx = make("Formula");
        giveFormula(*fx, "x");
        const auto out = run(*fx, l).l;
        double w = 0.0;
        for (size_t i = 0; i < l.size(); ++i) w = std::max(w, double(std::fabs(out[i] - l[i])));
        ok("x alone is the track in eight bits", w < 0.5 / 128.0 + 1e-4 && w > 1e-4, "worst " + num(w));
    }
    {
        // Fewer bits with a: the error grows as a goes up.
        auto lo = make("Formula", {{"a", 32.0f}}), hi = make("Formula", {{"a", 200.0f}});
        giveFormula(*lo, "x & (255 << (a >> 5))");
        giveFormula(*hi, "x & (255 << (a >> 5))");
        const auto ol = run(*lo, l).l, oh = run(*hi, l).l;
        double dl = 0.0, dh = 0.0;
        for (size_t i = 0; i < l.size(); ++i) {
            dl += std::pow(ol[i] - l[i], 2.0);
            dh += std::pow(oh[i] - l[i], 2.0);
        }
        ok("a knob read by the formula changes the sound", dh > dl * 10.0, num(dB(std::sqrt(dl))) + " to " + num(dB(std::sqrt(dh))) + " dB");
    }
    {
        // A gate on the counter: on and off at rate / 2048 / 2.
        auto fx = make("Formula", {{"rate", 8000.0f}});
        giveFormula(*fx, "t >> 11 & 1 ? x : 128");
        const auto out = run(*fx, tone(440.0f, 0.5f, 96000)).l;
        const size_t half = static_cast<size_t>(kRate * 2048.0 / 8000.0);
        ok("t counts at rate: a gate on it opens and shuts in time", rms(out, 100, half - 100) < 0.01 && rms(out, half + 100, 2 * half - 100) > 0.3,
           num(rms(out, 100, half - 100)) + " / " + num(rms(out, half + 100, 2 * half - 100)));
    }
    {
        std::string why;
        auto fx = make("Formula");
        ok("a formula that won't parse says why", !giveFormula(*fx, "x + (", &why) && !why.empty(), why);
    }
    {
        auto fx = make("Formula", {{"mix", 0.0f}});
        giveFormula(*fx, "x ^ r");
        ok("mix 0 is the track untouched", worst(run(*fx, l).l, l) < 1e-7);
    }
    {
        // Each example the editor offers parses, and none runs away.
        const char *examples[] = {"x", "x & (255 << (a >> 5))", "128 + ((x - 128) * (a + 16) >> 4)",
                                  "128 + ((x - 128) * sin(t * (a + 1) >> 3) >> 7)", "t >> 6 & 1 ? x : 128",
                                  "x ^ r >> (8 - (b >> 5))", "(x & 240) | (t >> 2 & 15)", "x ^ t >> 4"};
        bool all = true;
        double most = 0.0;
        for (const char *e : examples) {
            auto fx = make("Formula", {{"a", 200.0f}, {"b", 200.0f}});
            all = all && giveFormula(*fx, e);
            const auto out = run(*fx, l);
            all = all && finite(out);
            most = std::max(most, peak(out.l));
        }
        ok("every example parses and stays bounded", all && most <= 1.0 + 1e-6, "peak " + num(most));
    }
    staysBounded("Formula");
}

namespace {

// --- Delay modes ------------------------------------------------------------------

/** Runs a stereo signal through in blocks at [bpm]. */
Stereo runStereo(Effect &fx, const Stereo &in, float bpm = 120.0f) {
    Stereo s = in;
    const double ticksPerSample = kTicksPerBeat * bpm / 60.0 / kRate;
    for (size_t at = 0; at < s.l.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, s.l.size() - at));
        fx.onBlock(static_cast<int64_t>(at * ticksPerSample), static_cast<int64_t>((at + n) * ticksPerSample), bpm);
        fx.run(s.l.data() + at, s.r.data() + at, n, true);
    }
    return s;
}

/** The biggest |x| in [from, to). */
double peakIn(const std::vector<float> &x, size_t from, size_t to) {
    double p = 0.0;
    for (size_t i = from; i < std::min(to, x.size()); ++i) p = std::max(p, double(std::fabs(x[i])));
    return p;
}

void delayModes() {
    printf("- delay modes\n");
    // A quarter note at 120 is 24000 frames (time #4).
    constexpr size_t kQuarter = 24000;
    std::vector<float> click(3 * kQuarter, 0.0f);
    click[0] = 1.0f;
    {
        auto fx = make("Delay", {{"mode", 0.0f}, {"time", 4.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, click);
        ok("normal: the echo lands on the quarter in both sides",
           peakIn(out.l, kQuarter - 1, kQuarter + 2) > 0.9 && peakIn(out.r, kQuarter - 1, kQuarter + 2) > 0.9 &&
               peakIn(out.l, 2, kQuarter - 2) < 1e-6,
           num(peakIn(out.l, kQuarter - 1, kQuarter + 2)) + " " + num(peakIn(out.r, kQuarter - 1, kQuarter + 2)));
    }
    {
        auto fx = make("Delay", {{"mode", 1.0f}, {"time", 4.0f}, {"feedback", 0.9f}, {"mix", 1.0f}, {"tone", 18000.0f}});
        const auto out = run(*fx, click);
        const bool first = peakIn(out.l, kQuarter - 4, kQuarter + 4) > 0.3 && peakIn(out.r, kQuarter - 4, kQuarter + 4) < 1e-6;
        const bool second = peakIn(out.r, 2 * kQuarter - 4, 2 * kQuarter + 4) > 0.2 && peakIn(out.l, 2 * kQuarter - 4, 2 * kQuarter + 4) < 1e-6;
        ok("ping-pong: the first echo is on the left, the second on the right", first && second);
    }
    {
        // Four even taps in a quarter: one every sixteenth, outside in.
        auto fx = make("Delay", {{"mode", 2.0f}, {"time", 4.0f}, {"taps", 4.0f}, {"spacing", 0.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, click);
        bool at = true;
        std::string where;
        for (int k = 1; k <= 4; ++k) {
            const size_t t = kQuarter * k / 4;
            const double here = peakIn(out.l, t - 2, t + 3) + peakIn(out.r, t - 2, t + 3);
            at = at && here > 0.6;
            where += num(here) + " ";
        }
        // Nothing between the taps.
        double between = 0.0;
        for (int k = 0; k < 4; ++k) {
            const size_t a = kQuarter * k / 4 + 4, b = kQuarter * (k + 1) / 4 - 3;
            between = std::max(between, std::max(peakIn(out.l, a, b), peakIn(out.r, a, b)));
        }
        ok("multi-tap: four even taps land on each sixteenth", at && between < 1e-6, where + "between " + num(between));
        const size_t t1 = kQuarter / 4, t2 = kQuarter / 2;
        ok("multi-tap: the first tap is hard left and the second hard right",
           peakIn(out.r, t1 - 2, t1 + 3) < 1e-4 && peakIn(out.l, t2 - 2, t2 + 3) < 1e-4);
    }
    {
        // Spacing up bunches the taps late: the first of three moves past a third.
        auto fx = make("Delay", {{"mode", 2.0f}, {"time", 4.0f}, {"taps", 3.0f}, {"spacing", 1.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, click);
        size_t first = 0;
        for (size_t i = 2; i < kQuarter && first == 0; ++i) if (std::fabs(out.l[i]) + std::fabs(out.r[i]) > 0.3) first = i;
        ok("multi-tap: spacing up moves the first tap late", first > kQuarter / 2, std::to_string(first) + " frames");
    }
    {
        // A rising ramp a quarter long comes back falling over the next quarter.
        std::vector<float> ramp(3 * kQuarter, 0.0f);
        for (size_t i = 0; i < kQuarter; ++i) ramp[i] = 0.5f * static_cast<float>(i) / kQuarter;
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 4.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, ramp);
        double sxy = 0.0, sxx = 0.0, syy = 0.0;
        for (size_t t = 600; t < kQuarter - 600; ++t) {
            const double want = ramp[kQuarter - 1 - t], got = out.l[kQuarter + t];
            sxy += want * got; sxx += want * want; syy += got * got;
        }
        const double corr = sxy / std::sqrt(sxx * syy + 1e-30);
        ok("reverse: the stretch before plays back end first", corr > 0.999 && peakIn(out.l, 0, kQuarter) <= 0.5 + 1e-6,
           "correlation " + num(corr));
        ok("reverse: a whole stretch later it's gone (feedback 0)", peakIn(out.l, 2 * kQuarter + 600, 3 * kQuarter) < 1e-3,
           num(peakIn(out.l, 2 * kQuarter + 600, 3 * kQuarter)));
    }
    {
        // A bar at 120 is longer than the line holds twice, so it plays back in halves.
        constexpr size_t kHalf = 2 * kQuarter;
        std::vector<float> ramp(3 * kHalf, 0.0f);
        for (size_t i = 0; i < kHalf; ++i) ramp[i] = 0.5f * static_cast<float>(i) / kHalf;
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 7.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, ramp);
        double sxy = 0.0, sxx = 0.0, syy = 0.0;
        for (size_t t = 600; t < kHalf - 600; ++t) {
            const double want = ramp[kHalf - 1 - t], got = out.l[kHalf + t];
            sxy += want * got; sxx += want * want; syy += got * got;
        }
        ok("reverse: a bar too long for the line plays back in halves", sxy / std::sqrt(sxx * syy + 1e-30) > 0.999,
           "correlation " + num(sxy / std::sqrt(sxx * syy + 1e-30)));
    }
    {
        // Feedback plays the reversed echo forwards again.
        std::vector<float> ramp(4 * kQuarter, 0.0f);
        for (size_t i = 0; i < kQuarter; ++i) ramp[i] = 0.5f * static_cast<float>(i) / kQuarter;
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 4.0f}, {"feedback", 0.5f}, {"mix", 1.0f}, {"tone", 18000.0f}});
        const auto out = run(*fx, ramp);
        const double early = rms(out.l, 2 * kQuarter + 600, 2 * kQuarter + 6000), late = rms(out.l, 3 * kQuarter - 6000, 3 * kQuarter - 600);
        ok("reverse: with feedback the next pass runs forwards", late > 2.0 * early && late > 0.05, num(early) + " -> " + num(late));
    }
    for (int mode = 0; mode < 4; ++mode) {
        // Played, reset and played again, every mode comes out the same.
        auto fx = make("Delay", {{"mode", float(mode)}, {"feedback", 0.7f}, {"wobble", 0.5f}, {"time", 3.0f}});
        const auto music = noise(0.3f, 96000, 5);
        const auto a = run(*fx, music);
        fx->reset();
        fx->resetMod();
        const auto b = run(*fx, music);
        ok("mode " + std::to_string(mode) + ": reset plays it back the same", worst(a.l, b.l) == 0.0 && worst(a.r, b.r) == 0.0);
    }
    for (int mode = 0; mode < 4; ++mode) {
        auto fx = make("Delay", {{"mode", float(mode)}, {"feedback", 0.95f}, {"wobble", 1.0f}, {"time", 7.0f}});
        const auto out = run(*fx, noise(0.9f, 240000), 40.0f);
        ok("mode " + std::to_string(mode) + ": a 1-bar delay at 40 bpm, full feedback and wobble stays bounded",
           finite(out) && peak(out.l) < 8.0, "peak " + num(peak(out.l)));
    }
}

// --- Transient --------------------------------------------------------------------

/** Noise hits that ring down, four a second. */
std::vector<float> hits(size_t n) {
    auto x = noise(0.6f, n, 7);
    for (size_t i = 0; i < n; ++i) x[i] *= static_cast<float>(std::exp(-double(i % 12000) / 2400.0));
    return x;
}

void transient() {
    printf("- transient\n");
    const auto in = hits(96000);
    {
        auto fx = make("Transient");
        ok("attack and sustain at 0 is the track untouched", worst(run(*fx, in).l, in) < 1e-7);
    }
    // The onset: the first 5 ms of each hit.
    auto onset = [](const std::vector<float> &x) {
        double p = 0.0;
        for (size_t h = 12000; h < x.size(); h += 12000) p = std::max(p, peakIn(x, h, h + 240));
        return p;
    };
    auto tail = [](const std::vector<float> &x) {
        double s = 0.0;
        for (size_t h = 12000; h < x.size(); h += 12000) s += rms(x, h + 4800, h + 11000);
        return s;
    };
    {
        auto fx = make("Transient", {{"attack", 1.0f}});
        const auto out = run(*fx, in).l;
        const double d = dB(onset(out) / onset(in));
        ok("attack up raises each hit's onset peak", d > 3.0, num(d) + " dB");
    }
    {
        auto fx = make("Transient", {{"attack", -1.0f}});
        const double d = dB(onset(run(*fx, in).l) / onset(in));
        ok("attack down lowers it", d < -3.0, num(d) + " dB");
    }
    {
        auto fx = make("Transient", {{"sustain", 1.0f}});
        const double d = dB(tail(run(*fx, in).l) / tail(in));
        ok("sustain up raises the ring after the hit", d > 3.0, num(d) + " dB");
    }
    {
        auto fx = make("Transient", {{"sustain", -1.0f}});
        const double d = dB(tail(run(*fx, in).l) / tail(in));
        ok("sustain down shortens it", d < -3.0, num(d) + " dB");
    }
    {
        // With the split up high a held bass tone under it comes through.
        std::vector<float> mix = in;
        const auto bass = tone(60.0f, 0.3f, in.size());
        for (size_t i = 0; i < mix.size(); ++i) mix[i] = 0.1f * mix[i] + bass[i];
        auto fx = make("Transient", {{"attack", 1.0f}, {"sustain", -1.0f}, {"split", 2000.0f}});
        auto full = make("Transient", {{"attack", 1.0f}, {"sustain", -1.0f}});
        const auto split = run(*fx, mix).l, whole = run(*full, mix).l;
        // How far the bass moved: the output against the input, lowpassed by a 4800-frame mean.
        auto lowErr = [&](const std::vector<float> &out) {
            double e = 0.0, b = 0.0;
            for (size_t i = 48000; i < 96000; i += 400) {
                double so = 0.0, si = 0.0;
                for (size_t j = i; j < i + 400; ++j) { so += out[j] * bass[j]; si += mix[j] * bass[j]; }
                e += std::fabs(so - si);
                b += std::fabs(si);
            }
            return e / b;
        };
        ok("split: a bass under the split is left nearly alone", lowErr(split) < 0.02 && lowErr(whole) > 0.05,
           num(lowErr(split)) + " against " + num(lowErr(whole)) + " shaping all of it");
    }
    staysBounded("Transient");
}

// --- Midside ----------------------------------------------------------------------

void midSide() {
    printf("- midside\n");
    // Different sounds left and right.
    Stereo in{tone(440.0f, 0.3f, 48000), noise(0.2f, 48000, 3)};
    {
        auto fx = make("Midside");
        const auto out = runStereo(*fx, in);
        ok("at its defaults it puts the track back", worst(out.l, in.l) < 1e-5 && worst(out.r, in.r) < 1e-5,
           num(std::max(worst(out.l, in.l), worst(out.r, in.r))));
    }
    {
        auto fx = make("Midside", {{"width", 0.0f}});
        const auto out = runStereo(*fx, in);
        ok("width 0 is mono", worst(out.l, out.r) == 0.0);
    }
    {
        auto fx = make("Midside", {{"width", 2.0f}});
        const auto out = runStereo(*fx, in);
        double sIn = 0.0, sOut = 0.0;
        for (size_t i = 0; i < in.l.size(); ++i) {
            sIn += std::pow(double(in.l[i] - in.r[i]), 2.0);
            sOut += std::pow(double(out.l[i] - out.r[i]), 2.0);
        }
        ok("width 2 doubles the side", std::fabs(dB(std::sqrt(sOut / sIn)) - 6.02) < 0.1, num(dB(std::sqrt(sOut / sIn))) + " dB");
    }
    {
        // A 50 Hz tone out of phase is all side: mono at 200 Hz takes it out.
        const auto lo = tone(50.0f, 0.3f, 96000);
        Stereo anti{lo, lo};
        for (auto &v : anti.r) v = -v;
        auto fx = make("Midside", {{"mono", 200.0f}});
        const auto out = runStereo(*fx, anti);
        const double d = dB(rms(out.l, 48000, 96000) / rms(lo, 48000, 96000));
        ok("mono at 200 Hz takes the side out of a 50 Hz tone", d < -20.0, num(d) + " dB");
    }
    {
        auto fx = make("Midside", {{"side", -24.0f}, {"mid", 6.0f}});
        Stereo same{in.l, in.l};
        const auto out = runStereo(*fx, same);
        const double d = dB(rms(out.l, 4800, 48000) / rms(same.l, 4800, 48000));
        ok("mid +6 dB lifts a centred sound by 6 dB", std::fabs(d - 6.0) < 0.1, num(d) + " dB");
    }
    {
        // Breathe: a loud centre narrows the side.
        Stereo loud{tone(220.0f, 0.5f, 48000), tone(220.0f, 0.5f, 48000)};
        const auto n = noise(0.05f, 48000, 9);
        for (size_t i = 0; i < 48000; ++i) { loud.l[i] += n[i]; loud.r[i] -= n[i]; }
        auto fx = make("Midside", {{"breathe", 1.0f}});
        const auto out = runStereo(*fx, loud);
        double sIn = 0.0, sOut = 0.0;
        for (size_t i = 24000; i < 48000; ++i) {
            sIn += std::pow(double(loud.l[i] - loud.r[i]), 2.0);
            sOut += std::pow(double(out.l[i] - out.r[i]), 2.0);
        }
        ok("breathe: a loud centre closes the side", dB(std::sqrt(sOut / sIn)) < -20.0, num(dB(std::sqrt(sOut / sIn))) + " dB");
    }
    staysBounded("Midside");
}

// --- Deesser ----------------------------------------------------------------------

void deEsser() {
    printf("- de-esser\n");
    const auto ess = tone(6000.0f, 0.3f, 48000);
    const auto low = tone(200.0f, 0.5f, 48000);
    {
        auto fx = make("Deesser");
        const double d = dB(rms(run(*fx, ess).l, 24000, 48000) / rms(ess, 24000, 48000));
        ok("split: a loud band at 6 kHz is ducked", d < -6.0, num(d) + " dB");
    }
    {
        auto fx = make("Deesser");
        const double d = dB(rms(run(*fx, low).l, 24000, 48000) / rms(low, 24000, 48000));
        ok("a 200 Hz tone is left alone", std::fabs(d) < 0.1, num(d) + " dB");
    }
    {
        // The ess on top of a low tone: wide ducks both, split only the ess.
        std::vector<float> both(48000);
        for (size_t i = 0; i < both.size(); ++i) both[i] = ess[i] + low[i];
        auto split = make("Deesser");
        auto wide = make("Deesser", {{"mode", 1.0f}});
        const auto a = run(*split, both).l, b = run(*wide, both).l;
        // The low tone's part of each, by correlation with it.
        auto lowPart = [&](const std::vector<float> &x) {
            double s = 0.0, n = 0.0;
            for (size_t i = 24000; i < 48000; ++i) { s += x[i] * low[i]; n += low[i] * low[i]; }
            return s / n;
        };
        ok("split leaves the low tone under an ess; wide turns it down",
           std::fabs(dB(lowPart(a))) < 0.5 && dB(lowPart(b)) < -3.0, num(dB(lowPart(a))) + " / " + num(dB(lowPart(b))) + " dB");
    }
    {
        auto fx = make("Deesser", {{"listen", 1.0f}});
        const double d = dB(rms(run(*fx, low).l, 24000, 48000) / rms(low, 24000, 48000));
        ok("listen plays the band, so a low tone nearly vanishes", d < -25.0, num(d) + " dB");
    }
    {
        // Relative: the same ess 20 dB quieter is caught the same.
        std::vector<float> quiet = ess;
        for (auto &v : quiet) v *= 0.1f;
        auto fx = make("Deesser", {{"relative", 1.0f}});
        auto fx2 = make("Deesser", {{"relative", 1.0f}});
        const double dl = dB(rms(run(*fx, ess).l, 24000, 48000) / rms(ess, 24000, 48000));
        const double dq = dB(rms(run(*fx2, quiet).l, 24000, 48000) / rms(quiet, 24000, 48000));
        ok("relative: a quiet ess is cut as much as a loud one", std::fabs(dl - dq) < 0.5 && dl < -3.0, num(dl) + " / " + num(dq) + " dB");
    }
    {
        // Relative from the defaults: a vowel-ish low tone with the band 20 dB
        // down is left near unity, a bare ess is cut.
        std::vector<float> vowel(48000);
        for (size_t i = 0; i < vowel.size(); ++i) vowel[i] = low[i] + 0.1f * ess[i];
        auto fx = make("Deesser", {{"relative", 1.0f}});
        auto fx2 = make("Deesser", {{"relative", 1.0f}});
        const double dv = dB(rms(run(*fx, vowel).l, 24000, 48000) / rms(vowel, 24000, 48000));
        const double de = dB(rms(run(*fx2, ess).l, 24000, 48000) / rms(ess, 24000, 48000));
        ok("relative from defaults: a vowel is left alone, an ess is cut", std::fabs(dv) < 0.3 && de < -3.0, num(dv) + " / " + num(de) + " dB");
    }
    staysBounded("Deesser");
}
} // namespace

// --- Reverb -----------------------------------------------------------------------

/** The wet part of a mono run at mix 1: what came out less the dry, scaled as the effect scaled it. */
std::vector<float> wetOf(const std::vector<float> &out, const std::vector<float> &in, float dryGain = 1.0f) {
    std::vector<float> w(out.size());
    for (size_t i = 0; i < out.size(); ++i) w[i] = out[i] - in[i] * dryGain;
    return w;
}

/** Seconds from [from] until the 50 ms rms of [x] falls 30 dB under its level just after [from]. */
double fallTime(const std::vector<float> &x, size_t from) {
    const size_t win = 2400;
    const double start = rms(x, from, from + win);
    for (size_t at = from; at + win < x.size(); at += 480)
        if (dB(rms(x, at, at + win) / start) < -30.0) return double(at - from) / kRate;
    return double(x.size() - from) / kRate;
}

/** A Hann-windowed DFT's strength at [hz] over [from, to), at [rate]. */
double strengthAt(const std::vector<float> &x, double hz, size_t from, size_t to, double rate) {
    // The tone and the window as rotating phasors, renormalised now and then.
    const double w = 2.0 * M_PI * hz / rate, n = double(to - from), v = 2.0 * M_PI / n;
    const double cw = std::cos(w), sw = std::sin(w), cv = std::cos(v), sv = std::sin(v);
    double re = 0.0, im = 0.0, pc = 1.0, ps = 0.0, hc = 1.0, hs = 0.0;
    for (size_t i = from; i < to; ++i) {
        const double h = 0.5 - 0.5 * hc;
        re += x[i] * h * pc;
        im -= x[i] * h * ps;
        const double a = pc * cw - ps * sw, b = ps * cw + pc * sw;
        const double c = hc * cv - hs * sv, d = hs * cv + hc * sv;
        pc = a; ps = b; hc = c; hs = d;
        if (((i - from) & 4095) == 4095) {
            const double k = 1.0 / std::sqrt(pc * pc + ps * ps), m = 1.0 / std::sqrt(hc * hc + hs * hs);
            pc *= k; ps *= k; hc *= m; hs *= m;
        }
    }
    return std::sqrt(re * re + im * im) / n;
}

/** The strongest frequency within [cents] of [hz], to about a tenth of a cent. */
double peakNear(const std::vector<float> &x, double hz, double cents, size_t from, size_t to, double rate) {
    // Four-cent steps, then a golden-section search around the best.
    const auto at = [&](double c) { return strengthAt(x, hz * std::pow(2.0, c / 1200.0), from, to, rate); };
    double best = 0.0, top = -1.0;
    for (double c = -cents; c <= cents; c += 4.0) {
        const double v = at(c);
        if (v > top) { top = v; best = c; }
    }
    const double g = 0.5 * (std::sqrt(5.0) - 1.0);
    double lo = best - 4.0, hi = best + 4.0;
    double c1 = hi - g * (hi - lo), c2 = lo + g * (hi - lo), v1 = at(c1), v2 = at(c2);
    for (int k = 0; k < 16; ++k) {
        if (v1 > v2) { hi = c2; c2 = c1; v2 = v1; c1 = hi - g * (hi - lo); v1 = at(c1); }
        else { lo = c1; c1 = c2; v1 = v2; c2 = lo + g * (hi - lo); v2 = at(c2); }
    }
    return hz * std::pow(2.0, 0.5 * (lo + hi) / 1200.0);
}

double cents(double hz, double ref) { return 1200.0 * std::log2(hz / ref); }

/** Tube and string: in tune, open, stiff, pick, follow. */
void reverbWaves() {
    using acidulous::effect::Reverb;
    const auto prepared = [](int32_t rate, std::initializer_list<std::pair<const char *, float>> set) {
        std::unique_ptr<Effect> fx(EffectRegistry::create("Reverb"));
        fx->prepare(rate);
        for (const auto &[name, value] : set) {
            const int32_t i = fx->params().indexOf(name);
            fx->params().set(i, fx->params().def(i).unmap(value));
        }
        fx->params().jumpAll();
        return fx;
    };
    // A click through it; the left side, or the right with [side] 1.
    const auto ring = [](Effect &fx, size_t n, int side = 0) {
        std::vector<float> l(n, 0.0f), r(n, 0.0f);
        l[64] = r[64] = 1.0f;
        for (size_t at = 0; at < n; at += kBlock) fx.run(l.data() + at, r.data() + at, kBlock, false);
        return side == 0 ? l : r;
    };
    auto &q = EngineSettings::get().quality;
    const int was = q.load();

    // In tune: the ring's fundamental against the knob, every rate, both qualities, across the range.
    // Full quality runs a line a side, 1.5 cents either way of the knob; lean one line on it.
    for (int m : {6, 7}) {
        double worstCents = 0.0;
        std::string where;
        for (int32_t rate : {44100, 48000, 96000}) {
            for (int quality : {1, 0}) {
                q.store(quality);
                for (float pitch : {-36.0f, -24.0f, -12.0f, -5.0f, 0.0f, 7.0f, 12.0f, 24.0f}) {
                    auto fx = prepared(rate, {{"model", float(m)}, {"mix", 1.0f}, {"size", 1.0f}, {"damp", 0.0f},
                                              {"tone", 18000.0f}, {"predelay", 0.0f}, {"pitch", pitch}, {"stiff", 0.0f},
                                              {"pick", 0.0f}});
                    const size_t n = size_t(rate) * 5 / 4;
                    for (int side = 0; side < quality + 1; ++side) {
                    if (side == 1) fx->reset();
                    const auto w = ring(*fx, n, side);
                    const double want = 261.6256 * std::pow(2.0, (pitch + (quality ? (side ? 0.015 : -0.015) : 0.0)) / 12.0);
                    const double got = peakNear(w, want, 40.0, size_t(rate) / 4, n, double(rate));
                    const double off = cents(got, want);
                    if (std::fabs(off) > std::fabs(worstCents)) {
                        worstCents = off;
                        where = std::to_string(rate) + " Hz, pitch " + num(pitch) + (quality ? " full" : " lean");
                    }
                    }
                }
            }
        }
        q.store(was);
        ok(std::string(m == 6 ? "tube" : "string") + ": in tune across the pitch range at 44.1, 48 and 96 kHz",
           std::fabs(worstCents) < 1.0, "worst " + num(worstCents) + " cents at " + where);
    }

    // Follow: tuned to the last note, pitch on top of it.
    for (int m : {6, 7}) {
        auto fx = prepared(48000, {{"model", float(m)}, {"mix", 1.0f}, {"size", 1.0f}, {"damp", 0.0f},
                                   {"predelay", 0.0f}, {"follow", 1.0f}, {"pitch", 12.0f}, {"stiff", 0.0f}});
        fx->onNote(45, 110.0f);
        q.store(0);
        const auto w = ring(*fx, 120000);
        q.store(was);
        const double got = peakNear(w, 220.0, 40.0, 12000, 120000, 48000.0);
        ok(std::string(m == 6 ? "tube" : "string") + ": follow tunes to the last note", std::fabs(cents(got, 220.0)) < 1.0,
           num(got) + " Hz for A2 an octave up");
        // A retuned track hands over its own frequency for the note: 45 sits 40 cents sharp.
        const double sharp = 110.0 * std::pow(2.0, 40.0 / 1200.0);
        auto tuned = prepared(48000, {{"model", float(m)}, {"mix", 1.0f}, {"size", 1.0f}, {"damp", 0.0f},
                                      {"predelay", 0.0f}, {"follow", 1.0f}, {"pitch", 12.0f}, {"stiff", 0.0f}});
        tuned->onNote(45, static_cast<float>(sharp));
        q.store(0);
        const auto wt = ring(*tuned, 120000);
        q.store(was);
        const double gotT = peakNear(wt, 2.0 * sharp, 40.0, 12000, 120000, 48000.0);
        ok(std::string(m == 6 ? "tube" : "string") + ": follow lands on a retuned note's frequency",
           std::fabs(cents(gotT, 2.0 * sharp)) < 3.0, num(gotT) + " Hz for " + num(2.0 * sharp));
    }

    // Open: the closed tube rings in odd harmonics, the open one in all of them.
    {
        const auto evenOverOdd = [&](float open) {
            auto fx = prepared(48000, {{"model", 6.0f}, {"mix", 1.0f}, {"size", 0.8f}, {"damp", 0.0f},
                                       {"predelay", 0.0f}, {"pitch", -12.0f}, {"open", open}});
            const auto w = ring(*fx, 96000);
            const double f = 130.8128;
            const double even = strengthAt(w, 2 * f, 4800, 96000, 48000.0) + strengthAt(w, 4 * f, 4800, 96000, 48000.0);
            const double odd = strengthAt(w, f, 4800, 96000, 48000.0) + strengthAt(w, 3 * f, 4800, 96000, 48000.0);
            return dB(even / odd);
        };
        const double closed = evenOverOdd(0.0f), half = evenOverOdd(0.5f), open = evenOverOdd(1.0f);
        ok("tube: open brings the even harmonics in", closed < -20.0 && open > -6.0 && half > closed && half < open,
           num(closed) + " -> " + num(half) + " -> " + num(open) + " dB even against odd");
    }

    // Stiff: the overtones pull sharp.
    {
        const auto stretch = [&](float stiff) {
            auto fx = prepared(48000, {{"model", 7.0f}, {"mix", 1.0f}, {"size", 0.8f}, {"damp", 0.0f},
                                       {"predelay", 0.0f}, {"pitch", -12.0f}, {"stiff", stiff}, {"pick", 0.0f}});
            const auto w = ring(*fx, 96000);
            const double f = 130.8128;
            const double f1 = peakNear(w, f, 30.0, 4800, 96000, 48000.0);
            const double f6 = peakNear(w, 6 * f1, 150.0, 4800, 96000, 48000.0);
            return cents(f6, 6 * f1);
        };
        const double loose = stretch(0.0f), stiff = stretch(1.0f);
        ok("string: stiff pulls the sixth harmonic sharp", std::fabs(loose) < 5.0 && stiff > 20.0,
           num(loose) + " -> " + num(stiff) + " cents");
    }

    // Pick: in the middle the even harmonics drop out.
    {
        const auto evenOverOdd = [&](float pick) {
            auto fx = prepared(48000, {{"model", 7.0f}, {"mix", 1.0f}, {"size", 0.8f}, {"damp", 0.0f},
                                       {"predelay", 0.0f}, {"pitch", -12.0f}, {"stiff", 0.0f}, {"pick", pick}});
            q.store(0);
            const auto w = ring(*fx, 96000);
            q.store(was);
            const double f = 130.8128;
            const double even = strengthAt(w, 2 * f, 4800, 96000, 48000.0) + strengthAt(w, 4 * f, 4800, 96000, 48000.0);
            const double odd = strengthAt(w, f, 4800, 96000, 48000.0) + strengthAt(w, 3 * f, 4800, 96000, 48000.0);
            return dB(even / odd);
        };
        const double end = evenOverOdd(0.0f), middle = evenOverOdd(1.0f);
        ok("string: picked in the middle loses the even harmonics", middle < end - 15.0,
           num(end) + " -> " + num(middle) + " dB even against odd");
    }

    // Loud hits growl in the tube: more of the ring is harmonics it doesn't have when quiet.
    {
        const auto rough = [&](float amp) {
            auto fx = prepared(48000, {{"model", 6.0f}, {"mix", 1.0f}, {"size", 0.6f}, {"damp", 0.0f},
                                       {"predelay", 0.0f}, {"pitch", -12.0f}});
            q.store(0);
            auto in = tone(130.8128f, amp, 48000);
            // Faded in, so the start doesn't ring the third by itself.
            for (size_t i = 0; i < 9600; ++i) in[i] *= float(0.5 - 0.5 * std::cos(M_PI * double(i) / 9600.0));
            for (size_t i = 24000; i < in.size(); ++i) in[i] = 0.0f;
            const auto w = wetOf(run(*fx, in).l, in);
            q.store(was);
            const double f = 130.8128;
            const double fund = strengthAt(w, f, 12000, 24000, 48000.0);
            return dB(strengthAt(w, 3 * f, 12000, 24000, 48000.0) / fund);
        };
        const double quiet = rough(0.0005f), loud = rough(0.5f);
        ok("tube: a loud note growls", loud > quiet + 12.0, num(quiet) + " -> " + num(loud) + " dB third against first");
    }
}

void reverb() {
    printf("- reverb\n");
    const char *names[] = {"room", "plate", "hall", "spring", "early", "space", "tube", "string"};
    constexpr int kModels = 8;
    // A half-second burst of noise, then four and a half seconds of tail.
    auto burst = noise(0.25f, 240000, 3);
    for (size_t i = 24000; i < burst.size(); ++i) burst[i] = 0.0f;

    double roomLevel = 0.0;
    for (int m = 0; m < kModels; ++m) {
        const std::string n = names[m];
        auto fx = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}});
        const auto wet = wetOf(run(*fx, burst).l, burst);
        const double level = rms(wet, 12000, 24000);
        if (m == 0) roomLevel = level;
        else ok(n + ": about as loud as the room at its defaults", std::fabs(dB(level / roomLevel)) < 2.0,
                num(dB(level / roomLevel)) + " dB");
        // A bigger size rings longer. The space has no size; its room is checked below.
        if (m != acidulous::effect::Reverb::Space) {
        auto small = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"size", 0.2f}});
        auto big = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"size", 0.8f}});
        const double ts = fallTime(wetOf(run(*small, burst).l, burst), 24000 + 2400);
        const double tb = fallTime(wetOf(run(*big, burst).l, burst), 24000 + 2400);
        ok(n + ": size 0.8 rings longer than 0.2", tb > ts * 1.5, num(ts) + " s -> " + num(tb) + " s");
        }
        // Damp takes the top off the tail.
        auto bright = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"damp", 0.0f}});
        auto dull = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"damp", 1.0f}});
        const auto high = [&](Effect &e) {
            const auto w = wetOf(run(e, burst).l, burst);
            std::vector<float> d(w.size());
            for (size_t i = 1; i < w.size(); ++i) d[i] = w[i] - w[i - 1];
            return dB(rms(d, 48000, 96000) / rms(w, 48000, 96000));
        };
        const double hb = high(*bright), hd = high(*dull);
        ok(n + ": damp darkens the tail", hd < hb - 3.0, num(hb) + " -> " + num(hd) + " dB of top");
    }

    // Every model, with freeze after two seconds or shimmer at the top, size
    // at the top and wobble on, for 30 s of bursts: finite and bounded.
    {
        auto in = noise(0.5f, 48000 * 30, 11);
        for (size_t i = 48000 * 2; i < in.size(); ++i) if ((i / 48000) % 4 != 0) in[i] = 0.0f;
        for (int m = 0; m < kModels; ++m) {
            for (int frozen = 0; frozen < 2; ++frozen) {
                // The model knobs at their tops too: the biggest tiled room, the top rate, all drip and knock.
                auto fx = make("Reverb", {{"model", float(m)}, {"size", 1.0f}, {"shimmer", 1.0f}, {"mix", 1.0f},
                                          {"wobble", 1.0f}, {"bloom", 0.5f}, {"duck", 0.5f}, {"distance", 0.5f},
                                          {"drip", 1.0f}, {"knock", 1.0f}, {"rate", 32000.0f}, {"width", 30.0f},
                                          {"depth", 50.0f}, {"height", 15.0f}, {"position", 1.0f}, {"material", 0.0f},
                                          {"pitch", -36.0f}, {"open", 1.0f}, {"stiff", 1.0f}, {"pick", 1.0f}});
                Stereo s{in, in};
                for (size_t at = 0; at < in.size(); at += kBlock) {
                    if (frozen && at == 48000 * 2) set(*fx, "freeze", 1.0f);
                    fx->run(s.l.data() + at, s.r.data() + at, kBlock, false);
                }
                const double last = rms(s.l, s.l.size() - 48000, s.l.size());
                ok(std::string(names[m]) + (frozen ? ": frozen" : ": shimmer and size at the top") +
                       " for 30 s, finite and bounded",
                   finite(s) && peak(s.l) < 4.0 && peak(s.r) < 4.0 && (!frozen || last > 0.01),
                   "peak " + num(std::max(peak(s.l), peak(s.r))) + ", last second " + num(last));
            }
        }
    }

    // reset() makes the next render the same as the first, every model, with
    // everything that holds state switched on.
    for (int m = 0; m < kModels; ++m) {
        auto fx = make("Reverb", {{"model", float(m)}, {"shimmer", 0.5f}, {"wobble", 0.6f}, {"bits", 10.0f},
                                  {"crush", 3.0f}, {"gate", 0.3f}, {"duck", 0.5f}, {"distance", 0.4f}, {"bloom", 0.5f},
                                  {"knock", 1.0f}});
        const auto in = hits(96000);
        const auto a = run(*fx, in);
        fx->reset();
        const auto b = run(*fx, in);
        ok(std::string(names[m]) + ": reset() renders the same twice", worst(a.l, b.l) == 0.0 && worst(a.r, b.r) == 0.0);
    }

    // Lean quality: still a reverb, about as loud.
    {
        auto &q = EngineSettings::get().quality;
        const int was = q.load();
        for (int m = 1; m < kModels; ++m) {
            q.store(1);
            auto full = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}});
            const double lf = rms(wetOf(run(*full, burst).l, burst), 12000, 48000);
            q.store(0);
            auto lean = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}});
            const double ll = rms(wetOf(run(*lean, burst).l, burst), 12000, 48000);
            ok(std::string(names[m]) + ": lean within 2 dB of full", std::fabs(dB(ll / lf)) < 2.0, num(dB(ll / lf)) + " dB");
        }
        q.store(was);
    }

    // Duck: the tail drops while the track plays and comes back in the gaps.
    {
        // Four bars: a second of noise then a second of nothing, twice.
        auto in = noise(0.25f, 48000 * 4, 5);
        for (size_t i = 0; i < in.size(); ++i) if ((i / 48000) % 2 == 1) in[i] = 0.0f;
        for (int m = 0; m < kModels; ++m) {
            auto plain = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"size", 0.8f}});
            auto ducked = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"size", 0.8f}, {"duck", 1.0f}, {"duckrel", 100.0f}});
            const auto a = wetOf(run(*plain, in).l, in), b = wetOf(run(*ducked, in).l, in);
            const double playing = dB(rms(b, 48000 * 2 + 12000, 48000 * 3) / rms(a, 48000 * 2 + 12000, 48000 * 3));
            const double gap = dB(rms(b, 48000 * 3 + 24000, 48000 * 4) / rms(a, 48000 * 3 + 24000, 48000 * 4));
            ok(std::string(names[m]) + ": duck pulls the tail down while it plays, not in the gaps",
               playing < -12.0 && gap > -1.0, num(playing) + " dB playing, " + num(gap) + " dB in the gap");
        }
    }

    // Distance: later, more wet against the dry, less top.
    for (int m = 0; m < kModels; ++m) {
        std::vector<float> click(96000, 0.0f);
        click[100] = 1.0f;
        auto near = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"knock", 0.0f}});
        auto far = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"distance", 1.0f}, {"knock", 0.0f}});
        const auto a = wetOf(run(*near, click).l, click), b = wetOf(run(*far, click).l, click, 0.5f);
        const auto first = [](const std::vector<float> &x) {
            const double p = peak(x);
            for (size_t i = 0; i < x.size(); ++i) if (std::fabs(x[i]) > 0.05 * p) return double(i) / kRate;
            return 0.0;
        };
        // Over the same stretch of tail: the far one starts 80 ms later.
        const auto top = [](const std::vector<float> &x, size_t from) {
            std::vector<float> d(x.size());
            for (size_t i = 1; i < x.size(); ++i) d[i] = x[i] - x[i - 1];
            return dB(rms(d, from, from + 43200) / rms(x, from, from + 43200));
        };
        const double later = (first(b) - first(a)) * 1000.0;
        // The wet against the dry: the far dry is half, so a louder wet shows twice.
        const double louder = dB(rms(b, 0, 96000) / 0.5 / rms(a, 0, 96000));
        ok(std::string(names[m]) + ": distance starts the tail later, louder against the dry and darker",
           // The spring has little over 4 kHz for the air loss to take.
           // The tube and string have little over 3 kHz to take.
           later > 50.0 && louder > 6.0 && (m >= 6 || top(b, 4800 + 3840) < top(a, 4800) - (m == 3 ? 1.0 : 3.0)),
           num(later) + " ms later, " + num(louder) + " dB more wet, top " + num(top(a, 4800)) + " -> " + num(top(b, 4800 + 3840)) + " dB");
    }

    // Bloom: the tail starts thin and fills in.
    for (int m = 0; m < kModels; ++m) {
        auto in = noise(0.25f, 48000 * 3, 9);
        auto plain = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}});
        auto bloom = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"bloom", 1.0f}});
        const auto a = wetOf(run(*plain, in).l, in), b = wetOf(run(*bloom, in).l, in);
        const double early = dB(rms(b, 4800, 14400) / rms(a, 4800, 14400));
        const double late = dB(rms(b, 48000 * 2, 48000 * 3) / rms(a, 48000 * 2, 48000 * 3));
        ok(std::string(names[m]) + ": bloom starts thin and fills in by two seconds", early < -8.0 && late > -2.0,
           num(early) + " dB at the start, " + num(late) + " dB after two seconds");
    }

    // Knock: a hit shakes a low thump out of the spring, a sustained tone doesn't.
    {
        const auto low = [](const std::vector<float> &x, size_t from, size_t to) {
            return rms(lowPass(lowPass(x, 90.0), 90.0), from, to);
        };
        std::vector<float> hit(96000, 0.0f);
        for (size_t i = 24000; i < 24000 + 480; ++i) hit[i] = 0.8f * std::exp(-double(i - 24000) / 60.0) * ((i & 1) ? 1.0f : -1.0f);
        auto soft = make("Reverb", {{"model", 3.0f}, {"mix", 1.0f}, {"knock", 0.0f}});
        auto hard = make("Reverb", {{"model", 3.0f}, {"mix", 1.0f}, {"knock", 1.0f}});
        const double a = low(wetOf(run(*soft, hit).l, hit), 24000, 48000);
        const double b = low(wetOf(run(*hard, hit).l, hit), 24000, 48000);
        ok("spring: knock thumps on a hit", dB(b / (a + 1e-9)) > 12.0, num(dB(b / (a + 1e-9))) + " dB more under 90 Hz");
        // A 220 Hz tone faded in over 100 ms and held: no thump while it holds.
        auto held = tone(220.0f, 0.5f, 96000);
        for (size_t i = 0; i < 4800; ++i) held[i] *= float(i) / 4800.0f;
        auto softT = make("Reverb", {{"model", 3.0f}, {"mix", 1.0f}, {"knock", 0.0f}});
        auto hardT = make("Reverb", {{"model", 3.0f}, {"mix", 1.0f}, {"knock", 1.0f}});
        const auto wa = wetOf(run(*softT, held).l, held), wb = wetOf(run(*hardT, held).l, held);
        ok("spring: knock leaves a held tone alone", worst(wa, wb) == 0.0, num(worst(wa, wb)) + " apart");
    }

    // Drip spreads the chirp: in the first echoes a click's highs peak later than its lows.
    {
        std::vector<float> click(48000, 0.0f);
        click[100] = 1.0f;
        const auto peakAt = [](const std::vector<float> &x, double hz) {
            double best = 0.0, at = 0.0;
            for (size_t t = 30; t <= 80; t += 2) {
                const size_t from = 100 + t * 48;
                const double e = goertzel(x, hz, from, from + 192);
                if (e > best) { best = e; at = double(t); }
            }
            return at;
        };
        const auto lag = [&](float drip) {
            auto fx = make("Reverb", {{"model", 3.0f}, {"mix", 1.0f}, {"drip", drip}, {"knock", 0.0f}, {"damp", 0.0f}});
            const auto w = wetOf(run(*fx, click).l, click);
            return peakAt(w, 3600.0) - peakAt(w, 1000.0);
        };
        const double lo = lag(0.0), hi = lag(1.0);
        ok("spring: drip brings the highs back later than the lows", hi > lo + 4.0,
           num(lo) + " ms -> " + num(hi) + " ms behind");
    }

    // Early: a lower rate takes more of the top away and leaves images above it.
    {
        const auto top = [&](float rate) {
            auto fx = make("Reverb", {{"model", 4.0f}, {"mix", 1.0f}, {"rate", rate}});
            const auto w = wetOf(run(*fx, burst).l, burst);
            return crossingsHz(w, 48000, 96000);
        };
        const double a = top(8000.0f), b = top(32000.0f);
        ok("early: the rate changes the tail", std::fabs(a - b) > 50.0, num(a) + " Hz of crossings at 8k, " + num(b) + " at 32k");
    }

    // Space: a bigger room rings longer, curtains shorter than tile.
    {
        const auto tail = [&](float w, float d, float h, float mat) {
            auto fx = make("Reverb", {{"model", 5.0f}, {"mix", 1.0f}, {"width", w}, {"depth", d}, {"height", h},
                                      {"material", mat}});
            return fallTime(wetOf(run(*fx, burst).l, burst), 24000 + 2400);
        };
        const double small = tail(4.0f, 5.0f, 3.0f, 1.0f), big = tail(25.0f, 40.0f, 12.0f, 1.0f);
        ok("space: a bigger room rings longer", big > small * 2.0, num(small) + " s -> " + num(big) + " s");
        const double tile = tail(9.0f, 14.0f, 4.5f, 0.0f), curtains = tail(9.0f, 14.0f, 4.5f, 3.0f);
        ok("space: curtains ring shorter than tile", curtains < tile * 0.5, num(tile) + " s -> " + num(curtains) + " s");
        // The first reflection: nearer the back wall, the back wall comes back sooner.
        std::vector<float> click(48000, 0.0f);
        click[100] = 1.0f;
        const auto firstEr = [&](float pos) {
            auto fx = make("Reverb", {{"model", 5.0f}, {"mix", 1.0f}, {"predelay", 0.0f}, {"position", pos}});
            const auto w = wetOf(run(*fx, click).l, click);
            const double p = peak(w);
            for (size_t i = 0; i < w.size(); ++i) if (std::fabs(w[i]) > 0.3 * p) return double(i);
            return 0.0;
        };
        ok("space: the reflections move with where you stand", firstEr(0.0f) != firstEr(1.0f),
           num(firstEr(0.0f)) + " -> " + num(firstEr(1.0f)) + " samples");
    }

    // Space: moving the room while a tone plays doesn't click.
    {
        auto in = tone(330.0f, 0.4f, 96000);
        auto fx = make("Reverb", {{"model", 5.0f}, {"mix", 1.0f}});
        Stereo s{in, in};
        for (size_t at = 0; at < in.size(); at += kBlock) {
            if (at == 48000) {
                set(*fx, "position", 1.0f);
                set(*fx, "width", 25.0f);
                set(*fx, "height", 2.0f);
                set(*fx, "material", 3.0f);
            }
            fx->run(s.l.data() + at, s.r.data() + at, kBlock, false);
        }
        const auto w = wetOf(s.l, in);
        const auto rough = [&](size_t from, size_t to) {
            double m = 0.0;
            for (size_t i = from; i < to; ++i) m = std::max(m, double(std::fabs(w[i] - 2.0f * w[i - 1] + w[i - 2])));
            return m;
        };
        // A 330 Hz tone's second difference is about 0.002 of its level; a click is far over that.
        const double before = rough(24000, 48000) / peak(w), after = rough(48000, 48000 + 9600) / peak(w);
        ok("space: moving the room doesn't click", after < 0.01, num(before) + " -> " + num(after) + " of the peak");
    }

    // The model knobs do nothing on the other models.
    for (int m = 0; m < kModels; ++m) {
        auto a = make("Reverb", {{"model", float(m)}, {"mix", 0.5f}});
        auto b = make("Reverb", {{"model", float(m)}, {"mix", 0.5f}, {"drip", m == 3 ? 0.5f : 0.9f},
                                 {"knock", m == 3 ? 0.25f : 1.0f}, {"rate", m == 4 ? 16000.0f : 9000.0f},
                                 {"width", m == 5 ? 9.0f : 20.0f}, {"depth", m == 5 ? 14.0f : 3.0f},
                                 {"height", m == 5 ? 4.5f : 12.0f}, {"position", m == 5 ? 0.5f : 0.1f},
                                 {"material", m == 5 ? 1.0f : 3.0f}, {"pitch", m >= 6 ? 0.0f : 7.0f},
                                 {"follow", m >= 6 ? 0.0f : 1.0f}, {"open", m == 6 ? 0.0f : 0.8f},
                                 {"stiff", m == 7 ? 0.2f : 0.9f}, {"pick", m == 7 ? 0.25f : 0.8f}});
        b->onNote(40, 440.0f * std::pow(2.0f, -29.0f / 12.0f));
        ok(std::string(names[m]) + ": the other models' knobs change nothing",
           worst(run(*a, hits(48000)).l, run(*b, hits(48000)).l) == 0.0);
    }

    // At 44.1 and 96 kHz too, every model with everything up stays finite.
    for (int32_t rate : {44100, 96000}) {
        for (int m = 0; m < kModels; ++m) {
            std::unique_ptr<Effect> fx(EffectRegistry::create("Reverb"));
            fx->prepare(rate);
            for (int32_t i = 0; i < fx->params().size(); ++i) {
                const char *n = fx->params().def(i).name;
                if (std::strcmp(n, "gain") == 0 || std::strcmp(n, "freeze") == 0 || std::strcmp(n, "gate") == 0 ||
                    std::strcmp(n, "duck") == 0)
                    continue;
                fx->params().set(i, 1.0f);
            }
            fx->params().set(fx->params().indexOf("model"), float(m) / float(kModels - 1));
            fx->params().jumpAll();
            auto in = noise(0.5f, size_t(rate) * 4, 13);
            for (size_t i = size_t(rate); i < in.size(); ++i) in[i] = 0.0f;
            Stereo s{in, in};
            for (size_t at = 0; at + kBlock <= in.size(); at += kBlock) fx->run(s.l.data() + at, s.r.data() + at, kBlock, false);
            const double tail = rms(s.l, size_t(rate) * 11 / 10, size_t(rate) * 16 / 10);
            ok(std::string(names[m]) + " at " + std::to_string(rate) + " Hz, everything up: finite, bounded and ringing",
               finite(s) && peak(s.l) < 4.0 && tail > 1e-3, "peak " + num(peak(s.l)) + ", tail " + num(tail));
        }
    }

    reverbWaves();

    // The new knobs at their defaults leave the room as it was: checked against
    // master by tools/match_master.sh. Here, at least, they cost nothing.
    {
        auto a = make("Reverb", {{"size", 0.7f}, {"shimmer", 0.3f}});
        auto b = make("Reverb", {{"size", 0.7f}, {"shimmer", 0.3f}, {"duck", 0.0f}, {"distance", 0.0f}, {"bloom", 0.0f},
                                 {"duckrel", 1500.0f}});
        ok("room: duckrel alone changes nothing", worst(run(*a, burst).l, run(*b, burst).l) == 0.0);
    }
}

int main() {
    printf("character effects\n");
    smash();
    acid();
    mouth();
    tape();
    slicer();
    magneto();
    horn();
    spectral();
    formula();
    delayModes();
    transient();
    midSide();
    deEsser();
    reverb();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
