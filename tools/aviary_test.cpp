// Aviary, birdsong: a whistle in tune two octaves up, a chirp sweeping, the
// syrinx's two sides singing two pitches at once, syllables landing on the
// beat, a call answered in the gaps, pressure, and silence when let go.
//
//   aviary_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/aviary/Aviary.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Aviary;

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

/** An Aviary played block by block. */
struct Birds {
    std::unique_ptr<Machine> owned;
    Aviary *f;
    std::vector<float> out;

    explicit Birds(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Aviary"));
        f = static_cast<Aviary *>(owned.get());
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

enum { Whistle, Chirp, Trill, Warble, Call, Chorus };

void whistle() {
    std::printf("- whistle\n");
    for (int note : {48, 60, 67}) {
        Birds g({{"pattern", Whistle}, {"sweep", 0.0f}, {"space", 0.0f}});
        g.f->noteOn(static_cast<uint8_t>(note), 100);
        g.play(1.0);
        const double want = noteHz(note + 24);
        const double got = g.peak(want * 0.97, want * 1.03, 0.3, 0.95);
        check(std::fabs(cents(got, want)) < 5.0, fmt("a whistle on %d sings two octaves up", note).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
    {
        Birds g({{"pattern", Whistle}, {"sweep", 12.0f}, {"space", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(0.6);
        const double want = noteHz(84);
        const double early = g.peak(want * 0.6, want * 1.05, 0.0, 0.05), late = g.peak(want * 0.9, want * 1.05, 0.4, 0.6);
        check(cents(late, early) > 100.0, "and slides up into it", fmt("%+.0f cents from the start", cents(late, early)));
    }
}

void chirps() {
    std::printf("- chirps\n");
    {
        // Four syllables a beat at 120: one every 125 ms, each sung for half that.
        Birds g({{"pattern", Chirp}, {"rate", 2.0f}, {"length", 0.5f}, {"sweep", 12.0f}, {"space", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(1.0);
        const double base = noteHz(84);
        double early = 0.0, late = 0.0;
        for (int s = 1; s < 6; ++s) {
            const double t = 0.125 * s;
            early += g.peak(base * 1.3, base * 2.1, t + 0.001, t + 0.015);
            late += g.peak(base * 0.9, base * 1.25, t + 0.045, t + 0.062);
        }
        check(cents(early / 5.0, late / 5.0) > 500.0, "a chirp sweeps down to the note", fmt("%+.0f cents across a syllable", cents(early / 5.0, late / 5.0)));
        double on = 0.0, off = 0.0;
        for (int s = 1; s < 7; ++s) {
            on += g.rms(0.125 * s + 0.01, 0.125 * s + 0.05);
            off += g.rms(0.125 * s + 0.075, 0.125 * s + 0.12);
        }
        check(on / 6.0 > off / 6.0 + 30.0, "its syllables land on the beat's sixteenths", fmt("%.1f dB in them over between", (on - off) / 6.0));
    }
    {
        Birds g({{"pattern", Chirp}, {"rate", 2.0f}, {"sweep", -12.0f}, {"space", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(0.5);
        const double base = noteHz(84);
        const double early = g.peak(base * 0.45, base * 0.8, 0.126, 0.14), late = g.peak(base * 0.8, base * 1.05, 0.17, 0.187);
        check(cents(late, early) > 500.0, "a negative sweep rises to the note instead", fmt("%+.0f cents", cents(late, early)));
    }
    {
        Birds slow({{"pattern", Chirp}, {"rate", 2.0f}, {"space", 0.0f}});
        slow.bpm = 60.0f;
        slow.f->onBlock(0, 0, 60.0f);
        slow.f->noteOn(60, 100);
        slow.play(1.0);
        double on = 0.0, off = 0.0;
        for (int s = 1; s < 4; ++s) {
            on += slow.rms(0.25 * s + 0.01, 0.25 * s + 0.1);
            off += slow.rms(0.25 * s + 0.15, 0.25 * s + 0.24);
        }
        check(on > off + 60.0, "and follow the tempo", fmt("%.1f dB at 60 bpm", (on - off) / 3.0));
    }
}

void syrinx() {
    std::printf("- syrinx\n");
    {
        Birds g({{"pattern", Whistle}, {"sweep", 0.0f}, {"two", 1.0f}, {"interval", 7.0f}, {"space", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(1.0);
        const double a = noteHz(84), b = a * std::exp2(7.0 / 12.0);
        const double la = g.level(a, 0.3, 0.95), lb = g.level(b, 0.3, 0.95), between = g.level(a * 1.25, 0.3, 0.95);
        check(la > between + 20.0 && lb > between + 20.0, "the syrinx's two sides sing two notes at once", fmt("%.1f and %.1f dB, %.1f between", la, lb, between));
    }
    {
        Birds soft({{"pattern", Whistle}, {"sweep", 0.0f}, {"space", 0.0f}}), pressed({{"pattern", Whistle}, {"sweep", 0.0f}, {"space", 0.0f}});
        soft.f->noteOn(60, 60);
        pressed.f->noteOn(60, 60);
        pressed.f->channelPressure(127);
        soft.play(0.5);
        pressed.play(0.5);
        check(pressed.rms(0.2, 0.5) > soft.rms(0.2, 0.5) + 2.0, "pressure blows harder: louder", fmt("%+.1f dB", pressed.rms(0.2, 0.5) - soft.rms(0.2, 0.5)));
    }
    {
        Birds g({{"pattern", Call}, {"rate", 1.0f}, {"length", 0.6f}, {"flock", 2.0f}, {"spread", 1.0f}, {"space", 0.0f}});
        g.f->noteOn(60, 100);
        g.play(2.0);
        // Two syllables a beat: the first bird sings the first, the second answers in the next.
        const double hi = noteHz(84), lo = noteHz(80);
        const double first = g.level(hi, 0.0, 0.15), answer = g.level(lo, 0.25, 0.4);
        check(first > -50.0 && answer > -50.0, "a call, and an answer a third lower", fmt("%.1f then %.1f dB", first, answer));
    }
}

void lifecycle() {
    std::printf("- voices\n");
    {
        Birds g({{"pattern", Warble}, {"flock", 3.0f}});
        g.f->noteOn(60, 100);
        g.f->noteOn(64, 100);
        g.play(0.5);
        g.f->noteOff(60);
        g.f->noteOff(64);
        g.play(0.3);
        check(g.rms(0.7, 0.8) < -100.0, "birds stop singing when the note's let go", fmt("%.1f dB", g.rms(0.7, 0.8)));
        check(g.f->activeVoices() == 0, "and free their voices", fmt("%d left", g.f->activeVoices()));
    }
    for (int song = 0; song <= Chorus; ++song) {
        Birds g({{"pattern", static_cast<float>(song)}, {"rate", 4.0f}, {"length", 1.0f}, {"sweep", 24.0f}, {"rasp", 1.0f}, {"two", 1.0f},
                 {"breath", 1.0f}, {"throat", 1.0f}, {"beak", 1.0f}, {"flock", 4.0f}, {"volume", 1.0f}});
        g.f->channelPressure(127);
        g.f->controlChange(1, 127);
        for (int n = 40; n < 100; n += 15) g.f->noteOn(static_cast<uint8_t>(n), 127);
        g.play(1.5);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        check(finite && g.peakAbs() < 6.0, fmt("song %d, every knob up, stays bounded", song).c_str(), fmt("peak %.2f", g.peakAbs()));
    }
}

} // namespace

int main() {
    whistle();
    chirps();
    syrinx();
    lifecycle();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
