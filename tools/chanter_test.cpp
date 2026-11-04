// Chanter, bagpipes and the hurdy-gurdy: in tune, the drones going on from
// the bag after the last note and stopping when it's empty, grace notes, a
// closed chanter's gaps, the melody strings stopping with the key, and the
// dog buzzing only when the wheel turns fast enough or is pushed on the beat.
// The drones following a bass on the sidechain, and off to the bit; a finite
// bag draining, filling, sagging and choking the drones, the wheel coasting.
//
//   chanter_test.sh
#include <engine/core/Constants.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/chanter/Chanter.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <functional>
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
    /** Another track on the sidechain: a bass note (MIDI, -1 for none) at each time, played as a bright tone. */
    std::function<int(double)> bass;
    double bassPhase = 0.0;
    /** Per block: drone 0's note, the bass drone's, the bag's pressure and its air. */
    std::vector<float> drone0, droneLow, wind, store;
    void play(double seconds) {
        float L[kBlock], R[kBlock], key[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        for (int b = 0; b < blocks; ++b) {
            if (bass) {
                for (int i = 0; i < kBlock; ++i) {
                    const double t = static_cast<double>(out.size() + i) / kRate;
                    const int n = bass(t);
                    double v = 0.0;
                    if (n >= 0) {
                        bassPhase += noteHzOf(n) / kRate;
                        bassPhase -= std::floor(bassPhase);
                        for (int h = 1; h <= 6; ++h) v += std::sin(6.283185307179586 * h * bassPhase) / h;
                    }
                    key[i] = static_cast<float>(0.25 * v);
                }
                f->setKey(key);
            }
            f->onBlock(0, 0, bpm);
            f->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
            drone0.push_back(f->droneNote(0));
            droneLow.push_back(f->droneNote(2));
            wind.push_back(f->wind());
            store.push_back(f->bagStore());
        }
        if (bass) f->setKey(nullptr);
    }
    static double noteHzOf(int n) { return 440.0 * std::pow(2.0, (n - 69.0) / 12.0); }
    /** A per-block trace's value at [t] seconds. */
    static float at(const std::vector<float> &v, double t) {
        const auto i = static_cast<size_t>(t * kRate / kBlock);
        return v.empty() ? 0.0f : v[std::min(i, v.size() - 1)];
    }
    /** As play(), with the song rolling from [tick] in a bar of [barTicks], ticks counted like the engine's clock. */
    void roll(double seconds, int64_t tick, int32_t barTicks) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        const double perFrame = bpm / 60.0 * kPPQN / kRate;
        for (int b = 0; b < blocks; ++b) {
            const int64_t from = tick + static_cast<int64_t>(b * kBlock * perFrame);
            const int64_t to = tick + static_cast<int64_t>((b + 1) * kBlock * perFrame);
            f->onTransport(true, from);
            f->onBar(from % barTicks, barTicks, 50.0f, kPPQN / 2);
            f->onBlock(from, to, bpm);
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
    /** The sharpest corner between [from] and [to]: the largest second difference. A click is a corner. */
    double corner(double from, double to) const {
        const auto a = static_cast<size_t>(std::max(2.0, from * kRate)), b = std::min(out.size(), static_cast<size_t>(to * kRate));
        double c = 0.0;
        for (size_t i = a; i < b; ++i) c = std::max(c, std::fabs(double(out[i]) - 2.0 * out[i - 1] + out[i - 2]));
        return c;
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

/** How far out the note is, cents, from its first six partials weighted by their energy. */
double fineCents(const Pipes &g, double want, double from, double to) {
    double num = 0.0, den = 0.0;
    for (int n = 1; n <= 6; ++n) {
        const double h = want * n;
        const double got = g.peak(h * 0.98, h * 1.02, from, to);
        const double e = std::pow(10.0, g.level(got, from, to) / 10.0);
        num += e * cents(got, h);
        den += e;
    }
    return num / den;
}

void inTune() {
    std::printf("- every chanter in tune, bottom to top\n");
    // Each kind in its usual key, at the bottom, the middle and the top of where it's played.
    const struct { const char *what; float model; float key; int low; } cases[] = {
        {"highland", 0, 9, 67}, {"smallpipes", 1, 7, 65}, {"gaita", 2, 0, 70}, {"uilleann", 4, 2, 62},
        {"gaida", 5, 7, 67}, {"cornemuse", 6, 7, 67}, {"musette", 7, 7, 67}, {"säckpipa", 8, 9, 69},
        {"dudy", 9, 3, 63}, {"zampogna", 10, 7, 55}, {"tulum", 11, 2, 57}, {"launeddas", 12, 0, 55},
    };
    for (const auto &c : cases) {
        double worst = 0.0;
        int at = 0;
        for (int step : {0, 7, 12}) {
            Pipes g({{"model", c.model}, {"key", c.key}, {"drones", 0.0f}, {"drift", 0.0f}, {"air", 0.0f}});
            g.f->noteOn(static_cast<uint8_t>(c.low + step), 100);
            g.play(1.2);
            const double off = fineCents(g, noteHz(c.low + step), 0.4, 1.2);
            if (std::fabs(off) > std::fabs(worst)) { worst = off; at = c.low + step; }
        }
        check(std::fabs(worst) < 12.0, fmt("%s within 12 cents", c.what).c_str(), fmt("worst %+.1f cents at %d", worst, at));
    }
    // Heavy rosin pulls a bowed string flat; the hurdy-gurdy makes up for it.
    for (float rosin : {0.4f, 0.7f, 1.0f}) {
        for (float wheel : {0.2f, 0.8f}) {
            Pipes g({{"model", Gurdy}, {"drones", 0.0f}, {"dog", 0.0f}, {"drift", 0.0f}, {"rosin", rosin}, {"wheel", wheel}});
            g.f->noteOn(67, 100);
            g.play(1.2);
            const double off = fineCents(g, noteHz(67), 0.5, 1.2);
            check(std::fabs(off) < 6.0, fmt("hurdy-gurdy in tune at rosin %.1f, crank %.1f", rosin, wheel).c_str(), fmt("%+.1f cents", off));
        }
    }
}

/** How far out the note is, cents, from its first three partials, each found within 50 cents. Quick enough for the top notes. */
double quickCents(const Pipes &g, double want, double from, double to) {
    double num = 0.0, den = 0.0;
    for (int n = 1; n <= 3; ++n) {
        const double h = want * n;
        double best = 0.0, bestLevel = -1e9;
        for (double c = -50.0; c <= 50.0; c += 2.0) {
            const double l = g.level(h * std::pow(2.0, c / 1200.0), from, to);
            if (l > bestLevel) { bestLevel = l; best = c; }
        }
        const double coarse = best;
        for (double c = coarse - 2.0; c <= coarse + 2.0; c += 0.2) {
            const double l = g.level(h * std::pow(2.0, c / 1200.0), from, to);
            if (l > bestLevel) { bestLevel = l; best = c; }
        }
        const double e = std::pow(10.0, bestLevel / 10.0);
        num += e * best;
        den += e;
    }
    return num / den;
}

void topNotes() {
    std::printf("- the top notes agree from key to key\n");
    // The bore below the holes once pulled these 30 to 40 cents apart by key.
    const struct { const char *what; float model; } cases[] = {{"smallpipes", 1}, {"musette", 7}};
    for (const auto &c : cases) {
        for (int note : {89, 91, 93, 100}) {
            double lo = 1e9, hi = -1e9;
            for (int key : {0, 1, 2, 4, 6, 9}) {
                Pipes g({{"model", c.model}, {"key", static_cast<float>(key)}, {"drones", 0.0f}, {"drift", 0.0f}, {"air", 0.0f}});
                g.f->noteOn(static_cast<uint8_t>(note), 100);
                g.play(0.6);
                const double off = quickCents(g, noteHz(note), 0.3, 0.6);
                lo = std::min(lo, off);
                hi = std::max(hi, off);
            }
            check(hi - lo < 15.0 && std::fabs(lo) < 15.0 && std::fabs(hi) < 15.0,
                  fmt("%s note %d in tune in every key", c.what, note).c_str(), fmt("%+.1f..%+.1f cents", lo, hi));
        }
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
    auto play = [&](float wheel, float threshold, int pressure, float dog, float coup, int note = 69) {
        auto g = std::make_unique<Pipes>(std::initializer_list<std::pair<const char *, float>>{
            {"model", Gurdy}, {"wheel", wheel}, {"threshold", threshold}, {"dog", dog}, {"coup", coup}, {"drift", 0.0f}});
        g->bpm = 120.0f;
        g->f->noteOn(static_cast<uint8_t>(note), 100);
        if (pressure > 0) g->f->channelPressure(static_cast<uint8_t>(pressure));
        g->play(3.0);
        return g;
    };
    // Over four notes, since how much of the melody lands in the band
    // changes from note to note.
    auto buzz = [&](float wheel, float threshold, int pressure) {
        double sum = 0.0;
        for (int note : {64, 67, 69, 72})
            sum += band(*play(wheel, threshold, pressure, 1.0f, 0.0f, note), 1800.0, 2600.0, 0.4, 0.95) -
                   band(*play(wheel, threshold, pressure, 0.0f, 0.0f, note), 1800.0, 2600.0, 0.4, 0.95);
        return sum / 4.0;
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
    {
        // On 2 and 4: beats 2 and 4 (0.5 s and 1.5 s) buzz, 3 and 5 (1 s and 2 s) don't.
        auto g = std::make_unique<Pipes>(std::initializer_list<std::pair<const char *, float>>{
            {"model", Gurdy}, {"wheel", 0.5f}, {"threshold", 0.6f}, {"dog", 1.0f}, {"coup", 1.0f},
            {"coupbeats", 1.0f}, {"drift", 0.0f}});
        g->f->noteOn(69, 100);
        g->play(3.0);
        auto at = [&](double t) { return band(*g, 1800.0, 2600.0, t + 0.005, t + 0.05); };
        const double on = (at(0.5) + at(1.5)) / 2.0, off = (at(1.0) + at(2.0)) / 2.0;
        check(on > off + 8.0, "on 2 and 4, coups skip the other beats", fmt("%+.1f dB on 2 and 4, %+.1f on 1 and 3", on, off));
    }
    {
        // The song started a beat and a half into the bar: beat 3 is at 0.25 s,
        // then 4 at 0.75, 1 at 1.25, 2 at 1.75, 3 at 2.25. The coups go by the bar.
        auto g = std::make_unique<Pipes>(std::initializer_list<std::pair<const char *, float>>{
            {"model", Gurdy}, {"wheel", 0.5f}, {"threshold", 0.6f}, {"dog", 1.0f}, {"coup", 1.0f},
            {"coupbeats", 1.0f}, {"drift", 0.0f}});
        g->f->noteOn(69, 100);
        g->roll(3.0, kPPQN * 3 / 2, 4 * kPPQN);
        auto at = [&](double t) { return band(*g, 1800.0, 2600.0, t + 0.005, t + 0.05); };
        const double on = (at(0.75) + at(1.75)) / 2.0, off = (at(1.25) + at(2.25)) / 2.0;
        check(on > off + 8.0, "started mid-bar, coups still fall on 2 and 4 of the bar",
              fmt("%+.1f dB on 2 and 4, %+.1f on 1 and 3", on, off));
    }
}

void lifecycle() {
    std::printf("- a note after the first\n");
    // A pipe or a string played again must start as it did the first time:
    // nothing kept from the last note (a bow's push, a reed's state) may thump.
    for (float kind : {static_cast<float>(Highland), static_cast<float>(Smallpipes), static_cast<float>(Gurdy)}) {
        for (double gap : {0.08, 3.0}) {
            Pipes g({{"model", kind}, {"bag", 0.0f}, {"drift", 0.0f}, {"air", 0.0f}});
            g.f->noteOn(67, 100);
            g.play(1.0);
            g.f->noteOff(67);
            g.play(gap);
            g.f->noteOn(67, 100);
            g.play(1.0);
            double first = -200.0, again = -200.0;
            for (double t = 0.0; t < 0.15; t += 0.005) {
                first = std::max(first, g.rms(t, t + 0.005));
                again = std::max(again, g.rms(1.0 + gap + t, 1.0 + gap + t + 0.005));
            }
            check(again < first + 1.5, fmt("kind %d after %.2f s starts as the first did", static_cast<int>(kind), gap).c_str(),
                  fmt("%.1f vs %.1f dB", again, first));
        }
    }
    std::printf("- onsets\n");
    // A note starts and stops without a click: the first 5 ms of a note, on
    // its own or after a gap or from the note before, and the 30 ms after one
    // is let go, have no corner much sharper than the held note's own. The drones are off so only the chanter or strings speak.
    for (int kind = 0; kind < Chanter::KindCount; ++kind) {
        Pipes g({{"model", static_cast<float>(kind)}, {"drones", 0.0f}, {"grace", 0.0f}, {"drift", 0.0f}, {"air", 0.0f}, {"bag", 1.0f}});
        g.f->noteOn(67, 100);
        g.play(0.5);
        g.f->noteOff(67);
        g.play(0.1);
        g.f->noteOn(69, 100);
        g.play(0.5);
        g.f->noteOn(72, 100);
        g.f->noteOff(69);
        g.play(0.5);
        // Held from 50 ms in: a bowed string's own corners come and go in its first moments.
        const double held = std::max({g.corner(0.05, 0.5), g.corner(0.65, 1.1), g.corner(1.15, 1.6)});
        const double start = std::max({g.corner(0.0, 0.005), g.corner(0.6, 0.605), g.corner(1.1, 1.105)});
        check(start < 2.0 * held, fmt("kind %d starts its notes without a click", kind).c_str(),
              fmt("corner %.4f against %.4f held", start, held));
        // Let go with the knobs where a patch leaves them, breath and all.
        // Six notes, let go at different points in the waveform.
        Pipes h({{"model", static_cast<float>(kind)}, {"drones", 0.0f}, {"bag", 1.0f}});
        double stop = 0.0, still = 0.0;
        for (int n = 0; n < 6; ++n) {
            const double at = static_cast<double>(h.out.size()) / kRate, hold = 0.3 + 0.0371 * n;
            h.f->noteOn(static_cast<uint8_t>(60 + n), 100);
            h.play(hold);
            const double off = static_cast<double>(h.out.size()) / kRate;
            h.f->noteOff(static_cast<uint8_t>(60 + n));
            h.play(0.15);
            still = std::max(still, h.corner(at + 0.05, off));
            stop = std::max(stop, h.corner(off, off + 0.03));
        }
        check(stop < 2.0 * still, fmt("kind %d lets a note go without a click", kind).c_str(),
              fmt("corner %.4f against %.4f held", stop, still));
    }
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

/** The same render, sample for sample. */
bool same(const Pipes &a, const Pipes &b) { return a.out.size() == b.out.size() && std::equal(a.out.begin(), a.out.end(), b.out.begin()); }

/** A phrase over a bass: eight notes a bar of the tune, for the off checks. */
void phrase(Pipes &g) {
    const uint8_t tune[] = {69, 71, 73, 74, 76, 74, 73, 71};
    for (uint8_t n : tune) {
        g.f->noteOn(n, 100);
        g.play(0.25);
        g.f->noteOff(n);
    }
    g.play(3.0);
}

void follow() {
    std::printf("- the drones follow the song's bass\n");
    // A held F under the pipes in A: the drones go to F3 and the bass drone F2.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.8f}, {"glide", 0.3f}, {"drift", 0.0f}, {"air", 0.0f}});
        g.bass = [](double) { return 29; };
        g.f->noteOn(69, 100);
        g.play(3.0);
        check(g.f->followRoot() >= 0 && (g.f->followRoot() - 29) % 12 == 0, "hears the bass as F", fmt("key %d", g.f->followRoot()));
        check(std::fabs(g.f->droneNote(0) - 53.0f) < 0.01f && std::fabs(g.f->droneNote(2) - 41.0f) < 0.01f,
              "the drones land on F3 and F2", fmt("%.2f and %.2f", g.f->droneNote(0), g.f->droneNote(2)));
        const double onF = g.level(noteHz(41), 2.0, 3.0), onA = g.level(noteHz(45), 2.0, 3.0);
        check(onF > onA + 20.0, "and sound there", fmt("F2 %+.1f dB, A2 %+.1f dB", onF, onA));
    }
    // The drones start on the bass note when the bag fills after it's heard.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.8f}, {"drift", 0.0f}});
        g.bass = [](double) { return 31; };
        g.play(1.0);
        g.f->noteOn(69, 100);
        g.play(0.1);
        check(std::fabs(g.f->droneNote(0) - 55.0f) < 0.01f, "start on a bass heard before the first note", fmt("%.2f", g.f->droneNote(0)));
    }
    // A to D: the pipes glide, and a move shorter than the glide never jumps.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 1.0f}, {"glide", 0.6f}, {"drift", 0.0f}});
        g.bass = [](double t) { return t < 2.0 ? 33 : 38; };
        g.f->noteOn(69, 100);
        g.play(4.0);
        float biggest = 0.0f;
        int between = 0;
        for (size_t i = 1; i < g.drone0.size(); ++i) {
            biggest = std::max(biggest, std::fabs(g.drone0[i] - g.drone0[i - 1]));
            if (g.drone0[i] > 57.2f && g.drone0[i] < 61.8f) ++between;
        }
        check(std::fabs(Pipes::at(g.drone0, 3.9) - 62.0f) < 0.01f, "A to D: the drones go up a fourth to D4", fmt("%.2f", Pipes::at(g.drone0, 3.9)));
        check(between > 20 && biggest < 0.3f, "and glide there", fmt("%d blocks between, the biggest step %.2f semitones", between, biggest));
    }
    // A hurdy-gurdy's drone strings move like a peg turned, evenly, in the glide time.
    {
        Pipes g({{"model", Gurdy}, {"sidechain", 1.0f}, {"follow", 1.0f}, {"glide", 1.0f}, {"drift", 0.0f}});
        g.bass = [](double t) { return t < 1.5 ? 33 : 31; };
        g.f->noteOn(69, 100);
        g.play(4.0);
        // The mouche, a fourth under A3, goes to D3 under G.
        const float d0 = Pipes::at(g.drone0, 3.9);
        size_t from = 0, to = 0;
        for (size_t i = 1; i < g.drone0.size(); ++i) {
            if (from == 0 && g.drone0[i] != g.drone0[i - 1]) from = i;
            if (g.drone0[i] != g.drone0[i - 1]) to = i;
        }
        const double took = static_cast<double>(to - from) * kBlock / kRate;
        check(std::fabs(d0 - 43.0f) < 0.01f && took > 0.8 && took < 1.1, "hurdy-gurdy: the drone strings turn to G over the glide",
              fmt("%.2f after %.2f s", d0, took));
    }
    // Passing notes don't drag the drones: A with a quick C every second.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.5f}, {"drift", 0.0f}});
        g.bass = [](double t) { return std::fmod(t, 1.0) > 0.8 ? 36 : 33; };
        g.f->noteOn(69, 100);
        g.play(5.0);
        float lo = 1e9f, hi = -1e9f;
        for (size_t i = g.drone0.size() / 4; i < g.drone0.size(); ++i) {
            lo = std::min(lo, g.drone0[i]);
            hi = std::max(hi, g.drone0[i]);
        }
        check(lo == 57.0f && hi == 57.0f, "passing notes don't move them", fmt("%.2f to %.2f", lo, hi));
        // Held longer than the hold, the C takes them.
        g.bass = [](double) { return 36; };
        g.play(2.0);
        check(std::fabs(g.drone0.back() - 60.0f) < 0.01f, "but a held one does", fmt("%.2f", g.drone0.back()));
    }
    // A bass line in eighths at 120 with a kick on every beat: A for a bar
    // with passing notes, then F and G. The drones take A, F and G only.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.6f}, {"drift", 0.0f}});
        static const int line[16] = {33, 33, 45, 33, 36, 33, 43, 33, 29, 29, 41, 29, 31, 31, 43, 38};
        g.bass = [](double t) { return line[static_cast<int>(t / 0.25) % 16]; };
        float L[kBlock], R[kBlock], key[kBlock];
        double phase = 0.0;
        std::vector<int> roots;
        g.f->noteOn(69, 100);
        for (int b = 0; b < kRate * 12 / kBlock; ++b) {
            for (int i = 0; i < kBlock; ++i) {
                const double t = static_cast<double>(b * kBlock + i) / kRate;
                phase += noteHz(line[static_cast<int>(t / 0.25) % 16]) / kRate;
                double v = 0.0;
                for (int h = 1; h <= 6; ++h) v += std::sin(6.283185307179586 * h * phase) / h;
                // The kick: a 150 to 50 Hz drop, every half second.
                const double k = std::fmod(t, 0.5);
                v += 3.0 * std::exp(-k / 0.08) * std::sin(6.283185307179586 * (50.0 * k + 100.0 * 0.03 * (1.0 - std::exp(-k / 0.03))));
                key[i] = static_cast<float>(0.25 * v);
            }
            g.f->setKey(key);
            g.f->onBlock(0, 0, 120.0f);
            g.f->render(L, R, kBlock);
            if (roots.empty() || roots.back() != g.f->followRoot()) roots.push_back(g.f->followRoot());
        }
        bool onlyRoots = true;
        std::string seen;
        for (int r : roots) {
            seen += fmt("%d ", r);
            if (r >= 0 && r % 12 != 9 && r % 12 != 5 && r % 12 != 7) onlyRoots = false;
        }
        check(onlyRoots && roots.size() >= 5, "a bass line over a kick: A, F and G, not the passing notes", seen);
    }
    // In a tuning: the bass is heard on the tuning's keys and the drones take them.
    {
        static float ratios[128];
        for (int n = 0; n < 128; ++n) ratios[n] = std::pow(2.0f, (n % 2 == 0 ? 30.0f : -20.0f) / 1200.0f);
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.8f}, {"drift", 0.0f}});
        g.f->setTuning(ratios);
        // F1 in the tuning, 20 cents flat, played by hand.
        double phase = 0.0;
        const double hz = noteHz(29) * std::pow(2.0, -20.0 / 1200.0);
        g.bass = nullptr;
        float L[kBlock], R[kBlock], key[kBlock];
        g.f->noteOn(69, 100);
        for (int b = 0; b < kRate * 3 / kBlock; ++b) {
            for (int i = 0; i < kBlock; ++i) {
                phase += hz / kRate;
                double v = 0.0;
                for (int h = 1; h <= 6; ++h) v += std::sin(6.283185307179586 * h * phase) / h;
                key[i] = static_cast<float>(0.25 * v);
            }
            g.f->setKey(key);
            g.f->onBlock(0, 0, 120.0f);
            g.f->render(L, R, kBlock);
        }
        check(g.f->followRoot() == 29 && std::fabs(g.f->droneNote(0) - 53.0f) < 0.01f, "in a tuning, on the tuning's F",
              fmt("heard %d, drones on %.2f", g.f->followRoot(), g.f->droneNote(0)));
    }
    // Following and a finite bag, played again after a reset, repeat to the bit.
    {
        Pipes g({{"model", Highland}, {"sidechain", 1.0f}, {"follow", 0.7f}, {"wind", 0.35f}, {"sag", 0.8f}, {"bag", 0.5f}});
        auto run = [&g]() {
            g.bassPhase = 0.0;
            g.bass = [](double t) { return t < 2.0 ? 33 : 29; };
            g.f->noteOn(69, 100);
            g.play(4.0);
            g.f->noteOff(69);
            g.play(3.0);
        };
        run();
        const std::vector<float> first = g.out;
        g.out.clear();
        g.f->allNotesOff();
        g.f->reset();
        run();
        check(first == g.out, "following and a finite bag repeat after a reset");
    }
    std::printf("- off, the drones stay as they were\n");
    for (float kind : {static_cast<float>(Highland), static_cast<float>(Gurdy), static_cast<float>(Smallpipes)}) {
        Pipes plain({{"model", kind}});
        phrase(plain);
        // A track to follow, but follow at 0; and follow up with no track.
        Pipes keyed({{"model", kind}, {"sidechain", 1.0f}, {"follow", 0.0f}});
        keyed.bass = [](double) { return 29; };
        phrase(keyed);
        Pipes untracked({{"model", kind}, {"follow", 1.0f}});
        untracked.bass = [](double) { return 29; };
        phrase(untracked);
        check(same(plain, keyed) && same(plain, untracked), fmt("kind %d: follow 0 or no track renders as before, to the bit", static_cast<int>(kind)).c_str());
    }
}

