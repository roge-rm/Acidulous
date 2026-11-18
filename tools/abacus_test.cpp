// Abacus, the additive machine: the first partial's pitch across the
// keyboard, the laws' mode ratios, odd, comb and peak doing what they say,
// nothing between 0.45 of the sample rate and Nyquist and no aliases,
// damping taking the high partials first, the partial count, spread 0 mono
// and spread 1 wide, and a released note going to sleep.
//
//   abacus_test.sh
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/abacus/Abacus.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <utility>
#include <vector>

using namespace acidulous;
using acidulous::machine::Abacus;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr double kPi = 3.141592653589793;
int failures = 0, checks = 0;

std::string fmt(const char *f, ...) {
    char buf[256];
    va_list a;
    va_start(a, f);
    std::vsnprintf(buf, sizeof(buf), f, a);
    va_end(a);
    return buf;
}

void check(bool ok, const std::string &what, const std::string &detail = "") {
    ++checks;
    if (!ok) ++failures;
    std::printf("  %s %-60s %s\n", ok ? "ok  " : "FAIL", what.c_str(), detail.c_str());
}

double noteHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }

/** An Abacus played block by block, both sides kept. */
struct Play {
    std::unique_ptr<Machine> owned;
    Abacus *a;
    std::vector<float> left, right;

    explicit Play(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
        owned.reset(MachineRegistry::create("Abacus"));
        a = static_cast<Abacus *>(owned.get());
        a->prepare(kRate);
        for (const auto &k : knobs) set(k.first, k.second);
        a->params().jumpAll();
        a->reset();
    }
    void set(const char *name, float value) {
        ParamSet &p = a->params();
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
            a->onBlock(0, 0, 120.0f);
            a->render(L, R, kBlock);
            left.insert(left.end(), L, L + kBlock);
            right.insert(right.end(), R, R + kBlock);
        }
    }
};

/** The Hann-windowed level at [hz] over [from, to) seconds of [x], dB of a full-scale sine. */
double level(const std::vector<float> &x, double hz, double from, double to) {
    const auto a = static_cast<size_t>(from * kRate), b = std::min(x.size(), static_cast<size_t>(to * kRate));
    double re = 0.0, im = 0.0, wsum = 0.0;
    for (size_t i = a; i < b; ++i) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i - a) / static_cast<double>(b - a));
        const double ph = 2.0 * kPi * hz * static_cast<double>(i) / kRate;
        re += x[i] * w * std::cos(ph);
        im -= x[i] * w * std::sin(ph);
        wsum += w;
    }
    return 20.0 * std::log10(2.0 * std::sqrt(re * re + im * im) / wsum + 1e-15);
}

/** The frequency of the strongest peak within [cents] of [hz], by a golden-section search on the level. */
double peakNear(const std::vector<float> &x, double hz, double cents, double from, double to) {
    double lo = hz * std::pow(2.0, -cents / 1200.0), hi = hz * std::pow(2.0, cents / 1200.0);
    // A coarse scan first so the search starts on the right lobe.
    double best = lo, bestL = -1e9;
    for (int i = 0; i <= 40; ++i) {
        const double f = lo + (hi - lo) * i / 40.0;
        const double l = level(x, f, from, to);
        if (l > bestL) { bestL = l; best = f; }
    }
    const double step = (hi - lo) / 40.0;
    lo = std::max(lo, best - step);
    hi = std::min(hi, best + step);
    const double g = 0.6180339887;
    double c = hi - g * (hi - lo), d = lo + g * (hi - lo);
    double lc = level(x, c, from, to), ld = level(x, d, from, to);
    for (int i = 0; i < 40; ++i) {
        if (lc > ld) { hi = d; d = c; ld = lc; c = hi - g * (hi - lo); lc = level(x, c, from, to); }
        else { lo = c; c = d; lc = ld; d = lo + g * (hi - lo); ld = level(x, d, from, to); }
    }
    return 0.5 * (lo + hi);
}

