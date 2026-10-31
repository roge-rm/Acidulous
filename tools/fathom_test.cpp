// Fathom, water and weather: a bubble's pitch set by the note and rising,
// rain thickening with density, a held note keeping a texture going and
// letting go fading it out, the wind whistling on the note, waves on the
// tempo, and crackles in a fire.
//
//   fathom_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/fathom/Fathom.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Fathom;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
int failures = 0, checks = 0;

std::string fmt(const char *f, ...) {
    char buf[256];
    va_list a;
    va_start(a, f);
    std::vsnprintf(buf, sizeof(buf), f, a);
    va_end(a);
    return buf;
}

void check(bool ok, const char *what, const std::string &detail = "") {
    ++checks;
    if (!ok) ++failures;
    std::printf("  %s %-58s %s\n", ok ? "ok  " : "FAIL", what, detail.c_str());
}

double noteHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }

/** A Fathom played block by block. */
struct Water {
    std::unique_ptr<Machine> owned;
    Fathom *f;
    std::vector<float> out;

    explicit Water(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Fathom"));
        f = static_cast<Fathom *>(owned.get());
        f->prepare(kRate);
        f->reset();
        f->params().jumpAll();
        for (const auto &k : knobs) set(k.first, k.second);
    }
    void set(const char *name, float value) {
        ParamSet &p = f->params();
        const int i = p.indexOf(name);
        if (i < 0) {
            std::printf("no parameter %s\n", name);
            std::exit(2);
        }
        p.jump(i, p.def(i).unmap(value));
    }
    float bpm = 120.0f;
    void play(double seconds) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        for (int b = 0; b < blocks; ++b) {
            f->onBlock(0, 0, bpm);
            f->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
        }
    }
    double level(double hz, double from, double to) const {
        const auto a = static_cast<size_t>(from * kRate), b = static_cast<size_t>(to * kRate);
        double re = 0.0, im = 0.0, wsum = 0.0;
        for (size_t i = a; i < b && i < out.size(); ++i) {
            const double w = 0.5 - 0.5 * std::cos(6.283185307179586 * static_cast<double>(i - a) / static_cast<double>(b - a));
            const double ph = 6.283185307179586 * hz * static_cast<double>(i) / kRate;
            re += out[i] * w * std::cos(ph);
            im -= out[i] * w * std::sin(ph);
            wsum += w;
        }
        return 20.0 * std::log10(std::sqrt(re * re + im * im) / wsum + 1e-12);
    }
    double peak(double lo, double hi, double from, double to) const {
        double best = lo, bestLevel = -1e9;
        for (double f0 = lo; f0 <= hi; f0 += 0.25) {
            const double l = level(f0, from, to);
            if (l > bestLevel) { bestLevel = l; best = f0; }
        }
        for (double f0 = best - 0.25; f0 <= best + 0.25; f0 += 0.01) {
            const double l = level(f0, from, to);
            if (l > bestLevel) { bestLevel = l; best = f0; }
        }
        return best;
    }
    /** The level of harmonic [n] of [hz], dB: the loudest within a few cents of it. */
    double harmonic(double hz, int n, double from, double to) const {
        const double h = hz * n;
        return level(peak(h * 0.995, h * 1.005, from, to), from, to);
    }
    double rms(double from, double to) const {
        const auto a = static_cast<size_t>(from * kRate), b = std::min(out.size(), static_cast<size_t>(to * kRate));
        double s = 0.0;
        for (size_t i = a; i < b; ++i) s += double(out[i]) * out[i];
        return 10.0 * std::log10(s / std::max<size_t>(1, b - a) + 1e-20);
    }
    double peakAbs() const {
        double p = 0.0;
        for (float v : out) p = std::max(p, double(std::fabs(v)));
        return p;
    }
};

double cents(double a, double b) { return 1200.0 * std::log2(a / b); }

enum { Bubbles, Drips, Rain, Stream, Surf, Wind, Fire };

