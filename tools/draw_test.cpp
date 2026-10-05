// Draw, the free reeds: in tune across the range, brighter and barely flatter
// blown harder, the harmonics entering in order, silent when blown too
// gently, stopping when let go, and pressure for one note at a time. The
// harmonicas: every note of a harp played like a player in tune, natural
// or bent (a bend wanders as a player's does, so to 10 cents on average); the straight and chromatic harps in tune; the wheel
// bending a draw note down with the mouth; cupped hands darker.
//
//   draw_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/draw/Draw.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <memory>
#include <string>
#include <utility>
#include <vector>
#include <xmmintrin.h>

using namespace acidulous;
using acidulous::machine::Draw;

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

struct Reeds {
    std::unique_ptr<Machine> owned;
    Draw *d;
    std::vector<float> out;

    explicit Reeds(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Draw"));
        d = static_cast<Draw *>(owned.get());
        d->prepare(kRate);
        d->reset();
        d->params().jumpAll();
        for (const auto &k : knobs) set(k.first, k.second);
    }
    void set(const char *name, float value) {
        ParamSet &p = d->params();
        const int i = p.indexOf(name);
        if (i < 0) {
            std::printf("no parameter %s\n", name);
            std::exit(2);
        }
        p.jump(i, p.def(i).unmap(value));
    }
    void play(double seconds) {
        float L[kBlock], R[kBlock];
        for (int b = 0; b < static_cast<int>(seconds * kRate / kBlock); ++b) {
            d->render(L, R, kBlock);
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
        const double step = (hi - lo) / 400.0;
        for (double f = lo; f <= hi; f += step) {
            const double l = level(f, from, to);
            if (l > bestLevel) { bestLevel = l; best = f; }
        }
        for (double f = best - step; f <= best + step; f += step / 40.0) {
            const double l = level(f, from, to);
            if (l > bestLevel) { bestLevel = l; best = f; }
        }
        return best;
    }
    double rms(double from, double to) const {
        double s = 0.0;
        int n = 0;
        for (size_t i = static_cast<size_t>(from * kRate); i < static_cast<size_t>(to * kRate) && i < out.size(); ++i, ++n) s += out[i] * out[i];
        return 20.0 * std::log10(std::sqrt(s / std::max(n, 1)) + 1e-12);
    }
    /** The brightness over a stretch: the centroid of the first 16 harmonics of [hz]. */
    double centroid(double hz, double from, double to) const {
        double num = 0.0, den = 0.0;
        for (int h = 1; h <= 16 && h * hz < 20000.0; ++h) {
            const double a = std::pow(10.0, level(h * hz, from, to) / 20.0);
            num += a * h * hz;
            den += a;
        }
        return num / den;
    }
};

double cents(double a, double b) { return 1200.0 * std::log2(a / b); }
double noteHz(double n) { return 440.0 * std::pow(2.0, (n - 69.0) / 12.0); }

void tuning() {
    std::printf("tuning\n");
    // Each instrument over the notes it's made for.
    const int ranges[][3] = {{4, 33, 96}, {5, 33, 91}, {6, 48, 96}, {7, 53, 89}, {8, 40, 96}, {9, 55, 93}, {11, 45, 81}, {12, 60, 84}};
    for (const auto &range : ranges) {
        const int model = range[0];
        double worst = 0.0, at = 0.0;
        for (int note = range[1]; note <= range[2]; note += 5) {
            // One reed: a pair a few cents apart is closer than the window can part.
            Reeds r({{"model", static_cast<float>(model)}, {"register", 1.0f}, {"shake", 0.0f}});
            r.d->noteOn(static_cast<uint8_t>(note), 100);
            r.play(1.0);
            const double f = noteHz(note);
            const double got = r.peak(f * 0.97, f * 1.03, 0.4, 1.0);
            const double c = cents(got, f);
            if (std::fabs(c) > std::fabs(worst)) { worst = c; at = note; }
        }
        check(std::fabs(worst) < 5.0, fmt("every note in tune, model %d", model).c_str(), fmt("worst %+.2f c at note %.0f", worst, at));
    }
}

void pressure() {
    std::printf("pressure\n");
    const double f = noteHz(69);
    Reeds soft({{"pressure", 0.2f}}), hard({{"pressure", 0.8f}});
    soft.d->noteOn(69, 100);
    hard.d->noteOn(69, 100);
    soft.play(1.0);
    hard.play(1.0);
    const double fs = soft.peak(f * 0.97, f * 1.03, 0.4, 1.0), fh = hard.peak(f * 0.97, f * 1.03, 0.4, 1.0);
    const double bs = soft.centroid(f, 0.4, 1.0), bh = hard.centroid(f, 0.4, 1.0);
    // Measured: 10 to 15 cents flat over the playing range of pressure (Cottingham).
    check(std::fabs(cents(fh, fs)) < 20.0, "blown harder, the pitch barely moves", fmt("%+.1f c", cents(fh, fs)));
    check(bh > bs * 1.05, "blown harder, it's brighter", fmt("centroid %.0f against %.0f Hz", bh, bs));
}

void attack() {
    std::printf("attack\n");
    const double f = noteHz(57);
    Reeds r({{"attack", 60.0f}});
    r.d->noteOn(57, 100);
    r.play(0.6);
    // When each of the first four harmonics reaches -6 dB of its own steady level.
    int when[4];
    for (int h = 1; h <= 4; ++h) {
        const double steady = r.level(h * f, 0.4, 0.6);
        when[h - 1] = -1;
        for (int ms = 0; ms < 400; ms += 2) {
            if (r.level(h * f, ms / 1000.0, ms / 1000.0 + 0.012) > steady - 6.0) { when[h - 1] = ms; break; }
        }
    }
    check(when[0] >= 0 && when[0] <= when[1] && when[0] <= when[2] && when[0] <= when[3], "the fundamental speaks first",
          fmt("h1 %d, h2 %d, h3 %d, h4 %d ms", when[0], when[1], when[2], when[3]));
}

void threshold() {
    std::printf("threshold and release\n");
    Reeds faint({{"pressure", 0.0f}, {"velocity", 1.0f}});
    faint.d->noteOn(60, 1);
    faint.play(0.8);
    Reeds normal;
    normal.d->noteOn(60, 100);
    normal.play(0.8);
    const double quiet = faint.rms(0.4, 0.8), loud = normal.rms(0.4, 0.8);
    check(quiet < loud - 20.0, "blown too gently, a reed hardly speaks", fmt("%.1f dB against %.1f", quiet, loud));
    Reeds r({{"release", 50.0f}});
    r.d->noteOn(60, 100);
    r.play(0.5);
    r.d->noteOff(60);
    r.play(0.6);
    check(r.rms(0.9, 1.1) < r.rms(0.3, 0.5) - 50.0, "let go, it stops", fmt("%.1f dB after", r.rms(0.9, 1.1) - r.rms(0.3, 0.5)));
}

void perNote() {
    std::printf("pressure for one note\n");
    // One reed a note, so neither beats against its pair between the two stretches.
    Reeds r({{"register", 1.0f}});
    r.d->noteOn(60, 90);
    r.d->noteOn(67, 90);
    r.play(0.5);
    r.d->notePressure(67, 127);
    r.play(0.5);
    const double c4 = r.level(noteHz(60), 0.7, 1.0) - r.level(noteHz(60), 0.2, 0.5);
    const double g4 = r.level(noteHz(67), 0.7, 1.0) - r.level(noteHz(67), 0.2, 0.5);
    check(g4 > 1.0 && std::fabs(c4) < 0.5, "a note's own pressure blows that note harder", fmt("G4 %+.1f dB, C4 %+.1f dB", g4, c4));
}

/** Plays [note] on [knobs] and returns how far from it the strongest pitch near it is, cents. */
double playedCents(std::initializer_list<std::pair<const char *, float>> knobs, int note, double wheel = 0.0) {
    Reeds r(knobs);
    if (wheel != 0.0) r.d->pitchBend(static_cast<int16_t>(wheel * 8191.0));
    r.d->noteOn(static_cast<uint8_t>(note), 100);
    // Two seconds: a bend wanders as a player's does, and is in tune on average.
    r.play(2.5);
    const double f = noteHz(note);
    const double lo = wheel < 0.0 ? f * std::pow(2.0, -4.0 / 12.0) : f * std::pow(2.0, -0.8 / 12.0);
    // The pitch in 85 ms windows, and its median: a wandering bend is heard at the middle of its wander.
    std::vector<double> track;
    for (double t = 0.5; t + 0.085 <= 2.5; t += 0.085) track.push_back(cents(r.peak(lo, f * std::pow(2.0, 0.8 / 12.0), t, t + 0.085), f));
    std::sort(track.begin(), track.end());
    const double c = track[track.size() / 2];
    if (std::getenv("VERBOSE")) std::printf("    note %d: %+.1f c\n", note, c);
    return c;
}

/** The fundamental's level, dB, of [hz] in a note held with these knobs. */
double heard(std::initializer_list<std::pair<const char *, float>> knobs, int note, double hz) {
    Reeds r(knobs);
    r.d->noteOn(static_cast<uint8_t>(note), 100);
    r.play(1.0);
    return r.level(hz, 0.4, 1.0);
}

void bellows() {
    std::printf("bellows\n");
    // A 16' reed sounds an octave below the note, where an 8' has nothing;
    // a 4' has nothing at the note itself.
    const double f = noteHz(57);
    const double low = heard({{"register", 4.0f}}, 57, f / 2) - heard({{"register", 1.0f}}, 57, f / 2);
    const double high = heard({{"register", 1.0f}}, 57, f) - heard({{"register", 5.0f}}, 57, f);
    check(low > 30.0 && high > 30.0, "16' sounds an octave down, 4' an octave up", fmt("16' %+.0f dB an octave down, 8' %+.0f dB over a 4' at the note", low, high));
    // Two 8' reeds 20 cents apart beat at the difference: the fundamental's level swings at it.
    {
        Reeds r({{"register", 2.0f}, {"detune", 20.0f}});
        r.d->noteOn(69, 100);
        r.play(3.0);
        const double want = 440.0 * (std::pow(2.0, 20.0 / 1200.0) - 1.0);
        // The beat's rate: count the fundamental's level dips over two seconds in 20 ms windows.
        std::vector<double> lv;
        for (double t = 1.0; t < 3.0; t += 0.02) lv.push_back(r.level(440.0 * std::pow(2.0, 10.0 / 1200.0), t, t + 0.04));
        int dips = 0;
        for (size_t i = 1; i + 1 < lv.size(); ++i) dips += lv[i] < lv[i - 1] && lv[i] <= lv[i + 1] && lv[i] < *std::max_element(lv.begin(), lv.end()) - 6.0;
        check(std::fabs(dips / 2.0 - want) < 1.0, "musette: two reeds 20 cents apart beat at the difference", fmt("%.1f beats/s, wanted %.1f", dips / 2.0, want));
    }
    // The cassotto darkens the 16' and first 8'.
    Reeds open({{"register", 6.0f}}), boxed({{"register", 6.0f}, {"cassotto", 1.0f}});
    open.d->noteOn(57, 100);
    boxed.d->noteOn(57, 100);
    open.play(1.0);
    boxed.play(1.0);
    check(boxed.centroid(f / 2, 0.4, 1.0) < 0.97 * open.centroid(f / 2, 0.4, 1.0), "the cassotto is darker",
          fmt("centroid %.0f against %.0f Hz", boxed.centroid(f / 2, 0.4, 1.0), open.centroid(f / 2, 0.4, 1.0)));
    // Shaken 8 times a second, the level dips about 8 times a second.
    {
        Reeds r({{"shake", 8.0f}});
        r.d->noteOn(57, 100);
        r.play(3.0);
        std::vector<double> lv;
        for (double t = 1.0; t < 3.0; t += 0.01) lv.push_back(r.rms(t, t + 0.01));
        const double top = *std::max_element(lv.begin(), lv.end());
        int dips = 0;
        bool in = false;
        for (double l : lv) {
            if (!in && l < top - 10.0) { ++dips; in = true; }
            if (in && l > top - 4.0) in = false;
        }
        check(std::fabs(dips / 2.0 - 8.0) < 1.5, "the bellows shaken 8 times a second dip 8 times", fmt("%.1f a second", dips / 2.0));
    }
}

void pipes() {
    std::printf("pipes\n");
    // A reed in a closed pipe: the pipe rings at the note and its odd harmonics, so the 2nd is far down.
    {
        Reeds r({{"model", 9.0f}});
        r.d->noteOn(69, 100);
        r.play(1.0);
        const double h2 = r.level(880.0, 0.4, 1.0) - r.level(440.0, 0.4, 1.0);
        check(h2 < -20.0, "a sheng's pipe takes out the even harmonics", fmt("2nd %+.0f dB", h2));
    }
    // A sho key sounds its chord: kotsu, on A, is A4 E5 A5 B5 E6 F#6, tuned in fifths from A.
    {
        Reeds r({{"model", 10.0f}});
        r.d->noteOn(69, 100);
        r.play(2.0);
        const double a4 = r.level(440.0, 1.0, 2.0);
        double weakest = 0.0, worst = 0.0;
        const int pipes[] = {69, 76, 81, 83, 88, 90};
        for (int n : pipes) {
            const double pyth[12] = {-5.9, 7.8, -2.0, 11.7, 2.0, -7.8, 5.9, -3.9, 9.8, 0.0, -9.8, 3.9};
            const double f = noteHz(n) * std::pow(2.0, pyth[n % 12] / 1200.0);
            const double got = r.peak(f * 0.99, f * 1.01, 1.0, 2.0);
            weakest = std::min(weakest, r.level(f, 1.0, 2.0) - a4);
            if (std::fabs(cents(got, f)) > std::fabs(worst)) worst = cents(got, f);
        }
        check(weakest > -30.0 && std::fabs(worst) < 4.0, "a sho key sounds its chord, in fifths from A",
              fmt("quietest pipe %+.0f dB against A4, worst %+.1f c", weakest, worst));
    }
}

void harps() {
    std::printf("harmonicas\n");
    // Like a player on a C harp: C4 to C7, every note there's a way to play.
    double worst = 0.0;
    int at = 0;
    for (int note = 60; note <= 96; ++note) {
        const double c = playedCents({{"model", 0.0f}}, note);
        if (std::fabs(c) > std::fabs(worst)) { worst = c; at = note; }
    }
    check(std::fabs(worst) < 10.0, "a C harp like a player, every note in tune", fmt("worst %+.2f c at note %d", worst, at));
    worst = 0.0;
    for (int note = 55; note <= 91; note += 2) {
        const double c = playedCents({{"model", 0.0f}, {"harp key", 0.0f}}, note);
        if (std::fabs(c) > std::fabs(worst)) { worst = c; at = note; }
    }
    check(std::fabs(worst) < 10.0, "a G harp like a player, in tune", fmt("worst %+.2f c at note %d", worst, at));
    for (const int model : {0, 1, 2, 3}) {
        worst = 0.0;
        for (int note = 48; note <= 96; note += 5) {
            const double c = playedCents({{"model", static_cast<float>(model)}, {"playing", 1.0f}, {"detune", 0.0f}}, note);
            if (std::fabs(c) > std::fabs(worst)) { worst = c; at = note; }
        }
        check(std::fabs(worst) < 5.0, fmt("straight, model %d, in tune", model).c_str(), fmt("worst %+.2f c at note %d", worst, at));
    }
    // Hole 3 draw (B4) bends three semitones; the wheel full down with a range of 3 takes it there.
    const double bent = playedCents({{"model", 0.0f}, {"bend", 3.0f}}, 71, -1.0);
    check(bent < -250.0 && bent > -330.0, "hole 3 draw, the wheel bends it down with the mouth", fmt("%+.0f c", bent));
    Reeds open({{"model", 0.0f}}), cupped({{"model", 0.0f}, {"cup", 1.0f}});
    open.d->noteOn(67, 100);
    cupped.d->noteOn(67, 100);
    open.play(0.8);
    cupped.play(0.8);
    const double f = noteHz(67);
    check(cupped.centroid(f, 0.4, 0.8) < 0.8 * open.centroid(f, 0.4, 0.8), "cupped hands are darker",
          fmt("centroid %.0f against %.0f Hz", cupped.centroid(f, 0.4, 0.8), open.centroid(f, 0.4, 0.8)));
}

} // namespace

int main() {
    _mm_setcsr(_mm_getcsr() | 0x8040);
    std::printf("draw_test: the free reeds\n\n");
    tuning();
    pressure();
    attack();
    threshold();
    perNote();
    bellows();
    pipes();
    harps();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