double centsOff(double got, double want) { return 1200.0 * std::log2(got / want); }

void pitch() {
    std::printf("pitch of the first partial\n");
    double worst = 0.0;
    int worstNote = 0;
    for (int note = 21; note <= 127; note += 9) {
        Play p({{"slope", -24.0f}});
        p.a->noteOn(static_cast<uint8_t>(note), 100);
        p.play(1.2);
        const double want = noteHz(note);
        const double got = peakNear(p.left, want, 60.0, 0.2, 1.2);
        const double off = std::fabs(centsOff(got, want));
        if (off > worst) { worst = off; worstNote = note; }
    }
    check(worst < 3.0, "partial 1 is within 3 cents from note 21 to 120", fmt("worst %.2f cents at note %d", worst, worstNote));
    // The bends and knobs move it by what they say.
    {
        Play p({{"slope", -24.0f}, {"transpose", 7.0f}, {"fine", 25.0f}});
        p.a->noteOn(48, 100);
        p.play(1.2);
        const double want = noteHz(48 + 7.25);
        const double off = centsOff(peakNear(p.left, want, 60.0, 0.2, 1.2), want);
        check(std::fabs(off) < 3.0, "transpose and fine move it by what they say", fmt("%.2f cents out", off));
    }
}

void laws() {
    std::printf("laws\n");
    struct Law {
        const char *name;
        int law;
        std::vector<double> want; // partials 2 and up, against the first
    };
    // Textbook mode ratios: a free bar's (b2/b1)^2, a bell's from its hum, a
    // square plate's (m^2 + k^2) / 2.
    const double b = 0.0003; // the string's inharmonicity at stretch 0
    auto stiff = [&](int n) { return n * std::sqrt((1.0 + b * n * n) / (1.0 + b)); };
    const Law kLaws[] = {
        {"harmonic", 0, {2.0, 3.0, 4.0, 5.0, 6.0}},
        {"string", 1, {stiff(2), stiff(3), stiff(4), stiff(8), stiff(16)}},
        {"bar", 2, {2.7565, 5.4039, 8.9330, 13.3443}},
        {"bell", 3, {2.0, 2.4, 3.0, 4.0}},
        {"plate", 4, {2.5, 4.0, 5.0, 6.5}},
    };
    for (const Law &l : kLaws) {
        Play p({{"slope", 0.0f}, {"law", static_cast<float>(l.law)}});
        p.a->noteOn(36, 100);
        p.play(1.2);
        const double f0 = noteHz(36);
        double worst = 0.0;
        std::string at;
        // The string's eighth and sixteenth partial are where the stretch shows.
        const int index[] = {2, 3, 4, 8, 16};
        for (size_t i = 0; i < l.want.size(); ++i) {
            const double want = f0 * l.want[i];
            const double got = peakNear(p.left, want, 40.0, 0.2, 1.2);
            const double off = std::fabs(centsOff(got, want));
            if (off > worst) {
                worst = off;
                at = l.law == 1 ? fmt("partial %d", index[i]) : fmt("mode %zu", i + 2);
            }
        }
        check(worst < 3.0, std::string("the ") + l.name + " law's modes sit at its ratios", fmt("worst %.2f cents at %s", worst, at.c_str()));
    }
    // Stretch -1 takes a modal law back to harmonic.
    {
        Play p({{"slope", 0.0f}, {"law", 2.0f}, {"stretch", -1.0f}});
        p.a->noteOn(36, 100);
        p.play(1.2);
        const double want = noteHz(36) * 3.0;
        const double off = centsOff(peakNear(p.left, want, 40.0, 0.2, 1.2), want);
        check(std::fabs(off) < 3.0, "a bar at stretch -1 is harmonic", fmt("partial 3 %.2f cents out", off));
    }
}

