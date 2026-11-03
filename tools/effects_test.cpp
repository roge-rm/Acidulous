// Tests the character effects: Smash, Acid, Mouth, Tape, Slicer, Horn,
// Spectral, Formula, Transient, Midside, Deesser, Venue, Vessel, the Delay's
// modes and the Reverb's models. Each is rendered and measured for the thing it's for,
// since none of it shows in a frequency response.
#include <functional>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <tuple>
#include <vector>

#include <engine/core/Settings.h>
#include <engine/effect/Effect.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Magneto.h>
#include <engine/effect/Mouth.h>
#include <engine/effect/Reverb.h>
#include <engine/effect/Spectral.h>
#include <engine/effect/Vessel.h>
#include <engine/machine/diction/Phones.h>
#include <engine/machine/formulate/Expr.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Rack.h>
#include "venue_impulse.h"

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

/** Runs a mono signal through at 120 with the song rolling, [key] as the sidechain if given. */
Stereo runRolling(Effect &fx, const std::vector<float> &in, const std::vector<float> *key = nullptr,
                  const ModTransport *transport = nullptr) {
    ModTransport t;
    t.rolling = true;
    fx.setTransport(transport != nullptr ? *transport : t);
    Stereo s{in, in};
    const double ticksPerSample = kTicksPerBeat * 120.0 / 60.0 / kRate;
    for (size_t at = 0; at < in.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, in.size() - at));
        fx.setKey(key != nullptr ? key->data() + at : nullptr);
        fx.onBlock(static_cast<int64_t>(at * ticksPerSample), static_cast<int64_t>((at + n) * ticksPerSample), 120.0f);
        fx.run(s.l.data() + at, s.r.data() + at, n, false);
    }
    return s;
}

/** The beat: each band ducking on a rhythm or under another track. */
void smashBeat() {
    // A beat at 120 is 24000 frames; a sixteenth 6000.
    const auto bass = tone(80.0f, 0.2f, 96000);
    const auto music = noise(0.3f, 96000);
    {
        // Every new knob turned but each band's duck off: as without them, bit for bit.
        auto plain = make("Smash");
        auto off = make("Smash", {{"lowdepth", 20.0f}, {"middepth", 3.0f}, {"recover", 600.0f}, {"sidechain", 3.0f}});
        const auto key = noise(0.5f, 96000, 7);
        ok("beat: every duck off plays as before, to the bit",
           worst(runRolling(*plain, music).l, runRolling(*off, music, &key).l) == 0.0);
    }
    // Only the beat: no squash, no lift and depth 0, so what moves is the duck.
    auto beat = [&](std::initializer_list<std::pair<const char *, float>> extra) {
        auto fx = make("Smash", {{"depth", 0.0f}, {"up", 0.0f}, {"down", 0.0f}, {"lowdepth", 12.0f}, {"recover", 100.0f}});
        for (const auto &[name, value] : extra) set(*fx, name, value);
        return fx;
    };
    {
        // Low on the beats: down at each beat, back up before the next, at depth 0.
        auto fx = beat({{"lowduck", 2.0f}});
        const auto out = runRolling(*fx, bass).l;
        const double at = dB(rms(out, 24000, 24480) / rms(bass, 24000, 24480));
        const double before = dB(rms(out, 46000, 47520) / rms(bass, 46000, 47520));
        ok("beat: the lows duck on the beat, at depth 0", at < -7.0 && before > -1.0,
           num(at) + " dB on the beat, " + num(before) + " dB before the next");
        // Down by the time the beat lands: the 5 ms before it already going.
        const double lead = dB(rms(out, 23880, 24000) / rms(bass, 23880, 24000));
        ok("beat: the duck starts just before the beat", lead < -1.0 && lead > -12.0, num(lead) + " dB");
        double step = 0.0, stepIn = 0.0;
        for (size_t i = 1; i < out.size(); ++i) {
            step = std::max(step, double(std::fabs(out[i] - out[i - 1])));
            stepIn = std::max(stepIn, double(std::fabs(bass[i] - bass[i - 1])));
        }
        ok("beat: no step bigger than the tone's own", step <= stepIn * 1.05, num(step) + " vs " + num(stepIn));
    }
    {
        // Offbeats on the highs leave a bass alone.
        auto fx = beat({{"highduck", 3.0f}, {"highdepth", 12.0f}});
        const auto out = runRolling(*fx, bass).l;
        const double d = dB(rms(out, 30000, 30480) / rms(bass, 30000, 30480));
        ok("beat: the highs' offbeats leave a bass alone", std::fabs(d) < 0.3, num(d) + " dB on an offbeat");
    }
    {
        // Stopped, the rhythms don't play.
        auto fx = beat({{"lowduck", 2.0f}});
        ok("beat: stopped, nothing ducks", worst(run(*fx, bass).l, run(*make("Smash", {{"depth", 0.0f}, {"up", 0.0f}, {"down", 0.0f}}), bass).l) < 1e-6);
    }
    {
        // Swung all the way, the second sixteenth lands at tick 90, not 60.
        auto swung = beat({{"lowduck", 5.0f}, {"recover", 20.0f}});
        ModTransport sw;
        sw.rolling = true;
        sw.swing = 75.0f;
        const auto out = runRolling(*swung, bass, nullptr, &sw).l;
        size_t low = 0;
        double lowest = 1e9;
        for (size_t w = 3000; w + 480 < 12000; w += 120) {
            const double r = rms(out, w, w + 480) / rms(bass, w, w + 480);
            if (r < lowest) { lowest = r; low = w + 240; }
        }
        ok("beat: swing moves the second sixteenth with the track", low > 8400 && low < 9600, "deepest at frame " + std::to_string(low));
    }
    {
        // Keyed: a burst on the key every beat ducks the lows under it; sidechain own, nothing.
        std::vector<float> key(96000, 0.0f);
        for (size_t b = 12000; b < key.size(); b += 24000)
            for (size_t i = 0; i < 2400; ++i) key[b + i] = 0.8f * std::sin(2.0f * float(M_PI) * 60.0f * i / kRate);
        auto fx = beat({{"lowduck", 1.0f}, {"sidechain", 1.0f}, {"threshold", -24.0f}});
        const auto out = runRolling(*fx, bass, &key).l;
        const double under = dB(rms(out, 13000, 14000) / rms(bass, 13000, 14000));
        const double clear = dB(rms(out, 34000, 35000) / rms(bass, 34000, 35000));
        // The low band holds most of an 80 Hz tone but not all, so 12 dB on it is about 9 on the tone.
        ok("keyed: the lows duck under the key's hits", under < -7.0 && clear > -1.0,
           num(under) + " dB under a hit, " + num(clear) + " dB between");
        auto own = beat({{"lowduck", 1.0f}});
        ok("keyed: with no track keyed, nothing ducks",
           worst(runRolling(*own, bass).l, run(*make("Smash", {{"depth", 0.0f}, {"up", 0.0f}, {"down", 0.0f}}), bass).l) < 1e-6);
        // One NaN in the key must not latch the duck.
        {
            std::vector<float> bad = key;
            bad[20000] = std::nanf("");
            auto nf = beat({{"lowduck", 1.0f}, {"sidechain", 1.0f}, {"threshold", -24.0f}});
            const auto o = runRolling(*nf, bass, &bad);
            bool fin = true;
            for (size_t i = 0; i < o.l.size(); ++i) fin = fin && std::isfinite(o.l[i]) && std::isfinite(o.r[i]);
            const double after = dB(rms(o.l, 34000, 35000) / rms(bass, 34000, 35000));
            ok("keyed: a NaN in the key doesn't latch the duck", fin && after > -1.0, num(after) + " dB after");
        }
        // Reset puts it all back: the same twice.
        auto twice = make("Smash", {{"lowduck", 1.0f}, {"sidechain", 1.0f}, {"midduck", 4.0f}, {"highduck", 9.0f}});
        const auto a = runRolling(*twice, music, &key).l;
        twice->reset();
        ok("beat: after a reset it plays the same again", worst(a, runRolling(*twice, music, &key).l) == 0.0);
    }
}

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
    smashBeat();
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

/** Clicks every kClickGap samples: each one's ring is the filter's response as it's set then. */
constexpr size_t kClickGap = 4096;
std::vector<float> clicks(size_t n) {
    std::vector<float> x(n, 0.0f);
    for (size_t i = 0; i < n; i += kClickGap) x[i] = 0.1f;
    return x;
}

/**
 * Where the response to the clicks in [from, to) peaks between [lo] and
 * [hi] Hz, searched in 2-cent steps. [from] is on a click.
 */
