// Tine, struck and plucked bars, tines and pans: in tune, each kind's
// overtones where its shape puts them, the tube under the bar, the motor, the
// damper and its pedal, a pan's octave growing, rolls, the bow and the rattle.
//
//   tine_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/tine/Tine.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Tine;

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

/** A Tine played block by block. */
struct Bars {
    std::unique_ptr<Machine> owned;
    Tine *f;
    std::vector<float> out;

    explicit Bars(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Tine"));
        f = static_cast<Tine *>(owned.get());
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

enum { Marimba, Vibraphone, Xylophone, Glockenspiel, Kalimba, MusicBox, SteelPan, Handpan, TongueDrum };

void tuning() {
    std::printf("- tuning\n");
    const struct { const char *kind; float model; int note; } cases[] = {
        {"marimba C3", Marimba, 48}, {"marimba C6", Marimba, 84}, {"vibraphone F3", Vibraphone, 53},
        {"xylophone C5", Xylophone, 72}, {"glockenspiel G6", Glockenspiel, 91}, {"thumb piano C4", Kalimba, 60},
        {"music box E6", MusicBox, 88}, {"steel pan C4", SteelPan, 60}, {"handpan D3", Handpan, 50},
        {"tongue drum C4", TongueDrum, 60}, {"marimba A1", Marimba, 33},
    };
    for (const auto &c : cases) {
        Bars g({{"model", c.model}});
        g.f->noteOn(static_cast<uint8_t>(c.note), 90);
        g.play(0.8);
        const double want = noteHz(c.note);
        const double got = g.peak(want * 0.97, want * 1.03, 0.05, 0.75);
        check(std::fabs(cents(got, want)) < 3.0, fmt("%s plays in tune", c.kind).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
}

void partials() {
    std::printf("- overtones\n");
    const struct { const char *what; float model; float ratio; } cases[] = {
        {"a marimba bar's first overtone is two octaves up", Marimba, 3.99f},
        {"a marimba bar's second is two octaves and a third", Marimba, 9.95f},
        {"a xylophone bar's first is a twelfth up", Xylophone, 3.0f},
        {"a glockenspiel bar's sits at 2.76", Glockenspiel, 2.756f},
        {"a tine's first overtone is far up, at 6.27", Kalimba, 6.27f},
        {"a pan's note has its octave", SteelPan, 2.0f},
        {"a tongue's overtone is just under a twelfth", TongueDrum, 2.92f},
    };
    const int note = 48;
    for (const auto &c : cases) {
        Bars g({{"model", c.model}, {"mallet", 1.0f}, {"position", 0.8f}, {"bright", 0.8f}});
        g.f->noteOn(note, 110);
        g.play(0.4);
        const double want = noteHz(note) * c.ratio;
        const double got = g.peak(want * 0.97, want * 1.03, 0.01, 0.2);
        // And it stands out: louder than the spectrum a little either side.
        const double at = g.level(got, 0.01, 0.2);
        const double beside = 0.5 * (g.level(want * 0.9, 0.01, 0.2) + g.level(want * 1.1, 0.01, 0.2));
        check(std::fabs(cents(got, want)) < 5.0 && at > beside + 15.0, c.what, fmt("%+.1f cents, %.1f dB above", cents(got, want), at - beside));
    }
}

void mallets() {
    std::printf("- mallets\n");
    const double hz = noteHz(60);
    Bars soft({{"mallet", 0.0f}}), hard({{"mallet", 1.0f}});
    soft.f->noteOn(60, 100);
    hard.f->noteOn(60, 100);
    soft.play(0.3);
    hard.play(0.3);
    const double s = soft.level(soft.peak(hz * 3.9, hz * 4.1, 0.01, 0.2), 0.01, 0.2) - soft.level(hz, 0.01, 0.2);
    const double h = hard.level(hard.peak(hz * 3.9, hz * 4.1, 0.01, 0.2), 0.01, 0.2) - hard.level(hz, 0.01, 0.2);
    check(h > s + 6.0, "a hard mallet brings out the overtones", fmt("overtone %.1f vs %.1f dB", h, s));
    const double sf = soft.level(hz, 0.01, 0.2), hf = hard.level(hz, 0.01, 0.2);
    check(std::fabs(sf - hf) < 3.0, "and the note itself is as loud either way", fmt("%.1f vs %.1f dB", hf, sf));
}

void tube() {
    std::printf("- tube and motor\n");
    const double hz = noteHz(48);
    Bars off({{"tube", 0.0f}}), on({{"tube", 1.0f}});
    off.f->noteOn(48, 100);
    on.f->noteOn(48, 100);
    off.play(0.6);
    on.play(0.6);
    const double a = off.level(hz, 0.05, 0.5), b = on.level(hz, 0.05, 0.5);
    check(b > a + 3.0, "the tube sings along with the note", fmt("%+.1f dB", b - a));
    const double o4a = off.level(off.peak(hz * 3.9, hz * 4.1, 0.01, 0.2), 0.01, 0.2);
    const double o4b = on.level(on.peak(hz * 3.9, hz * 4.1, 0.01, 0.2), 0.01, 0.2);
    check(std::fabs(o4b - o4a) < 3.0, "but not with the overtone two octaves up", fmt("%+.1f dB", o4b - o4a));

    // The motor: the note's level swells and falls at the discs' rate.
    auto wobble = [&](float motor) {
        Bars g({{"model", Vibraphone}, {"tube", 1.0f}, {"motor", motor}, {"depth", 1.0f}});
        g.f->noteOn(53, 100);
        g.play(2.0);
        // The level in 10 ms steps, in dB, and how much of it moves at 5 Hz.
        std::vector<double> env;
        for (double t = 0.3; t < 1.9; t += 0.01) env.push_back(g.rms(t, t + 0.01));
        double re = 0.0, im = 0.0, mean = 0.0;
        for (double e : env) mean += e;
        mean /= static_cast<double>(env.size());
        for (size_t i = 0; i < env.size(); ++i) {
            const double ph = 6.283185307179586 * 5.0 * 0.01 * static_cast<double>(i);
            re += (env[i] - mean) * std::cos(ph);
            im += (env[i] - mean) * std::sin(ph);
        }
        return 2.0 * std::sqrt(re * re + im * im) / static_cast<double>(env.size());
    };
    const double still = wobble(0.0f), turning = wobble(5.0f);
    check(turning > 2.0 && turning > still * 5.0, "the motor swells the note at its rate", fmt("%.2f vs %.2f dB at 5 Hz", turning, still));
}

void dampers() {
    std::printf("- dampers\n");
    auto after = [&](float damp, bool pedal) {
        Bars g({{"model", Vibraphone}, {"damp", damp}});
        if (pedal) g.f->setDampers(true);
        g.f->noteOn(60, 100);
        g.play(0.3);
        g.f->noteOff(60);
        g.play(0.6);
        return g.rms(0.25, 0.3) - g.rms(0.8, 0.9);
    };
    const double free = after(0.0f, false), damped = after(1.0f, false), pedalled = after(1.0f, true);
    check(damped > free + 30.0, "the damper stops a let-go note", fmt("falls %.1f vs %.1f dB", damped, free));
    check(std::fabs(pedalled - free) < 3.0, "unless the pedal holds it off", fmt("falls %.1f dB", pedalled));
    {
        Bars g({{"model", Vibraphone}, {"damp", 1.0f}});
        g.f->setDampers(true);
        g.f->noteOn(60, 100);
        g.play(0.3);
        g.f->noteOff(60);
        g.play(0.2);
        g.f->setDampers(false);
        g.play(0.4);
        check(g.rms(0.8, 0.9) < g.rms(0.4, 0.5) - 30.0, "letting the pedal up damps it then", fmt("%.1f dB", g.rms(0.8, 0.9) - g.rms(0.4, 0.5)));
    }
}

void bloom() {
    std::printf("- bloom\n");
    const double hz = noteHz(60);
    auto octave = [&](float bloom) {
        Bars g({{"model", SteelPan}, {"bloom", bloom}, {"mallet", 0.3f}});
        g.f->noteOn(60, 120);
        g.play(0.8);
        return g.level(hz * 2.0, 0.3, 0.6) - g.level(hz * 2.0, 0.0, 0.04);
    };
    const double none = octave(0.0f), full = octave(1.0f);
    check(full > none + 4.0, "a pan's octave grows out of the note", fmt("octave later %+.1f vs %+.1f dB", full, none));
}

void hands() {
    std::printf("- rolls, bow, rattle\n");
    {
        Bars plain, rolled({{"roll", 12.0f}});
        plain.f->noteOn(60, 100);
        rolled.f->noteOn(60, 100);
        plain.play(2.5);
        rolled.play(2.5);
        check(rolled.rms(2.0, 2.5) > plain.rms(2.0, 2.5) + 20.0, "a roll keeps a held note going", fmt("%.1f vs %.1f dB", rolled.rms(2.0, 2.5), plain.rms(2.0, 2.5)));
    }
    {
        Bars plain, bowed;
        plain.f->noteOn(60, 100);
        bowed.f->noteOn(60, 100);
        bowed.f->channelPressure(127);
        plain.play(3.0);
        bowed.play(3.0);
        check(bowed.rms(2.5, 3.0) > plain.rms(2.5, 3.0) + 20.0, "pressure bows a held bar", fmt("%.1f vs %.1f dB", bowed.rms(2.5, 3.0), plain.rms(2.5, 3.0)));
        const double hz = noteHz(60);
        check(bowed.level(hz, 2.5, 3.0) > bowed.level(hz * 1.5, 2.5, 3.0) + 20.0, "and the bow sounds the note", fmt("%.1f dB over its fifth", bowed.level(hz, 2.5, 3.0) - bowed.level(hz * 1.5, 2.5, 3.0)));
    }
    {
        auto top = [&](float buzz) {
            Bars g({{"model", Kalimba}, {"buzz", buzz}});
            g.f->noteOn(55, 120);
            g.play(0.5);
            double s = 0.0;
            for (double f0 = 5000.0; f0 < 9000.0; f0 += 250.0) s += std::pow(10.0, g.level(f0, 0.05, 0.4) / 10.0);
            return 10.0 * std::log10(s);
        };
        check(top(1.0f) > top(0.0f) + 10.0, "the rattle buzzes", fmt("%+.1f dB at the top", top(1.0f) - top(0.0f)));
    }
}

void lifecycle() {
    std::printf("- voices\n");
    {
        Bars g({{"model", Xylophone}});
        for (int n = 48; n < 72; n += 3) g.f->noteOn(static_cast<uint8_t>(n), 100);
        g.play(0.1);
        for (int n = 48; n < 72; n += 3) g.f->noteOff(static_cast<uint8_t>(n));
        g.play(4.0);
        check(g.f->activeVoices() == 0, "a short bar's notes end and free their voices", fmt("%d left", g.f->activeVoices()));
    }
    {
        Bars g({{"voices", 4.0f}});
        for (int n = 48; n < 60; ++n) g.f->noteOn(static_cast<uint8_t>(n), 100);
        g.play(0.1);
        check(g.f->activeVoices() <= 4, "voices are capped", fmt("%d", g.f->activeVoices()));
    }
    for (int kind = 0; kind <= TongueDrum; ++kind) {
        Bars g({{"model", static_cast<float>(kind)}, {"mallet", 1.0f}, {"bright", 1.0f}, {"decay", 1.0f}, {"tube", 1.0f},
                {"motor", 10.0f}, {"depth", 1.0f}, {"bloom", 1.0f}, {"buzz", 1.0f}, {"roll", 24.0f}, {"volume", 1.0f}});
        g.f->channelPressure(127);
        for (int n = 24; n < 108; n += 12) g.f->noteOn(static_cast<uint8_t>(n), 127);
        g.play(2.0);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        check(finite && g.peakAbs() < 12.0, fmt("kind %d, every knob up, stays bounded", kind).c_str(), fmt("peak %.2f", g.peakAbs()));
    }
}

} // namespace

/** A ringing note struck again quickly must not click: no sharper edge at the re-hit than at the first hit. */
void restrikeDoesNotClick() {
    std::printf("- a quick re-hit\n");
    for (int model = 0; model < 19; ++model) {
        Bars b({{"model", static_cast<float>(model)}});
        b.f->noteOn(67, 110);
        b.play(0.05);
        const size_t again = b.out.size();
        for (int hit = 0; hit < 8; ++hit) {
            b.f->noteOn(67, 110);
            b.play(0.05);
        }
        auto edge = [&](size_t from, size_t to) {
            double best = 0.0;
            for (size_t i = from + 2; i < to && i < b.out.size(); ++i)
                best = std::max(best, std::fabs(double(b.out[i]) - 2.0 * b.out[i - 1] + b.out[i - 2]));
            return best;
        };
        const double first = edge(0, 480), second = edge(again - 64, b.out.size());
        char d[96];
        std::snprintf(d, sizeof d, "model %d: re-hit edge %.4f, first hit %.4f", model, second, first);
        check(second <= first * 1.5 + 1e-4, "a re-hit is no sharper than the first", d);
    }
}

int main() {
    tuning();
    partials();
    mallets();
    tube();
    dampers();
    bloom();
    hands();
    lifecycle();
    restrikeDoesNotClick();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
