// Hammer, the modelled piano: what must hold whatever the voicing.
//
// How it compares with the recordings it was built from is
// tools/hammer_reference.sh's job (bands, not pass or fail). Here: every key
// in tune, with the stretch and without; the strings settle and hold no DC;
// dampers stop what they should and not what they shouldn't; the level
// follows the house velocity law; a blow takes as long as a felt hammer's
// does; nothing blows up at the ends of every knob; a reset plays back the
// same; lean stays in tune with full; the voice cap holds.
#include <engine/core/Settings.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/Voices.h>
#include <engine/machine/hammer/Hammer.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using machine::Hammer;

namespace {

constexpr int32_t kSr = 48000, kBlock = 64;
int failures = 0, checks = 0;

void check(bool ok, const char *what, const std::string &detail = "") {
    ++checks;
    std::printf("  %-58s %s %s\n", what, ok ? "ok" : "FAIL", detail.c_str());
    if (!ok) ++failures;
}

std::string fmt(const char *f, double a, double b = 0.0, double c = 0.0) {
    char s[160];
    std::snprintf(s, sizeof s, f, a, b, c);
    return s;
}

std::unique_ptr<Hammer> piano(std::initializer_list<std::pair<const char *, float>> knobs = {}) {
    std::unique_ptr<Hammer> m(static_cast<Hammer *>(MachineRegistry::create("Hammer")));
    m->prepare(kSr);
    int32_t n = 0;
    const ParamDef *defs = m->paramDefs(n);
    for (const auto &k : knobs) {
        for (int32_t p = 0; p < n; ++p) {
            if (std::string(defs[p].name) == k.first) m->params().set(p, defs[p].unmap(k.second));
        }
    }
    m->params().jumpAll();
    m->reset();
    return m;
}

/** [seconds] of the machine, mono, with [play] called before each block with the block's index. */
template <typename Play>
std::vector<float> render(Machine &m, float seconds, Play play, bool stereoMean = true) {
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    const int32_t blocks = static_cast<int32_t>(seconds * kSr / kBlock);
    for (int32_t b = 0; b < blocks; ++b) {
        play(b);
        std::fill(L, L + kBlock, 0.0f);
        std::fill(R, R + kBlock, 0.0f);
        m.render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(stereoMean ? 0.5f * (L[i] + R[i]) : L[i]);
    }
    return out;
}

std::vector<float> strike(Machine &m, uint8_t key, uint8_t velocity, float seconds, float letGo = -1.0f) {
    const int32_t off = letGo < 0.0f ? -1 : static_cast<int32_t>(letGo * kSr / kBlock);
    return render(m, seconds, [&](int32_t b) {
        if (b == 0) m.noteOn(key, velocity);
        if (b == off) m.noteOff(key);
    });
}

/** |DFT| of [x] from [from], [n] samples Hann-windowed, at [hz]. */
double magnitudeAt(const std::vector<float> &x, size_t from, size_t n, double hz) {
    double re = 0.0, im = 0.0;
    const double w = 2.0 * M_PI * hz / kSr;
    for (size_t i = 0; i < n && from + i < x.size(); ++i) {
        const double win = 0.5 - 0.5 * std::cos(2.0 * M_PI * static_cast<double>(i) / static_cast<double>(n - 1));
        re += x[from + i] * win * std::cos(w * static_cast<double>(i));
        im -= x[from + i] * win * std::sin(w * static_cast<double>(i));
    }
    return std::sqrt(re * re + im * im);
}

/** The peak of [x]'s spectrum within [cents] of [hz], found by golden section. */
double peakNear(const std::vector<float> &x, size_t from, size_t n, double hz, double cents = 15.0) {
    double lo = hz * std::exp2(-cents / 1200.0), hi = hz * std::exp2(cents / 1200.0);
    const double g = 0.6180339887;
    double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
    double fa = magnitudeAt(x, from, n, a), fb = magnitudeAt(x, from, n, b);
    for (int it = 0; it < 40; ++it) {
        if (fa > fb) { hi = b; b = a; fb = fa; a = hi - g * (hi - lo); fa = magnitudeAt(x, from, n, a); }
        else { lo = a; a = b; fa = fb; b = lo + g * (hi - lo); fb = magnitudeAt(x, from, n, b); }
    }
    return 0.5 * (lo + hi);
}

double rms(const std::vector<float> &x, size_t from, size_t to) {
    double s = 0.0;
    to = std::min(to, x.size());
    for (size_t i = from; i < to; ++i) s += static_cast<double>(x[i]) * x[i];
    return std::sqrt(s / std::max<size_t>(1, to - from));
}

double dB(double v) { return 20.0 * std::log10(v + 1e-12); }

} // namespace