double peakHz(const std::vector<float> &x, size_t from, size_t to, double lo, double hi) {
    const size_t n = kClickGap;
    double best = lo, bestPower = -1.0;
    for (double f = lo; f <= hi; f *= std::exp2(2.0 / 1200.0)) {
        double power = 0.0;
        for (size_t at = from; at + n <= to; at += n) {
            double re = 0.0, im = 0.0;
            for (size_t i = 0; i < n; ++i) {
                const double ph = 2.0 * M_PI * f * i / kRate;
                re += x[at + i] * std::cos(ph);
                im += x[at + i] * std::sin(ph);
            }
            power += re * re + im * im;
        }
        if (power > bestPower) { bestPower = power; best = f; }
    }
    return best;
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
    {
        // Follow at 0 is the filter as it was, whatever the key knobs and the notes say.
        auto plain = make("Acid", {{"pattern", 7.0f}, {"resonance", 0.9f}});
        auto keyed = make("Acid", {{"pattern", 7.0f}, {"resonance", 0.9f}, {"from", 1.0f}, {"slides", 1.0f},
                                   {"glide", 300.0f}, {"octave", 3.0f}, {"key", 2.0f}, {"scale", 10.0f}});
        keyed->setHearsNotes(true);
        keyed->onNote(45, 110.0f);
        keyed->onNote(52, 164.8f);
        const auto a = run(*plain, buzz).l, b = run(*keyed, buzz).l;
        ok("follow 0 is bit for bit the filter without a key", worst(a, b) == 0.0, num(worst(a, b)));
    }
    // A screaming filter rung by clicks: where its peak is, in cents from [want].
    const auto hiss = clicks(96000);
    auto cents = [](double hz, double want) { return 1200.0 * std::log2(hz / want); };
    const std::initializer_list<std::pair<const char *, float>> scream = {
        {"resonance", 0.98f}, {"env", 0.0f}, {"drive", 0.0f}, {"follow", 1.0f}, {"cutoff", 2000.0f}};
    {
        // Halves: step 0 is the key's root and step 8 four steps up, a fifth
        // in A minor, an octave up from A3.
        auto fx = make("Acid", scream);
        set(*fx, "pattern", 6.0f);
        set(*fx, "slides", 0.0f);
        const auto out = run(*fx, hiss).l;
        const double a = peakHz(out, 4096, 45056, 300.0, 1000.0), e = peakHz(out, 49152, 94208, 300.0, 1000.0);
        ok("follow 1 sings the key's root", std::fabs(cents(a, 440.0)) < 20.0, num(a) + " Hz");
        ok("and the step's note, a fifth up", std::fabs(cents(e, 659.255)) < 20.0, num(e) + " Hz");
    }
    {
        // From the track's notes, with the track's hits: the note played, up an octave.
        auto fx = make("Acid", scream);
        set(*fx, "from", 1.0f);
        fx->setHearsNotes(true);
        fx->onNote(50, 146.83f);
        const auto out = run(*fx, hiss).l;
        const double d = peakHz(out, 4096, 94208, 150.0, 600.0);
        ok("from the notes, it sings the note played", std::fabs(cents(d, 293.66)) < 20.0, num(d) + " Hz");
        // Off a track no notes come, so it keeps to the scale: A4 on the first step of halves.
        auto off = make("Acid", scream);
        set(*off, "from", 1.0f);
        set(*off, "pattern", 6.0f);
        const auto o = run(*off, hiss).l;
        const double s = peakHz(o, 4096, 45056, 300.0, 1000.0);
        ok("off a track, from notes keeps to the scale", std::fabs(cents(s, 440.0)) < 20.0, num(s) + " Hz");
    }
    {
        // Halfway between follow 0 and 1 it sits halfway in octaves between the knob and the note.
        auto fx = make("Acid", scream);
        set(*fx, "pattern", 6.0f);
        set(*fx, "follow", 0.5f);
        set(*fx, "cutoff", 1760.0f);
        const auto out = run(*fx, hiss).l;
        const double h = peakHz(out, 4096, 45056, 500.0, 1400.0);
        ok("follow 0.5 sits halfway between knob and note", std::fabs(cents(h, 880.0)) < 20.0, num(h) + " Hz");
    }
    {
        // A slide step glides: 24 to 110 ms after step 8 it's still on its way up.
        auto jump = make("Acid", scream), glide = make("Acid", scream);
        for (auto *fx : {jump.get(), glide.get()}) set(*fx, "pattern", 6.0f);
        set(*jump, "slides", 0.0f);
        set(*glide, "slides", 1.0f);
        set(*glide, "glide", 600.0f);
        const auto j = run(*jump, hiss).l, g = run(*glide, hiss).l;
        const double jHz = peakHz(j, 49152, 53248, 300.0, 1000.0);
        const double gHz = peakHz(g, 49152, 53248, 300.0, 1000.0);
        ok("a step without a slide jumps to its note", std::fabs(cents(jHz, 659.255)) < 20.0, num(jHz) + " Hz");
        ok("a slide step is still gliding up to it", gHz > 450.0 && gHz < 620.0, num(gHz) + " Hz");
    }
    {
        // Reset and play again: the same, key and glide included.
        auto fx = make("Acid", scream);
        set(*fx, "pattern", 3.0f);
        set(*fx, "slides", 0.6f);
        const auto a = run(*fx, buzz).l;
        fx->reset();
        const auto b = run(*fx, buzz).l;
        ok("with a key, a reset plays the same again", worst(a, b) == 0.0, num(worst(a, b)));
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

void mouthWords() {
    printf("- mouth words\n");
    const auto voice = saw(110.0f, 0.4f, 48000);
    uint8_t ee[8], sh[8];
    const int32_t eeCount = machine::diction::parsePhones("IY", ee, 8);
    const int32_t shCount = machine::diction::parsePhones("S IY", sh, 8);
    // Through a rack: its words and notes reach the insert.
    {
        Rack rack;
        auto fx = make("Mouth", {{"vowel", 0.0f}, {"depth", 0.0f}, {"words", 1.0f}});
        auto *m = static_cast<effect::Mouth *>(fx.get());
        rack.swapEffect(0, fx.get());
        rack.lyric(ee, eeCount);
        rack.playSequenced(0x90, 60, 100);
        ok("a rack's words reach its insert", m->saying() && m->wordFormant(1) > 2000.0f, num(m->wordFormant(1)) + " Hz");
        rack.playSequenced(0x80, 60, 0);
        rack.playSequenced(0x90, 62, 100);
        ok("a note without words goes back to the move", !m->saying());
        rack.swapEffect(0, nullptr);
    }
    // With a machine and the sustain down, the insert hears the off when the machine does.
    {
        Rack rack;
        std::unique_ptr<Machine> synth(MachineRegistry::create("Trinity"));
        synth->prepare(static_cast<int32_t>(kRate));
        synth->reset();
        rack.swapMachine(synth.get());
        auto fx = make("Mouth", {{"words", 1.0f}});
        auto *m = static_cast<effect::Mouth *>(fx.get());
        rack.swapEffect(0, fx.get());
        rack.setParam(Unit::Performance, kPerfSustain, 1.0f, false);
        rack.lyric(sh, shCount);
        rack.playSequenced(0x90, 60, 100);
        rack.playSequenced(0x80, 60, 0);
        ok("a pedal holds the word", m->saying() && !m->released());
        rack.setParam(Unit::Performance, kPerfSustain, 0.0f, false);
        ok("and the pedal coming up lets it go", m->released());
        rack.swapEffect(0, nullptr);
        rack.swapMachine(nullptr);
    }
    // A key played twice without an off is one key: its off lets it go.
    {
        auto fx = make("Mouth", {{"words", 1.0f}});
        auto *m = static_cast<effect::Mouth *>(fx.get());
        fx->onLyric(ee, eeCount);
        fx->onNote(60, 261.6f);
        fx->onNote(60, 261.6f);
        fx->onNoteOff(60);
        fx->onNote(62, 293.7f);
        ok("a retriggered key is let go by one off", !m->saying());
    }
    // A word ending on a stop: once it's said, the mouth opens back to the move.
    {
        uint8_t at[8];
        const int32_t atCount = machine::diction::parsePhones("AA T", at, 8);
        auto fx = make("Mouth", {{"vowel", 0.3f}, {"depth", 0.0f}, {"words", 1.0f}});
        auto *m = static_cast<effect::Mouth *>(fx.get());
        fx->onLyric(at, atCount);
        fx->onNote(45, 110.0f);
        run(*fx, saw(110.0f, 0.4f, 14400));
        fx->onNoteOff(45);
        const auto after = run(*fx, saw(110.0f, 0.4f, 24000)).l;
        const auto plain = run(*make("Mouth", {{"vowel", 0.3f}, {"depth", 0.0f}}), saw(110.0f, 0.4f, 48000)).l;
        ok("after a word's last sound it's over", !m->saying());
        ok("and the mouth is open again", rms(after, 14400, 24000) > rms(plain, 24000, 48000) * 0.8,
           num(dB(rms(after, 14400, 24000))) + " vs " + num(dB(rms(plain, 24000, 48000))) + " dB");
    }
    // Words on an oo: the note's ee takes it, as far as the knob says.
    const auto said = [&](float words, const uint8_t *phones, int32_t count) {
        auto fx = make("Mouth", {{"vowel", 0.0f}, {"depth", 0.0f}, {"words", words}});
        fx->onLyric(phones, count);
        fx->onNote(45, 110.0f);
        return run(*fx, voice).l;
    };
    const auto plain = run(*make("Mouth", {{"vowel", 0.0f}, {"depth", 0.0f}}), voice).l;
    const auto off = said(0.0f, ee, eeCount), full = said(1.0f, ee, eeCount), half = said(0.5f, ee, eeCount);
    bool same = true;
    for (size_t i = 0; i < plain.size(); ++i) same = same && plain[i] == off[i];
    ok("with words at 0 the words change nothing", same);
    ok("words say ee on an oo", upperFormants(full, 9600) > upperFormants(plain, 9600) + 10.0,
       num(upperFormants(plain, 9600)) + " -> " + num(upperFormants(full, 9600)) + " dB");
    ok("half the words is in between", upperFormants(half, 9600) > upperFormants(plain, 9600) + 2.0 &&
       upperFormants(half, 9600) < upperFormants(full, 9600) - 2.0, num(upperFormants(half, 9600)) + " dB");
    // An s first: the mouth nearly shut and hissing, then the vowel.
    const auto hissed = said(1.0f, sh, shCount);
    const double sRms = rms(hissed, 2400, 4800), vRms = rms(hissed, 24000, 48000);
    // How much of it is in the top, from the size of the steps between samples.
    const auto edge = [&](size_t from, size_t to) {
        double d = 0.0;
        for (size_t i = from; i < to; ++i) d += double(hissed[i] - hissed[i - 1]) * (hissed[i] - hissed[i - 1]);
        return std::sqrt(d / double(to - from)) / (rms(hissed, from, to) + 1e-20);
    };
    ok("an s is quieter than its vowel", sRms < vRms * 0.7, num(dB(sRms)) + " vs " + num(dB(vRms)) + " dB");
    ok("and it hisses", edge(2400, 4800) > edge(24000, 48000) * 1.5, num(edge(2400, 4800)) + " vs " + num(edge(24000, 48000)));
    // Word after word, each starting on a hiss or a stop: none kicks.
    {
        auto fx = make("Mouth", {{"vowel", 0.3f}, {"depth", 0.0f}, {"words", 1.0f}});
        const char *lines[] = {"T W IH NG", "K AH L", "S T AA R", "P AA"};
        std::vector<float> out;
        int pitch = 45;
        for (const char *line : lines) {
            uint8_t ph[16];
            const int32_t n = machine::diction::parsePhones(line, ph, 16);
            fx->onLyric(ph, n);
            fx->onNote(pitch, 110.0f);
            const auto part = run(*fx, saw(110.0f, 0.4f, 24000)).l;
            fx->onNoteOff(pitch);
            const auto gap = run(*fx, saw(110.0f, 0.4f, 9600)).l;
            out.insert(out.end(), part.begin(), part.end());
            out.insert(out.end(), gap.begin(), gap.end());
        }
        // The loudest held vowel: the last note's ah.
        const double vowelPeak = peak(std::vector<float>(out.end() - 21600, out.end() - 9600));
        ok("word after word, none kicks", peak(out) < vowelPeak * 1.25, num(peak(out)) + " against " + num(vowelPeak));
    }
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

/** The stutter: off leaves Spectral as it was, the loop repeats, reset replays it, and letting go returns to the track. */
void spectralStutter() {
    // At 117.1875 bpm a sixteenth is 6144 frames, twelve hops of 512, so steps
    // land on hops and every lap is played the same way.
    const float bpm = 117.1875f;
    const size_t step = 6144, lap = 4 * step, n = 6 * lap;
    const auto l = music(n, 7);
    {
        auto plain = make("Spectral", {{"blur", 0.3f}});
        auto off = make("Spectral", {{"blur", 0.3f}, {"grid", 0.0f}, {"length", 9.0f}, {"order", 3.0f}, {"fade", 0.7f}});
        const auto a = run(*plain, l, bpm).l, b = run(*off, l, bpm).l;
        ok("stutter off: its other knobs change nothing", worst(a, b) == 0.0, num(worst(a, b)));
    }
    const auto plain = run(*make("Spectral"), l, bpm).l;
    {
        auto fx = make("Spectral", {{"stutter", 1.0f}, {"grid", 4.0f}, {"length", 4.0f}});
        const auto out = run(*fx, l, bpm).l;
        const std::vector<float> a(out.begin() + 2 * lap, out.begin() + 3 * lap), b(out.begin() + 3 * lap, out.begin() + 4 * lap),
            c(out.begin() + 5 * lap, out.end()), live(plain.begin() + 3 * lap, plain.begin() + 4 * lap);
        ok("the loop repeats exactly", worst(a, b) < 1e-6 && worst(a, c) < 1e-6 && rms(a, 0, lap) > 0.05,
           num(worst(a, b)) + " / " + num(worst(a, c)) + ", rms " + num(rms(a, 0, lap)));
        ok("...and isn't the track", worst(b, live) > 0.1, num(worst(b, live)));
    }
    {
        // Reversed: from the second lap, each step is one of the forward lap's,
        // the order running the other way.
        auto fwd = make("Spectral", {{"stutter", 1.0f}, {"length", 4.0f}});
        auto rev = make("Spectral", {{"stutter", 1.0f}, {"length", 4.0f}, {"order", 1.0f}});
        const auto f = run(*fwd, l, bpm).l, r = run(*rev, l, bpm).l;
        // The middle of a step on the way out, clear of the frames that overlap its neighbours.
        auto differ = [&](size_t rj, size_t fj) {
            double w = 0.0;
            for (size_t i = 0; i < 2048; ++i) w = std::max(w, double(std::fabs(r[3 * lap + rj * step + 7 * 512 + i] - f[3 * lap + fj * step + 7 * 512 + i])));
            return w;
        };
        double w = 0.0;
        int matched[4];
        for (size_t j = 0; j < 4; ++j) {
            double best = 1e9;
            for (size_t k = 0; k < 4; ++k) {
                const double d = differ(j, k);
                if (d < best) { best = d; matched[j] = static_cast<int>(k); }
            }
            w = std::max(w, best);
        }
        bool backwards = true;
        for (int j = 1; j < 4; ++j) backwards = backwards && (matched[j] - matched[j - 1] + 4) % 4 == 3;
        w = backwards ? w : 1.0;
        ok("reverse plays the steps backwards", w < 1e-6, num(w));
    }
    {
        auto fx = make("Spectral", {{"stutter", 1.0f}, {"grid", 5.0f}, {"length", 7.0f}, {"order", 3.0f}, {"fade", 0.5f}, {"smear", 0.3f}});
        const auto a = run(*fx, l, 128.0f).l;
        fx->reset();
        const auto b = run(*fx, l, 128.0f).l;
        ok("reset replays a random stutter the same", worst(a, b) == 0.0, num(worst(a, b)));
        auto fwd = make("Spectral", {{"stutter", 1.0f}, {"grid", 5.0f}, {"length", 7.0f}, {"fade", 0.5f}, {"smear", 0.3f}});
        const auto f = run(*fwd, l, 128.0f).l;
        ok("...and random isn't forward", worst(a, f) > 0.05, num(worst(a, f)));
    }
    {
        // Renewed every lap: a lap taken, a lap looped, a lap taken again. The
        // first step is the one it started in, a step before the song's first.
        auto fx = make("Spectral", {{"stutter", 1.0f}, {"length", 4.0f}, {"renew", 1.0f}});
        const auto out = run(*fx, l, bpm).l;
        auto lapAt = [&](size_t firstStep) { return std::vector<float>(out.begin() + firstStep * step + 4096, out.begin() + (firstStep + 4) * step + 1536); };
        const auto taken = lapAt(7), looped = lapAt(11), earlier = lapAt(3);
        ok("renew: the lap taken again loops", worst(taken, looped) < 1e-6, num(worst(taken, looped)));
        ok("...and is new", worst(earlier, looped) > 0.1, num(worst(earlier, looped)));
    }
    {
        // A mono track that turns stereo part way through a loop: both sides loop.
        auto fx = make("Spectral", {{"stutter", 1.0f}, {"length", 4.0f}});
        Stereo s{l, l};
        const double ticksPerSample = kTicksPerBeat * bpm / 60.0 / kRate;
        for (size_t at = 0; at < n; at += kBlock) {
            fx->onBlock(static_cast<int64_t>(at * ticksPerSample), static_cast<int64_t>((at + kBlock) * ticksPerSample), bpm);
            fx->run(s.l.data() + at, s.r.data() + at, kBlock, at >= 2 * lap + 3000);
        }
        const std::vector<float> L(s.l.begin() + 3 * lap, s.l.end()), R(s.r.begin() + 3 * lap, s.r.end());
        ok("mono turned stereo mid-loop: the right loops as the left", worst(L, R) == 0.0 && rms(R, 0, R.size()) > 0.05, num(worst(L, R)));
    }
    {
        // Frozen on one note, stuttered over another and let go: back to the freeze.
        auto fx = make("Spectral");
        const auto a440 = tone(440.0f, 0.3f, 40000), b660 = tone(660.0f, 0.3f, 192000);
        run(*fx, a440, 120.0f);
        set(*fx, "freeze", 1.0f);
        const std::vector<float> lead(b660.begin(), b660.begin() + 24000), first(b660.begin() + 24000, b660.begin() + 96000),
            rest(b660.begin() + 96000, b660.end());
        run(*fx, lead, 120.0f, 40000);
        set(*fx, "stutter", 1.0f);
        const auto looping = run(*fx, first, 120.0f, 64000).l;
        set(*fx, "stutter", 0.0f);
        const auto back = run(*fx, rest, 120.0f, 136000).l;
        const double during = pitchHz(looping, 40000, 44000), after = pitchHz(back, 48000, 52000);
        ok("stutter over a freeze, let go: the freeze again", std::fabs(during - 660.0) < 10.0 && std::fabs(after - 440.0) < 10.0,
           num(during) + " Hz looping, " + num(after) + " Hz after");
    }
    {
        // Held for two laps and let go: it fades back into the track.
        auto fx = make("Spectral", {{"stutter", 1.0f}, {"length", 4.0f}, {"fade", 0.3f}});
        const size_t cut = 2 * lap + 1000;
        const auto held = run(*fx, std::vector<float>(l.begin(), l.begin() + cut), bpm).l;
        set(*fx, "stutter", 0.0f);
        const auto after = run(*fx, std::vector<float>(l.begin() + cut, l.end()), bpm, cut).l;
        const std::vector<float> back(after.begin() + 24000, after.begin() + 48000), live(plain.begin() + cut + 24000, plain.begin() + cut + 48000);
        ok("let go, it's the track again within half a second", worst(back, live) < 1e-4, num(worst(back, live)));
        double jump = 0.0, usual = 0.0;
        for (size_t i = 1; i < 12000; ++i) jump = std::max(jump, double(std::fabs(after[i] - after[i - 1])));
        for (size_t i = 1; i < held.size(); ++i) usual = std::max(usual, double(std::fabs(held[i] - held[i - 1])));
        for (size_t i = cut; i < cut + 12000; ++i) usual = std::max(usual, double(std::fabs(plain[i] - plain[i - 1])));
        ok("...with no jump as it lets go", jump <= usual * 1.2, num(jump) + " against " + num(usual) + " held or live");
    }
}

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
    spectralStutter();
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
    // A stretch up to the longest the line holds, 3 s, plays back whole:
    // a bar at 120 and at 80. A bar at 60 is 4 s and plays in halves.
    for (const auto &[bpm, beats, label] : {std::tuple{120.0f, 4.0, "a bar at 120 plays back whole"},
                                            std::tuple{80.0f, 4.0, "a bar at 80 (3 s) plays back whole"},
                                            std::tuple{60.0f, 2.0, "a bar at 60 plays back in halves"}}) {
        const auto len = static_cast<size_t>(beats * 60.0 / bpm * kRate + 0.5);
        std::vector<float> ramp(3 * len, 0.0f);
        for (size_t i = 0; i < len; ++i) ramp[i] = 0.5f * static_cast<float>(i) / len;
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 7.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, ramp, bpm);
        double sxy = 0.0, sxx = 0.0, syy = 0.0;
        for (size_t t = 600; t < len - 600; ++t) {
            const double want = ramp[len - 1 - t], got = out.l[len + t];
            sxy += want * got; sxx += want * want; syy += got * got;
        }
        const double corr = sxy / std::sqrt(sxx * syy + 1e-30);
        ok(std::string("reverse: ") + label, corr > 0.999, "correlation " + num(corr));
    }
    {
        // Stretches fall on the song's bars, played from the start or from
        // part way into a bar. Under a rising ramp each stretch's echo falls, and
        // the fade into the next rises, so the lowest point is the bar line.
        constexpr size_t kBar = 4 * kQuarter;
        constexpr size_t kFrom = 124800; // 1.3 bars, on a whole tick
        std::vector<float> ramp(6 * kBar);
        for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = 0.9f * static_cast<float>(i) / ramp.size();
        // The echo alone: the output less the ramp, [from] frames into it.
        auto lowest = [&](const std::vector<float> &x, size_t from, size_t to, size_t offset) {
            size_t at = from;
            for (size_t k = from; k < to; ++k)
                if (x[k] - ramp[k + offset] < x[at] - ramp[at + offset]) at = k;
            return at;
        };
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 7.0f}, {"feedback", 0.0f}, {"mix", 1.0f}});
        const auto whole = run(*fx, ramp);
        fx->reset();
        const auto late = run(*fx, std::vector<float>(ramp.begin() + kFrom, ramp.end()), 120.0f, kFrom);
        // As the engine starts part way: the clock from 0, the song's position as an offset.
        fx->reset();
        ModTransport moved;
        moved.songOffset = static_cast<int64_t>(kFrom * kTicksPerBeat * 2 / static_cast<size_t>(kRate));
        fx->setTransport(moved);
        const auto offset = run(*fx, std::vector<float>(ramp.begin() + kFrom, ramp.end()));
        std::string where;
        bool onBars = true;
        for (size_t bar = 4; bar <= 5; ++bar) {
            const size_t a = lowest(whole.l, bar * kBar - kBar / 4, bar * kBar + kBar / 4, 0);
            const size_t b = lowest(late.l, bar * kBar - kBar / 4 - kFrom, bar * kBar + kBar / 4 - kFrom, kFrom) + kFrom;
            const size_t c = lowest(offset.l, bar * kBar - kBar / 4 - kFrom, bar * kBar + kBar / 4 - kFrom, kFrom) + kFrom;
            const size_t found[3] = {a, b, c};
            for (int k = 0; k < 3; ++k) {
                onBars = onBars && found[k] + 2 >= bar * kBar && found[k] <= bar * kBar + 2;
                where += (k == 0 ? " " : "/") + std::to_string(static_cast<long long>(found[k]) - static_cast<long long>(bar * kBar));
            }
        }
        ok("reverse: stretches start on the bar, from the start or part way in", onBars, "off by" + where);
    }
    {
        // A stretch that starts over part way in fades from where the old one
        // was: a 3-beat song loop over 4-beat stretches (beats 2 to 5, so the
        // jump back lands 2 beats into a stretch), and the time turned from a
        // bar to a dotted quarter part way into one.
        auto in = tone(331.7f, 0.4f, 336000);
        // Faded in, so the tone's own start played backwards isn't a cut.
        for (size_t i = 0; i < 4800; ++i) in[i] *= static_cast<float>(i) / 4800.0f;
        // The worst second difference of the echo, as a share of its peak. A
        // 330 Hz tone's is about 0.002; a hard cut is far over that.
        auto rough = [&](const Stereo &s) {
            double m = 0.0, p = 0.0;
            for (size_t i = 2; i < in.size(); ++i) {
                const double w0 = s.l[i] - in[i], w1 = s.l[i - 1] - in[i - 1], w2 = s.l[i - 2] - in[i - 2];
                m = std::max(m, std::fabs(w0 - 2.0 * w1 + w2));
                p = std::max(p, std::fabs(w0));
            }
            return p > 0.0 ? m / p : 1.0;
        };
        auto fx = make("Delay", {{"mode", 3.0f}, {"time", 7.0f}, {"feedback", 0.0f}, {"mix", 1.0f}, {"wobble", 0.0f}});
        Stereo looped{in, in};
        const double ticksPerFrame = kTicksPerBeat * 120.0 / 60.0 / kRate;
        size_t song = 0;
        for (size_t at = 0; at < in.size(); at += kBlock) {
            if (song == 5 * kQuarter) song = 2 * kQuarter;
            fx->onBlock(static_cast<int64_t>(song * ticksPerFrame), static_cast<int64_t>((song + kBlock) * ticksPerFrame), 120.0f);
            fx->run(looped.l.data() + at, looped.r.data() + at, kBlock, false);
            song += kBlock;
        }
        const double loop = rough(looped);
        ok("reverse: a song loop off the stretch line doesn't click", loop < 0.02, num(loop) + " of the peak");
        auto turned = make("Delay", {{"mode", 3.0f}, {"time", 7.0f}, {"feedback", 0.0f}, {"mix", 1.0f}, {"wobble", 0.0f}});
        Stereo t{in, in};
        for (size_t at = 0; at < in.size(); at += kBlock) {
            if (at == 222016) set(*turned, "time", 5.0f);
            turned->onBlock(static_cast<int64_t>(at * ticksPerFrame), static_cast<int64_t>((at + kBlock) * ticksPerFrame), 120.0f);
            turned->run(t.l.data() + at, t.r.data() + at, kBlock, false);
        }
        const double time = rough(t);
        ok("reverse: turning the time part way into a stretch doesn't click", time < 0.02, num(time) + " of the peak");
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
    for (int rate : {48000, 96000}) {
        for (float time : {3.0f, 7.0f}) {
            // Reverse at full feedback must die away: 2 s of tone, then 38 s of silence.
            auto fx = make("Delay", {{"mode", 3.0f}, {"feedback", 0.95f}, {"time", time}});
            fx->prepare(rate);
            const auto sec = static_cast<size_t>(rate);
            std::vector<float> in(40 * sec, 0.0f);
            for (size_t i = 0; i < 2 * sec; ++i) in[i] = 0.7f * static_cast<float>(std::sin(2.0 * M_PI * 185.0 * i / rate));
            const auto out = run(*fx, in);
            const double mid = rms(out.l, 19 * sec, 20 * sec), end = rms(out.l, 39 * sec, 40 * sec);
            ok("reverse: full feedback dies away (time " + std::to_string(int(time)) + ", " + std::to_string(rate) + " Hz)",
               finite(out) && end < 0.6 * mid && mid < 0.5, num(mid) + " -> " + num(end));
        }
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

/** The accent grid: each hit shaped by the step it lands on. */
void transientGrid() {
    printf("- transient grid\n");
    // A hit every eighth note at 120 bpm: every other sixteenth step.
    const auto in = hits(96000);
    const char *steps[16] = {"a01", "a02", "a03", "a04", "a05", "a06", "a07", "a08",
                             "a09", "a10", "a11", "a12", "a13", "a14", "a15", "a16"};
    {
        // Grid off, with everything else of the grid's turned, is the plain shaper to the bit.
        auto plain = make("Transient", {{"attack", 0.5f}, {"sustain", -0.3f}});
        auto off = make("Transient", {{"attack", 0.5f}, {"sustain", -0.3f}, {"depth", 12.0f}, {"rate", 2.0f}, {"length", 5.0f}});
        for (int k = 0; k < 16; ++k) set(*off, steps[k], k % 2 ? 2.0f : 0.0f);
        const auto a = run(*plain, in).l, b = run(*off, in).l;
        ok("grid off is the plain shaper, bit for bit", worst(a, b) == 0.0);
        auto flat = make("Transient", {{"attack", 0.5f}, {"sustain", -0.3f}, {"grid", 1.0f}, {"depth", 12.0f}});
        ok("grid on with every step flat is too", worst(a, run(*flat, in).l) == 0.0);
    }
    // The song playing, as the engine says it on every insert.
    ModTransport rolling;
    rolling.rolling = true;
    // Hits on steps 1, 5, 9, 13 up, on 3, 7, 11, 15 down.
    auto grid = [&](std::initializer_list<std::pair<const char *, float>> extra = {}) {
        auto fx = make("Transient", {{"grid", 1.0f}, {"depth", 9.0f}});
        fx->setTransport(rolling);
        for (int k = 0; k < 16; k += 4) set(*fx, steps[k], 2.0f);
        for (int k = 2; k < 16; k += 4) set(*fx, steps[k], 0.0f);
        for (const auto &[name, value] : extra) set(*fx, name, value);
        return fx;
    };
    // Each hit's onset (its first 5 ms, RMS) against the same hit with the grid off, in dB, for the
    // hits on even (up) and odd (down) eighths. The first hit starts from silence
    // and is left out.
    auto byHit = [&](const std::vector<float> &out, const std::vector<float> &ref, bool odd) {
        double s = 0.0;
        int n = 0;
        for (size_t h = 12000, k = 1; h + 240 < out.size(); h += 12000, ++k) {
            if ((k % 2 == 1) != odd) continue;
            s += dB(rms(out, h, h + 240) / rms(ref, h, h + 240));
            ++n;
        }
        return s / std::max(1, n);
    };
    const auto ref = run(*make("Transient"), in).l;
    {
        auto fx = grid();
        const auto out = run(*fx, in).l;
        const double up = byHit(out, ref, false), down = byHit(out, ref, true);
        ok("an up step raises the hit's onset", up > 3.0, num(up) + " dB");
        ok("a down step lowers it", down < -3.0, num(down) + " dB");
        // The ring after the onset is left alone.
        double worstTail = 0.0;
        for (size_t h = 12000; h + 12000 <= out.size(); h += 12000)
            worstTail = std::max(worstTail, std::fabs(dB(rms(out, h + 6000, h + 11000) / rms(ref, h + 6000, h + 11000))));
        ok("the tail of each hit is as it was", worstTail < 0.5, num(worstTail) + " dB at most");
    }
    {
        // Started a sixteenth in, every hit lands two steps later: the up and down swap.
        auto fx = grid();
        const auto out = run(*fx, in, 120.0f, 12000).l;
        const double even = byHit(out, ref, false), odd = byHit(out, ref, true);
        ok("the pattern follows the song's position", even < -3.0 && odd > 3.0,
           "even hits " + num(even) + " dB, odd " + num(odd) + " dB");
    }
    {
        // At 1/8 over 4 steps the hits go step 1 (up), 2, 3 (down), 4.
        auto fx = grid({{"rate", 2.0f}, {"length", 4.0f}});
        const auto out = run(*fx, in).l;
        double upHit = 0.0, downHit = 0.0, flatHit = 0.0;
        for (size_t h = 12000, k = 1; h + 240 < out.size(); h += 12000, ++k) {
            const double d = dB(rms(out, h, h + 240) / rms(ref, h, h + 240));
            if (k % 4 == 0) upHit = std::max(upHit, d);
            else if (k % 4 == 2) downHit = std::min(downHit, d);
            else flatHit = std::max(flatHit, std::fabs(d));
        }
        ok("rate and length: 1/8 steps over a 4-step loop", upHit > 3.0 && downHit < -3.0 && flatHit < 0.5,
           num(upHit) + " dB on step 1, " + num(downHit) + " on 3, " + num(flatHit) + " on 2 and 4");
    }
    {
        // A note on step 1's second half swung to where step 2 starts (75%,
        // sixteenths: tick 40 sounds at 60). With the swing known it counts as step 1.
        std::vector<float> one(24000, 0.0f);
        const auto hit = hits(12000);
        for (size_t i = 0; i < 6000; ++i) one[6000 + i] = hit[i];
        const auto plainOne = run(*make("Transient"), one).l;
        auto swung = grid({{"a02", 0.0f}});
        ModTransport sw = rolling;
        sw.swing = 75.0f;
        swung->setTransport(sw);
        auto straight = grid({{"a02", 0.0f}});
        const double s = dB(rms(run(*swung, one).l, 6000, 6240) / rms(plainOne, 6000, 6240));
        const double t = dB(rms(run(*straight, one).l, 6000, 6240) / rms(plainOne, 6000, 6240));
        ok("swing: a swung note is accented as the step it was written on", s > 3.0 && t < -3.0,
           num(s) + " dB swung, " + num(t) + " dB as if straight");
    }
    {
        // Stopped, the grid leaves the hits alone, though the clock's ticks
        // still move, as they do in the engine.
        auto fx = grid();
        fx->setTransport(ModTransport{});
        ok("stopped, the grid does nothing", worst(run(*fx, in).l, ref) == 0.0);
    }
    {
        auto fx = grid({{"attack", 0.3f}});
        const auto a = run(*fx, in).l;
        fx->reset();
        const auto b = run(*fx, in).l;
        ok("reset: played again it's the same to the bit", worst(a, b) == 0.0);
    }
    // A kick and a ringing tone, every step up: the accent holds through
    // each hit, with no steps in the gain at the wave's rate (noise hid this).
    auto kick = [](float hz, float tau) {
        std::vector<float> x(96000, 0.0f);
        for (size_t h = 0; h < x.size(); h += 24000)
            for (size_t i = 0; i < 24000; ++i)
                x[h + i] = 0.8f * static_cast<float>(std::exp(-double(i) / kRate / tau) * std::sin(2.0 * M_PI * hz * i / kRate));
        return x;
    };
    // The gain applied, in dB, where the input is big enough to say; and the
    // biggest step from one such sample to the next, hits' starts and all.
    auto worstStep = [](const std::vector<float> &out, const std::vector<float> &x, size_t skip = 0) {
        double w = 0.0, peak = 0.0;
        for (float v : x) peak = std::max(peak, double(std::fabs(v)));
        for (size_t i = 1; i < x.size(); ++i) {
            if (i % 24000 < skip) continue;
            if (std::fabs(x[i]) < 0.05 * peak || std::fabs(x[i - 1]) < 0.05 * peak) continue;
            const double a = dB(std::fabs(out[i] / x[i])), b = dB(std::fabs(out[i - 1] / x[i - 1]));
            w = std::max(w, std::fabs(a - b));
        }
        return w;
    };
    // The gain at [ms] into the second hit, from the output's level against the input's over 1 ms.
    auto gainAt = [](const std::vector<float> &out, const std::vector<float> &x, double ms) {
        const auto at = static_cast<size_t>(24000 + ms * 48.0);
        return dB(rms(out, at, at + 48) / rms(x, at, at + 48));
    };
    for (const auto &[name, hz, tau] : {std::tuple<const char *, float, float>{"a 50 Hz kick", 50.0f, 0.15f},
                                        std::tuple<const char *, float, float>{"a 3 kHz ring", 3000.0f, 0.03f}}) {
        const auto x = kick(hz, tau);
        auto on = make("Transient", {{"grid", 1.0f}, {"depth", 12.0f}, {"attack", 0.5f}});
        on->setTransport(rolling);
        for (int k = 0; k < 16; ++k) set(*on, steps[k], 2.0f);
        auto off = make("Transient", {{"attack", 0.5f}});
        const auto yOn = run(*on, x).l, yOff = run(*off, x).l;
        // Each starts at full slope from nothing, a click of its own: the first
        // 3 ms may take the quick slew, the rest must be as smooth as grid off.
        const double sOn = worstStep(yOn, x, 144), sOff = worstStep(yOff, x, 144);
        const double cOn = worstStep(yOn, x), cOff = worstStep(yOff, x);
        ok(std::string(name) + ": no step in the gain sample to sample", sOn < 0.06 + sOff && cOn < 0.8 + cOff,
           num(sOn) + " dB against " + num(sOff) + " grid off, " + num(cOn) + " in the click");
        // Up at the start, then easing off with the onset, never dropping and
        // coming back as a second start would.
        double first = 0.0, top = -1e9, dip = 1e9, again = 0.0;
        for (double ms = 2.0; ms <= 30.0; ms += 1.0) {
            const double d = gainAt(yOn, x, ms) - gainAt(yOff, x, ms);
            if (ms == 2.0) first = d;
            if (d >= top && dip > 1e8) top = d;
            else { dip = std::min(dip, d); again = std::max(again, d - dip); }
        }
        ok(std::string(name) + ": the accent holds through the hit's start", first > 3.0 && top > 6.0 && again < 0.5,
           num(first) + " dB at 2 ms, " + num(top) + " at the top, back up " + num(again) + " after");
        // Only the hit's own step is up: a second start inside the hit would land on a down one.
        auto one = make("Transient", {{"grid", 1.0f}, {"depth", 12.0f}, {"attack", 0.5f}});
        one->setTransport(rolling);
        for (int k = 0; k < 16; ++k) set(*one, steps[k], k % 4 ? 0.0f : 2.0f);
        double low = 1e9;
        const auto yOne = run(*one, x).l;
        for (double ms = 2.0; ms <= 60.0; ms += 1.0) low = std::min(low, gainAt(yOne, x, ms) - gainAt(yOff, x, ms));
        ok(std::string(name) + ": it starts once a hit", low > -0.5, num(low) + " dB at the lowest");
    }
    {
        // A 30 Hz sub, long enough that the second hit starts over the first's tail.
        const auto x = kick(30.0f, 0.3f);
        auto on = make("Transient", {{"grid", 1.0f}, {"depth", 12.0f}, {"attack", 0.5f}});
        on->setTransport(rolling);
        for (int k = 0; k < 16; ++k) set(*on, steps[k], 2.0f);
        auto off = make("Transient", {{"attack", 0.5f}});
        const double sOn = worstStep(run(*on, x).l, x), sOff = worstStep(run(*off, x).l, x);
        ok("a 30 Hz sub: no step in the gain sample to sample", sOn < 0.06 + sOff,
           num(sOn) + " dB against " + num(sOff) + " grid off");
        // The same, swelling up out of the first with no kink to hear it start by.
        std::vector<float> swell(96000);
        for (size_t i = 0; i < swell.size(); ++i) {
            const double t = double(i % 24000) / kRate, tail = std::exp(-(t + 0.5) / 0.3);
            const double rise = std::min(1.0, t / 0.004), a = tail + (std::exp(-t / 0.3) - tail) * rise;
            swell[i] = 0.8f * static_cast<float>(a * std::sin(2.0 * M_PI * 30.0 * i / kRate));
        }
        on->reset();
        const double wOn = worstStep(run(*on, swell).l, swell), wOff = worstStep(run(*off, swell).l, swell);
        ok("a 30 Hz sub swelling in: no step either", wOn < 0.06 + wOff,
           num(wOn) + " dB against " + num(wOff) + " grid off");
    }
    // Hits at [at] (frames) at [bpm] through a grid of alternating up and down
    // steps [rate] long. Each hit's onset (5 ms, or 15 where it has no click to
    // let the accent in quickly) against grid off should go its
    // own step's way, the first left out as it starts from silence. The gain
    // mustn't step from one sample to the next more than with the grid off,
    // except, where the material starts [clicky] with a click of its own, in
    // each hit's first 3 ms, by the quick slew at most. Unless [accented], hits
    // may go unaccented, and only the steps are checked.
    auto ownSteps = [&](const char *name, const std::vector<float> &x, const std::vector<size_t> &at, float bpm, float rate,
                        bool clicky = true, double downMost = -2.0, bool accented = true) {
        auto on = make("Transient", {{"grid", 1.0f}, {"depth", 9.0f}, {"attack", 0.3f}, {"rate", rate}});
        on->setTransport(rolling);
        for (int k = 0; k < 16; ++k) set(*on, steps[k], k % 2 ? 0.0f : 2.0f);
        auto off = make("Transient", {{"attack", 0.3f}});
        const auto yOn = run(*on, x, bpm).l, yOff = run(*off, x, bpm).l;
        const int64_t step = rate < 0.5f ? 40 : rate < 1.5f ? 60 : 120;
        double up = 1e9, down = -1e9;
        for (size_t k = 1; k < at.size(); ++k) {
            const auto tick = static_cast<int64_t>(double(at[k]) * kTicksPerBeat * bpm / 60.0 / kRate);
            const bool isUp = ((tick + step / 4) / step) % 2 == 0;
            const size_t span = clicky ? 240 : 720;
            const double d = dB(rms(yOn, at[k], at[k] + span) / rms(yOff, at[k], at[k] + span));
            if (isUp) up = std::min(up, d); else down = std::max(down, d);
        }
        if (accented)
            ok(std::string(name) + ": each hit gets its own step", up > 2.0 && down < downMost,
               num(up) + " dB the least up, " + num(down) + " the least down");
        // The worst step outside the clicks and in them.
        auto steps2 = [&](const std::vector<float> &out, double &inClick) {
            double w = 0.0, pk = 0.0;
            inClick = 0.0;
            for (float v : x) pk = std::max(pk, double(std::fabs(v)));
            size_t next = 1;
            for (size_t i = at[0] + 144; i < x.size(); ++i) {
                while (next < at.size() && at[next] + 144 <= i) ++next;
                const bool click = clicky && next < at.size() && i >= at[next];
                if (std::fabs(x[i]) < 0.05 * pk || std::fabs(x[i - 1]) < 0.05 * pk) continue;
                const double d = std::fabs(dB(std::fabs(out[i] / x[i])) - dB(std::fabs(out[i - 1] / x[i - 1])));
                (click ? inClick : w) = std::max(click ? inClick : w, d);
            }
            return w;
        };
        double cOn = 0.0, cOff = 0.0;
        const double sOn = steps2(yOn, cOn), sOff = steps2(yOff, cOff);
        ok(std::string(name) + ": no step in the gain sample to sample", sOn < 0.06 + sOff && cOn < 0.8 + cOff,
           num(sOn) + " dB against " + num(sOff) + " grid off" + (clicky ? ", " + num(cOn) + " in the clicks" : ""));
    };
    // A noise hit [amp] high falling with time constant [tau] seconds, added in at [from].
    auto addHit = [](std::vector<float> &x, size_t from, float amp, double tau, uint32_t seed) {
        const auto n = noise(amp, std::min<size_t>(x.size() - from, size_t(tau * 10.0 * kRate)), seed);
        for (size_t i = 0; i < n.size(); ++i) x[from + i] += n[i] * static_cast<float>(std::exp(-double(i) / kRate / tau));
    };
    {
        // A 32nd snare roll at 180 bpm: a hit every 2000 frames, each down 26 dB by 100 ms.
        std::vector<float> x(96000, 0.0f);
        std::vector<size_t> at;
        for (size_t h = 0; h + 2000 < x.size(); h += 2000) { addHit(x, h, 0.5f, 0.1 / 3.0, 11 + h); at.push_back(h); }
        ownSteps("a 32nd roll at 180 bpm", x, at, 180.0f, 1.0f);
    }
    for (double tau : {0.06, 0.1}) {
        // The same roll of snares that ring: each hit falls with time constant tau.
        std::vector<float> x(96000, 0.0f);
        std::vector<size_t> at;
        for (size_t h = 0; h + 2000 < x.size(); h += 2000) { addHit(x, h, 0.5f, tau, 13 + h); at.push_back(h); }
        // At 100 ms a hit is too little over the ring before it to tell from
        // a saw's clicks, so some go unaccented.
        const bool slow = tau > 0.08;
        ownSteps((std::string("a ringing roll, ") + (slow ? "100" : "60") + " ms").c_str(), x, at, 180.0f, 1.0f, true, -2.0,
                 !slow);
    }
    {
        // Flams at 180 bpm on 1/16 triplets: a quiet grace note 20 ms before each main hit.
        std::vector<float> x(96000, 0.0f);
        std::vector<size_t> at;
        for (size_t h = 8000; h + 4000 < x.size(); h += 8000) {
            addHit(x, h - 960, 0.25f, 0.03, 3 + h);
            addHit(x, h, 0.6f, 0.04, 5 + h);
            at.push_back(h - 960);
            at.push_back(h);
        }
        ownSteps("a 20 ms flam", x, at, 180.0f, 0.0f, true, -1.9);
    }
    {
        // 16th hats at 180 bpm: short and high.
        std::vector<float> x(96000, 0.0f);
        std::vector<size_t> at;
        for (size_t h = 0; h + 4000 < x.size(); h += 4000) {
            std::vector<float> one(4000, 0.0f);
            addHit(one, 0, 0.4f, 0.01, 17 + h);
            float lp = 0.0f;
            for (size_t i = 0; i < one.size(); ++i) { lp += (one[i] - lp) * 0.5f; x[h + i] += one[i] - lp; }
            at.push_back(h);
        }
        ownSteps("16th hats at 180 bpm", x, at, 180.0f, 1.0f);
    }
    {
        // Kicks with a click on top, an eighth apart at 120 bpm.
        std::vector<float> x(96000, 0.0f);
        std::vector<size_t> at;
        for (size_t h = 0; h + 12000 <= x.size(); h += 12000) {
            for (size_t i = 0; i < 12000; ++i)
                x[h + i] = 0.7f * static_cast<float>(std::exp(-double(i) / kRate / 0.12) * std::sin(2.0 * M_PI * 50.0 * i / kRate));
            addHit(x, h, 0.4f, 0.0015, 23 + h);
            at.push_back(h);
        }
        ownSteps("a kick with a click", x, at, 120.0f, 2.0f);
    }
    {
        // Snare hits a sixteenth apart over a held 55 Hz saw bass.
        auto x = saw(55.0f, 0.3f, 96000);
        std::vector<size_t> at;
        for (size_t h = 3000; h + 6000 <= x.size(); h += 6000) { addHit(x, h, 0.5f, 0.03, 29 + h); at.push_back(h); }
        ownSteps("snares over a bass", x, at, 120.0f, 1.0f);
    }
    {
        // A pure 50 Hz kick on each eighth, faded in over 2 ms, under a held organ of
        // four saws: the organ is bright, but the kick has no click to cover a quick change.
        std::vector<float> x(96000, 0.0f);
        for (float hz : {220.0f, 330.0f, 440.0f, 660.0f}) {
            const auto s = saw(hz, 0.08f, x.size());
            for (size_t i = 0; i < x.size(); ++i) x[i] += s[i];
        }
        std::vector<size_t> at;
        for (size_t h = 0; h + 12000 <= x.size(); h += 12000) {
            for (size_t i = 0; i < 12000; ++i)
                x[h + i] += 0.7f * static_cast<float>(std::exp(-double(i) / kRate / 0.12) * std::sin(2.0 * M_PI * 50.0 * i / kRate) *
                                                      (i < 96 ? 0.5 - 0.5 * std::cos(M_PI * i / 96.0) : 1.0));
            at.push_back(h);
        }
        ownSteps("a clean kick under an organ", x, at, 120.0f, 2.0f, false);
    }
    {
        // Turned on mid-song between hits: nothing happens until the next hit, which gets its own step.
        auto x = saw(55.0f, 0.3f, 96000);
        for (size_t h = 3000; h + 6000 <= x.size(); h += 6000) addHit(x, h, 0.5f, 0.03, 31 + h);
        auto on = make("Transient", {{"depth", 9.0f}, {"attack", 0.3f}});
        on->setTransport(rolling);
        for (int k = 0; k < 16; ++k) set(*on, steps[k], k % 2 ? 0.0f : 2.0f);
        auto off = make("Transient", {{"attack", 0.3f}});
        const std::vector<float> head(x.begin(), x.begin() + 46500), rest(x.begin() + 46500, x.end());
        const auto yOff = run(*off, x).l;
        auto yOn = run(*on, head).l;
        set(*on, "grid", 1.0f);
        const auto tail = run(*on, rest, 120.0f, head.size()).l;
        yOn.insert(yOn.end(), tail.begin(), tail.end());
        double before = 0.0;
        for (size_t i = 46500; i + 48 <= 51000; i += 48) before = std::max(before, std::fabs(dB(rms(yOn, i, i + 48) / rms(yOff, i, i + 48))));
        // The hit at 51000 is on step 9, up.
        const double hit = dB(rms(yOn, 51000, 51240) / rms(yOff, 51000, 51240));
        ok("turned on mid-song: no hit until the next one, and that on its own step", before < 0.1 && hit > 3.0,
           num(before) + " dB before it, " + num(hit) + " on it");
    }
    {
        // A held saw bass from E1 up, whose every cycle starts with a click, isn't a run of hits:
        // with every other step up, it comes out as with the grid off.
        double w = 0.0;
        for (float hz : {41.2f, 55.0f, 110.0f}) {
            auto x = saw(hz, 0.5f, 96000);
            auto on = make("Transient", {{"grid", 1.0f}, {"depth", 9.0f}, {"attack", 0.3f}});
            on->setTransport(rolling);
            for (int k = 0; k < 16; ++k) set(*on, steps[k], k % 2 ? 0.0f : 2.0f);
            auto off = make("Transient", {{"attack", 0.3f}});
            const auto yOn = run(*on, x).l, yOff = run(*off, x).l;
            for (size_t i = 4800; i + 480 <= x.size(); i += 480) w = std::max(w, std::fabs(dB(rms(yOn, i, i + 480) / rms(yOff, i, i + 480))));
        }
        ok("a held saw bass: the grid leaves it alone", w < 0.2, num(w) + " dB at most");
    }
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

std::vector<float> both_of(const std::vector<float> &a, const std::vector<float> &b) {
    std::vector<float> x(std::min(a.size(), b.size()));
    for (size_t i = 0; i < x.size(); ++i) x[i] = a[i] + b[i];
    return x;
}

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
    // Throw: an ess 50 ms long at 0.1 s, then silence.
    const size_t essAt = 4800, essLen = 2400;
    std::vector<float> burst(96000, 0.0f);
    for (size_t i = 0; i < essLen; ++i) burst[essAt + i] = ess[i];
    {
        // At throw 0 the time and feedback change nothing and it stays mono.
        auto a = make("Deesser");
        auto b = make("Deesser", {{"time", 5.0f}, {"feedback", 0.9f}});
        const auto oa = run(*a, both_of(ess, low)), ob = run(*b, both_of(ess, low));
        bool mono = true;
        {
            auto c = make("Deesser");
            std::vector<float> l(ess.begin(), ess.begin() + kBlock), r(kBlock, 0.0f);
            mono = !c->run(l.data(), r.data(), kBlock, false);
        }
        ok("throw 0: time and feedback change nothing, the output stays mono", worst(oa.l, ob.l) == 0.0 && mono,
           num(worst(oa.l, ob.l)));
    }
    for (const float bpm : {120.0f, 90.0f}) {
        // 1/8 at the tempo: the cut comes back on the left an eighth later,
        // then on the right an eighth after that.
        auto fx = make("Deesser", {{"throw", 1.0f}, {"time", 1.0f}, {"feedback", 0.5f}});
        const auto out = run(*fx, burst, bpm);
        const auto d = static_cast<size_t>(0.5f * 60.0f / bpm * kRate);
        const double gap = rms(out.l, essAt + essLen + 600, essAt + d - 300);
        const double echoL = rms(out.l, essAt + d, essAt + d + essLen);
        const double echoR = rms(out.r, essAt + 2 * d, essAt + 2 * d + essLen);
        const double earlyR = rms(out.r, essAt + essLen + 600, essAt + 2 * d - 300);
        ok("throw at " + num(bpm) + " bpm: the ess echoes left at 1/8, right at 1/4",
           dB(echoL) > -40.0 && dB(echoL / (gap + 1e-12)) > 40.0 && dB(echoR / (earlyR + 1e-12)) > 30.0,
           num(dB(echoL)) + " dB, gap " + num(dB(gap)) + ", right " + num(dB(echoR)) + " / " + num(dB(earlyR)));
    }
    {
        // Feedback rings on after the input stops, then sleeps; asleep it
        // plays exactly what a de-esser without a throw would.
        const std::vector<float> head(burst.begin(), burst.begin() + 9600);
        auto fx = make("Deesser", {{"throw", 1.0f}, {"time", 0.0f}, {"feedback", 0.6f}});
        run(*fx, head);
        set(*fx, "throw", 0.0f);
        const auto still = run(*fx, std::vector<float>(24000, 0.0f), 120.0f, head.size());
        run(*fx, std::vector<float>(48000 * 4, 0.0f), 120.0f, head.size() + 24000);
        auto plain = make("Deesser");
        run(*plain, head);
        run(*plain, std::vector<float>(24000 + 48000 * 4, 0.0f));
        const auto a = run(*fx, ess), b = run(*plain, ess);
        ok("throw: the tail rings after the input and throw stop, then it sleeps",
           dB(rms(still.l, 0, 12000) + rms(still.r, 0, 12000)) > -60.0 && worst(a.l, b.l) == 0.0 && worst(a.r, b.r) == 0.0,
           num(dB(rms(still.l, 0, 12000) + rms(still.r, 0, 12000))) + " dB, " + num(worst(a.l, b.l)));
    }
    {
        // Reset clears the echo: silence after it is silence, and the same input plays the same.
        auto fx = make("Deesser", {{"throw", 1.0f}, {"feedback", 0.8f}});
        const auto first = run(*fx, burst);
        fx->reset();
        const auto quiet = run(*fx, std::vector<float>(48000, 0.0f));
        fx->reset();
        const auto again = run(*fx, burst);
        ok("throw: reset clears the echo and the same input plays the same",
           peak(quiet.l) == 0.0 && peak(quiet.r) == 0.0 && worst(first.l, again.l) == 0.0 && worst(first.r, again.r) == 0.0,
           num(peak(quiet.l)) + " / " + num(worst(first.l, again.l)));
    }
    {
        // Listen plays only the band; the throw is silent.
        auto fx = make("Deesser", {{"throw", 1.0f}, {"listen", 1.0f}});
        const auto out = run(*fx, burst);
        const double after = rms(out.l, essAt + essLen + 2400, out.l.size()) + rms(out.r, essAt + essLen + 2400, out.r.size());
        ok("throw: listen keeps the echo silent", dB(after) < -100.0, num(dB(after)) + " dB");
    }
    {
        // A time change crossfades the read. A steady tone plays all through,
        // so dry and echo are one sine but for the change, and a jump in the
        // read shows as a kink: the largest second difference against the
        // peak, after the change and before it. The tone is not a whole
        // number of cycles in either time, or the jump would land in phase,
        // and sent gently, so the loop's limit leaves it a sine.
        auto fx = make("Deesser", {{"freq", 2000.0f}, {"throw", 0.1f}, {"time", 1.0f}, {"feedback", 0.7f}});
        const auto t = tone(2130.0f, 0.3f, 96000);
        const std::vector<float> first(t.begin(), t.begin() + 48000), second(t.begin() + 48000, t.end());
        const auto out1 = run(*fx, first);
        set(*fx, "time", 3.0f);
        const auto out2 = run(*fx, second, 120.0f, 48000);
        auto kink = [](const Stereo &o, size_t from, size_t to) {
            double k = 0.0, p = 1e-12;
            for (size_t i = from + 2; i < to; ++i) {
                for (const auto *x : {&o.l, &o.r}) {
                    k = std::max(k, double(std::fabs((*x)[i] - 2.0f * (*x)[i - 1] + (*x)[i - 2])));
                    p = std::max(p, double(std::fabs((*x)[i])));
                }
            }
            return k / p;
        };
        const double after = kink(out2, 0, 4800), before = kink(out1, 43200, 48000);
        ok("throw: a new time glides without a click", finite(out2) && after <= before * 1.2, num(after) + " vs " + num(before));
    }
    {
        // staysBounded turns listen on, which mutes the throw: this runs the echo flat out.
        auto fx = make("Deesser", {{"throw", 1.0f}, {"listen", 0.0f}, {"feedback", 0.9f}, {"range", 24.0f}, {"threshold", -60.0f}});
        const auto out = run(*fx, noise(0.9f, 240000));
        ok("throw: full send and feedback stay finite and bounded", finite(out) && peak(out.l) < 8.0 && peak(out.r) < 8.0,
           num(peak(out.l)) + " / " + num(peak(out.r)));
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
        for (size_t at = 0; at < n; at += kBlock)
            fx.run(l.data() + at, r.data() + at, static_cast<int32_t>(std::min<size_t>(kBlock, n - at)), false);
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

/** The strongest frequency between [lo] and [hi] over [from, to), to a hertz. */
double strongestIn(const std::vector<float> &x, double lo, double hi, size_t from, size_t to) {
    double best = 0.0, at = lo;
    for (double f = lo; f <= hi; f += 1.0) {
        const double v = strengthAt(x, f, from, to, kRate);
        if (v > best) { best = v; at = f; }
    }
    return at;
}

/** Drumhead, thunder sheet, howl and flutter: what each is for. */
void reverbBodies() {
    const auto highPass = [](const std::vector<float> &x, double hz) {
        const auto l = lowPass(lowPass(x, hz), hz);
        std::vector<float> h(x.size());
        for (size_t i = 0; i < x.size(); ++i) h[i] = x[i] - l[i];
        return h;
    };
    // A kick: a sine falling from 150 Hz to 50 Hz, dying over 80 ms, at [at].
    const auto kick = [](float amp, size_t n, size_t at) {
        std::vector<float> k(n, 0.0f);
        double ph = 0.0;
        for (size_t i = at; i < std::min(n, at + 9600); ++i) {
            const double t = double(i - at) / kRate;
            ph += 2.0 * M_PI * (50.0 + 100.0 * std::exp(-t / 0.03)) / kRate;
            k[i] = float(amp * std::exp(-t / 0.08) * std::sin(ph));
        }
        return k;
    };

    // Snares: the buzz is what the wires add, so with and without them, on a loud kick and a quiet one.
    {
        const auto buzz = [&](float amp, double &head) {
            const auto k = kick(amp, 48000, 4800);
            auto a = make("Reverb", {{"model", 8.0f}, {"mix", 1.0f}, {"snares", 0.0f}});
            auto b = make("Reverb", {{"model", 8.0f}, {"mix", 1.0f}, {"snares", 1.0f}});
            const auto wa = wetOf(run(*a, k).l, k), wb = wetOf(run(*b, k).l, k);
            std::vector<float> d(wa.size());
            for (size_t i = 0; i < d.size(); ++i) d[i] = wb[i] - wa[i];
            head = rms(wa, 4800, 24000);
            return rms(d, 4800, 24000);
        };
        double loudHead = 0.0, quietHead = 0.0;
        const double loud = buzz(0.8f, loudHead), quiet = buzz(0.04f, quietHead);
        ok("drumhead: a loud kick sets the snares off, a quiet one doesn't",
           dB(loud / loudHead) > -10.0 && quiet < 1e-7,
           num(dB(loud / loudHead)) + " dB of buzz on the loud one, " + num(quiet) + " rms on the quiet one");
    }
    // Tension tunes the head: the strongest ring under 2 kHz.
    {
        std::vector<float> click(48000, 0.0f);
        click[100] = 1.0f;
        const auto ringAt = [&](float tension) {
            auto fx = make("Reverb", {{"model", 8.0f}, {"mix", 1.0f}, {"tension", tension}});
            return strongestIn(wetOf(run(*fx, click).l, click), 30.0, 2000.0, 2400, 48000);
        };
        const double slack = ringAt(0.2f), tight = ringAt(0.8f);
        // Three octaves over the knob, so 0.2 to 0.8 is 1.8 octaves.
        ok("drumhead: tension raises the head's pitch", std::fabs(tight / slack / std::pow(2.0, 1.8) - 1.0) < 0.1,
           num(slack) + " Hz -> " + num(tight) + " Hz");
    }

    // Thunder: the top grows after a hard hit, from the strike to a quarter second on, and not after a soft one.
    {
        const auto growth = [&](float amp) {
            auto hit = noise(amp, 72000, 4);
            for (size_t i = 0; i < hit.size(); ++i) if (i < 4800 || i >= 4800 + 960) hit[i] = 0.0f;
            auto fx = make("Reverb", {{"model", 9.0f}, {"mix", 1.0f}});
            const auto h = highPass(wetOf(run(*fx, hit).l, hit), 2500.0);
            return dB(rms(h, 4800 + 12000, 4800 + 16800) / rms(h, 4800 + 960, 4800 + 3360));
        };
        const double hard = growth(0.8f), soft = growth(0.08f);
        ok("thunder: a hard hit blooms into the highs, a soft one doesn't", hard > 6.0 && soft < 0.0 && hard > soft + 10.0,
           num(soft) + " dB soft, " + num(hard) + " dB hard, over 2.5 kHz at 0.25 s against the strike");
        // Metal 0 doesn't spill at all.
        const auto growthAt = [&](float metal) {
            auto hit = noise(0.8f, 72000, 4);
            for (size_t i = 0; i < hit.size(); ++i) if (i < 4800 || i >= 4800 + 960) hit[i] = 0.0f;
            auto fx = make("Reverb", {{"model", 9.0f}, {"mix", 1.0f}, {"metal", metal}});
            const auto h = highPass(wetOf(run(*fx, hit).l, hit), 2500.0);
            return dB(rms(h, 4800 + 12000, 4800 + 16800) / rms(h, 4800 + 960, 4800 + 3360));
        };
        const double gong = growthAt(0.0f), sheet = growthAt(1.0f);
        ok("thunder: more metal blooms more", sheet > gong + 10.0, num(gong) + " dB at 0, " + num(sheet) + " dB at 1");
    }

    // Howl: half a second of noise, then nothing. Turned up, the room sings on, bounded and pitched,
    // and size moves the pitch: the peak follows the room, so the same harmonics sing.
    {
        const auto sing = [&](float howl, float size, double &last, double &top, double &pitched) {
            auto in = noise(0.5f, 48000 * 8, 7);
            for (size_t i = 24000; i < in.size(); ++i) in[i] = 0.0f;
            auto fx = make("Reverb", {{"model", 10.0f}, {"mix", 1.0f}, {"howl", howl}, {"size", size}});
            const auto s = run(*fx, in);
            const auto w = wetOf(s.l, in);
            last = rms(w, 48000 * 7, 48000 * 8);
            top = std::max(peak(s.l), peak(s.r));
            const double f = strongestIn(w, 100.0, 3000.0, 48000 * 7, 48000 * 8);
            pitched = strengthAt(w, f, 48000 * 7, 48000 * 8, kRate) / (last + 1e-12);
            return f;
        };
        double lastS, topS, pS, lastB, topB, pB, lastQ, topQ, pQ;
        const double small = sing(1.0f, 0.3f, lastS, topS, pS), big = sing(1.0f, 0.7f, lastB, topB, pB);
        sing(0.3f, 0.7f, lastQ, topQ, pQ);
        ok("howl: turned up, the room holds a drone seven seconds on, bounded",
           lastS > 0.05 && lastB > 0.05 && topS < 1.0 && topB < 1.0,
           "last second " + num(lastS) + " and " + num(lastB) + ", peak " + num(std::max(topS, topB)));
        // A sine reads about 0.35 here and noise near 0.
        ok("howl: the drone is pitched", pS > 0.1 && pB > 0.1, num(pS) + " and " + num(pB) + " of a sine");
        const double want = std::pow(2.0, 0.8);
        ok("howl: size retunes the drone", std::fabs(small / big / want - 1.0) < 0.1,
           num(small) + " Hz -> " + num(big) + " Hz, " + num(small / big) + " against " + num(want));
        ok("howl: at its default the room dies away", lastQ < 1e-4, "last second " + num(lastQ));
    }

    // Flutter: the pitch is the round trip between the walls.
    {
        auto &q = EngineSettings::get().quality;
        const int was = q.load();
        double worstCents = 0.0;
        for (int quality : {1, 0}) {
            q.store(quality);
            for (float walls : {0.5f, 1.0f, 3.0f}) {
                std::vector<float> click(96000, 0.0f);
                click[100] = 1.0f;
                auto fx = make("Reverb", {{"model", 11.0f}, {"mix", 1.0f}, {"walls", walls}, {"size", 0.8f}, {"damp", 0.0f}});
                const auto w = wetOf(run(*fx, click).l, click);
                const double want = 343.0 / (2.0 * walls);
                const double off = cents(peakNear(w, want, 100.0, 4800, 96000, kRate), want);
                if (std::fabs(off) > std::fabs(worstCents)) worstCents = off;
            }
        }
        q.store(was);
        ok("flutter: the pitch follows the walls", std::fabs(worstCents) < 2.0, "worst " + num(worstCents) + " cents");
    }
}

void reverb() {
    printf("- reverb\n");
    const char *names[] = {"room", "plate", "hall", "spring", "early", "space", "tube", "string",
                           "drumhead", "thunder", "howl", "flutter"};
    constexpr int kModels = 12;
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
        // Damp takes the top off the tail. Howl's tail is the speaker's peak, which damp leaves
        // where it is, so its bare room is checked.
        auto bright = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"damp", 0.0f}, {"howl", m == 10 ? 0.0f : 0.3f}});
        auto dull = make("Reverb", {{"model", float(m)}, {"mix", 1.0f}, {"damp", 1.0f}, {"howl", m == 10 ? 0.0f : 0.3f}});
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
                // The model knobs at their tops too: the biggest tiled room, the top rate, all drip and knock,
                // the tightest head with all its snares, the thinnest sheet, all the howl and the widest walls.
                auto fx = make("Reverb", {{"model", float(m)}, {"size", 1.0f}, {"shimmer", 1.0f}, {"mix", 1.0f},
                                          {"wobble", 1.0f}, {"bloom", 0.5f}, {"duck", 0.5f}, {"distance", 0.5f},
                                          {"drip", 1.0f}, {"knock", 1.0f}, {"rate", 32000.0f}, {"width", 30.0f},
                                          {"depth", 50.0f}, {"height", 15.0f}, {"position", 1.0f}, {"material", 0.0f},
                                          {"pitch", -36.0f}, {"open", 1.0f}, {"stiff", 1.0f}, {"pick", 1.0f},
                                          {"tension", 1.0f}, {"snares", 1.0f}, {"metal", 1.0f}, {"howl", 1.0f},
                                          {"walls", 20.0f}});
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
                                  {"knock", 1.0f}, {"snares", 0.6f}, {"metal", 0.8f}, {"howl", 0.9f}});
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
                                 {"material", m == 5 ? 1.0f : 3.0f}, {"pitch", m == 6 || m == 7 ? 0.0f : 7.0f},
                                 {"follow", m == 6 || m == 7 ? 0.0f : 1.0f}, {"open", m == 6 ? 0.0f : 0.8f},
                                 {"stiff", m == 7 ? 0.2f : 0.9f}, {"pick", m == 7 ? 0.25f : 0.8f},
                                 {"tension", m == 8 ? 0.5f : 0.9f}, {"snares", m == 8 ? 0.0f : 1.0f},
                                 {"metal", m == 9 ? 0.5f : 1.0f}, {"howl", m == 10 ? 0.3f : 1.0f},
                                 {"walls", m == 11 ? 2.0f : 0.5f}});
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
    reverbBodies();

    // The new knobs at their defaults leave the room as it was: checked against
    // master by tools/match_master.sh. Here, at least, they cost nothing.
    {
        auto a = make("Reverb", {{"size", 0.7f}, {"shimmer", 0.3f}});
        auto b = make("Reverb", {{"size", 0.7f}, {"shimmer", 0.3f}, {"duck", 0.0f}, {"distance", 0.0f}, {"bloom", 0.0f},
                                 {"duckrel", 1500.0f}});
        ok("room: duckrel alone changes nothing", worst(run(*a, burst).l, run(*b, burst).l) == 0.0);
    }
}

