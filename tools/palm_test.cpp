// Palm, hand drums: in tune, a tabla's harmonic overtones against a plain
// head's, the strokes (muted dies first, a slap is bright, a bass stroke is
// low), a djembe's body, a cajón's snares, a talking drum squeezed, a head
// going sharp when struck hard, a finger damping it, rolls, cycles.
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
    /** Plays [seconds] with the song rolling from [tick] at 120 bpm in 4/4, swung [swing] over sixteenths. */
    void playSong(double seconds, int64_t tick, float swing = 50.0f, int32_t perBar = 4 * kPPQN) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        const double ticksPerFrame = 120.0 * kPPQN / (60.0 * kRate);
        const double start = static_cast<double>(tick) - static_cast<double>(out.size()) * ticksPerFrame;
        for (int b = 0; b < blocks; ++b) {
            const auto at = static_cast<double>(out.size());
            const auto t0 = static_cast<int64_t>(start + at * ticksPerFrame);
            const auto t1 = static_cast<int64_t>(start + (at + kBlock) * ticksPerFrame);
            f->onTransport(true, t0);
            f->onBar(t0 % perBar, perBar, swing, kPPQN / 2);
            f->onCountIn(0.0);
            f->onBlock(t0, t1, 120.0f);
            f->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
        }
    }
    /** Plays [seconds] stopped, in 4/4 at 120 bpm, counting in from [left] ticks before the downbeat if it's more than 0. */
    void playStopped(double seconds, double left = 0.0) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        const double ticksPerBlock = 120.0 * kPPQN / 60.0 * kBlock / kRate;
        for (int b = 0; b < blocks; ++b) {
            f->onTransport(false, 0);
            f->onBar(0, 4 * kPPQN, 50.0f, kPPQN / 2);
            f->onCountIn(left > 1e-9 ? left : 0.0);
            f->onBlock(0, 0, 120.0f);
            f->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
            left -= ticksPerBlock;
        }
    }
    /** The first sample from [from] seconds louder than [floor]. */
    double firstAbove(double from, float floor) const {
        for (size_t i = static_cast<size_t>(from * kRate); i < out.size(); ++i)
            if (std::fabs(out[i]) > floor) return static_cast<double>(i) / kRate;
        return -1.0;
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

/** The largest difference in loudness between bars [a] and [b] (two seconds each at 120 bpm), dB, in 5 ms windows
 *  no more than 40 dB under the bar's loudest. The crack is noise, so samples never repeat exactly. */
/** As barDiff, for any two stretches, in windows of [win] samples. Strokes in the song land on its blocks, so
 *  stretches that aren't a whole number of blocks apart want wider windows. */
double envDiff(const std::vector<float> &x, size_t xa, const std::vector<float> &y, size_t ya, size_t len, size_t win = kRate / 200) {
    std::vector<double> ea, eb;
    double top = 1e-20;
    for (size_t w = 0; w + win <= len; w += win) {
        double p = 0.0, q = 0.0;
        for (size_t i = 0; i < win; ++i) {
            p += double(x[xa + w + i]) * x[xa + w + i];
            q += double(y[ya + w + i]) * y[ya + w + i];
        }
        ea.push_back(p);
        eb.push_back(q);
        top = std::max({top, p, q});
    }
    double worst = 0.0;
    for (size_t i = 0; i < ea.size(); ++i) {
        if (std::max(ea[i], eb[i]) < top * 1e-4) continue;
        worst = std::max(worst, std::fabs(10.0 * std::log10((ea[i] + 1e-20) / (eb[i] + 1e-20))));
    }
    return worst;
}

double barDiff(const Drum &g, int a, int b, size_t len = 2 * kRate, size_t win = kRate / 200) {
    return envDiff(g.out, a * len, g.out, b * len, len, win);
}

void cycles() {
    std::printf("- cycles\n");
    enum { Off, Tumbao, Kuku, Baladi, Maqsum, Theka, SixEight, Groove, Talk };
    {
        Drum g({{"model", 6}, {"cycle", Tumbao}, {"bars", 0}, {"feel", 0.0f}});
        g.f->noteOn(57, 100);
        g.play(8.0);
        check(barDiff(g, 2, 3) < 1.5, "with no feel a held note plays the same bar again", fmt("%.1f dB", barDiff(g, 2, 3)));
        check(g.rms(7.5, 8.0) > -60.0, "and keeps playing while held", fmt("%.1f dB", g.rms(7.5, 8.0)));
        g.f->noteOff(57);
        g.play(4.0);
        check(g.f->activeVoices() == 0, "letting go stops it and frees the voice", fmt("%d left", g.f->activeVoices()));
    }
    {
        Drum g({{"model", 6}, {"cycle", Tumbao}, {"bars", 0}, {"feel", 0.8f}});
        g.f->noteOn(57, 100);
        g.play(8.0);
        check(barDiff(g, 2, 3) > 6.0, "with feel each bar is a little different", fmt("%.1f dB", barDiff(g, 2, 3)));
    }
    {
        Drum g({{"model", 6}, {"cycle", Tumbao}, {"bars", 1}, {"feel", 0.0f}});
        g.f->noteOn(57, 100);
        g.play(10.0);
        check(barDiff(g, 1, 2) > 6.0 && barDiff(g, 1, 3) < 1.5, "two bars a cycle: the second is a fill",
              fmt("fill %.1f dB, again %.1f dB", barDiff(g, 1, 2), barDiff(g, 1, 3)));
    }
    {
        Drum a({{"model", 8}, {"cycle", Baladi}, {"feel", 0.7f}}), b({{"model", 8}, {"cycle", Baladi}, {"feel", 0.7f}});
        a.f->noteOn(60, 100);
        a.play(9.0);
        b.f->noteOn(60, 100);
        b.play(9.0);
        bool same = a.out == b.out;
        a.f->reset();
        a.out.clear();
        a.f->noteOn(60, 100);
        a.play(9.0);
        same = same && a.out == b.out;
        check(same, "a varied cycle plays the same again after a reset");
    }
    {
        // From beat three the heel lands at once; from just after, the toe two sixteenths on.
        Drum on({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}}), after({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}});
        on.f->noteOn(57, 100);
        on.playSong(1.0, 4 * kPPQN * 2 + 2 * kPPQN);
        after.f->noteOn(57, 100);
        after.playSong(1.0, 4 * kPPQN * 2 + 2 * kPPQN + 20);
        const double first = after.firstAbove(0.0, 1e-4f);
        check(on.firstAbove(0.0, 1e-4f) >= 0.0 && on.firstAbove(0.0, 1e-4f) < 0.005, "with the song rolling, strokes land on its sixteenths",
              fmt("%.4f s", on.firstAbove(0.0, 1e-4f)));
        check(std::fabs(first - 100.0 / 480.0) < 0.004, "joining after a step waits for the next one", fmt("%.4f s", first));
    }
    {
        // Kuku's slap on the fourth sixteenth moves with the track's swing.
        Drum straight({{"model", 2}, {"cycle", Kuku}, {"feel", 0.0f}}), swung({{"model", 2}, {"cycle", Kuku}, {"feel", 0.0f}});
        straight.f->noteOn(55, 100);
        swung.f->noteOn(55, 100);
        straight.playSong(1.0, 0);
        swung.playSong(1.0, 0, 66.667f);
        size_t i = 0;
        while (i < straight.out.size() && straight.out[i] == swung.out[i]) ++i;
        const double at = static_cast<double>(i) / kRate * 480.0;
        check(at > 175.0 && at < 185.0, "swing moves the offbeat sixteenths", fmt("first difference at tick %.1f", at));
    }
    {
        // Two held notes share the pattern: bass strokes low, the rest high.
        Drum one({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}}), two({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}});
        one.f->noteOn(60, 100);
        two.f->noteOn(60, 100);
        two.f->noteOn(48, 100);
        one.play(2.0);
        two.play(2.0);
        const double hz = noteHz(48);
        check(two.level(hz, 0.0, 2.0) > one.level(hz, 0.0, 2.0) + 10.0, "two held notes: the lower drum takes the bass strokes",
              fmt("%+.1f dB at the low note", two.level(hz, 0.0, 2.0) - one.level(hz, 0.0, 2.0)));
    }
    {
        // The second hand presses the talking drum up.
        Drum flat({{"model", 5}, {"cycle", Talk}, {"feel", 0.0f}, {"press", 0.0f}}), pressed({{"model", 5}, {"cycle", Talk}, {"feel", 0.0f}, {"press", 7.0f}});
        flat.f->noteOn(55, 100);
        pressed.f->noteOn(55, 100);
        flat.play(2.0);
        pressed.play(2.0);
        // The press on the fourth sixteenth: 0.375 s, settled by 0.55.
        const double up = cents(pressed.peak(noteHz(55) * 1.3, noteHz(55) * 1.6, 0.55, 0.74), noteHz(55));
        check(std::fabs(up - 700.0) < 40.0, "a pressed stroke bends up by press", fmt("%.0f cents", up));
    }
    for (int kind = 1; kind <= Talk; ++kind) {
        Drum g({{"model", static_cast<float>(kind % 19)}, {"cycle", static_cast<float>(kind)}, {"bars", 3}, {"feel", 1.0f}, {"press", 12.0f}, {"volume", 1.0f}});
        g.f->noteOn(50, 127);
        g.f->noteOn(62, 127);
        g.play(18.0);
        bool finite = true;
        for (float v : g.out) finite = finite && std::isfinite(v);
        if (!finite || g.peakAbs() >= 12.0 || g.rms(1.0, 16.0) < -60.0)
            check(false, fmt("cycle %d plays and stays bounded", kind).c_str(), fmt("peak %.2f", g.peakAbs()));
    }
    check(true, "every cycle plays and stays bounded");

    // Other meters keep the pattern's start and its closing strokes at the bar's end.
    for (const int perBar : {3 * kPPQN, 7 * kPPQN / 2, 5 * kPPQN}) {
        Drum g({{"model", 6}, {"cycle", Tumbao}, {"bars", 0}, {"feel", 0.0f}});
        g.f->noteOn(57, 100);
        g.playSong(4.0 * perBar / 480.0 + 0.1, 0, 50.0f, perBar);
        const size_t len = static_cast<size_t>(perBar) * 100;
        const double again = barDiff(g, 1, 2, len, kRate / 25);
        // The open tone of the pattern's last beat, a beat before the bar's end.
        const double lastBeat = g.rms(2.0 * perBar / 480.0 - 0.5, 2.0 * perBar / 480.0 - 0.3);
        check(again < 1.5 && lastBeat > -45.0, fmt("%d/%d bars repeat and end with the pattern's end", perBar == 3 * kPPQN ? 3 : perBar == 5 * kPPQN ? 5 : 7, perBar == 7 * kPPQN / 2 ? 8 : 4).c_str(),
              fmt("again %.1f dB, last beat %.1f dB", again, lastBeat));
    }
    {
        // 5/4: the extra beat is filler, played soft, and the downbeat accent stays on one.
        Drum five({{"model", 2}, {"cycle", Kuku}, {"bars", 0}, {"feel", 0.0f}});
        five.f->noteOn(55, 100);
        five.playSong(2.5, 0, 50.0f, 5 * kPPQN);
        const double one = five.rms(0.0, 0.05), extra = five.rms(1.0, 1.05);
        check(extra < one - 6.0, "a 5/4 bar's extra beat is softer than its one", fmt("%.1f vs %.1f dB", extra, one));
    }
    {
        // Stopped, a held voice taken for a legato note doesn't restart the bar.
        Drum g({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}, {"voices", 1}});
        g.f->noteOn(57, 100);
        g.play(1.3);
        g.f->noteOn(60, 100);
        g.play(0.4);
        check(g.rms(1.31, 1.49) < -100.0 && g.rms(1.5, 1.6) > -50.0, "a stolen held note keeps the bar going",
              fmt("%.1f dB before the next step, %.1f after", g.rms(1.31, 1.49), g.rms(1.5, 1.6)));
    }
    {
        // Stopping with a key down carries on from where the song was.
        Drum a({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}}), b({{"model", 6}, {"cycle", Tumbao}, {"feel", 0.0f}});
        a.f->noteOn(57, 100);
        b.f->noteOn(57, 100);
        a.playSong(1.3, 0);
        a.playStopped(1.0);
        b.playSong(2.3, 0);
        const size_t at = static_cast<size_t>(1.3 * kRate) / kBlock * kBlock;
        const double d = envDiff(a.out, at, b.out, at, static_cast<size_t>(0.9 * kRate), kRate / 25);
        check(d < 1.5, "stopped with a key down, it plays on in time", fmt("%.1f dB from the song", d));
    }
    {
        // During a count-in it plays the count-in's bar, then the song's. One
        // bar a cycle, since the bar before the song's first is a fill.
        Drum a({{"model", 6}, {"cycle", Tumbao}, {"bars", 0}, {"feel", 0.0f}}), b({{"model", 6}, {"cycle", Tumbao}, {"bars", 0}, {"feel", 0.0f}});
        const double left = 672 * 0.64;
        a.f->noteOn(57, 100);
        a.playStopped(672.0 * kBlock / kRate, left);
        a.playSong(1.5, 0);
        b.f->noteOn(57, 100);
        b.playSong(672.0 * kBlock / kRate + 1.5, 4 * kPPQN - static_cast<int64_t>(left));
        const double d = envDiff(a.out, 0, b.out, 0, a.out.size() - kRate / 10, kRate / 25);
        check(d < 1.5, "a count-in plays in the count-in's bar", fmt("%.1f dB from the song", d));
    }
    {
        // All notes off drops strokes still waiting to land.
        Drum a({{"model", 6}, {"cycle", Tumbao}, {"feel", 1.0f}}), b({{"model", 6}, {"cycle", Tumbao}, {"feel", 1.0f}});
        a.f->noteOn(57, 100);
        b.f->noteOn(57, 100);
        a.play(0.5 + double(kBlock) / kRate);
        b.play(0.5 + double(kBlock) / kRate);
        a.f->allNotesOff();
        b.f->noteOff(57);
        a.play(0.1);
        b.play(0.1);
        const double quiet = a.rms(0.505, 0.53), late = b.rms(0.505, 0.53);
        check(quiet < late - 10.0, "all notes off drops the strokes waiting", fmt("%.1f vs %.1f dB", quiet, late));
    }
    {
        // A cuica held with the song rolling squeaks at its own note before the first stroke.
        Drum g({{"model", 18}, {"cycle", Tumbao}, {"feel", 0.0f}});
        g.f->noteOn(64, 100);
        g.playSong(0.2, 20);
        const double hz = noteHz(64), got = g.peak(hz * 0.9, hz * 1.1, 0.02, 0.18);
        check(g.rms(0.02, 0.18) > -80.0 && std::fabs(cents(got, hz)) < 30.0, "a cuica rubs its own head before the first stroke",
              fmt("%.1f dB at %+.0f cents", g.rms(0.02, 0.18), cents(got, hz)));
    }
    {
        // With the song rolling, a note played again while a pressed stroke
        // rings keeps the press until the next stroke.
        Drum g({{"model", 5}, {"cycle", Talk}, {"feel", 0.0f}, {"press", 7.0f}});
        g.f->noteOn(55, 100);
        g.playSong(0.55, 0);
        g.f->noteOn(55, 100);
        g.playSong(0.3, static_cast<int64_t>(std::lround(g.out.size() * 480.0 / kRate)));
        const double up = cents(g.peak(noteHz(55) * 1.3, noteHz(55) * 1.6, 0.57, 0.74), noteHz(55));
        check(std::fabs(up - 700.0) < 40.0, "playing the note again keeps the press until the next stroke", fmt("%.0f cents", up));
    }
}

} // namespace

int main() {
    tuning();
    heads();
    strokes();
    hands();
    lifecycle();
    cycles();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