int main() {
    EngineSettings::get().quality.store(1);

    std::printf("tuning\n");
    {
        // Partial 1 where the key's stretch and its own inharmonicity put it.
        double worst = 0.0, worstFlat = 0.0;
        int at = 0, atFlat = 0;
        for (int key = 21; key <= 108; key += 3) {
            for (float stretch : {1.0f, 0.0f}) {
                auto m = piano({{"stretch", stretch}, {"unison", 0.0f}, {"polar", 0.0f}, {"board", 0.0f}, {"tail", 0.0f}});
                const auto x = strike(*m, static_cast<uint8_t>(key), 90, key < 60 ? 3.0f : 1.6f);
                const machine::hammer::KeySpec &k = m->keySpec(key);
                const double want = 440.0 * std::exp2((key - 69) / 12.0) *
                                    std::exp2(k.stretchCents * stretch / 1200.0) * std::sqrt(1.0 + k.B);
                const size_t n = static_cast<size_t>(key < 60 ? 2.4 * kSr : 1.2 * kSr);
                const double got = peakNear(x, static_cast<size_t>(0.2 * kSr), n, want);
                const double cents = 1200.0 * std::log2(got / want);
                if (stretch > 0.5f && std::fabs(cents) > std::fabs(worst)) { worst = cents; at = key; }
                if (stretch < 0.5f && std::fabs(cents) > std::fabs(worstFlat)) { worstFlat = cents; atFlat = key; }
            }
        }
        {
            // The estimator itself: told the wrong note, it says how wrong.
            auto m = piano({{"unison", 0.0f}, {"polar", 0.0f}});
            const auto x = strike(*m, 69, 90, 1.6f);
            const double truth = 440.0 * std::exp2(m->keySpec(69).stretchCents / 1200.0) * std::sqrt(1.0 + m->keySpec(69).B);
            const double got = peakNear(x, static_cast<size_t>(0.2 * kSr), static_cast<size_t>(1.2 * kSr), truth * std::exp2(3.0 / 1200.0));
            check(std::fabs(1200.0 * std::log2(got / truth)) < 0.2, "the pitch estimate finds a note 3 cents off",
                  fmt("%+.2f c", 1200.0 * std::log2(got / truth)));
        }
        check(std::fabs(worst) < 1.5, "every key in tune, stretched", fmt("worst %+.2f c at key %.0f", worst, at));
        check(std::fabs(worstFlat) < 1.5, "every key in tune, unstretched", fmt("worst %+.2f c at key %.0f", worstFlat, atFlat));

        auto full = piano({{"unison", 0.0f}, {"polar", 0.0f}});
        auto lean = piano({{"unison", 0.0f}, {"polar", 0.0f}});
        EngineSettings::get().quality.store(0);
        const auto xl = strike(*lean, 45, 90, 3.0f);
        EngineSettings::get().quality.store(1);
        const auto xf = strike(*full, 45, 90, 3.0f);
        const double fl = peakNear(xl, kSr / 5, 2 * kSr, 110.0 * std::exp2(full->keySpec(45).stretchCents / 1200.0));
        const double ff = peakNear(xf, kSr / 5, 2 * kSr, 110.0 * std::exp2(full->keySpec(45).stretchCents / 1200.0));
        check(std::fabs(1200.0 * std::log2(fl / ff)) < 0.5, "lean stays in tune with full", fmt("%+.2f c", 1200.0 * std::log2(fl / ff)));
    }

    std::printf("strings\n");
    {
        auto m = piano();
        const auto x = strike(*m, 33, 110, 6.0f);
        double mean = 0.0;
        for (size_t i = kSr; i < x.size(); ++i) mean += x[i];
        mean /= static_cast<double>(x.size() - kSr);
        const double level = rms(x, kSr, x.size());
        check(std::fabs(mean) < 1e-3 * level + 1e-7, "a held bass note holds no DC", fmt("mean %.2e against rms %.2e", mean, level));
        const double early = dB(rms(x, kSr / 10, kSr / 2)), late = dB(rms(x, 5 * kSr, 6 * kSr));
        check(late < early - 3.0 && late > early - 50.0, "a bass note rings on, and dies", fmt("%.1f dB down after 5 s", early - late));

        // The double decay: the first second falls faster than the last.
        auto c = piano();
        const auto y = strike(*c, 60, 100, 12.0f);
        auto slope = [&](float a, float b) {
            return (dB(rms(y, static_cast<size_t>(b * kSr), static_cast<size_t>((b + 0.2f) * kSr))) -
                    dB(rms(y, static_cast<size_t>(a * kSr), static_cast<size_t>((a + 0.2f) * kSr)))) / (b - a);
        };
        const double prompt = slope(0.3f, 1.5f), after = slope(9.0f, 11.5f);
        check(prompt < 1.8 * after && after < 0.0, "middle C decays twice: prompt sound, then aftersound",
              fmt("%.1f then %.1f dB/s", prompt, after));
        auto one = piano({{"unison", 0.0f}, {"polar", 0.0f}});
        const auto z = strike(*one, 60, 100, 12.0f);
        auto slopeZ = [&](float a, float b) {
            return (dB(rms(z, static_cast<size_t>(b * kSr), static_cast<size_t>((b + 0.2f) * kSr))) -
                    dB(rms(z, static_cast<size_t>(a * kSr), static_cast<size_t>((a + 0.2f) * kSr)))) / (b - a);
        };
        // Late enough for an aftersound, early enough to stay well above
        // where a fast prompt sound runs into float rounding.
        check(slopeZ(3.0f, 5.0f) < 0.6 * slopeZ(0.3f, 1.5f),
              "strings in perfect unison have no aftersound", fmt("%.1f then %.1f dB/s", slopeZ(0.3f, 1.5f), slopeZ(3.0f, 5.0f)));
    }

    std::printf("dampers\n");
    {
        // The strings' own stop: the room rings on after them, as a room does.
        auto stopAfter = [&](uint8_t key, float hold) {
            auto m = piano({{"tail", 0.0f}});
            const auto x = strike(*m, key, 100, hold + 2.0f, hold);
            const double at = dB(rms(x, static_cast<size_t>((hold - 0.05f) * kSr), static_cast<size_t>(hold * kSr)));
            for (float t = hold; t + 0.02f < hold + 2.0f; t += 0.01f) {
                if (dB(rms(x, static_cast<size_t>(t * kSr), static_cast<size_t>((t + 0.02f) * kSr))) < at - 60.0) return t - hold;
            }
            return 99.0f;
        };
        const float c4 = stopAfter(60, 0.5f), c6 = stopAfter(84, 0.5f), a1 = stopAfter(33, 0.5f);
        check(c4 < 0.4f, "a released middle C is gone in 400 ms", fmt("-60 dB in %.0f ms", c4 * 1000.0));
        check(c6 < 0.25f, "a released C6 is gone in 250 ms", fmt("-60 dB in %.0f ms", c6 * 1000.0));
        check(a1 < 1.5f, "a released A1 is gone in 1.5 s", fmt("-60 dB in %.0f ms", a1 * 1000.0));
        auto m = piano({{"tail", 0.0f}}), h = piano({{"tail", 0.0f}});
        const auto x = strike(*m, 100, 100, 1.5f, 0.3f), kept = strike(*h, 100, 100, 1.5f);
        // 0.2 s after: a free top note is near silence by 0.8 s, and retired.
        const double gap = dB(rms(kept, static_cast<size_t>(0.5 * kSr), static_cast<size_t>(0.55 * kSr))) -
                           dB(rms(x, static_cast<size_t>(0.5 * kSr), static_cast<size_t>(0.55 * kSr)));
        check(std::fabs(gap) < 1.0, "the top keys have no dampers", fmt("released %.1f dB under held, 0.2 s after", gap));
        auto p = piano({{"tail", 0.0f}});
        p->setDampers(true);
        const auto y = strike(*p, 60, 100, 2.0f, 0.3f);
        const double held = dB(rms(y, static_cast<size_t>(0.25 * kSr), static_cast<size_t>(0.3 * kSr))) -
                            dB(rms(y, static_cast<size_t>(1.2 * kSr), static_cast<size_t>(1.25 * kSr)));
        check(held < 20.0, "the pedal holds a released key", fmt("%.1f dB down 0.9 s after", held));
    }

    std::printf("the blow\n");
    {
        auto m = piano();
        strike(*m, 60, 64, 0.1f);
        const float slow = static_cast<float>(m->lastContactSamples()) * 1000.0f / kSr;
        auto f = piano();
        strike(*f, 60, 127, 0.1f);
        const float fast = static_cast<float>(f->lastContactSamples()) * 1000.0f / kSr;
        check(slow > 0.4f && slow < 5.0f && fast < slow, "middle C's contact is a felt hammer's, shorter when harder",
              fmt("%.2f ms at 64, %.2f ms at 127", slow, fast));
        // The level follows the house law: velocity 64 against 127.
        auto a = piano(), b = piano();
        const auto xa = strike(*a, 60, 64, 0.6f), xb = strike(*b, 60, 127, 0.6f);
        const double law = dB(velocityGain(64.0f / 127.0f, 1.0f));
        const double got = dB(rms(xa, 0, xa.size())) - dB(rms(xb, 0, xb.size()));
        check(std::fabs(got - law) < 3.0, "velocity 64 against 127 follows the house law",
              fmt("%+.1f dB, law %+.1f", got, law));
    }

    std::printf("voices\n");
    {
        auto m = piano({{"voices", 2.0f}});
        int most = 0;
        render(*m, 3.0f, [&](int32_t b) {
            if (b % 8 == 0 && b / 8 < 40) m->noteOn(static_cast<uint8_t>(30 + (b / 8) * 2), 100);
            if (b % 8 == 4 && b / 8 < 40) m->noteOff(static_cast<uint8_t>(30 + (b / 8) * 2));
            most = std::max(most, m->activeVoices());
        });
        check(most <= 8, "never more voices than the cap (8)", fmt("at most %.0f", most));
        // A struck key that's still ringing is the same strings, not another voice.
        auto r = piano();
        render(*r, 1.0f, [&](int32_t b) { if (b % 100 == 0) r->noteOn(60, 100); if (b % 100 == 50) r->noteOff(60); });
        check(r->activeVoices() == 1, "striking a ringing key again uses its own strings", fmt("%.0f voices", r->activeVoices()));
        auto s = piano();
        const auto x = strike(*s, 60, 100, 12.0f, 0.5f);
        const auto tail = render(*s, 1.0f, [](int32_t) {});
        check(s->activeVoices() == 0 && rms(tail, 0, tail.size()) == 0.0, "a released note frees its voice, and the machine sleeps",
              fmt("%.0f voices", s->activeVoices()));
    }

    std::printf("reset and extremes\n");
    {
        auto phrase = [&](Machine &m) {
            return render(m, 2.0f, [&](int32_t b) {
                const uint8_t keys[] = {36, 48, 55, 60, 64, 67, 72, 84, 96};
                if (b % 40 == 0 && b / 40 < 9) m.noteOn(keys[b / 40], static_cast<uint8_t>(40 + 10 * (b / 40)));
                if (b == 1000) m.allNotesOff();
            });
        };
        auto m = piano();
        const auto a = phrase(*m);
        m->reset();
        const auto b = phrase(*m);
        check(a == b, "after a reset it plays back the same");
        int32_t n = 0;
        const ParamDef *defs = m->paramDefs(n);
        bool finite = true;
        double peak = 0.0;
        for (int corner = 0; corner < 24 && finite; ++corner) {
            auto e = piano();
            uint32_t h = 0x9e3779b9u * static_cast<uint32_t>(corner + 1);
            for (int32_t p = 0; p < n; ++p) {
                h = h * 1664525u + 1013904223u;
                const std::string name = defs[p].name;
                if (name == "volume" || name == "octave" || name == "transpose") continue;
                e->params().set(p, (h >> 31) ? 1.0f : 0.0f);
            }
            e->params().jumpAll();
            const auto x = phrase(*e);
            for (float v : x) {
                if (!std::isfinite(v)) { finite = false; break; }
                peak = std::max(peak, static_cast<double>(std::fabs(v)));
            }
        }
        check(finite && peak < 8.0, "every knob at its ends: no NaN, nothing runs away", fmt("peak %.2f", peak));
    }

    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
