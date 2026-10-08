// Fret, electric guitars and basses: in tune, the pickups hearing what a
// pickup at that place would, a palm muting, a harmonic, a strum across the
// strings, a slide, and the amp feeding a note back.
//
//   fret_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/fret/Fret.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Fret;

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

/** A Fret played block by block. */
struct Guitar {
    std::unique_ptr<Machine> owned;
    Fret *f;
    std::vector<float> out;

    explicit Guitar(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Fret"));
        f = static_cast<Fret *>(owned.get());
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

void tuning() {
    std::printf("- tuning\n");
    const struct { const char *kind; float model; int note; } cases[] = {
        {"guitar E2", 0, 40}, {"guitar E3", 0, 52}, {"guitar E4", 0, 64}, {"guitar E5", 0, 76},
        {"bass E1", 3, 28}, {"bass E2", 3, 40}, {"5-string B0", 4, 23}, {"baritone B1", 2, 35},
    };
    for (const auto &c : cases) {
        Guitar g({{"model", c.model}, {"pickup", 0}});
        g.f->noteOn(static_cast<uint8_t>(c.note), 90);
        g.play(1.5);
        const double want = noteHz(c.note);
        const double got = g.peak(want * 0.97, want * 1.03, 0.3, 1.3);
        check(std::fabs(cents(got, want)) < 6.0, fmt("%s plays in tune", c.kind).c_str(), fmt("%+.1f cents", cents(got, want)));
    }
}

/** The balance of a note's upper harmonics (5 to 12) against its lower (1 to 2), dB. */
double brightness(const Guitar &g, double hz) {
    double hi = 0.0, lo = 0.0;
    for (int n = 5; n <= 12; ++n) hi += std::pow(10.0, g.harmonic(hz, n, 0.1, 0.6) / 10.0);
    for (int n = 1; n <= 2; ++n) lo += std::pow(10.0, g.harmonic(hz, n, 0.1, 0.6) / 10.0);
    return 10.0 * std::log10(hi / lo);
}

void pickups() {
    std::printf("- pickups\n");
    const double hz = noteHz(52);
    Guitar neck({{"pickup", 0}}), bridge({{"pickup", 2}});
    neck.f->noteOn(52, 100);
    bridge.f->noteOn(52, 100);
    neck.play(0.8);
    bridge.play(0.8);
    const double bn = brightness(neck, hz), bb = brightness(bridge, hz);
    check(bb > bn + 6.0, "the bridge pickup is brighter than the neck", fmt("%.1f vs %.1f dB", bb, bn));
    // A pickup a quarter of the way along is deaf to the 4th harmonic.
    const double dip = neck.harmonic(hz, 4, 0.1, 0.6) - 0.5 * (neck.harmonic(hz, 3, 0.1, 0.6) + neck.harmonic(hz, 5, 0.1, 0.6));
    const double flat = bridge.harmonic(hz, 4, 0.1, 0.6) - 0.5 * (bridge.harmonic(hz, 3, 0.1, 0.6) + bridge.harmonic(hz, 5, 0.1, 0.6));
    check(dip < flat - 10.0, "the neck pickup can't hear the 4th harmonic", fmt("%.1f vs %.1f dB", dip, flat));
    Guitar single({{"pickup", 2}, {"coil", 0}}), hum({{"pickup", 2}, {"coil", 1}});
    single.f->noteOn(52, 100);
    hum.f->noteOn(52, 100);
    single.play(0.8);
    hum.play(0.8);
    auto top = [&](const Guitar &g) {
        double hi = 0.0, lo = 0.0;
        for (int n = 8; n <= 20; ++n) hi += std::pow(10.0, g.harmonic(hz, n, 0.1, 0.6) / 10.0);
        for (int n = 1; n <= 2; ++n) lo += std::pow(10.0, g.harmonic(hz, n, 0.1, 0.6) / 10.0);
        return 10.0 * std::log10(hi / lo);
    };
    check(top(hum) < top(single) - 3.0, "a humbucker is darker than a single coil", fmt("%.1f vs %.1f dB", top(hum), top(single)));
}

void hands() {
    std::printf("- hands\n");
    {
        Guitar open, muted({{"mute", 0.8f}});
        open.f->noteOn(45, 100);
        muted.f->noteOn(45, 100);
        open.play(1.0);
        muted.play(1.0);
        const double o = open.rms(0.05, 0.1) - open.rms(0.5, 0.6), m = muted.rms(0.05, 0.1) - muted.rms(0.5, 0.6);
        check(m > o + 15.0, "a palm on the strings cuts the note short", fmt("falls %.1f vs %.1f dB", m, o));
    }
    {
        const double hz = noteHz(45);
        Guitar g({{"harmonic", 1}});
        g.f->noteOn(45, 100);
        g.play(1.0);
        const double f1 = g.harmonic(hz, 1, 0.3, 0.9), f2 = g.harmonic(hz, 2, 0.3, 0.9);
        check(f2 > f1 + 10.0, "a finger at the 12th fret leaves the octave", fmt("octave %.1f dB over the note", f2 - f1));
    }
    {
        // Three notes strummed 30 ms apart: one string, then two, then three.
        Guitar g({{"strum", 30.0f}, {"direction", 0}});
        g.f->noteOn(64, 100);
        g.f->noteOn(40, 100);
        g.f->noteOn(52, 100);
        int counts[3];
        for (int k = 0; k < 3; ++k) {
            g.play(0.03);
            counts[k] = g.f->activeVoices();
        }
        check(counts[0] == 1 && counts[1] == 2 && counts[2] == 3, "a strum goes across the strings 30 ms apart",
              fmt("%d, %d, %d strings", counts[0], counts[1], counts[2]));
        // Down: the low string first; up: the low string last.
        Guitar u({{"strum", 30.0f}, {"direction", 1}});
        u.f->noteOn(64, 100);
        u.f->noteOn(40, 100);
        u.f->noteOn(52, 100);
        u.play(0.09);
        const double low = noteHz(40), d = g.level(low, 0.0, 0.028), up = u.level(low, 0.0, 0.028);
        check(d > up + 12.0, "a down strum starts on the low string, an up strum ends on it", fmt("%.1f vs %.1f dB", d, up));
    }
    {
        Guitar g({{"voices", 1}, {"slide", 100.0f}});
        g.f->noteOn(48, 100);
        g.play(0.3);
        g.f->noteOn(55, 100);
        g.f->noteOff(48);
        g.play(0.6);
        const double want = noteHz(55);
        const double got = g.peak(want * 0.9, want * 1.1, 0.6, 0.9);
        check(std::fabs(cents(got, want)) < 10.0 && g.f->activeVoices() == 1, "with one voice a new note slides there",
              fmt("%+.1f cents, %d string", cents(got, want), g.f->activeVoices()));
    }
}

void amp() {
    std::printf("- amp\n");
    Guitar dry({{"drive", 0.6f}}), loud({{"drive", 0.6f}, {"feedback", 1.0f}});
    dry.f->noteOn(64, 100);
    loud.f->noteOn(64, 100);
    dry.play(4.0);
    loud.play(4.0);
    const double d = dry.rms(3.0, 4.0) - dry.rms(0.1, 0.4), l = loud.rms(3.0, 4.0) - loud.rms(0.1, 0.4);
    check(l > d + 15.0 && l > -12.0, "the amp feeds a held note back and it sustains", fmt("after 3 s %.1f vs %.1f dB", l, d));
    check(loud.peakAbs() < 1.5, "...and stays in bounds", fmt("peak %.2f", loud.peakAbs()));
}

void endings() {
    std::printf("- endings\n");
    Guitar g;
    g.f->noteOn(40, 127);
    g.play(1.0);
    g.f->noteOff(40);
    g.play(1.5);
    check(g.rms(2.0, 2.5) < -90.0 && g.f->activeVoices() == 0, "let go, it stops and the string is freed", fmt("%.1f dB", g.rms(2.0, 2.5)));
    Guitar twelve({{"model", 1}}), six;
    twelve.f->noteOn(45, 100);
    six.f->noteOn(45, 100);
    twelve.play(0.8);
    six.play(0.8);
    const double hz = noteHz(45);
    const double t = twelve.harmonic(hz, 2, 0.2, 0.7) - twelve.harmonic(hz, 1, 0.2, 0.7);
    const double s = six.harmonic(hz, 2, 0.2, 0.7) - six.harmonic(hz, 1, 0.2, 0.7);
    check(t > s + 2.0, "a twelve-string's low courses add the octave", fmt("octave %.1f vs %.1f dB", t, s));
    Guitar wild({{"drive", 1.0f}, {"feedback", 1.0f}, {"buzz", 1.0f}, {"hardness", 1.0f}, {"stroke", 2}, {"bright", 1.0f}, {"sustain", 1.0f}});
    for (int n : {28, 40, 52, 64, 76, 88}) wild.f->noteOn(static_cast<uint8_t>(n), 127);
    wild.play(5.0);
    bool finite = true;
    for (float v : wild.out) finite = finite && std::isfinite(v);
    check(finite && wild.peakAbs() < 2.0, "every knob up and a full chord: finite and bounded", fmt("peak %.2f", wild.peakAbs()));
}

} // namespace

/** A finger swept up and down the strum keys: fast notes over ringing strings. Once let go, it must die away. */
void aFastStrumDiesAway() {
    std::printf("- a fast strum\n");
    const int ladder[] = {71, 75, 78, 83, 87, 90, 95, 99, 102, 95, 90, 87, 83, 78, 75};
    for (int trial = 0; trial < 3; ++trial) {
        Guitar g;
        if (trial == 1) g.set("buzz", 1.0f);
        if (trial == 2) { g.set("buzz", 1.0f); g.set("drive", 1.0f); }
        double t = 0.0;
        int prev = -1;
        for (int i = 0; i < 240; ++i) {
            const int n = ladder[(i + trial) % 15];
            if (prev >= 0) g.f->noteOff(static_cast<uint8_t>(prev));
            g.f->noteOn(static_cast<uint8_t>(n), 127);
            prev = n;
            g.play(0.025 + 0.01 * trial);
            t += 0.025 + 0.01 * trial;
        }
        g.f->noteOff(static_cast<uint8_t>(prev));
        g.play(6.0);
        const double end = t + 6.0;
        const double tail = g.rms(end - 1.0, end);
        char d[96];
        std::snprintf(d, sizeof d, "last second %.1f dB, peak %.2f", tail, g.peakAbs());
        check(tail < -70.0, "let go, it dies away", d);
    }
}

/** Notes far above a guitar's range, as strum keys can send: each must come back quickly. */
void veryHighNotesReturn() {
    std::printf("- very high notes\n");
    for (int n = 84; n <= 127; n += 3) {
        Guitar g;
        g.f->noteOn(static_cast<uint8_t>(n), 127);
        g.play(0.3);
        g.f->noteOff(static_cast<uint8_t>(n));
        g.play(6.0);
        const double tail = g.rms(5.3, 6.3);
        char d[64];
        std::snprintf(d, sizeof d, "note %d: %.1f dB six seconds after letting go", n, tail);
        check(tail < -70.0, "a very high note dies away", d);
    }
}

int main() {
    std::printf("Fret\n");
    tuning();
    pickups();
    hands();
    amp();
    endings();
    aFastStrumDiesAway();
    veryHighNotesReturn();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