// --- Venue ----------------------------------------------------------------------
// The convolution itself is tested in venue_test; here, how it sits in a track.

void venue() {
    printf("- venue\n");
    const auto music = noise(0.3f, 4 * 48000);
    {
        auto fx = make("Venue");
        const auto out = run(*fx, music);
        ok("no impulse: the track untouched", worst(out.l, music) == 0.0);
    }
    {
        // A made-up two second room at the defaults: about as loud as dry.
        auto fx = make("Venue");
        fx->swapObject(0, venue_harness::room(2.0).release());
        fx->reset();
        const auto out = run(*fx, music);
        const double db = dB(rms(out.l, 96000, music.size()) / rms(music, 96000, music.size()));
        ok("a room at the defaults sits within 1.5 dB of dry", std::fabs(db) < 1.5, num(db) + " dB");
    }
    {
        auto fx = make("Venue", {{"mix", 0.0f}});
        fx->swapObject(0, venue_harness::room(1.0).release());
        fx->reset();
        ok("mix 0 is the track untouched", worst(run(*fx, music).l, music) == 0.0);
    }
    {
        auto fx = make("Venue");
        fx->swapObject(0, venue_harness::room(3.0).release());
        fx->reset();
        for (int32_t i = 0; i < fx->params().size(); ++i) {
            if (std::strcmp(fx->params().def(i).name, "gain") == 0) continue;
            fx->params().set(i, 1.0f);
        }
        fx->params().jumpAll();
        const auto out = run(*fx, noise(0.9f, 240000));
        ok("Venue: every knob up, it stays finite and bounded", finite(out) && peak(out.l) < 8.0, "peak " + num(peak(out.l)));
    }
}