/** When the per-block trace first falls under [below] after [from] seconds, or -1. */
double firstUnder(const std::vector<float> &v, float below, double from) {
    for (size_t i = static_cast<size_t>(from * kRate / kBlock); i < v.size(); ++i)
        if (v[i] < below) return static_cast<double>(i) * kBlock / kRate;
    return -1.0;
}

void windBag() {
    std::printf("- a finite bag\n");
    // A long phrase on a weak wind drains it; the pressure holds, then sags.
    {
        Pipes g({{"model", Highland}, {"wind", 0.35f}, {"sag", 1.0f}, {"bag", 1.0f}, {"drift", 0.0f}, {"air", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(12.0);
        const float early = Pipes::at(g.store, 1.0), late = Pipes::at(g.store, 11.0);
        check(early < 0.98f && late < early, "playing drains it", fmt("air %.2f after 1 s, %.2f after 11", early, late));
        check(Pipes::at(g.wind, 1.0) > 0.99f && Pipes::at(g.wind, 11.0) < 0.9f, "the pressure holds, then sags",
              fmt("%.2f, then %.2f", Pipes::at(g.wind, 1.0), Pipes::at(g.wind, 11.0)));
        const double sharp = g.peak(430.0, 450.0, 0.5, 1.5), flat = g.peak(400.0, 450.0, 10.5, 11.5);
        check(cents(flat, sharp) < -10.0, "and the chanter goes flat", fmt("%+.0f cents", cents(flat, sharp)));
        // Rest with the piper still blowing: it fills again.
        g.f->noteOff(69);
        g.f->params().jump(g.f->params().indexOf("bag"), g.f->params().def(g.f->params().indexOf("bag")).unmap(8.0f));
        const float before = g.store.back();
        g.play(3.0);
        check(g.store.back() > before + 0.2f, "a rest fills it again", fmt("%.2f to %.2f", before, g.store.back()));
    }
    // Ordinary playing on a fair wind never runs it dry.
    {
        Pipes g({{"model", Highland}, {"wind", 0.6f}, {"bag", 1.5f}});
        for (int bar = 0; bar < 6; ++bar) {
            g.f->noteOn(69, 100);
            g.play(1.9);
            g.f->noteOff(69);
            g.play(0.1);
        }
        float least = 1.0f;
        for (size_t i = static_cast<size_t>(0.5 * kRate / kBlock); i < g.wind.size(); ++i) least = std::min(least, g.wind[i]);
        check(least > 0.99f, "a fair wind keeps up with a tune", fmt("pressure at least %.2f after the start", least));
    }
    std::printf("- the drones die\n");
    // Endless bag, sag up: let go and the drones groan down and choke one by one.
    {
        Pipes g({{"model", Highland}, {"sag", 1.0f}, {"bag", 0.5f}, {"drift", 0.0f}, {"air", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(2.0);
        g.f->noteOff(69);
        std::vector<float> open0, open2;
        float L[kBlock], R[kBlock];
        for (int b = 0; b < kRate * 4 / kBlock; ++b) {
            g.f->onBlock(0, 0, 120.0f);
            g.f->render(L, R, kBlock);
            g.out.insert(g.out.end(), L, L + kBlock);
            open0.push_back(g.f->droneOpen(0));
            open2.push_back(g.f->droneOpen(2));
        }
        const double first = firstUnder(open0, 0.5f, 0.0), last = firstUnder(open2, 0.5f, 0.0);
        check(first > 0.0 && last > first + 0.1, "the drones choke one by one", fmt("the first at %.2f s, the bass at %.2f s", first, last));
        // The bass drone, A2, falls as it dies.
        const double full = g.peak(100.0, 120.0, 1.0, 2.0);
        const double dying = g.peak(90.0, 120.0, 2.0 + last - 0.25, 2.0 + last);
        check(cents(dying, full) < -40.0, "and groan down as they go", fmt("the bass drone %+.0f cents", cents(dying, full)));
        check(g.rms(5.5, 6.0) < -90.0, "and then they're gone", fmt("%.1f dB", g.rms(5.5, 6.0)));
    }
    // A finite bag empties after a long phrase let go.
    {
        Pipes g({{"model", Smallpipes}, {"wind", 0.4f}, {"sag", 0.5f}, {"bag", 0.5f}});
        g.f->noteOn(69, 100);
        g.play(6.0);
        g.f->noteOff(69);
        g.play(5.0);
        check(g.store.back() == 0.0f && g.rms(10.5, 11.0) < -90.0, "let go, the bag empties and the drones stop",
              fmt("air %.2f, %.1f dB", g.store.back(), g.rms(10.5, 11.0)));
    }
    // From an empty bag the piper strikes in: the next note speaks at once.
    {
        Pipes g({{"model", Highland}, {"wind", 0.4f}, {"bag", 0.2f}, {"drift", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(4.0);
        g.f->noteOff(69);
        g.play(4.0);
        g.f->noteOn(69, 100);
        g.play(1.0);
        check(g.store[static_cast<size_t>(7.9 * kRate / kBlock)] < 0.05f && g.level(noteHz(69), 8.2, 8.6) > g.level(noteHz(69), 2.0, 2.4) - 6.0,
              "an empty bag strikes in for the next note", fmt("%.1f dB against %.1f", g.level(noteHz(69), 8.2, 8.6), g.level(noteHz(69), 2.0, 2.4)));
    }
    std::printf("- the wheel coasts\n");
    {
        Pipes g({{"model", Gurdy}, {"wind", 0.4f}, {"sag", 1.0f}, {"bag", 0.3f}, {"drift", 0.0f}, {"dog", 0.0f}});
        g.f->noteOn(69, 100);
        g.play(10.0);
        check(Pipes::at(g.wind, 9.5) < 0.9f, "a long phrase on a tired arm slows the wheel", fmt("%.2f", Pipes::at(g.wind, 9.5)));
        g.f->noteOff(69);
        g.play(4.0);
        const double half = firstUnder(g.wind, 0.5f * Pipes::at(g.wind, 10.3), 10.3);
        check(half > 10.6, "let go, it coasts down rather than stops", fmt("half speed %.2f s after", half - 10.3));
        const double full = g.peak(100.0, 120.0, 10.35, 10.55), slow = g.peak(90.0, 120.0, half - 0.1, half + 0.1);
        check(cents(slow, full) < -10.0, "and the strings sag with it", fmt("%+.0f cents", cents(slow, full)));
    }
    std::printf("- launeddas breathe in\n");
    {
        Pipes g({{"model", 12.0f}, {"wind", 0.2f}});
        g.f->noteOn(69, 100);
        g.play(8.0);
        int dips = 0;
        for (size_t i = 1; i < g.wind.size(); ++i)
            if (g.wind[i] < 0.9f && g.wind[i - 1] >= 0.9f) ++dips;
        check(dips >= 2 && g.store.back() == 1.0f, "a breath through the nose every few seconds, no bag", fmt("%d dips", dips));
    }
}

/** How deep the two melody strings beat on a held note, dB: the swing of a 50 ms RMS over eight seconds, drones and dog off. */
double beating(int note) {
    Pipes g({{"model", Gurdy}, {"drones", 0.0f}, {"dog", 0.0f}, {"drift", 0.0f}});
    g.f->noteOn(static_cast<uint8_t>(note), 100);
    g.play(10.0);
    double lo = 1e9, hi = -1e9;
    for (double t = 2.0; t + 0.05 <= 10.0; t += 0.01) {
        const double r = g.rms(t, t + 0.05);
        lo = std::min(lo, r);
        hi = std::max(hi, r);
    }
    return hi - lo;
}

void melodyBeat() {
    std::printf("- the melody strings beating\n");
    for (int note : {57, 69, 76}) {
        const double d = beating(note);
        check(d > 2.5 && d < 5.5, fmt("note %d beats about 4 dB deep", note).c_str(), fmt("%.1f dB", d));
    }
}

} // namespace

int main() {
    tuning();
    inTune();
    topNotes();
    bag();
    fingers();
    dog();
    lifecycle();
    melodyBeat();
    follow();
    windBag();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