void spectrum() {
    std::printf("spectrum\n");
    const double f0 = noteHz(36);
    auto levels = [&](std::initializer_list<std::pair<const char *, float>> knobs, int upTo) {
        Play p(knobs);
        p.a->noteOn(36, 127);
        p.play(1.0);
        std::vector<double> out(static_cast<size_t>(upTo) + 1, 0.0);
        for (int n = 1; n <= upTo; ++n) out[static_cast<size_t>(n)] = level(p.left, f0 * n, 0.2, 1.0);
        return out;
    };
    // The slope: -6.02 dB an octave is a level of 1/n.
    {
        const auto l = levels({{"slope", -6.0206f}}, 8);
        check(std::fabs((l[2] - l[1]) + 6.02) < 0.3 && std::fabs((l[8] - l[1]) + 18.06) < 0.3,
              "slope -6 dB an octave is a level of 1/n", fmt("2nd %.2f, 8th %.2f dB", l[2] - l[1], l[8] - l[1]));
    }
    // Odd +1 is odd partials only, -1 even only.
    {
        const auto plus = levels({{"slope", 0.0f}, {"odd", 1.0f}}, 6);
        const auto minus = levels({{"slope", 0.0f}, {"odd", -1.0f}}, 6);
        const double evenGone = std::max({plus[2], plus[4], plus[6]}) - plus[1];
        const double oddKept = std::min(plus[3], plus[5]) - plus[1];
        check(evenGone < -80.0 && oddKept > -0.5, "odd +1 keeps the odd partials only", fmt("even %.1f dB, odd %.1f dB", evenGone, oddKept));
        const double oddGone = std::max({minus[1], minus[3], minus[5]}) - minus[2];
        const double evenKept = std::min(minus[4], minus[6]) - minus[2];
        check(oddGone < -80.0 && evenKept > -0.5, "odd -1 keeps the even partials only", fmt("odd %.1f dB, even %.1f dB", oddGone, evenKept));
    }
    // The comb takes out every nth partial.
    {
        const auto l = levels({{"slope", 0.0f}, {"comb", 1.0f}, {"combn", 3.0f}}, 7);
        const double gone = std::max(l[3], l[6]) - l[1];
        const double kept = std::min({l[2], l[4], l[5], l[7]}) - l[1];
        check(gone < -80.0 && kept > -0.5, "comb 1 at 3 takes out every third partial", fmt("3rd and 6th %.1f dB, others %.1f dB", gone, kept));
    }
    // The peak lifts the partials at its centre by its gain and leaves the far ones.
    {
        const auto flat = levels({{"slope", 0.0f}}, 32);
        const auto peak = levels({{"slope", 0.0f}, {"peak", static_cast<float>(f0 * 16)}, {"peakgain", 18.0f}, {"peakwidth", 0.5f}}, 32);
        const double centre = (peak[16] - peak[1]) - (flat[16] - flat[1]);
        const double far = (peak[2] - peak[1]) - (flat[2] - flat[1]);
        check(std::fabs(centre - 18.0) < 0.5 && std::fabs(far) < 0.2, "the peak lifts its centre by its gain and not the far partials",
              fmt("centre +%.2f dB, three octaves off %+.2f dB", centre, far));
        // Half the gain half a width away (a quarter octave either side).
        const double half = (peak[19] - peak[1]) - (flat[19] - flat[1]);
        const double want = 18.0 * std::pow(2.0, -std::pow(std::log2(19.0 / 16.0) / 0.25, 2.0));
        check(std::fabs(half - want) < 0.5, "the peak falls off with its width", fmt("19th +%.2f dB, want +%.2f", half, want));
    }
    // The partial count.
    {
        const auto l16 = levels({{"slope", 0.0f}, {"partials", 0.0f}}, 18);
        check(l16[16] - l16[1] > -0.5 && l16[17] - l16[1] < -80.0, "partials 16 plays sixteen",
              fmt("16th %.1f dB, 17th %.1f dB", l16[16] - l16[1], l16[17] - l16[1]));
    }
    // The morph moves from A to B.
    {
        const auto a = levels({{"slope", -12.0f}, {"bslope", 12.0f}, {"morph", 0.0f}}, 4);
        const auto bb = levels({{"slope", -12.0f}, {"bslope", 12.0f}, {"morph", 1.0f}}, 4);
        check(std::fabs((a[4] - a[1]) + 24.0) < 0.5 && std::fabs(bb[4] - bb[1]) < 0.5, "morph 1 is spectrum B",
              fmt("4th at A %.1f dB, at B %.1f dB", a[4] - a[1], bb[4] - bb[1]));
    }
}