// --- Vessel ---------------------------------------------------------------------

/** The largest step from one sample to the next over [from, to). */
double steepest(const std::vector<float> &x, size_t from, size_t to) {
    double s = 0.0;
    for (size_t i = std::max<size_t>(from, 1); i < std::min(to, x.size()); ++i) s = std::max(s, double(std::fabs(x[i] - x[i - 1])));
    return s;
}

/** The largest second difference over [from, to): a kink a step can't show. */
double sharpest(const std::vector<float> &x, size_t from, size_t to) {
    double s = 0.0;
    for (size_t i = std::max<size_t>(from, 2); i < std::min(to, x.size()); ++i)
        s = std::max(s, double(std::fabs(x[i] - 2.0f * x[i - 1] + x[i - 2])));
    return s;
}

void vessel() {
    printf("- vessel\n");
    const char *names[] = {"glass", "wood", "metal", "skin", "stone", "bell", "plate", "tube"};
    const auto hz = [](float note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); };
    const auto fundamental = [](Effect &fx) { return static_cast<effect::Vessel &>(fx).fundamentalHz(); };
    // A click, then a second and a half of ring.
    std::vector<float> click(72000, 0.0f);
    click[0] = 0.5f;
    {
        // Rings at the size it's set to, on a few materials.
        const std::tuple<int, float> cases[] = {{7, 57.0f}, {2, 60.0f}, {0, 64.0f}, {3, 50.0f}};
        for (const auto &[m, note] : cases) {
            auto fx = make("Vessel", {{"material", float(m)}, {"size", note}, {"decay", 3.0f}, {"mix", 1.0f}, {"strike", 0.23f}, {"pickup", 0.31f}});
            const auto out = run(*fx, click);
            const double found = peakNear(out.l, hz(note), 50.0, 2400, 50400, kRate);
            ok(std::string(names[m]) + ": a click rings at the size", std::fabs(cents(found, hz(note))) < 2.0,
               num(found) + " Hz for " + num(hz(note)) + ", " + num(cents(found, hz(note))) + " cents");
        }
    }
    {
        // Key snaps size to the nearest note of the scale, the lower on a tie.
        auto fx = make("Vessel", {{"size", 61.4f}, {"key", 1.0f}, {"scale", 0.0f}, {"mix", 1.0f}});
        run(*fx, click);
        ok("key C major: 61.4 snaps to D", std::fabs(cents(fundamental(*fx), hz(62.0f))) < 0.1, num(fundamental(*fx)) + " Hz");
        set(*fx, "size", 61.0f);
        run(*fx, std::vector<float>(64, 0.0f));
        ok("key C major: 61 is a tie and takes C", std::fabs(cents(fundamental(*fx), hz(60.0f))) < 0.1, num(fundamental(*fx)) + " Hz");
        set(*fx, "key", 3.0f); // D
        set(*fx, "size", 60.4f);
        run(*fx, std::vector<float>(64, 0.0f));
        ok("key D major: 60.4 snaps to C#", std::fabs(cents(fundamental(*fx), hz(61.0f))) < 0.1, num(fundamental(*fx)) + " Hz");
        set(*fx, "key", 0.0f);
        run(*fx, std::vector<float>(64, 0.0f));
        ok("key off: size is free", std::fabs(cents(fundamental(*fx), hz(60.4f))) < 0.1, num(fundamental(*fx)) + " Hz");
        // A snapped body rings there.
        auto fy = make("Vessel", {{"material", 7.0f}, {"size", 56.6f}, {"key", 1.0f}, {"decay", 3.0f}, {"mix", 1.0f}, {"strike", 0.23f}});
        const auto out = run(*fy, click);
        const double found = peakNear(out.l, hz(57.0f), 50.0, 2400, 50400, kRate);
        ok("a snapped body rings at the scale's note", std::fabs(cents(found, hz(57.0f))) < 2.0, num(cents(found, hz(57.0f))) + " cents");
    }
    {
        // Follow: the size until a note comes, then the note, and a reset forgets it.
        auto fx = make("Vessel", {{"material", 7.0f}, {"size", 48.0f}, {"follow", 1.0f}, {"decay", 3.0f}, {"mix", 1.0f}, {"strike", 0.23f}});
        run(*fx, std::vector<float>(64, 0.0f));
        ok("follow with no note yet: the size", std::fabs(cents(fundamental(*fx), hz(48.0f))) < 0.1, num(fundamental(*fx)) + " Hz");
        fx->onNote(45, 110.0f);
        const auto out = run(*fx, click);
        ok("follow: the track's note", std::fabs(fundamental(*fx) - 110.0f) < 0.01, num(fundamental(*fx)) + " Hz");
        const double found = peakNear(out.l, 110.0, 50.0, 2400, 50400, kRate);
        ok("follow: it rings at the note", std::fabs(cents(found, 110.0)) < 2.0, num(cents(found, 110.0)) + " cents");
        auto fk = make("Vessel", {{"size", 48.0f}, {"follow", 1.0f}, {"key", 3.0f}});
        fk->onNote(45, 110.0f);
        run(*fk, std::vector<float>(64, 0.0f));
        ok("follow wins over key", std::fabs(fundamental(*fk) - 110.0f) < 0.01, num(fundamental(*fk)) + " Hz");
        fx->reset();
        fx->params().jumpAll();
        run(*fx, std::vector<float>(64, 0.0f));
        ok("a reset forgets the note", std::fabs(cents(fundamental(*fx), hz(48.0f))) < 0.1, num(fundamental(*fx)) + " Hz");
    }
    {
        // Damping: wood's high modes go long before metal's.
        const auto fallOf = [&](int m) {
            auto fx = make("Vessel", {{"material", float(m)}, {"size", 48.0f}, {"decay", 2.0f}, {"mix", 1.0f}});
            const auto out = run(*fx, click);
            return fallTime(out.l, 480);
        };
        const double wood = fallOf(1), metal = fallOf(2);
        ok("wood dies sooner than metal", wood < metal * 0.5, num(wood) + " s against " + num(metal) + " s");
    }
    {
        // A full mix at the defaults sits near dry on music and on noise, every material.
        const auto song = music(4 * 48000, 7);
        const auto hiss = noise(0.3f, 4 * 48000);
        std::string spread;
        for (int m = 0; m < effect::Vessel::KindCount; ++m) {
            auto fx = make("Vessel", {{"material", float(m)}});
            const auto out = run(*fx, song);
            const double db = dB(rms(out.l, 48000, song.size()) / rms(song, 48000, song.size()));
            auto fy = make("Vessel", {{"material", float(m)}});
            const auto outN = run(*fy, hiss);
            const double dbN = dB(rms(outN.l, 48000, hiss.size()) / rms(hiss, 48000, hiss.size()));
            ok(std::string(names[m]) + ": the defaults sit within 1.5 dB of dry on music", std::fabs(db) < 1.5,
               num(db) + " dB, noise " + num(dbN) + " dB");
        }
        auto fx = make("Vessel", {{"mix", 0.0f}});
        ok("mix 0 is the track untouched", worst(run(*fx, song).l, song) == 0.0);
    }
    {
        // A held tone sitting on a long mode is held to the guard, not left to build.
        auto fx = make("Vessel", {{"material", 7.0f}, {"size", 57.0f}, {"decay", 20.0f}, {"damp", 0.0f}, {"mix", 1.0f}});
        const auto out = run(*fx, tone(220.0f, 0.1f, 10 * 48000));
        const double over = dB(peak(out.l) / 0.1);
        ok("a tone on a 20 s mode stays within 16 dB of its own peak", over < 16.0, num(over) + " dB");
        // Once it has settled, the body tuned to the tone is held to 3 dB over
        // it, and the 2 dB trim a full mix carries.
        const double l = rms(out.l, 5 * 48000, out.l.size()), r = rms(out.r, 5 * 48000, out.r.size());
        const double held = dB(std::sqrt(0.5 * (l * l + r * r)) / (0.1 / std::sqrt(2.0)));
        ok("a tone on a 20 s mode settles within 6 dB of the tone", held < 6.0, num(held) + " dB, left " + num(dB(l / (0.1 / std::sqrt(2.0)))) + " dB");
    }
    {
        // A retune or a new material mid-ring doesn't click: no step much past the ring's own.
        for (const char *what : {"size", "material", "strike"}) {
            auto fx = make("Vessel", {{"material", 2.0f}, {"size", 60.0f}, {"decay", 4.0f}, {"mix", 1.0f}});
            auto in = noise(0.2f, 9600);
            in.resize(48000, 0.0f);
            Stereo s{in, in};
            for (size_t at = 0; at < in.size(); at += kBlock) {
                if (at == 19200) set(*fx, what, std::strcmp(what, "size") == 0 ? 67.0f : std::strcmp(what, "material") == 0 ? 6.0f : 0.6f);
                fx->run(s.l.data() + at, s.r.data() + at, kBlock, false);
            }
            const double before = steepest(s.l, 19200 - 4800, 19200), after = steepest(s.l, 19200, 19200 + 256);
            ok(std::string("a change of ") + what + " mid-ring doesn't click", after < 1.5 * before,
               num(after) + " against " + num(before));
        }
    }
    {
        // Mods slice a block into 8-frame calls; a retune still glides over its
        // own time, not one call, so it doesn't click, and comes out the same
        // as in whole blocks.
        const auto render = [&](int32_t call, const std::function<void(Effect &, size_t)> &at) {
            auto fx = make("Vessel", {{"material", 2.0f}, {"size", 60.0f}, {"decay", 4.0f}, {"mix", 1.0f}, {"follow", 1.0f}});
            fx->onNote(60, float(hz(60.0f)));
            auto in = noise(0.2f, 9600);
            in.resize(48000, 0.0f);
            Stereo s{in, in};
            for (size_t i = 0; i < in.size(); i += size_t(call)) {
                at(*fx, i);
                fx->run(s.l.data() + i, s.r.data() + i, call, false);
            }
            return s;
        };
        const auto sliced = [&](const char *what, const std::function<void(Effect &, size_t)> &at, bool same) {
            const Stereo s = render(8, at);
            const double before = steepest(s.l, 19200 - 4800, 19200), after = steepest(s.l, 19200, 19200 + 256);
            const double kinkBefore = sharpest(s.l, 19200 - 4800, 19200), kinkAfter = sharpest(s.l, 19200, 19200 + 256);
            ok(std::string("in 8-frame calls, ") + what + " doesn't click", after < 1.5 * before && kinkAfter < 1.5 * kinkBefore,
               num(after) + " against " + num(before) + ", kink " + num(kinkAfter) + " against " + num(kinkBefore));
            if (same) {
                const double off = worst(s.l, render(240, at).l);
                ok(std::string("in 8-frame calls, ") + what + " glides as in whole blocks", off < 1e-5, num(off));
            }
        };
        sliced("a follow retune", [&](Effect &fx, size_t i) {
            if (i == 19200) fx.onNote(65, float(hz(65.0f)));
        }, true);
        sliced("a size jump", [&](Effect &fx, size_t i) {
            if (i == 19200) set(fx, "follow", 0.0f), set(fx, "size", 65.0f);
        }, true);
        sliced("a size moved every call", [&](Effect &fx, size_t i) {
            if (i == 19200) set(fx, "follow", 0.0f);
            if (i >= 19200 && i < 24000) set(fx, "size", 60.0f + 5.0f * float(i - 19200) / 4800.0f);
        }, false);
    }
    {
        // In tune at the bottom of size: the tube's fundamental and the bell's
        // first mode, which sits near 20 Hz.
        auto long_ = click;
        long_.resize(4 * 48000, 0.0f);
        const std::tuple<int, double> low[] = {{7, hz(24.0f)}, {5, 0.618 * hz(24.0f)}};
        for (const auto &[m, want] : low) {
            auto fx = make("Vessel", {{"material", float(m)}, {"size", 24.0f}, {"decay", 20.0f}, {"damp", 0.0f}, {"mix", 1.0f},
                                      {"strike", 0.23f}, {"pickup", 0.31f}});
            const auto out = run(*fx, long_);
            const double found = peakNear(out.l, want, 50.0, 2400, long_.size(), kRate);
            ok(std::string(names[m]) + ": size 24 rings in tune", std::fabs(cents(found, want)) < 1.0,
               num(found) + " Hz for " + num(want) + ", " + num(cents(found, want)) + " cents");
        }
    }
    {
        // Every material at the longest decay and the most drive, its ends of size: finite and bounded.
        bool fine = true;
        double top = 0.0;
        auto burst = noise(0.9f, 96000, 9);
        for (size_t i = 48000; i < burst.size(); ++i) burst[i] = (i % 9600 == 0) ? 1.0f : 0.0f;
        for (int m = 0; m < effect::Vessel::KindCount; ++m) {
            for (float size : {24.0f, 60.0f, 96.0f}) {
                for (float damp : {0.0f, 1.0f}) {
                    auto fx = make("Vessel", {{"material", float(m)}, {"size", size}, {"decay", 20.0f}, {"drive", 24.0f},
                                              {"damp", damp}, {"mix", 1.0f}, {"strike", 0.02f}, {"pickup", 0.98f}});
                    const auto out = run(*fx, burst);
                    fine = fine && finite(out);
                    top = std::max(top, std::max(peak(out.l), peak(out.r)));
                }
            }
        }
        ok("every material at the longest decay and the most drive: finite and bounded", fine && top < 8.0, "peak " + num(top));
    }
    staysBounded("Vessel");
}

int main() {
    printf("character effects\n");
    smash();
    acid();
    mouth();
    mouthWords();
    tape();
    slicer();
    magneto();
    horn();
    spectral();
    formula();
    delayModes();
    transient();
    transientGrid();
    midSide();
    deEsser();
    reverb();
    venue();
    vessel();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
