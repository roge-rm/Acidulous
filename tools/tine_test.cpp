// Tine, struck and plucked bars, tines and pans: in tune, each kind's
// overtones where its shape puts them, the tube under the bar, the motor, the
// damper and its pedal, a pan's octave growing, rolls, the bow and the rattle,
// and a ringing note melting into another model.
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


/**
 * The worst click over [from, to) seconds: the sharpest corner in a 32-sample
 * slice against the middle of the slices around it. A click stands out of
 * its neighbours; a bright sound is sharp everywhere and reads near 1.
 */
double corner(const std::vector<float> &out, double from, double to) {
    const auto a = static_cast<size_t>(from * kRate), b = std::min(out.size(), static_cast<size_t>(to * kRate));
    std::vector<double> peaks;
    for (size_t s = a + 2; s + 32 <= b; s += 32) {
        double p = 0.0;
        for (size_t i = s; i < s + 32; ++i) p = std::max(p, std::fabs(double(out[i]) - 2.0 * out[i - 1] + out[i - 2]));
        peaks.push_back(p);
    }
    double worst = 0.0;
    for (size_t i = 8; i + 8 < peaks.size(); ++i) {
        std::vector<double> around(peaks.begin() + static_cast<long>(i) - 8, peaks.begin() + static_cast<long>(i) + 9);
        std::nth_element(around.begin(), around.begin() + 8, around.end());
        worst = std::max(worst, peaks[i] / (around[8] + 1e-12));
    }
    return worst;
}

enum { Gong = 13, SingingBowl = 14 };
constexpr float kMeltDest = static_cast<float>(Tine::McMelt);

/** The melt knob for [seconds]: 0.05 s at the bottom to 20 s at the top, on a log scale. */
float meltFor(double seconds) { return static_cast<float>(std::log(seconds / 0.05) / std::log(400.0)); }

