// Tests Venue, the convolution effect, and the convolver under it: that it
// convolves exactly, without latency, the same however the blocks are cut,
// and that impulses can be swapped while it plays.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/dsp/Convolver.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/effect/Venue.h>
#include "venue_impulse.h"

using namespace acidulous;
using effect::VenueImpulse;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-62s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

std::string num(double v) {
    char b[32];
    snprintf(b, sizeof b, "%.3g", v);
    return b;
}

constexpr int32_t kRate = kSampleRate;

struct Stereo {
    std::vector<float> l, r;
};

std::vector<float> noise(size_t n, uint32_t seed, float amp = 0.5f) {
    std::vector<float> x(n);
    uint32_t s = seed;
    for (auto &v : x) {
        s = s * 1664525u + 1013904223u;
        v = amp * (static_cast<float>(s >> 9) * (2.0f / 8388608.0f) - 1.0f);
    }
    return x;
}

std::unique_ptr<Effect> make(std::initializer_list<std::pair<const char *, float>> set = {}) {
    std::unique_ptr<Effect> fx(EffectRegistry::create("Venue"));
    fx->prepare(kRate);
    for (const auto &[name, value] : set) {
        const int32_t i = fx->params().indexOf(name);
        if (i < 0) {
            printf("  FAIL Venue has no parameter %s\n", name);
            ++failures;
            continue;
        }
        fx->params().set(i, fx->params().def(i).unmap(value));
    }
    fx->params().jumpAll();
    return fx;
}

/** Hands the effect an impulse. [settle]: and resets, as a render does first, so it's in at once. */
void mount(Effect &fx, std::unique_ptr<VenueImpulse> imp, bool settle = true) {
    delete static_cast<VenueImpulse *>(fx.swapObject(0, imp.release()));
    if (settle) fx.reset();
}

std::unique_ptr<VenueImpulse> impulseOf(const std::vector<float> &l, const std::vector<float> *r = nullptr) {
    return effect::buildVenueImpulse(l.data(), r ? r->data() : nullptr, static_cast<int32_t>(l.size()), kRate,
                                     effect::VenueShape{});
}

/** Runs [l]/[r] (r empty: mono in) through in blocks of [block]. Returns whether it came out stereo. */
bool run(Effect &fx, Stereo &s, bool stereoIn, int32_t block = kBlockFrames) {
    if (s.r.size() != s.l.size()) s.r.assign(s.l.size(), 0.0f);
    bool stereo = false;
    for (size_t at = 0; at < s.l.size(); at += static_cast<size_t>(block)) {
        const auto n = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(block), s.l.size() - at));
        fx.onBlock(0, 0, 120.0f);
        stereo = fx.run(s.l.data() + at, s.r.data() + at, n, stereoIn);
        if (!stereo && !stereoIn) std::memcpy(s.r.data() + at, s.l.data() + at, sizeof(float) * static_cast<size_t>(n));
    }
    return stereo;
}

/** Direct convolution in double at sample [i]. */
double direct(const std::vector<float> &h, const std::vector<float> &x, size_t i) {
    double y = 0.0;
    const size_t taps = std::min(h.size(), i + 1);
    for (size_t k = 0; k < taps; ++k) y += static_cast<double>(h[k]) * x[i - k];
    return y;
}

double peakOf(const std::vector<float> &x, size_t from = 0, size_t to = SIZE_MAX) {
    double p = 0.0;
    for (size_t i = from; i < std::min(to, x.size()); ++i) p = std::max(p, static_cast<double>(std::fabs(x[i])));
    return p;
}
double rmsOf(const std::vector<float> &x, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to; ++i) e += static_cast<double>(x[i]) * x[i];
    return std::sqrt(e / static_cast<double>(to - from));
}
bool finite(const Stereo &s) {
    for (size_t i = 0; i < s.l.size(); ++i)
        if (!std::isfinite(s.l[i]) || !std::isfinite(s.r[i])) return false;
    return true;
}

// --- the convolver alone ------------------------------------------------------------

