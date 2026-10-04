// Tongue, the jaw harp: in tune, its reeds on their chord, the mouth picking
// the harmonic a key asks for, legato keys leaving the harp alone, and
// patterns plucking on the grid.
//
//   tongue_test.sh
#include <engine/core/Constants.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/tongue/Tongue.h>

#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>
#include <xmmintrin.h>

using namespace acidulous;
using acidulous::machine::Tongue;

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

/** A Tongue played block by block, with the song's ticks running at [bpm]. */
struct Harp {
    std::unique_ptr<Machine> owned;
    Tongue *t;
    std::vector<float> out;
    double tick = 0.0;
    float bpm = 120.0f;

    explicit Harp(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Tongue"));
        t = static_cast<Tongue *>(owned.get());
        t->prepare(kRate);
        t->reset();
        ParamSet &p = t->params();
        p.jumpAll();
        for (const auto &k : knobs) set(k.first, k.second);
    }
    void set(const char *name, float value) {
        ParamSet &p = t->params();
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
        const double ticksPerBlock = kBlock * bpm * kPPQN / (60.0 * kRate);
        for (int b = 0; b < blocks; ++b) {
            const auto start = static_cast<int64_t>(tick);
            tick += ticksPerBlock;
            t->onBlock(start, static_cast<int64_t>(tick), bpm);
            t->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
        }
    }
    /** The level at [hz] over [from, to) seconds of what's played, dB, Hann windowed. */
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
    /** The loudest frequency within [lo, hi], to a hundredth of a hertz. */
    double peak(double lo, double hi, double from, double to) const {
        double best = lo, bestLevel = -1e9;
        for (double f = lo; f <= hi; f += 0.25) {
            const double l = level(f, from, to);
            if (l > bestLevel) { bestLevel = l; best = f; }
        }
        for (double f = best - 0.25; f <= best + 0.25; f += 0.01) {
            const double l = level(f, from, to);
            if (l > bestLevel) { bestLevel = l; best = f; }
        }
        return best;
    }
};

double cents(double a, double b) { return 1200.0 * std::log2(a / b); }
double noteHz(double n) { return 440.0 * std::pow(2.0, (n - 69.0) / 12.0); }

void tuning() {
    std::printf("tuning\n");
    double worst = 0.0, at = 0.0;
    for (int note : {43, 50, 55, 62, 69}) {
        Harp h;
        h.t->noteOn(static_cast<uint8_t>(note), 100);
        h.play(1.2);
        // The 4th harmonic, for resolution, over a steady stretch.
        const double f = noteHz(note) * 4.0;
        const double got = h.peak(f * 0.99, f * 1.01, 0.2, 1.2) / 4.0;
        const double c = cents(got, noteHz(note));
        if (std::fabs(c) > std::fabs(worst)) { worst = c; at = note; }
    }
    check(std::fabs(worst) < 3.0, "every drone in tune", fmt("worst %+.2f c at note %.0f", worst, at));
}

void chords() {
    std::printf("reeds\n");
    Harp h({{"reeds", 3}, {"chord", 4}}); // major
    h.t->noteOn(55, 100);
    h.play(1.2);
    double worst = 0.0;
    double quietest = 0.0;
    for (double step : {0.0, 4.0, 7.0}) {
        const double f = noteHz(55.0 + step);
        const double got = h.peak(f * 0.99, f * 1.01, 0.2, 1.2);
        worst = std::fmax(worst, std::fabs(cents(got, f)));
        quietest = std::fmin(quietest, h.level(got, 0.2, 1.2) - h.level(noteHz(55.0), 0.2, 1.2));
    }
    check(worst < 5.0, "three reeds on a major chord, each in tune", fmt("worst %.2f c", worst));
    check(quietest > -15.0, "each reed of the chord sounds", fmt("quietest %.1f dB against the root", quietest));

    // In turn: one note sounds one reed, three sound all three.
    Harp one({{"reeds", 3}, {"chord", 4}, {"order", 2}});
    one.t->noteOn(55, 100);
    one.play(0.6);
    const double alone = one.level(noteHz(62.0), 0.1, 0.6) - one.level(noteHz(55.0), 0.1, 0.6);
    one.t->noteOn(55, 100);
    one.play(0.3);
    one.t->noteOn(55, 100);
    one.play(0.6);
    const double together = one.level(noteHz(62.0), 1.0, 1.5) - one.level(noteHz(55.0), 1.0, 1.5);
    check(alone < -25.0 && together > -15.0, "in turn plucks one reed a note",
          fmt("the fifth %.1f dB after one note, %.1f after three", alone, together));
}