void nyquist() {
    std::printf("Nyquist\n");
    double worst = -300.0;
    std::string at;
    for (int note : {84, 96, 100, 105, 110, 118, 124}) {
        Play p({{"slope", 0.0f}});
        p.a->noteOn(static_cast<uint8_t>(note), 127);
        p.play(0.6);
        const double f0 = noteHz(note);
        const double ref = level(p.left, f0, 0.1, 0.6);
        // Every partial above 0.45 fs, where it would sound, and where it would fold back to.
        for (int n = 1; n <= 64; ++n) {
            const double f = f0 * n;
            if (f < 0.45 * kRate) continue;
            std::vector<double> places;
            if (f < 0.5 * kRate) places.push_back(f);
            double alias = std::fmod(f, static_cast<double>(kRate));
            if (alias > 0.5 * kRate) alias = kRate - alias;
            places.push_back(alias);
            for (double hz : places) {
                // Not where a real partial sits.
                bool real = false;
                for (int k = 1; k * f0 < 0.45 * kRate; ++k) real = real || std::fabs(k * f0 - hz) < 30.0;
                if (real || hz < 40.0) continue;
                const double l = level(p.left, hz, 0.1, 0.6) - ref;
                if (l > worst) { worst = l; at = fmt("note %d partial %d at %.0f Hz", note, n, hz); }
            }
        }
    }
    check(worst < -100.0, "nothing over 0.45 fs sounds or folds back", fmt("worst %.1f dB, %s", worst, at.c_str()));
}

void damping() {
    std::printf("damping\n");
    const double f0 = noteHz(36);
    auto drops = [&](float damp) {
        Play p({{"slope", 0.0f}, {"damp", damp}, {"decay", 2.0f}});
        p.a->noteOn(36, 127);
        p.play(1.0);
        double d[3];
        const int ns[3] = {1, 2, 8};
        for (int i = 0; i < 3; ++i) d[i] = level(p.left, f0 * ns[i], 0.6, 0.9) - level(p.left, f0 * ns[i], 0.05, 0.35);
        return std::vector<double>{d[0], d[1], d[2]};
    };
    const auto even = drops(0.0f);
    check(std::fabs(even[0] - even[2]) < 0.5 && even[0] < -1.0, "damp 0 decays every partial alike",
          fmt("1st %.1f, 8th %.1f dB", even[0], even[2]));
    const auto high = drops(1.0f);
    check(high[2] < high[1] - 20.0 && high[1] < high[0] - 5.0, "damp 1 takes the high partials first",
          fmt("1st %.1f, 2nd %.1f, 8th %.1f dB", high[0], high[1], high[2]));
    // Decay 30 holds.
    {
        Play p({{"slope", 0.0f}, {"damp", 1.0f}});
        p.a->noteOn(36, 127);
        p.play(1.0);
        const double d = level(p.left, f0 * 8, 0.6, 0.9) - level(p.left, f0 * 8, 0.05, 0.35);
        check(std::fabs(d) < 0.1, "decay 30 holds", fmt("8th %.2f dB", d));
    }
}