void convolver() {
    printf("- convolver\n");
    // Every stage boundary and then some: 32, 512, 4096, 32768 taps.
    for (int32_t len : {1, 31, 32, 33, 511, 513, 4095, 4097, 32767, 32769, 100000}) {
        const auto hl = noise(static_cast<size_t>(len), 7u + static_cast<uint32_t>(len), 0.05f);
        const auto hr = noise(static_cast<size_t>(len), 99u + static_cast<uint32_t>(len), 0.05f);
        const size_t n = 140000;
        const auto xl = noise(n, 3), xr = noise(n, 4);
        dsp::Convolver c(hl.data(), hr.data(), len);
        std::vector<float> yl(n), yr(n);
        // Uneven blocks, as a modulated slot cuts them.
        const int32_t cuts[] = {64, 7, 64, 1, 13, 8, 64};
        size_t at = 0;
        for (int k = 0; at < n; ++k) {
            const auto m = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(cuts[k % 7]), n - at));
            c.process(xl.data() + at, xr.data() + at, yl.data() + at, yr.data() + at, m);
            at += static_cast<size_t>(m);
        }
        double worst = 0.0, peak = 0.0;
        for (size_t i = 0; i < n; i += 61) {
            const double l = direct(hl, xl, i), r = direct(hr, xr, i);
            worst = std::max({worst, std::fabs(l - yl[i]), std::fabs(r - yr[i])});
            peak = std::max(peak, std::fabs(l));
        }
        ok("matches direct convolution, " + std::to_string(len) + " taps", worst <= 2e-6 * std::max(1.0, peak),
           "worst " + num(worst) + " of peak " + num(peak));
    }
    {
        // Cut differently, the same bits.
        const auto h = noise(60000, 5, 0.05f);
        const auto x = noise(100000, 6);
        std::vector<float> a(x.size()), b(x.size()), junk(x.size());
        dsp::Convolver c(h.data(), nullptr, static_cast<int32_t>(h.size()));
        c.process(x.data(), x.data(), a.data(), junk.data(), static_cast<int32_t>(x.size()));
        c.reset();
        for (size_t at = 0; at < x.size(); at += 37) {
            const auto m = static_cast<int32_t>(std::min<size_t>(37, x.size() - at));
            c.process(x.data() + at, x.data() + at, b.data() + at, junk.data() + at, m);
        }
        ok("one call or blocks of 37 after a reset: identical", std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0);
    }
}

// --- the effect -----------------------------------------------------------------------