void melt() {
    std::printf("- melt\n");
    const double hz = noteHz(60);
    {
        // Vibes into glockenspiel: the second mode goes from 4 to 2.756, through the
        // middle of the two on a log scale, and lands there.
        Bars g({{"model", Vibraphone}, {"into", Glockenspiel}, {"melt", meltFor(1.0)}});
        g.f->noteOn(60, 100);
        g.play(0.5);
        const double mid = g.f->modeRatio(1);
        g.play(0.6);
        const double end = g.f->modeRatio(1);
        check(std::fabs(mid - std::sqrt(4.0 * 2.756)) < 0.15, "halfway, a mode sits between the two on a log scale", fmt("%.3f, want %.3f", mid, std::sqrt(4.0 * 2.756)));
        check(std::fabs(end - 2.756) < 1e-3 && g.f->meltOf() > 0.999f, "and reaches the other model's ratio", fmt("%.4f, melt %.3f", end, g.f->meltOf()));
        g.play(0.4);
        Bars plain({{"model", Vibraphone}});
        plain.f->noteOn(60, 100);
        plain.play(1.5);
        const double at = g.level(hz * 2.756, 1.1, 1.5) - plain.level(hz * 2.756, 1.1, 1.5);
        const double was = g.level(hz * 4.0, 1.1, 1.5) - plain.level(hz * 4.0, 1.1, 1.5);
        check(at > 30.0 && was < -20.0, "the note sounds the other model's overtone in place of its own", fmt("2.756: %+.1f dB, 4.0: %+.1f dB", at, was));
    }
    {
        // No corners: the sharpest edge while a fast melt runs is no sharper than either model's own.
        auto edge = [&](int model, int into, float knob) {
            Bars g({{"model", static_cast<float>(model)}, {"into", static_cast<float>(into)}, {"melt", knob}});
            g.f->noteOn(60, 110);
            g.play(0.8);
            return corner(g.out, 0.06, 0.8);
        };
        const int pairs[][2] = {{Marimba, SingingBowl}, {Gong, Marimba}, {Glockenspiel, Gong}, {SteelPan, Vibraphone}, {Kalimba, Glockenspiel}};
        for (const auto &pr : pairs) {
            const double a = edge(pr[0], pr[0], 0.0f), b = edge(pr[1], pr[1], 0.0f);
            const double fast = edge(pr[0], pr[1], 0.0f + 1e-6f), slow = edge(pr[0], pr[1], meltFor(0.3));
            check(fast <= std::max(a, b) * 1.3 && slow <= std::max(a, b) * 1.3, fmt("model %d into %d melts without a click", pr[0], pr[1]).c_str(),
                  fmt("edge %.2f, %.2f vs %.2f, %.2f", fast, slow, a, b));
        }
    }
    {
        // Marimba into gong: the gong's fifth and sixth modes aren't on the marimba.
        // They grow out of the note without a strike, and the note goes on at about its level.
        Bars g({{"model", Marimba}, {"into", Gong}, {"melt", meltFor(0.5)}});
        g.f->noteOn(60, 100);
        g.play(1.2);
        Bars plain({{"model", Marimba}});
        plain.f->noteOn(60, 100);
        plain.play(1.2);
        const double grown = g.level(hz * 3.72, 0.8, 1.2) - plain.level(hz * 3.72, 0.8, 1.2);
        check(grown > 25.0, "a mode only the other model has grows out of the note", fmt("%+.1f dB at the gong's 3.72", grown));
        // Against the note, about where a struck gong has it against its own.
        Bars gong({{"model", static_cast<float>(Gong)}});
        gong.f->noteOn(60, 100);
        gong.play(1.2);
        const double note = g.level(hz * 3.72, 0.8, 1.2) - g.level(hz, 0.8, 1.2);
        const double struck = gong.level(hz * 3.72, 0.8, 1.2) - gong.level(hz, 0.8, 1.2);
        check(std::fabs(note - struck) < 10.0, "as loud against the note as a struck gong has it", fmt("%.1f dB under the note, struck %.1f", -note, -struck));
        // The level before and through the melt: no jump either way.
        const double before = g.rms(0.05, 0.1), through = g.rms(0.3, 0.35);
        const double pBefore = plain.rms(0.05, 0.1), pThrough = plain.rms(0.3, 0.35);
        check(std::fabs((through - before) - (pThrough - pBefore)) < 6.0, "the note's level carries through the melt",
              fmt("falls %.1f dB melting, %.1f plain", before - through, pBefore - pThrough));
    }
    {
        // Gong into marimba: the gong's extra modes fade out.
        Bars g({{"model", Gong}, {"into", Marimba}, {"melt", meltFor(0.3)}});
        g.f->noteOn(60, 100);
        g.play(1.0);
        Bars plain({{"model", Gong}});
        plain.f->noteOn(60, 100);
        plain.play(1.0);
        const double gone = g.level(hz * 3.72, 0.6, 1.0) - plain.level(hz * 3.72, 0.6, 1.0);
        check(gone < -40.0, "a mode only the note's model has fades out", fmt("%+.1f dB at the gong's 3.72", gone));
    }
    {
        // Off: into set but melt at 0, or melt on into the same model, is the note as before to the bit.
        auto render = [&](float into, float knob) {
            Bars g({{"model", SteelPan}, {"into", into}, {"melt", knob}, {"pair", 7.0f}, {"roll", 6.0f}, {"bloom", 0.8f}});
            g.f->noteOn(60, 100);
            g.f->noteOn(64, 80);
            g.play(0.6);
            g.f->noteOff(64);
            g.play(0.4);
            return g.out;
        };
        const auto base = render(SteelPan, 0.0f);
        check(render(Gong, 0.0f) == base, "melt at 0 leaves the note as it was, to the bit");
        check(render(SteelPan, 0.5f) == base, "melting into its own model leaves it as it was, to the bit");
    }
    {
        // Heat: a hard hit melts faster than a soft one, pressure faster still.
        auto at = [&](uint8_t vel, int press) {
            Bars g({{"model", Marimba}, {"into", SingingBowl}, {"melt", meltFor(2.0)}, {"heat", 1.0f}});
            g.f->noteOn(60, vel);
            if (press >= 0) g.f->notePressure(60, static_cast<uint8_t>(press));
            g.play(0.5);
            return g.f->meltOf();
        };
        const float soft = at(40, -1), hard = at(127, -1), pressed = at(40, 127);
        check(hard > soft * 2.0f, "with heat a hard hit melts faster", fmt("%.3f vs %.3f at 0.5 s", hard, soft));
        check(pressed > soft * 2.0f, "and so does a pressed note", fmt("%.3f vs %.3f", pressed, soft));
    }
    {
        // Back and forth: the melt turns round and comes back.
        Bars g({{"model", Vibraphone}, {"into", Gong}, {"melt", meltFor(0.5)}, {"return", 1.0f}});
        g.f->noteOn(60, 100);
        g.play(0.5);
        const float there = g.f->meltOf();
        g.play(0.5);
        const float back = g.f->meltOf();
        check(there > 0.95f && back < 0.05f, "back and forth goes there and comes back", fmt("%.3f then %.3f", there, back));
    }
    {
        // The macro moves a ringing note at once, melt time or not.
        Bars g({{"model", Marimba}, {"into", SingingBowl}, {"mac1_d1", kMeltDest}, {"mac1_a1", 1.0f}});
        g.f->noteOn(60, 100);
        g.play(0.2);
        const float before = g.f->meltOf();
        g.f->params().set(g.f->params().indexOf("mac1"), 1.0f);
        g.play(0.1);
        check(before < 0.0f && g.f->meltOf() > 0.99f, "the melt macro moves a ringing note", fmt("%.3f then %.3f", before, g.f->meltOf()));
        const double c = corner(g.out, 0.15, 0.3);
        Bars plain({{"model", Marimba}});
        plain.f->noteOn(60, 100);
        plain.play(0.3);
        Bars bowl({{"model", SingingBowl}});
        bowl.f->noteOn(60, 100);
        bowl.play(0.3);
        check(c <= std::max(corner(plain.out, 0.15, 0.3), corner(bowl.out, 0.15, 0.3)) * 1.3, "and doesn't click doing it", fmt("edge %.2f", c));
    }
    {
        // A note struck again while melting is struck where the melt is.
        Bars g({{"model", Vibraphone}, {"into", Glockenspiel}, {"melt", meltFor(1.0)}});
        g.f->noteOn(60, 100);
        g.play(0.5);
        const float mid = g.f->meltOf();
        g.f->noteOn(60, 100);
        g.play(0.02);
        check(std::fabs(g.f->meltOf() - mid) < 0.05f && g.f->modeRatio(1) < 3.9f, "a re-strike lands on the melt where it is",
              fmt("%.3f then %.3f, mode 2 at %.3f", mid, g.f->meltOf(), g.f->modeRatio(1)));
    }
    {
        // Sixteen melting voices, every knob up, stay bounded.
        Bars g({{"model", Gong}, {"into", 18.0f}, {"melt", meltFor(0.2)}, {"return", 1.0f}, {"heat", 1.0f}, {"pair", 30.0f},
                {"bloom", 1.0f}, {"roll", 12.0f}, {"buzz", 1.0f}, {"tube", 1.0f}, {"voices", 16.0f}});
        for (int n = 0; n < 16; ++n) g.f->noteOn(static_cast<uint8_t>(36 + n * 3), 127);
        g.f->channelPressure(127);
        g.play(3.0);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        check(finite && g.peakAbs() < 12.0, "sixteen melting voices stay bounded", fmt("peak %.2f", g.peakAbs()));
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
    melt();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