void spread() {
    std::printf("spread\n");
    const double f0 = noteHz(48);
    {
        Play p({{"spread", 0.0f}, {"shimmer", 1.0f}});
        p.a->noteOn(48, 100);
        p.a->noteOn(55, 100);
        p.play(0.5);
        check(p.left == p.right, "spread 0 is mono, bit for bit", "");
    }
    {
        Play p({{"slope", 0.0f}, {"spread", 1.0f}, {"spreadmode", 0.0f}});
        p.a->noteOn(48, 100);
        p.play(0.6);
        const double d2 = level(p.right, f0 * 2, 0.1, 0.6) - level(p.left, f0 * 2, 0.1, 0.6);
        const double d3 = level(p.left, f0 * 3, 0.1, 0.6) - level(p.right, f0 * 3, 0.1, 0.6);
        const double d1 = level(p.left, f0, 0.1, 0.6) - level(p.right, f0, 0.1, 0.6);
        check(d2 > 60.0 && d3 > 60.0 && std::fabs(d1) < 0.01, "spread 1 odd/even: even right, odd left, the first in the middle",
              fmt("2nd R-L %.0f dB, 3rd L-R %.0f dB, 1st %.2f", d2, d3, d1));
        double lr = 0, ll = 0, rr = 0;
        for (size_t i = 0; i < p.left.size(); ++i) {
            lr += double(p.left[i]) * p.right[i];
            ll += double(p.left[i]) * p.left[i];
            rr += double(p.right[i]) * p.right[i];
        }
        const double corr = lr / std::sqrt(ll * rr);
        check(corr < 0.5, "spread 1 is wide", fmt("correlation %.2f", corr));
    }
    {
        Play p({{"slope", 0.0f}, {"spread", 1.0f}, {"spreadmode", 2.0f}});
        p.a->noteOn(48, 100);
        p.play(0.6);
        const double low = level(p.left, f0 * 2, 0.1, 0.6) - level(p.right, f0 * 2, 0.1, 0.6);
        const double high = level(p.right, f0 * 30, 0.1, 0.6) - level(p.left, f0 * 30, 0.1, 0.6);
        check(low > 5.0 && high > 5.0, "spread 1 low-high: low left, high right", fmt("2nd L-R %.1f dB, 30th R-L %.1f dB", low, high));
    }
    {
        Play p({{"slope", 0.0f}, {"spread", 1.0f}, {"spreadmode", 1.0f}});
        p.a->noteOn(48, 100);
        p.play(0.6);
        int left = 0, right = 0;
        for (int n = 2; n <= 32; ++n) {
            const double d = level(p.left, f0 * n, 0.1, 0.6) - level(p.right, f0 * n, 0.1, 0.6);
            if (d > 3.0) ++left;
            if (d < -3.0) ++right;
        }
        check(left >= 5 && right >= 5, "spread 1 scatter puts partials both ways", fmt("%d left, %d right", left, right));
    }
}

void sleep() {
    std::printf("sleep\n");
    Play p({{"release", 0.05f}});
    p.a->noteOn(60, 100);
    p.play(0.3);
    p.a->noteOff(60);
    p.play(1.0);
    float peak = 0.0f;
    for (size_t i = p.left.size() - kRate / 2; i < p.left.size(); ++i) peak = std::max(peak, std::fabs(p.left[i]));
    check(p.a->activeVoices() == 0 && peak == 0.0f, "a released note retires and the machine sleeps",
          fmt("%d voices, peak %.2g", p.a->activeVoices(), peak));
    // A fully damped held note retires too.
    Play q({{"decay", 0.05f}});
    q.a->noteOn(60, 100);
    q.play(2.0);
    check(q.a->activeVoices() == 0, "a held note that has decayed away retires", fmt("%d voices", q.a->activeVoices()));
}

} // namespace

int main() {
    pitch();
    laws();
    spectrum();
    nyquist();
    damping();
    spread();
    sleep();
    std::printf("\n%d of %d checks passed\n", checks - failures, checks);
    if (failures == 0) std::printf("abacus: all passed\n");
    return failures == 0 ? 0 : 1;
}
