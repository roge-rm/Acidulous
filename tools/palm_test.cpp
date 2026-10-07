// Palm, hand drums: in tune, a tabla's harmonic overtones against a plain
// head's, the strokes (muted dies first, a slap is bright, a bass stroke is
// low), a djembe's body, a cajón's snares, a talking drum squeezed, a head
// going sharp when struck hard, a finger damping it, rolls.
//
//   palm_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/palm/Palm.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Palm;

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

/** A Palm played block by block. */
struct Drum {
    std::unique_ptr<Machine> owned;
    Palm *f;
    std::vector<float> out;

    explicit Drum(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Palm"));
        f = static_cast<Palm *>(owned.get());
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
    void play(double seconds) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        for (int b = 0; b < blocks; ++b) {
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

enum { Tabla, Bayan, Djembe, Cajon, Frame, Talking };
enum { Open, Slap, Muted, Bass, Rim, ByVelocity };

/** Energy from [lo] to [hi] Hz, dB, in 100 Hz steps. */
double band(const Drum &g, double lo, double hi, double from, double to) {
    double s = 0.0;
    for (double f0 = lo; f0 <= hi; f0 += 100.0) s += std::pow(10.0, g.level(f0, from, to) / 10.0);
    return 10.0 * std::log10(s + 1e-30);
}

void tuning() {
    std::printf("- tuning\n");
    const struct { const char *kind; float model; int note; } cases[] = {
        {"tabla D4", Tabla, 62}, {"tabla A4", Tabla, 69}, {"bayan C3", Bayan, 48}, {"djembe G3", Djembe, 55},
        {"cajon E3", Cajon, 52}, {"frame drum A2", Frame, 45}, {"talking drum C4", Talking, 60},
    };
    for (const auto &c : cases) {
        Drum g({{"model", c.model}, {"stroke", Bass}, {"drop", 0.0f}});
        g.f->noteOn(static_cast<uint8_t>(c.note), 90);
        g.play(0.4);
        const double want = noteHz(c.note);
        const double got = g.peak(want * 0.97, want * 1.03, 0.0, 0.25);
        check(std::fabs(cents(got, want)) < 5.0, fmt("%s plays in tune", c.kind).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
}

void heads() {
    std::printf("- heads\n");
    const double hz = noteHz(62);
    Drum tabla({{"model", Tabla}, {"stroke", Open}, {"drop", 0.0f}}), djembe({{"model", Djembe}, {"stroke", Open}, {"drop", 0.0f}});
    tabla.f->noteOn(62, 100);
    djembe.f->noteOn(62, 100);
    tabla.play(0.6);
    djembe.play(0.6);
    for (int n = 2; n <= 4; ++n) {
        const double got = tabla.peak(hz * n * 0.97, hz * n * 1.03, 0.02, 0.5);
        const double at = tabla.level(got, 0.02, 0.5), beside = tabla.level(hz * (n + 0.5), 0.02, 0.5);
        check(std::fabs(cents(got, hz * n)) < 10.0 && at > beside + 15.0, fmt("a tabla's overtone %d is harmonic", n).c_str(),
              fmt("%+.1f cents, %.1f dB above", cents(got, hz * n), at - beside));
    }
    const double plain = djembe.level(djembe.peak(hz * 1.56, hz * 1.62, 0.0, 0.2), 0.0, 0.2) - djembe.level(hz * 2.0, 0.0, 0.2);
    check(plain > 10.0, "a plain head's first overtone isn't (1.59, not 2)", fmt("%.1f dB over the octave", plain));
}

void strokes() {
    std::printf("- strokes\n");
    auto play = [&](int stroke, int velocity = 100) {
        auto g = std::make_unique<Drum>(std::initializer_list<std::pair<const char *, float>>{{"model", Djembe}, {"stroke", static_cast<float>(stroke)}, {"rattle", 0.0f}});
        g->f->noteOn(55, static_cast<uint8_t>(velocity));
        g->play(0.6);
        return g;
    };
    auto fall = [](const Drum &g) { return g.rms(0.0, 0.03) - g.rms(0.15, 0.2); };
    auto open = play(Open), slap = play(Slap), muted = play(Muted), bass = play(Bass), rim = play(Rim);
    check(fall(*muted) > fall(*open) + 15.0, "a muted stroke dies first", fmt("falls %.1f vs %.1f dB", fall(*muted), fall(*open)));
    const double hz = noteHz(55);
    auto bright = [&](const Drum &g) { return band(g, 2000.0, 8000.0, 0.0, 0.1) - band(g, 100.0, 600.0, 0.0, 0.1); };
    check(bright(*slap) > bright(*open) + 6.0, "a slap is brighter than an open stroke", fmt("%.1f vs %.1f dB", bright(*slap), bright(*open)));
    check(bright(*rim) > bright(*open) + 3.0, "and so is the rim", fmt("%.1f vs %.1f dB", bright(*rim), bright(*open)));
    const double low = bass->level(hz * 0.33, 0.0, 0.2) - open->level(hz * 0.33, 0.0, 0.2);
    check(low > 10.0, "a bass stroke rings the djembe's body", fmt("%+.1f dB at the body", low));
    {
        Drum none({{"model", Djembe}, {"stroke", Bass}, {"body", 0.0f}});
        none.f->noteOn(55, 100);
        none.play(0.3);
        const double b = bass->level(hz * 0.33, 0.0, 0.2) - none.level(hz * 0.33, 0.0, 0.2);
        check(b > 10.0, "which the body knob takes away", fmt("%+.1f dB", b));
    }
    {
        auto soft = std::make_unique<Drum>(std::initializer_list<std::pair<const char *, float>>{{"model", Djembe}, {"stroke", ByVelocity}});
        soft->f->noteOn(55, 25);
        soft->play(0.6);
        auto mid = std::make_unique<Drum>(std::initializer_list<std::pair<const char *, float>>{{"model", Djembe}, {"stroke", ByVelocity}});
        mid->f->noteOn(55, 80);
        mid->play(0.6);
        check(fall(*soft) > fall(*mid) + 15.0, "by velocity, a soft note is muted", fmt("falls %.1f vs %.1f dB", fall(*soft), fall(*mid)));
    }
}

void hands() {
    std::printf("- hands\n");
    {
        Drum dry({{"model", Cajon}, {"rattle", 0.0f}}), snares({{"model", Cajon}, {"rattle", 1.0f}});
        dry.f->noteOn(52, 110);
        snares.f->noteOn(52, 110);
        dry.play(0.3);
        snares.play(0.3);
        const double d = band(snares, 2500.0, 5000.0, 0.01, 0.15) - band(dry, 2500.0, 5000.0, 0.01, 0.15);
        check(d > 8.0, "a cajon's snares buzz", fmt("%+.1f dB at 2.5-5 kHz", d));
    }
    {
        Drum g({{"model", Talking}, {"squeeze", 7.0f}, {"drop", 0.0f}, {"stroke", Bass}, {"decay", 1.0f}});
        g.f->noteOn(60, 100);
        g.f->channelPressure(127);
        g.play(0.6);
        const double want = noteHz(67);
        const double got = g.peak(want * 0.95, want * 1.05, 0.3, 0.55);
        check(std::fabs(cents(got, want)) < 20.0, "squeezing a talking drum bends it up a fifth", fmt("%+.1f cents from G", cents(got, want)));
    }
    {
        Drum g({{"model", Frame}, {"drop", 1.0f}, {"stroke", Bass}, {"decay", 1.0f}});
        g.f->noteOn(45, 127);
        g.play(0.6);
        const double hz = noteHz(45);
        const double early = g.peak(hz * 0.98, hz * 1.12, 0.0, 0.06), late = g.peak(hz * 0.98, hz * 1.12, 0.3, 0.55);
        check(cents(early, late) > 20.0, "a head struck hard is sharp at first", fmt("%+.1f cents above where it settles", cents(early, late)));
        check(std::fabs(cents(late, hz)) < 10.0, "and settles on the note", fmt("%+.1f cents", cents(late, hz)));
    }
    {
        Drum free({{"model", Tabla}}), pressed({{"model", Tabla}});
        free.f->noteOn(62, 100);
        pressed.f->noteOn(62, 100);
        free.play(0.05);
        pressed.play(0.05);
        pressed.f->noteTimbre(62, 127);
        free.play(0.4);
        pressed.play(0.4);
        check(pressed.rms(0.3, 0.4) < free.rms(0.3, 0.4) - 15.0, "a finger sliding onto the head damps it", fmt("%.1f vs %.1f dB", pressed.rms(0.3, 0.4), free.rms(0.3, 0.4)));
    }
    {
        Drum plain({{"model", Frame}}), rolled({{"model", Frame}, {"roll", 12.0f}});
        plain.f->noteOn(45, 100);
        rolled.f->noteOn(45, 100);
        plain.play(2.0);
        rolled.play(2.0);
        check(rolled.rms(1.5, 2.0) > plain.rms(1.5, 2.0) + 20.0, "a roll keeps a held note going", fmt("%.1f vs %.1f dB", rolled.rms(1.5, 2.0), plain.rms(1.5, 2.0)));
    }
}

void lifecycle() {
    std::printf("- voices\n");
    {
        Drum g({{"model", Djembe}});
        for (int n = 45; n < 70; n += 3) g.f->noteOn(static_cast<uint8_t>(n), 100);
        g.play(0.1);
        for (int n = 45; n < 70; n += 3) g.f->noteOff(static_cast<uint8_t>(n));
        g.play(4.0);
        check(g.f->activeVoices() == 0, "struck heads ring out and free their voices", fmt("%d left", g.f->activeVoices()));
    }
    for (int kind = 0; kind <= Talking; ++kind) {
        for (int stroke = 0; stroke < 5; ++stroke) {
            Drum g({{"model", static_cast<float>(kind)}, {"stroke", static_cast<float>(stroke)}, {"hand", 1.0f}, {"decay", 1.0f},
                    {"drop", 1.0f}, {"squeeze", 12.0f}, {"rattle", 1.0f}, {"body", 1.0f}, {"roll", 24.0f}, {"volume", 1.0f}});
            g.f->channelPressure(127);
            for (int n = 30; n < 90; n += 12) g.f->noteOn(static_cast<uint8_t>(n), 127);
            g.play(1.5);
            bool finite = true;
            for (float v : g.out) finite = finite && std::isfinite(v);
            if (!finite || g.peakAbs() >= 12.0)
                check(false, fmt("kind %d stroke %d, every knob up, stays bounded", kind, stroke).c_str(), fmt("peak %.2f", g.peakAbs()));
        }
    }
    check(true, "every drum and stroke, every knob up, stays bounded");
}

} // namespace

int main() {
    tuning();
    heads();
    strokes();
    hands();
    lifecycle();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
