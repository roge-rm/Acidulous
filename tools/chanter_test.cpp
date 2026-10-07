// Chanter, bagpipes and the hurdy-gurdy: in tune, the drones going on from
// the bag after the last note and stopping when it's empty, grace notes, a
// closed chanter's gaps, the melody strings stopping with the key, and the
// dog buzzing only when the wheel turns fast enough or is pushed on the beat.
//
//   chanter_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/chanter/Chanter.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Chanter;

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

/** A Chanter played block by block. */
struct Pipes {
    std::unique_ptr<Machine> owned;
    Chanter *f;
    std::vector<float> out;

    explicit Pipes(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Chanter"));
        f = static_cast<Chanter *>(owned.get());
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

enum { Highland, Smallpipes, Gaita, Gurdy };

double band(const Pipes &g, double lo, double hi, double from, double to) {
    double s = 0.0;
    for (double f0 = lo; f0 <= hi; f0 += 10.0) s += std::pow(10.0, g.level(f0, from, to) / 10.0);
    return 10.0 * std::log10(s + 1e-30);
}

void tuning() {
    std::printf("- tuning\n");
    const struct { const char *what; float model; int note; } cases[] = {
        {"highland chanter A4", Highland, 69}, {"highland chanter E5", Highland, 76}, {"smallpipes G4", Smallpipes, 67},
        {"gaita D5", Gaita, 74}, {"hurdy-gurdy melody A4", Gurdy, 69}, {"hurdy-gurdy melody D5", Gurdy, 74},
    };
    for (const auto &c : cases) {
        Pipes g({{"model", c.model}, {"drones", 0.0f}, {"drift", 0.0f}});
        g.f->noteOn(static_cast<uint8_t>(c.note), 100);
        g.play(1.0);
        const double want = noteHz(c.note);
        const double got = g.peak(want * 0.95, want * 1.05, 0.4, 0.95);
        check(std::fabs(cents(got, want)) < 12.0, fmt("%s plays in tune", c.what).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
    {
        Pipes g({{"model", Highland}, {"key", 9.0f}, {"drift", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(1.0);
        const double want = noteHz(57);
        const double got = g.peak(want * 0.95, want * 1.05, 0.4, 0.95);
        check(std::fabs(cents(got, want)) < 12.0, "the drones are on the key", fmt("%+.1f cents from A3", cents(got, want)));
        check(g.level(noteHz(45), 0.4, 0.95) > g.level(noteHz(45) * 1.06, 0.4, 0.95) + 15.0, "with the bass drone an octave down",
              fmt("%.1f dB", g.level(noteHz(45), 0.4, 0.95)));
    }
}

void bag() {
    std::printf("- the bag\n");
    for (float model : {static_cast<float>(Highland), static_cast<float>(Gurdy)}) {
        Pipes g({{"model", model}, {"bag", 2.0f}, {"drift", 0.0f}});
        g.f->noteOn(71, 100);
        g.play(1.0);
        g.f->noteOff(71);
        g.play(4.0);
        const double during = g.rms(1.5, 2.5), after = g.rms(4.5, 5.0);
        const double melody = g.level(noteHz(71), 1.3, 1.8) - g.level(noteHz(71), 0.5, 0.9);
        check(during > -55.0, fmt("%s: the drones go on after the last note", model == Gurdy ? "hurdy-gurdy" : "bagpipe").c_str(), fmt("%.1f dB", during));
        check(melody < -20.0, fmt("%s: but the melody stops", model == Gurdy ? "hurdy-gurdy" : "bagpipe").c_str(), fmt("%+.1f dB", melody));
        check(after < -90.0, fmt("%s: and the drones stop when the bag's empty", model == Gurdy ? "hurdy-gurdy" : "bagpipe").c_str(), fmt("%.1f dB", after));
    }
}

void fingers() {
    std::printf("- fingers\n");
    {
        Pipes g({{"model", Highland}, {"grace", 30.0f}, {"drones", 0.0f}, {"drift", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(0.5);
        g.f->noteOn(71, 100);
        g.play(0.5);
        const double hiG = noteHz(79);
        const double inGrace = g.level(hiG, 0.505, 0.525) - g.level(hiG, 0.6, 0.62);
        check(inGrace > 10.0, "a grace note, high G, before the next note", fmt("%+.1f dB of high G", inGrace));
    }
    {
        Pipes open({{"model", Highland}, {"drones", 0.0f}, {"drift", 0.0f}}), closed({{"model", Smallpipes}, {"drones", 0.0f}, {"drift", 0.0f}});
        for (Pipes *g : {&open, &closed}) {
            g->f->noteOn(67, 100);
            g->play(0.5);
            g->f->noteOn(69, 100);
            g->play(0.3);
        }
        const double dipOpen = open.rms(0.4, 0.45) - open.rms(0.505, 0.515);
        const double dipClosed = closed.rms(0.4, 0.45) - closed.rms(0.505, 0.515);
        check(dipClosed > dipOpen + 6.0, "a closed chanter stops between notes", fmt("dips %.1f vs %.1f dB", dipClosed, dipOpen));
    }
}

void dog() {
    std::printf("- the dog\n");
    // The dog's own part: the top of the sound with it, against without.
    auto play = [&](float wheel, float threshold, int pressure, float dog, float coup) {
        auto g = std::make_unique<Pipes>(std::initializer_list<std::pair<const char *, float>>{
            {"model", Gurdy}, {"wheel", wheel}, {"threshold", threshold}, {"dog", dog}, {"coup", coup}, {"drift", 0.0f}});
        g->bpm = 120.0f;
        g->f->noteOn(69, 100);
        if (pressure > 0) g->f->channelPressure(static_cast<uint8_t>(pressure));
        g->play(3.0);
        return g;
    };
    auto buzz = [&](float wheel, float threshold, int pressure) {
        return band(*play(wheel, threshold, pressure, 1.0f, 0.0f), 1800.0, 2600.0, 0.4, 0.95) -
               band(*play(wheel, threshold, pressure, 0.0f, 0.0f), 1800.0, 2600.0, 0.4, 0.95);
    };
    const double slow = buzz(0.4f, 0.6f, 0), fast = buzz(0.9f, 0.6f, 0), pushed = buzz(0.4f, 0.6f, 127);
    check(fast > slow + 8.0, "the dog buzzes once the wheel's fast enough", fmt("%+.1f vs %+.1f dB", fast, slow));
    check(pushed > slow + 8.0, "or when the wheel's pushed (pressure)", fmt("%+.1f vs %+.1f dB", pushed, slow));
    {
        // A coup a beat: every half second, the dog buzzes for a moment.
        auto onOff = [&](const Pipes &g) {
            double on = 0.0, off = 0.0;
            for (int b = 1; b < 5; ++b) {
                on += band(g, 1800.0, 2600.0, 0.5 * b + 0.005, 0.5 * b + 0.05);
                off += band(g, 1800.0, 2600.0, 0.5 * b + 0.25, 0.5 * b + 0.3);
            }
            return (on - off) / 4.0;
        };
        const double with = onOff(*play(0.5f, 0.6f, 0, 1.0f, 1.0f)), without = onOff(*play(0.5f, 0.6f, 0, 0.0f, 1.0f));
        check(with > without + 8.0, "coups set it buzzing on the beat", fmt("%+.1f dB on the beat, %+.1f without the dog", with, without));
    }
}

void lifecycle() {
    std::printf("- extremes\n");
    for (int kind = 0; kind <= Gurdy; ++kind) {
        Pipes g({{"model", static_cast<float>(kind)}, {"reed", 1.0f}, {"grace", 60.0f}, {"drift", 1.0f}, {"air", 1.0f},
                 {"wheel", 1.0f}, {"rosin", 1.0f}, {"dog", 1.0f}, {"threshold", 0.0f}, {"coup", 3.0f}, {"drones", 1.0f}, {"volume", 1.0f}});
        g.f->channelPressure(127);
        for (int n = 55; n < 90; n += 5) {
            g.f->noteOn(static_cast<uint8_t>(n), 127);
            g.play(0.15);
        }
        g.play(1.0);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        check(finite && g.peakAbs() < 4.0, fmt("kind %d, every knob up, stays bounded", kind).c_str(), fmt("peak %.2f", g.peakAbs()));
    }
}

} // namespace

int main() {
    tuning();
    bag();
    fingers();
    dog();
    lifecycle();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
