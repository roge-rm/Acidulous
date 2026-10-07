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
#include <engine/effect/Magneto.h>
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
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