void venue() {
    printf("- venue\n");
    const size_t n = 96000;
    {
        auto fx = make({{"mix", 0.3f}});
        Stereo s{noise(n, 1), noise(n, 2)};
        const Stereo dry = s;
        run(*fx, s, true);
        ok("no impulse: the track passes untouched", s.l == dry.l && s.r == dry.r);
    }
    {
        auto fx = make({{"mix", 1.0f}});
        mount(*fx, impulseOf({1.0f}));
        Stereo s{noise(n, 1), noise(n, 2)};
        const Stereo dry = s;
        run(*fx, s, true);
        double worst = 0.0;
        for (size_t i = 0; i < n; ++i) worst = std::max({worst, (double)std::fabs(s.l[i] - dry.l[i]), (double)std::fabs(s.r[i] - dry.r[i])});
        ok("a unit impulse returns the input, no latency", worst < 1e-6, "worst " + num(worst));
    }
    {
        // A random second of impulse against direct convolution, through the effect.
        const auto hl = noise(48000, 11, 0.01f), hr = noise(48000, 12, 0.01f);
        auto fx = make({{"mix", 1.0f}});
        mount(*fx, impulseOf(hl, &hr));
        const auto x = noise(120000, 13);
        Stereo s{x, noise(120000, 14)};
        const Stereo in = s;
        run(*fx, s, true);
        double worst = 0.0, peak = 0.0;
        for (size_t i = 0; i < x.size(); i += 53) {
            const double l = direct(hl, in.l, i), r = direct(hr, in.r, i);
            worst = std::max({worst, std::fabs(l - s.l[i]), std::fabs(r - s.r[i])});
            peak = std::max(peak, std::fabs(l));
        }
        ok("a random 1 s stereo impulse matches direct convolution", worst <= 2e-6 * std::max(1.0, peak),
           "worst " + num(worst) + " of peak " + num(peak));
    }
    {
        // Mono impulse, stereo track: each side through the same impulse.
        const auto h = noise(3000, 21, 0.05f);
        auto fx = make({{"mix", 1.0f}});
        mount(*fx, impulseOf(h));
        Stereo s{noise(20000, 22), noise(20000, 23)};
        const Stereo in = s;
        const bool st = run(*fx, s, true);
        double worst = 0.0;
        for (size_t i = 0; i < s.l.size(); i += 17) {
            worst = std::max({worst, std::fabs(direct(h, in.l, i) - s.l[i]), std::fabs(direct(h, in.r, i) - s.r[i])});
        }
        ok("mono impulse on a stereo track: each side convolved", st && worst < 1e-5, "worst " + num(worst));
    }
    {
        // Stereo impulse, mono track: comes out stereo.
        const auto hl = noise(3000, 31, 0.05f), hr = noise(3000, 32, 0.05f);
        auto fx = make({{"mix", 1.0f}});
        mount(*fx, impulseOf(hl, &hr));
        Stereo s{noise(20000, 33), {}};
        const auto in = s.l;
        const bool st = run(*fx, s, false);
        double worst = 0.0;
        for (size_t i = 0; i < s.l.size(); i += 17) {
            worst = std::max({worst, std::fabs(direct(hl, in, i) - s.l[i]), std::fabs(direct(hr, in, i) - s.r[i])});
        }
        ok("stereo impulse on a mono track: stereo out", st && worst < 1e-5, "worst " + num(worst));
    }
    {
        // Pre-delay: 10 ms is 480 samples, exactly.
        auto fx = make({{"mix", 1.0f}, {"predelay", 10.0f}});
        mount(*fx, impulseOf({1.0f}));
        Stereo s{noise(n, 41), noise(n, 42)};
        const Stereo in = s;
        run(*fx, s, true);
        double worst = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const float l = i >= 480 ? in.l[i - 480] : 0.0f;
            worst = std::max(worst, (double)std::fabs(s.l[i] - l));
        }
        ok("predelay 10 ms delays the wet by 480 samples", worst < 1e-5, "worst " + num(worst));
    }
    {
        auto fx = make({{"mix", 0.0f}});
        mount(*fx, venue_harness::room(1.0));
        Stereo s{noise(n, 51), noise(n, 52)};
        const Stereo dry = s;
        run(*fx, s, true);
        ok("mix 0 is bit for bit dry", std::memcmp(s.l.data(), dry.l.data(), n * 4) == 0 &&
                                           std::memcmp(s.r.data(), dry.r.data(), n * 4) == 0);
    }
    {
        // Reset puts it back: the same input after a reset, bit for bit.
        auto fx = make({{"mix", 0.5f}, {"predelay", 23.0f}, {"lowcut", 200.0f}, {"highcut", 5000.0f}, {"width", 1.5f}});
        mount(*fx, venue_harness::room(1.5));
        auto pass = [&] {
            Stereo s{noise(n, 61), noise(n, 62)};
            run(*fx, s, true);
            return s;
        };
        fx->reset();
        fx->params().jumpAll();
        const Stereo a = pass();
        Stereo other{noise(30000, 63), noise(30000, 64)};
        run(*fx, other, true);
        fx->reset();
        fx->params().jumpAll();
        const Stereo b = pass();
        ok("after a reset it plays the same bits again", a.l == b.l && a.r == b.r);
    }
    {
        // Level: noise through a made-up room at full wet keeps its level.
        auto fx = make({{"mix", 1.0f}});
        mount(*fx, venue_harness::room(2.0));
        Stereo s{noise(4 * 48000, 71), noise(4 * 48000, 72)};
        const double before = rmsOf(s.l, 96000, s.l.size());
        run(*fx, s, true);
        const double db = 20.0 * std::log10(rmsOf(s.l, 96000, s.l.size()) / before);
        ok("a normalised impulse keeps noise's level at full wet", std::fabs(db) < 1.0, num(db) + " dB");
    }
    {
        // Every knob up, and a loud impulse: finite and bounded.
        auto fx = make({{"mix", 1.0f}, {"predelay", 250.0f}, {"lowcut", 2000.0f}, {"highcut", 1000.0f}, {"width", 2.0f},
                        {"gain", 0.0f}});
        mount(*fx, venue_harness::room(3.0));
        Stereo s{noise(5 * 48000, 81, 0.9f), noise(5 * 48000, 82, 0.9f)};
        run(*fx, s, true);
        ok("every knob up: finite, no NaN, bounded", finite(s) && peakOf(s.l) < 8.0, "peak " + num(peakOf(s.l)));
    }
}

