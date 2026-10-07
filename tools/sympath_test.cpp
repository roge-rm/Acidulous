// Sympath, plucked strings over a buzzing bridge: in tune, the bridge
// throwing the note's energy up into its high partials and more so the harder
// it's played, the sympathetic strings ringing on their own notes, the
// tanpura plucking in time, meend, and a finger pulling the string.
//
//   sympath_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/sympath/Sympath.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Sympath;

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

/** A Sympath played block by block. */
struct Strings {
    std::unique_ptr<Machine> owned;
    Sympath *f;
    std::vector<float> out;

    explicit Strings(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Sympath"));
        f = static_cast<Sympath *>(owned.get());
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


enum { Sitar, Tanpura, Veena, Shamisen };

/** The upper partials (10 to 30) against the lower (1 to 3), dB. */
double buzz(const Strings &g, double hz, double from, double to) {
    double hi = 0.0, lo = 0.0;
    for (int n = 10; n <= 30; ++n) hi += std::pow(10.0, g.level(hz * n, from, to) / 10.0);
    for (int n = 1; n <= 3; ++n) lo += std::pow(10.0, g.level(hz * n, from, to) / 10.0);
    return 10.0 * std::log10(hi / lo);
}

void tuning() {
    std::printf("- tuning\n");
    const struct { const char *kind; float model; int note; } cases[] = {
        {"sitar C3", Sitar, 48}, {"sitar C4", Sitar, 60}, {"sitar G4", Sitar, 67}, {"veena D3", Veena, 50},
        {"shamisen A3", Shamisen, 57}, {"shamisen E5", Shamisen, 76},
    };
    for (const auto &c : cases) {
        Strings g({{"model", c.model}, {"bridge", 0.0f}, {"tarbs", 0.0f}});
        g.f->noteOn(static_cast<uint8_t>(c.note), 90);
        g.play(1.0);
        const double want = noteHz(c.note);
        const double got = g.peak(want * 0.97, want * 1.03, 0.2, 0.9);
        check(std::fabs(cents(got, want)) < 6.0, fmt("%s plays in tune", c.kind).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
    {
        Strings g({{"model", Sitar}, {"tarbs", 0.0f}});
        g.f->noteOn(55, 90);
        g.play(1.0);
        const double want = noteHz(55);
        const double got = g.peak(want * 0.97, want * 1.03, 0.2, 0.9);
        check(std::fabs(cents(got, want)) < 25.0, "and with the bridge buzzing, near enough", fmt("%+.1f cents", cents(got, want)));
    }
}

void bridge() {
    std::printf("- bridge\n");
    const double hz = noteHz(50);
    Strings plain({{"bridge", 0.0f}, {"tarbs", 0.0f}}), buzzing({{"bridge", 1.0f}, {"tarbs", 0.0f}});
    plain.f->noteOn(50, 110);
    buzzing.f->noteOn(50, 110);
    plain.play(1.5);
    buzzing.play(1.5);
    const double p = buzz(plain, hz, 0.4, 1.2), b = buzz(buzzing, hz, 0.4, 1.2);
    check(b > p + 6.0, "the bridge throws the note up into its high partials", fmt("%.1f vs %.1f dB", b, p));
    Strings soft({{"bridge", 1.0f}, {"tarbs", 0.0f}});
    soft.f->noteOn(50, 35);
    soft.play(1.5);
    const double s = buzz(soft, hz, 0.4, 1.2);
    check(b > s + 4.0, "a harder pluck buzzes more", fmt("%.1f vs %.1f dB", b, s));
    Strings high({{"bridge", 1.0f}, {"curve", 1.0f}, {"tarbs", 0.0f}});
    high.f->noteOn(50, 110);
    high.play(1.5);
    const double h = buzz(high, hz, 0.4, 1.2);
    check(b > h + 4.0, "a higher curve needs a wider swing to buzz", fmt("%.1f vs %.1f dB", b, h));
}

void sympathy() {
    std::printf("- sympathetic strings\n");
    // Sa is C#, and Bhairavi's notes from it are C# D E F# G# A B: D# isn't one.
    auto after = [&](float tarbs, int note) {
        Strings g({{"sa", 1.0f}, {"scale", 4.0f}, {"tarbs", tarbs}, {"bridge", 0.3f}});
        g.f->noteOn(static_cast<uint8_t>(note), 110);
        g.play(0.3);
        g.f->noteOff(static_cast<uint8_t>(note));
        g.play(4.0);
        return g.rms(3.5, 4.0);
    };
    const double on = after(1.0f, 61), off = after(0.0f, 61);
    check(on > off + 10.0, "the sympathetic strings ring on after the note", fmt("%.1f vs %.1f dB", on, off));
    const double outside = after(1.0f, 63);
    check(on > outside + 6.0, "more for a note on the scale than off it", fmt("%.1f vs %.1f dB", on, outside));
}

void tanpura() {
    std::printf("- tanpura\n");
    // Four beats at 120: a cycle every two seconds, a string every 0.4 s:
    // Pa at 0, Sa at 0.4 and 0.8, low Sa at 1.2, then Pa again at 2.0.
    Strings g({{"model", Tanpura}, {"cycle", 1.0f}, {"bridge", 0.0f}});
    g.bpm = 120.0f;
    g.f->noteOn(48, 100);
    g.play(4.5);
    const double pa = noteHz(43), low = noteHz(36);
    const double paFirst = g.level(pa, 0.05, 0.35) - 0.5 * (g.level(pa * 0.92, 0.05, 0.35) + g.level(pa * 1.08, 0.05, 0.35));
    check(paFirst > 20.0, "its first string, Pa below Sa, starts the cycle", fmt("%.1f dB over its neighbours", paFirst));
    const double lowIn = g.level(low, 1.25, 1.55) - g.level(low, 0.9, 1.15);
    check(lowIn > 10.0, "its low Sa comes in at the fourth pluck", fmt("%+.1f dB at 1.2 s", lowIn));
    const double again = g.level(pa, 2.05, 2.3) - g.level(pa, 1.7, 1.95);
    check(again > 6.0, "and the cycle starts again on time", fmt("Pa %+.1f dB at 2.0 s", again));
    // Half the tempo from 4.5 s: the cycle that began at 4.0 now takes four
    // seconds, so Pa comes at 8.0 instead of 6.0.
    g.bpm = 60.0f;
    g.play(4.0);
    const double slow = g.level(pa, 8.05, 8.3) - g.level(pa, 7.7, 7.95);
    const double notYet = g.level(pa, 6.05, 6.3) - g.level(pa, 5.7, 5.95);
    check(slow > 6.0 && notYet < 1.0, "it follows the tempo", fmt("Pa %+.1f dB at 8.0 s, %+.1f at 6.0", slow, notYet));
    g.f->noteOff(48);
    g.play(4.0);
    const double after = g.level(pa, 12.05, 12.3) - g.level(pa, 11.7, 11.95);
    check(after < 1.0, "and stops plucking when the note's let go", fmt("Pa %+.1f dB when it would have come", after));
}

void hands() {
    std::printf("- hands\n");
    {
        Strings g({{"voices", 1.0f}, {"meend", 100.0f}, {"tarbs", 0.0f}, {"bridge", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(0.3);
        g.f->noteOn(62, 100);
        g.play(0.6);
        const double got = g.peak(noteHz(62) * 0.97, noteHz(62) * 1.03, 0.6, 0.9);
        check(std::fabs(cents(got, noteHz(62))) < 10.0 && g.f->activeVoices() == 1, "meend slides to the new note on one string",
              fmt("%+.1f cents, %d voices", cents(got, noteHz(62)), g.f->activeVoices()));
        check(g.rms(0.3, 0.32) < g.rms(0.0, 0.02) - 3.0, "without a new pluck", fmt("%.1f vs %.1f dB", g.rms(0.3, 0.32), g.rms(0.0, 0.02)));
    }
    {
        Strings g({{"tarbs", 0.0f}, {"bridge", 0.0f}});
        g.f->noteOn(60, 100);
        g.f->channelPressure(127);
        g.play(1.0);
        const double want = noteHz(62);
        const double got = g.peak(want * 0.97, want * 1.03, 0.5, 0.95);
        check(std::fabs(cents(got, want)) < 15.0, "pressure pulls the string up a whole tone", fmt("%+.1f cents from D", cents(got, want)));
    }
    {
        Strings plain({{"model", Sitar}, {"chikari", 0.0f}}), drones({{"model", Sitar}, {"chikari", 1.0f}});
        plain.f->noteOn(55, 100);
        drones.f->noteOn(55, 100);
        plain.play(0.5);
        drones.play(0.5);
        // Sa is C# by default: the drone strings at C#4 and C#5.
        const double d = drones.level(noteHz(73), 0.05, 0.4) - plain.level(noteHz(73), 0.05, 0.4);
        check(d > 10.0, "chikari strikes the drone strings", fmt("%+.1f dB at the upper Sa", d));
    }
}

void lifecycle() {
    std::printf("- voices\n");
    {
        Strings g({{"model", Shamisen}});
        for (int n = 50; n < 70; n += 5) g.f->noteOn(static_cast<uint8_t>(n), 100);
        g.play(0.2);
        g.f->allNotesOff();
        g.play(6.0);
        check(g.f->activeVoices() == 0, "let-go notes end and free their voices", fmt("%d left", g.f->activeVoices()));
    }
    for (int kind = 0; kind <= Shamisen; ++kind) {
        Strings g({{"model", static_cast<float>(kind)}, {"bridge", 1.0f}, {"curve", 0.0f}, {"pluck", 1.0f}, {"sustain", 1.0f},
                   {"bright", 1.0f}, {"tarbs", 1.0f}, {"chikari", 1.0f}, {"gamak", 1.0f}, {"volume", 1.0f}});
        g.f->channelPressure(127);
        for (int n = 36; n < 90; n += 13) g.f->noteOn(static_cast<uint8_t>(n), 127);
        g.play(3.0);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        check(finite && g.peakAbs() < 8.0, fmt("kind %d, every knob up, stays bounded", kind).c_str(), fmt("peak %.2f", g.peakAbs()));
    }
}

} // namespace

int main() {
    tuning();
    bridge();
    sympathy();
    tanpura();
    hands();
    lifecycle();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