void bubbles() {
    std::printf("- bubbles\n");
    for (int note : {60, 72, 84}) {
        Water g({{"model", Bubbles}, {"size", 0.0f}, {"rise", 0.0f}, {"density", 0.0f}});
        g.f->noteOn(static_cast<uint8_t>(note), 100);
        g.play(0.1);
        const double want = noteHz(note);
        const double got = g.peak(want * 0.9, want * 1.1, 0.0, 0.03);
        check(std::fabs(cents(got, want)) < 15.0, fmt("a bubble on %d rings at the note", note).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
    {
        Water g({{"model", Bubbles}, {"size", 0.0f}, {"rise", 1.0f}, {"density", 0.0f}, {"decay", 1.0f}});
        g.f->noteOn(60, 100);
        g.play(0.2);
        const double want = noteHz(60);
        const double early = g.peak(want * 0.9, want * 1.6, 0.0, 0.012), late = g.peak(want * 0.9, want * 2.2, 0.03, 0.05);
        check(cents(late, early) > 50.0, "and rises as it nears the surface", fmt("%+.0f cents", cents(late, early)));
    }
    {
        Water g({{"model", Drips}, {"size", 0.0f}, {"density", 0.0f}});
        g.f->noteOn(67, 100);
        g.play(0.2);
        check(g.f->grainsStarted() >= 2, "a drip is a tap and a bubble", fmt("%u grains", g.f->grainsStarted()));
    }
}

void textures() {
    std::printf("- textures\n");
    {
        auto drops = [&](float density) {
            Water g({{"model", Rain}, {"density", density}, {"fade", 0.05f}});
            g.f->noteOn(60, 100);
            g.play(1.0);
            return g.f->grainsStarted();
        };
        const uint32_t light = drops(0.3f), heavy = drops(0.8f);
        check(heavy > light * 5, "rain thickens with density", fmt("%u drops against %u", heavy, light));
    }
    {
        Water g({{"model", Rain}, {"fade", 0.3f}});
        g.f->noteOn(60, 100);
        g.play(2.0);
        g.f->noteOff(60);
        g.play(3.0);
        check(g.rms(1.5, 2.0) > -50.0, "a held note keeps the rain going", fmt("%.1f dB", g.rms(1.5, 2.0)));
        check(g.rms(4.0, 5.0) < -90.0 && g.f->activeVoices() == 0, "and letting go fades it out", fmt("%.1f dB, %d left", g.rms(4.0, 5.0), g.f->activeVoices()));
    }
    {
        Water dry({{"model", Rain}, {"surface", 0.0f}}), tin({{"model", Rain}, {"surface", 2.0f}});
        dry.f->noteOn(60, 100);
        tin.f->noteOn(60, 100);
        dry.play(1.0);
        tin.play(1.0);
        const double d = tin.level(910.0, 0.2, 1.0) - dry.level(910.0, 0.2, 1.0);
        check(d > 10.0, "rain on a tin roof rings it", fmt("%+.1f dB at the roof's note", d));
    }
    {
        auto count = [&](int pressure) {
            Water g({{"model", Stream}, {"density", 0.3f}});
            g.f->noteOn(60, 100);
            if (pressure > 0) g.f->channelPressure(static_cast<uint8_t>(pressure));
            g.play(1.0);
            return g.f->grainsStarted();
        };
        check(count(127) > count(0) * 3 / 2, "pressure thickens it", fmt("%u against %u", count(127), count(0)));
    }
    {
        Water g({{"model", Wind}, {"whistle", 1.0f}, {"gust", 0.2f}});
        g.f->noteOn(72, 100);
        g.play(1.5);
        const double at = g.level(noteHz(72), 0.5, 1.5), off = 0.5 * (g.level(noteHz(72) * 0.85, 0.5, 1.5) + g.level(noteHz(72) * 1.15, 0.5, 1.5));
        check(at > off + 15.0, "the wind whistles on the note", fmt("%.1f dB over beside it", at - off));
    }
    {
        // Four beats a wave: two seconds at 120, the crash a third of the way in.
        auto crash = [&](float bpm) {
            Water g({{"model", Surf}, {"swell", 4.0f}, {"gust", 1.0f}, {"fade", 0.01f}});
            g.bpm = bpm;
            g.f->noteOn(48, 100);
            g.play(4.5);
            return g;
        };
        Water fast = crash(120.0f), slow = crash(60.0f);
        const double f = fast.rms(2.5, 2.7) - fast.rms(1.75, 1.95);
        const double s = slow.rms(1.1, 1.3) - slow.rms(3.6, 3.8);
        check(f > 6.0 && s > 6.0, "waves crash in time with the tempo", fmt("%+.1f dB at 120, %+.1f at 60", f, s));
    }
    {
        Water g({{"model", Fire}, {"density", 0.6f}});
        g.f->noteOn(60, 100);
        g.play(1.0);
        check(g.f->grainsStarted() > 5 && g.rms(0.3, 1.0) > -60.0, "a fire crackles and roars", fmt("%u crackles, %.1f dB", g.f->grainsStarted(), g.rms(0.3, 1.0)));
    }
}

/** Plays [seconds] and returns the pitch of each new ringing grain. */
std::vector<double> rings(Water &g, double seconds) {
    std::vector<double> got;
    float L[kBlock], R[kBlock];
    uint32_t n = g.f->ringsStarted();
    for (int b = 0; b < static_cast<int>(seconds * kRate / kBlock); ++b) {
        g.f->onBlock(0, 0, 120.0f);
        g.f->render(L, R, kBlock);
        if (g.f->ringsStarted() != n) got.push_back(g.f->lastRingHz());
        n = g.f->ringsStarted();
    }
    return got;
}

/** The share of [hz] within 3 cents of a pitch class in [classes] (0 = C). */
double inKey(const std::vector<double> &hz, std::initializer_list<int> classes) {
    if (hz.empty()) return 0.0;
    int in = 0;
    for (double f : hz) {
        const double note = 69.0 + 12.0 * std::log2(f / 440.0);
        bool ok = false;
        for (int c : classes) {
            double d = std::fmod(note - c, 12.0);
            if (d < 0) d += 12.0;
            ok = ok || std::fmin(d, 12.0 - d) < 0.03;
        }
        in += ok ? 1 : 0;
    }
    return static_cast<double>(in) / static_cast<double>(hz.size());
}

void key() {
    std::printf("- the key\n");
    auto chord = [](float snap) {
        Water g({{"model", Bubbles}, {"size", 12.0f}, {"rise", 0.0f}, {"density", 1.0f}, {"snap", snap}});
        for (int n : {60, 64, 67}) g.f->noteOn(static_cast<uint8_t>(n), 100);
        return rings(g, 3.0);
    };
    const auto free = chord(0.0f), half = chord(0.5f), full = chord(1.0f);
    const double f0 = inKey(free, {0, 4, 7}), f5 = inKey(half, {0, 4, 7}), f1 = inKey(full, {0, 4, 7});
    check(full.size() > 50 && f1 > 0.999, "snapped, every bubble lands on the held chord", fmt("%.0f%% of %zu", 100 * f1, full.size()));
    check(f0 < 0.1, "free, they don't", fmt("%.0f%%", 100 * f0));
    check(f5 > f0 + 0.2 && f5 < f1 - 0.2, "half way, some do", fmt("%.0f%%", 100 * f5));
    {
        // The chord changes: new bubbles take the new notes.
        Water g({{"model", Bubbles}, {"size", 12.0f}, {"rise", 0.0f}, {"density", 1.0f}, {"snap", 1.0f}});
        for (int n : {60, 64, 67}) g.f->noteOn(static_cast<uint8_t>(n), 100);
        rings(g, 1.0);
        for (int n : {60, 64, 67}) g.f->noteOff(static_cast<uint8_t>(n));
        for (int n : {62, 66, 69}) g.f->noteOn(static_cast<uint8_t>(n), 100);
        const auto next = rings(g, 2.0);
        check(next.size() > 20 && inKey(next, {2, 6, 9}) > 0.999, "a new chord moves the bubbles to it", fmt("%.0f%% of %zu", 100 * inKey(next, {2, 6, 9}), next.size()));
    }
    {
        // A scale: D minor pentatonic, from one held note off the scale.
        Water g({{"model", Stream}, {"size", 12.0f}, {"rise", 0.0f}, {"density", 1.0f}, {"snap", 1.0f},
                 {"snap_to", 1.0f}, {"scale", 10.0f}, {"key", 2.0f}});
        g.f->noteOn(61, 100);
        const auto got = rings(g, 2.0);
        check(got.size() > 50 && inKey(got, {2, 5, 7, 9, 0}) > 0.999, "a scale puts the stream in it", fmt("%.0f%% of %zu", 100 * inKey(got, {2, 5, 7, 9, 0}), got.size()));
    }
    {
        // A kit: a held pad gives its note as set now, and a fire pad has no pitch to give.
        Water g({{"kit", 1.0f}, {"p01_model", Bubbles}, {"p01_note", 60.0f}, {"p01_density", 1.0f},
                 {"p02_model", Fire}, {"p02_note", 61.0f}, {"size", 12.0f}, {"rise", 0.0f}, {"snap", 1.0f}});
        g.f->noteOn(36, 100);
        g.f->noteOn(37, 100);
        const auto first = rings(g, 1.5);
        g.set("p01_note", 62.0f);
        rings(g, 0.1);
        const auto moved = rings(g, 1.5);
        check(first.size() > 20 && inKey(first, {0}) > 0.999, "a kit's chord is its pitched pads", fmt("%.0f%% of %zu", 100 * inKey(first, {0}), first.size()));
        check(moved.size() > 20 && inKey(moved, {2}) > 0.999, "and follows a held pad's note", fmt("%.0f%% of %zu", 100 * inKey(moved, {2}), moved.size()));
    }
    {
        // A stretched tuning, 1.3 semitones a key, played an octave up: the drops land on the note the voice plays.
        static float table[128];
        for (int n = 0; n < 128; ++n) table[n] = static_cast<float>(std::pow(2.0, (n - 60) * 0.3 / 12.0));
        Water g({{"model", Bubbles}, {"size", 12.0f}, {"rise", 0.0f}, {"density", 1.0f}, {"snap", 1.0f}, {"octave", 1.0f}});
        g.f->setTuning(table);
        g.f->noteOn(60, 100);
        const auto got = rings(g, 2.0);
        const double want = 69.0 + 12.0 * std::log2(noteHz(72) * table[72] / 440.0);
        int in = 0;
        for (double f : got) {
            double d = std::fmod(69.0 + 12.0 * std::log2(f / 440.0) - want, 12.0);
            if (d < 0) d += 12.0;
            in += std::fmin(d, 12.0 - d) < 0.03 ? 1 : 0;
        }
        check(got.size() > 20 && in == static_cast<int>(got.size()), "in a tuning and an octave up, on the tuned note", fmt("%d of %zu", in, got.size()));
    }
    {
        // A macro routed to snap brings the bubbles into key with the knob at 0.
        Water g({{"model", Bubbles}, {"size", 12.0f}, {"rise", 0.0f}, {"density", 1.0f},
                 {"mac1", 1.0f}, {"mac1_d1", static_cast<float>(Fathom::McSnap)}, {"mac1_a1", 1.0f}});
        for (int n : {60, 64, 67}) g.f->noteOn(static_cast<uint8_t>(n), 100);
        const auto got = rings(g, 2.0);
        check(got.size() > 20 && inKey(got, {0, 4, 7}) > 0.999, "a macro on snap puts them in key", fmt("%.0f%% of %zu", 100 * inKey(got, {0, 4, 7}), got.size()));
    }
    {
        // The rain catches more bubbles in the key, so it sings it.
        auto count = [](float snap) {
            Water g({{"model", Rain}, {"density", 0.6f}, {"snap", snap}});
            g.f->noteOn(60, 100);
            return rings(g, 3.0).size();
        };
        const size_t off = count(0.0f), on = count(1.0f);
        check(on > off * 3, "keyed rain carries more bubbles", fmt("%zu against %zu", on, off));
    }
}

void lifecycle() {
    std::printf("- extremes\n");
    for (int kind = 0; kind <= Fire; ++kind) {
        for (int surface = 0; surface < (kind == Rain ? 4 : 1); ++surface) {
            Water g({{"model", static_cast<float>(kind)}, {"surface", static_cast<float>(surface)}, {"density", 1.0f}, {"size", 24.0f},
                     {"rise", 1.0f}, {"decay", 1.0f}, {"gust", 1.0f}, {"whistle", 1.0f}, {"tone", 1.0f}, {"swell", 1.0f}, {"volume", 1.0f}});
            g.f->channelPressure(127);
            for (int n = 30; n < 100; n += 20) g.f->noteOn(static_cast<uint8_t>(n), 127);
            g.play(2.0);
            bool finite = true;
            for (float v : g.out) finite = finite && std::isfinite(v);
            check(finite && g.peakAbs() < 12.0, fmt("kind %d surface %d, every knob up, stays bounded", kind, surface).c_str(), fmt("peak %.2f", g.peakAbs()));
        }
    }
}

} // namespace

int main() {
    bubbles();
    textures();
    key();
    lifecycle();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