void mouth() {
    std::printf("mouth mode\n");
    // Drone D3; each key at a harmonic of it should bring that harmonic out.
    const double drone = noteHz(50.0);
    double worstLift = 1e9;
    int worstAt = 0;
    for (int n = 4; n <= 10; ++n) {
        const int key = static_cast<int>(std::lround(50.0 + 12.0 * std::log2(n)));
        Harp h({{"play", 1}, {"drone", 50}});
        h.t->noteOn(static_cast<uint8_t>(key), 100);
        h.play(0.8);
        const double f = drone * n;
        const double target = h.level(f, 0.2, 0.8);
        const double around = std::fmax(h.level(drone * (n - 1), 0.2, 0.8), h.level(drone * (n + 1), 0.2, 0.8));
        // The same drone with the mouth off, as the baseline.
        Harp flat({{"play", 1}, {"drone", 50}, {"depth", 0}});
        flat.t->noteOn(static_cast<uint8_t>(key), 100);
        flat.play(0.8);
        const double baseline = flat.level(f, 0.2, 0.8) - std::fmax(flat.level(drone * (n - 1), 0.2, 0.8), flat.level(drone * (n + 1), 0.2, 0.8));
        const double lift = (target - around) - baseline;
        if (lift < worstLift) { worstLift = lift; worstAt = n; }
    }
    check(worstLift > 6.0, "a key lifts its harmonic over its neighbours", fmt("worst %.1f dB more than the drone alone, harmonic %d", worstLift, worstAt));

    Harp keys({{"play", 1}, {"drone", 50}});
    keys.t->noteOn(74, 100); // the 4th harmonic
    keys.play(0.3);
    const float first = keys.t->pickTarget();
    keys.t->noteOn(78, 100); // the 5th, legato
    keys.play(0.3);
    const float second = keys.t->pickTarget();
    check(std::fabs(cents(first, drone * 4)) < 5.0 && std::fabs(cents(second, drone * 5)) < 5.0,
          "keys snap to the drone's harmonics", fmt("%.1f and %.1f Hz", first, second));
    check(keys.t->strikes() == 1, "a legato key moves the mouth without plucking", fmt("%d plucks", keys.t->strikes()));
    keys.t->noteOff(78);
    keys.play(0.1);
    check(std::fabs(cents(keys.t->pickTarget(), drone * 4)) < 5.0, "letting go goes back to the key still down",
          fmt("%.1f Hz", keys.t->pickTarget()));
    keys.t->noteOff(74);
    keys.t->noteOn(81, 100);
    keys.play(0.1);
    check(keys.t->strikes() == 2, "a key after silence plucks again", fmt("%d plucks", keys.t->strikes()));
}

void patterns() {
    std::printf("patterns\n");
    const double sixteenth = 60.0 / 120.0 / 4.0 * kRate;
    {
        Harp h({{"pattern", 2}}); // sixteenths
        h.t->noteOn(55, 100);
        h.play(2.05);
        const int n = h.t->strikes();
        double worst = 0.0;
        for (int i = 2; i < n; ++i) {
            const double gap = static_cast<double>(h.t->strikeTime(i) - h.t->strikeTime(i - 1));
            worst = std::fmax(worst, std::fabs(gap - sixteenth));
        }
        check(n >= 16 && n <= 18, "sixteenths at 120 pluck eight times a second", fmt("%d plucks in 2 s", n));
        check(worst <= 1.0, "each on its sixteenth, to the sample", fmt("worst %.1f samples off", worst));
        bool onGrid = true;
        for (int i = 1; i < n; ++i) {
            const double s = static_cast<double>(h.t->strikeTime(i)) / sixteenth;
            onGrid = onGrid && std::fabs(s - std::round(s)) * sixteenth <= 1.0;
        }
        check(onGrid, "on the song's grid, not the key's");
    }
    {
        Harp h({{"pattern", 3}}); // gallop: x . x x
        h.t->noteOn(55, 100);
        h.play(2.05);
        const int n = h.t->strikes();
        int longGaps = 0, shortGaps = 0;
        for (int i = 2; i < n; ++i) {
            const double gap = static_cast<double>(h.t->strikeTime(i) - h.t->strikeTime(i - 1)) / sixteenth;
            if (std::fabs(gap - 2.0) < 0.01) ++longGaps;
            else if (std::fabs(gap - 1.0) < 0.01) ++shortGaps;
        }
        // Two seconds at 120 is four gallops: a long gap and two short each.
        check(longGaps >= 3 && std::abs(shortGaps - 2 * longGaps) <= 2 && longGaps + shortGaps == n - 2, "the gallop goes long, short, short",
              fmt("%d long, %d short of %d", longGaps, shortGaps, n - 2));
    }
    {
        Harp plain({{"pattern", 2}}), split({{"pattern", 2}, {"ratchet", 1}});
        plain.t->noteOn(55, 100);
        split.t->noteOn(55, 100);
        plain.play(2.05);
        split.play(2.05);
        check(split.t->strikes() > plain.t->strikes() + 6, "ratchet splits steps into quick plucks",
              fmt("%d plucks against %d", split.t->strikes(), plain.t->strikes()));
    }
    {
        Harp h({{"pattern", 2}});
        h.t->noteOn(55, 100);
        h.play(0.5);
        h.t->noteOff(55);
        const int at = h.t->strikes();
        h.play(1.0);
        check(h.t->strikes() == at, "the pattern stops with the key", fmt("%d plucks after", h.t->strikes() - at));
    }
    {
        Harp h({{"pattern", 3}, {"play", 1}, {"drone", 50}});
        h.t->noteOn(74, 100);
        h.play(1.0);
        h.t->noteOn(78, 100);
        h.play(1.0);
        check(h.t->strikes() >= 10, "in mouth mode the pattern plucks the drone while keys move the mouth",
              fmt("%d plucks", h.t->strikes()));
    }
}

} // namespace

int main() {
    _mm_setcsr(_mm_getcsr() | 0x8040);
    std::printf("tongue_test: the jaw harp\n\n");
    tuning();
    chords();
    mouth();
    patterns();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