// --- shaping and swapping --------------------------------------------------------------

/** The wet impulse response of a shape, at full wet. */
std::vector<float> responseOf(const effect::VenueShape &shape, size_t n) {
    std::vector<float> l, r;
    venue_harness::synthRoom(2.0, false, 9, l, r);
    auto imp = effect::buildVenueImpulse(l.data(), nullptr, static_cast<int32_t>(l.size()), kRate, shape);
    auto fx = make({{"mix", 1.0f}});
    mount(*fx, std::move(imp));
    Stereo s{std::vector<float>(n, 0.0f), {}};
    s.l[0] = 1.0f;
    run(*fx, s, false);
    return s.l;
}

void shaping() {
    printf("- shaping\n");
    const size_t n = 5 * 48000;
    const auto plain = responseOf({}, n);
    {
        effect::VenueShape sh;
        sh.length = 0.5f;
        const auto cut = responseOf(sh, n);
        ok("length 0.5 s: nothing after half a second", peakOf(cut, 24000) < 1e-6 && peakOf(cut, 0, 24000) > 0.0,
           "after " + num(peakOf(cut, 24000)));
        ok("length 0.5 s: the start as it was", std::equal(cut.begin(), cut.begin() + 12000, plain.begin(),
                                                           [](float a, float b) { return std::fabs(a - b) < 1e-6f; }));
    }
    {
        effect::VenueShape sh;
        sh.decay = 0.3f;
        const auto shorter = responseOf(sh, n);
        const double was = rmsOf(plain, 48000, 72000), now = rmsOf(shorter, 48000, 72000);
        ok("decay 0.3 s: the tail at 1 s is at least 60 dB down", 20.0 * std::log10(now / was) < -60.0,
           num(20.0 * std::log10(now / was + 1e-30)) + " dB");
    }
    {
        effect::VenueShape sh;
        sh.stretch = 2.0f;
        const auto longer = responseOf(sh, n);
        ok("stretch 2: rings twice as long", peakOf(longer, 110000, 140000) > 1e-5 && peakOf(plain, 2 * 48000 + 10) < 1e-6 &&
                                              peakOf(longer, 4 * 48000 + 10) < 1e-6);
        double e1 = 0.0, e2 = 0.0;
        for (float v : plain) e1 += (double)v * v;
        for (float v : longer) e2 += (double)v * v;
        ok("stretch 2: keeps its energy within 1 dB", std::fabs(10.0 * std::log10(e2 / e1)) < 1.0, num(10.0 * std::log10(e2 / e1)) + " dB");
    }
}

void swapping() {
    printf("- swapping while it plays\n");
    const size_t n = 4 * 48000;
    auto fx = make({{"mix", 1.0f}});
    mount(*fx, venue_harness::room(1.0, true, 1));
    Stereo s{noise(n, 91, 0.3f), noise(n, 92, 0.3f)};
    // Sound for half a second, then three new impulses in quick succession,
    // then none, then silence in.
    for (size_t i = 72000; i < n; ++i) s.l[i] = s.r[i] = 0.0f;
    double jump = 0.0;
    float last = 0.0f;
    for (size_t at = 0; at < n; at += kBlockFrames) {
        if (at == 24000 - 64) mount(*fx, venue_harness::room(1.0, true, 2), false);
        if (at == 24000) mount(*fx, venue_harness::room(0.5, false, 3), false); // waits for the first fade
        if (at == 24064) mount(*fx, venue_harness::room(2.0, true, 4), false);  // replaces the one waiting
        if (at == 96000) mount(*fx, nullptr, false);
        fx->onBlock(0, 0, 120.0f);
        fx->run(s.l.data() + at, s.r.data() + at, kBlockFrames, true);
        for (size_t i = at; i < at + kBlockFrames; ++i) {
            jump = std::max(jump, (double)std::fabs(s.l[i] - last));
            last = s.l[i];
        }
    }
    ok("swaps while playing: finite", finite(s));
    ok("swaps while playing: no step bigger than the noise makes", jump < 1.5, "largest step " + num(jump));
    ok("taken away: silence after the fade", peakOf(s.l, 120000) == 0.0 && peakOf(s.r, 120000) == 0.0,
       "peak " + num(peakOf(s.l, 120000)));
}

} // namespace

int main() {
    printf("venue\n");
    convolver();
    venue();
    shaping();
    swapping();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
