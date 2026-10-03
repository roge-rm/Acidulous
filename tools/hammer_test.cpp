// Hammer, the modelled piano: what must hold whatever the voicing.
//
// How it compares with the recordings it was built from is
// tools/hammer_reference.sh's job (bands, not pass or fail). Here: every key
// in tune, with the stretch and without; the strings settle and hold no DC;
// dampers stop what they should and not what they shouldn't; the level
// follows the house velocity law; a blow takes as long as a felt hammer's
// does; nothing blows up at the ends of every knob; a reset plays back the
// same; lean stays in tune with full; the voice cap holds.
#include <engine/core/Messages.h>
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
        {
            // Struck again just after the pedal lifts, with its damper on the
            // way down: it rings like a fresh note, not a damped one.
            auto fresh = piano();
            const auto f = strike(*fresh, 76, 90, 1.5f);
            auto again = piano();
            const int32_t at = static_cast<int32_t>(0.5f * kSr / kBlock);
            const auto g = render(*again, 2.0f, [&](int32_t b) {
                if (b == 0) { again->pedal(kPerfSustain, 1.0f); again->noteOn(76, 90); }
                if (b == 10) again->noteOff(76);
                if (b == at - 15) again->pedal(kPerfSustain, 0.0f); // 20 ms before, as a pedal change
                if (b == at) again->noteOn(76, 90);
            });
            const size_t s = static_cast<size_t>(at) * kBlock;
            const double late = dB(rms(g, s + kSr / 2, s + kSr)) - dB(rms(f, kSr / 2, kSr));
            check(late > -6.0, "a key struck again as its damper falls rings on", fmt("%+.1f dB against a fresh one", late));
        }
    }

    std::printf("pedals\n");
    {
        // Half a pedal: the dampers touch the strings and take the top
        // first; a released note rings on more the further down it is.
        auto released = [&](float depth) {
            auto m = piano({{"tail", 0.0f}});
            m->pedal(kPerfSustain, depth);
            const auto y = strike(*m, 60, 100, 1.5f, 0.3f);
            return dB(rms(y, static_cast<size_t>(1.0 * kSr), static_cast<size_t>(1.1 * kSr)));
        };
        const double up = released(0.0f), half = released(0.45f), down = released(1.0f);
        check(up < half - 3.0 && half < down - 3.0, "half a pedal is between up and down",
              fmt("%.0f, %.0f, %.0f dB a second on", up, half, down));
        // With the pedal down, strings nobody played ring too: C3's own
        // fundamental, under a C4.
        auto c3 = [&](float sympathy, float depth) {
            auto m = piano({{"tail", 0.0f}, {"sympathy", sympathy}});
            m->pedal(kPerfSustain, depth);
            const auto y = strike(*m, 60, 100, 2.0f, 0.3f);
            return dB(magnitudeAt(y, static_cast<size_t>(1.0 * kSr), kSr / 2, 130.81) + 1e-12);
        };
        const double sym = c3(1.0f, 1.0f), none = c3(0.0f, 1.0f), lifted = c3(1.0f, 0.0f);
        check(sym > none + 15.0 && sym > lifted + 15.0, "with the pedal down, an unplayed octave rings",
              fmt("C3 %.0f dB over none, %.0f over the pedal up", sym - none, sym - lifted));
        // Una corda: the hammer misses a string and meets the others with
        // softer felt: quieter and darker.
        auto corda = [&](float soft) {
            auto m = piano({{"tail", 0.0f}});
            m->pedal(kPerfSoft, soft);
            return strike(*m, 60, 100, 0.6f);
        };
        const auto plain = corda(0.0f), soft = corda(1.0f);
        const double quieter = dB(rms(plain, 0, plain.size())) - dB(rms(soft, 0, soft.size()));
        auto bright = [&](const std::vector<float> &x) {
            double hi = 0.0, all = 0.0;
            for (double hz = 262.0; hz < 4000.0; hz *= 1.06) {
                const double v = magnitudeAt(x, kSr / 20, kSr / 4, hz);
                all += v * v;
                if (hz > 1000.0) hi += v * v;
            }
            return hi / all;
        };
        check(quieter > 1.0 && quieter < 6.0 && bright(soft) < bright(plain), "una corda is quieter and darker",
              fmt("%.1f dB quieter, top %.0f%% of what it was", quieter, 100.0 * bright(soft) / bright(plain)));
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
        // Over the cap, the quietest note fades out to make room: what goes
        // missing (four held notes and a fifth, against the four and the
        // fifth played apart) starts from nothing, not all at once.
        {
            const uint8_t held[] = {48, 52, 55, 59};
            const int32_t at = static_cast<int32_t>(1.0f * kSr / kBlock);
            auto together = piano({{"voices", 0.0f}}), four = piano({{"voices", 0.0f}}), fifth = piano({{"voices", 0.0f}});
            const auto a = render(*together, 1.2f, [&](int32_t b) {
                if (b == 0) for (uint8_t k : held) together->noteOn(k, 100);
                if (b == at) together->noteOn(64, 100);
            });
            const auto f = render(*four, 1.2f, [&](int32_t b) { if (b == 0) for (uint8_t k : held) four->noteOn(k, 100); });
            const auto g = render(*fifth, 1.2f, [&](int32_t b) { if (b == at) fifth->noteOn(64, 100); });
            std::vector<float> gone(a.size());
            for (size_t i = 0; i < a.size(); ++i) gone[i] = f[i] + g[i] - a[i];
            // A cut is a click: what goes missing then changes sample to
            // sample far faster than a piano note does (30 dB under it, cut;
            // 46 faded).
            const size_t t = static_cast<size_t>(at) * kBlock;
            std::vector<float> change(gone.size(), 0.0f);
            for (size_t i = 1; i < gone.size(); ++i) change[i] = gone[i] - gone[i - 1];
            const double click = rms(change, t, t + kSr / 100), note = rms(gone, t + kSr / 50, t + kSr / 25);
            check(note > 0.0 && click < 0.0125 * note, "a note made to give way fades out",
                  fmt("its change %.0f dB under it", dB(note) - dB(click)));
        }
        // The quality changing while notes ring: nothing breaks.
        {
            auto q = piano();
            double peak = 0.0;
            bool finite = true;
            const auto y = render(*q, 2.0f, [&](int32_t b) {
                if (b == 0) for (uint8_t k : {28, 40, 55, 67, 79}) q->noteOn(k, 110);
                if (b % 94 == 50) EngineSettings::get().quality.store((b / 94) % 2);
            });
            EngineSettings::get().quality.store(1);
            for (float v : y) { finite = finite && std::isfinite(v); peak = std::max(peak, static_cast<double>(std::fabs(v))); }
            check(finite && peak < 1.0, "switching quality while notes ring", fmt("peak %.2f", peak));
        }
        auto s = piano();
        const auto x = strike(*s, 60, 100, 12.0f, 0.5f);
        const auto tail = render(*s, 1.0f, [](int32_t) {});
        check(s->activeVoices() == 0 && rms(tail, 0, tail.size()) == 0.0, "a released note frees its voice, and the machine sleeps",
              fmt("%.0f voices", s->activeVoices()));
    }

    std::printf("instruments\n");
    {
        // Each string instrument in tune where its stiffness puts partial 1:
        // within 3 cents, the honky-tonk 5 (it's its loudest string that's in
        // tune, and the others pull at the top of the keyboard).
        double worst = 0.0, over = 0.0;
        int at = 0, which = 0;
        for (int model = 1; model <= 4; ++model) {
            for (int key = 21; key <= 108; key += 6) {
                auto m = piano({{"model", static_cast<float>(model)}, {"stretch", 0.0f}, {"unison", 0.0f}, {"polar", 0.0f},
                                {"board", 0.0f}, {"tail", 0.0f}});
                const auto x = strike(*m, static_cast<uint8_t>(key), 90, key < 60 ? 3.0f : 1.6f);
                const double want = 440.0 * std::exp2((key - 69) / 12.0) * std::sqrt(1.0 + m->keySpec(key).B);
                const size_t n = static_cast<size_t>(key < 60 ? 2.4 * kSr : 1.2 * kSr);
                const double cents = 1200.0 * std::log2(peakNear(x, static_cast<size_t>(0.2 * kSr), n, want) / want);
                const double allowed = model == 2 ? 5.0 : 3.0;
                if (std::fabs(cents) / allowed > over) { over = std::fabs(cents) / allowed; worst = cents; at = key; which = model; }
            }
        }
        check(over < 1.0, "upright, honky, fortepiano, electric grand in tune",
              fmt("worst %+.2f c at key %.0f, model %.0f", worst, at, which));

        // A baby grand's bass strings are shorter, so stiffer: partial 8 of A1 sharper.
        auto partial8 = [&](float size) {
            auto m = piano({{"size", size}, {"unison", 0.0f}, {"polar", 0.0f}});
            const auto x = strike(*m, 33, 100, 2.0f);
            const double f0 = 55.0 * std::exp2(m->keySpec(33).stretchCents / 1200.0);
            return peakNear(x, kSr / 5, kSr, 8.0 * f0 * std::sqrt(1.0 + 64.0 * m->keySpec(33).B), 40.0);
        };
        const double sharper = 1200.0 * std::log2(partial8(0.0f) / partial8(1.0f));
        check(sharper > 3.0 && sharper < 60.0, "a baby grand's bass is stiffer", fmt("A1's 8th partial %+.1f c", sharper));

        // Switching the instrument while notes ring: they ring on as they were struck.
        auto s = piano();
        int32_t n = 0;
        const ParamDef *defs = s->paramDefs(n);
        bool finite = true;
        double peak = 0.0;
        const auto x = render(*s, 3.0f, [&](int32_t b) {
            if (b % 50 == 0) s->noteOn(static_cast<uint8_t>(36 + ((b / 50) * 5) % 60), 100);
            if (b % 100 == 75) {
                for (int32_t p = 0; p < n; ++p) {
                    if (std::string(defs[p].name) == "model") s->params().set(p, defs[p].unmap(static_cast<float>((b / 100) % 5)));
                }
            }
        });
        for (float v : x) {
            finite = finite && std::isfinite(v);
            peak = std::max(peak, static_cast<double>(std::fabs(v)));
        }
        check(finite && peak < 1.0, "switching the instrument while notes ring", fmt("peak %.2f", peak));
    }

    std::printf("electric\n");
    {
        // In tune: a bar at its note, the tangent's string where its stiffness puts it.
        double worst = 0.0;
        int at = 0, which = 0;
        for (int model = 5; model <= 7; ++model) {
            for (int key = 29; key <= 96; key += 7) {
                auto m = piano({{"model", static_cast<float>(model)}, {"stretch", 0.0f}, {"drive", 0.0f}});
                const auto x = strike(*m, static_cast<uint8_t>(key), 60, 1.5f);
                const double want = 440.0 * std::exp2((key - 69) / 12.0) * (model == 7 ? std::sqrt(1.0 + m->keySpec(key).B) : 1.0);
                const double cents = 1200.0 * std::log2(peakNear(x, kSr / 10, kSr, want) / want);
                if (std::fabs(cents) > std::fabs(worst)) { worst = cents; at = key; which = model; }
            }
        }
        check(std::fabs(worst) < 3.0, "tine, reed and tangent in tune", fmt("worst %+.2f c at key %.0f, model %.0f", worst, at, which));

        // A hard blow swings the bar into the pickup's curve: the 2nd harmonic comes up.
        auto second = [&](int model, uint8_t velocity) {
            auto m = piano({{"model", static_cast<float>(model)}, {"drive", 0.0f}});
            const auto x = strike(*m, 60, velocity, 0.5f);
            return dB(magnitudeAt(x, kSr / 20, kSr / 4, 523.25)) - dB(magnitudeAt(x, kSr / 20, kSr / 4, 261.63));
        };
        const double tineBark = second(5, 120) - second(5, 30), reedBark = second(6, 120) - second(6, 30);
        check(tineBark > 12.0 && reedBark > 12.0, "a hard blow barks: the 2nd harmonic comes up",
              fmt("tine %+.0f dB, reed %+.0f dB, velocity 120 against 30", tineBark, reedBark));

        // Velocity follows the house law here too.
        auto level = [&](uint8_t velocity) {
            auto m = piano({{"model", 5.0f}, {"drive", 0.0f}});
            const auto x = strike(*m, 60, velocity, 0.5f);
            return dB(rms(x, kSr / 20, kSr / 4));
        };
        const double got = level(64) - level(127), law = dB(velocityGain(64.0f / 127.0f, 1.0f) / velocityGain(1.0f, 1.0f));
        check(std::fabs(got - law) < 4.0, "a tine's velocity follows the house law", fmt("%+.1f dB, law %+.1f", got, law));

        // The tremolo, from its knob or the mod wheel.
        auto swing = [&](float tremolo, uint8_t wheel) {
            auto m = piano({{"model", 5.0f}, {"tremolo", tremolo}, {"tremrate", 4.0f}, {"tremwide", 0.0f}});
            m->controlChange(1, wheel);
            const auto x = strike(*m, 72, 90, 1.5f);
            double lo = 1e9, hi = 0.0;
            for (size_t b = kSr / 2; b + kSr / 50 < x.size(); b += kSr / 50) {
                const double r = rms(x, b, b + kSr / 50);
                lo = std::min(lo, r);
                hi = std::max(hi, r);
            }
            return dB(hi) - dB(lo);
        };
        const double still = swing(0.0f, 0), knob = swing(0.8f, 0), wheel = swing(0.0f, 127);
        check(knob > still + 6.0 && wheel > still + 6.0, "the tremolo swings, from its knob or the mod wheel",
              fmt("%.1f dB still, %.1f knob, %.1f wheel", still, knob, wheel));

        // A tangent's note stops dead when the key comes up.
        {
            auto m = piano({{"model", 7.0f}});
            const auto x = strike(*m, 60, 100, 1.0f, 0.4f);
            const double held = dB(rms(x, kSr / 4, kSr * 2 / 5)), after = dB(rms(x, kSr * 2 / 5 + kSr / 5, kSr * 2 / 5 + kSr / 4));
            check(held - after > 40.0, "a tangent's note stops when the key comes up", fmt("%.0f dB down 0.2 s after", held - after));
            auto p = piano({{"model", 7.0f}});
            p->pedal(kPerfSustain, 1.0f);
            const auto y = strike(*p, 60, 100, 1.0f, 0.4f);
            const double pedalled = dB(rms(y, kSr / 4, kSr * 2 / 5)) - dB(rms(y, kSr * 2 / 5 + kSr / 5, kSr * 2 / 5 + kSr / 4));
            check(pedalled > 40.0, "even with the sustain pedal down", fmt("%.0f dB down 0.2 s after", pedalled));
        }
        // Its pickups: the first, the second, both, and both against each other sound different.
        {
            double levels[4];
            for (int p = 0; p < 4; ++p) {
                auto m = piano({{"model", 7.0f}, {"pickups", static_cast<float>(p)}});
                const auto x = strike(*m, 48, 100, 0.5f);
                levels[p] = dB(magnitudeAt(x, kSr / 20, kSr / 4, 3.0 * 130.81)) - dB(magnitudeAt(x, kSr / 20, kSr / 4, 130.81));
            }
            const bool differ = std::fabs(levels[0] - levels[1]) > 1.0 && std::fabs(levels[2] - levels[3]) > 1.0;
            check(differ, "the tangent's pickups sound different", fmt("3rd against 1st: %+.0f %+.0f %+.0f", levels[0], levels[1], levels[2]) + fmt(" %+.0f dB", levels[3]));
        }
    }

    std::printf("bars and courses\n");
    {
        // In tune: the celesta's bars, the dulcimer's and cimbalom's courses.
        // A toy piano is out of tune on purpose: within 15 cents. Rung long:
        // a celesta's short note is a wide peak, and the board's colour
        // leaned it 3.5 cents (the bar alone was exact).
        double worst = 0.0, over = 0.0;
        int at = 0, which = 0;
        for (int model = 8; model <= 11; ++model) {
            for (int key = 48; key <= 96; key += 8) {
                auto m = piano({{"model", static_cast<float>(model)}, {"stretch", 0.0f}, {"unison", 0.0f}, {"polar", 0.0f}, {"sustain", 1.0f}});
                const auto x = strike(*m, static_cast<uint8_t>(key), 70, 1.2f);
                const bool course = model >= 10;
                const double want = 440.0 * std::exp2((key - 69) / 12.0) * (course ? std::sqrt(1.0 + m->keySpec(key).B) : 1.0);
                const double allowed = model == 9 ? 16.0 : 3.0;
                const double cents = 1200.0 * std::log2(peakNear(x, kSr / 10, kSr * 4 / 5, want, model == 9 ? 25.0 : 15.0) / want);
                if (std::fabs(cents) / allowed > over) { over = std::fabs(cents) / allowed; worst = cents; at = key; which = model; }
            }
        }
        check(over < 1.0, "celesta, toy, dulcimer, cimbalom in tune", fmt("worst %+.2f c at key %.0f, model %.0f", worst, at, which));

        // A dulcimer has no dampers: let go, it rings on.
        auto held = [&](float model) {
            auto m = piano({{"model", model}});
            const auto x = strike(*m, 67, 90, 1.2f, 0.3f);
            return dB(rms(x, kSr * 9 / 10, kSr)) - dB(rms(x, kSr / 5, kSr * 3 / 10));
        };
        const double dulcimer = held(10.0f), celesta = held(8.0f);
        check(dulcimer > -15.0, "a dulcimer rings on when let go", fmt("%.0f dB 0.6 s after", dulcimer));
        check(celesta < -40.0, "a celesta's dampers stop it", fmt("%.0f dB 0.6 s after", celesta));

        // And its unplayed strings always ring along, no pedal needed.
        auto c3 = [&](float sympathy) {
            auto m = piano({{"model", 10.0f}, {"tail", 0.0f}, {"sympathy", sympathy}});
            const auto y = strike(*m, 60, 100, 2.0f, 0.3f);
            return dB(magnitudeAt(y, static_cast<size_t>(1.0 * kSr), kSr / 2, 130.81) + 1e-12);
        };
        const double sym = c3(1.0f) - c3(0.0f);
        check(sym > 10.0, "a dulcimer's unplayed strings ring along", fmt("C3 %.0f dB over none", sym));
    }

    std::printf("preparations\n");
    {
        // What's between a C3's partials, 0.1-0.6 s: a rattle's buzz lives there.
        auto between = [&](const std::vector<float> &x) {
            double sum = 0.0;
            for (int k = 6; k < 30; ++k) sum += magnitudeAt(x, kSr / 10, kSr / 2, 130.81 * (k + 0.5));
            return dB(sum);
        };
        auto c3 = [&](float prep) { auto m = piano({{"prep", prep}}); return strike(*m, 48, 100, 0.7f); };
        const auto plain = c3(0.0f);
        const double plainBetween = between(plain), plainLevel = dB(rms(plain, 0, plain.size()));
        const char *names[] = {"", "rubber", "a screw", "a bolt", "paper"};
        std::string changed;
        bool all = true;
        for (int kind = 1; kind <= 4; ++kind) {
            const auto x = c3(static_cast<float>(kind));
            const double level = dB(rms(x, 0, x.size())) - plainLevel, buzz = between(x) - plainBetween;
            const double partial10 = dB(magnitudeAt(x, kSr / 10, kSr / 2, 1308.1 * 1.004)) - dB(magnitudeAt(plain, kSr / 10, kSr / 2, 1308.1 * 1.004));
            const bool differs = std::fabs(level) > 1.0 || std::fabs(buzz) > 3.0 || std::fabs(partial10) > 3.0;
            all = all && differs;
            changed += std::string(names[kind]) + fmt(" %+.0f/%+.0f dB ", level, buzz);
        }
        check(all, "each preparation changes a C3", changed);
        {
            const auto x = c3(3.0f);
            const double buzz = between(x) - plainBetween;
            check(buzz > 6.0, "a bolt rattles between the partials", fmt("%+.1f dB", buzz));
        }
        // On every key, at every amount, loud: nothing runs away.
        bool finite = true;
        double peak = 0.0;
        int at = 0;
        for (int kind = 1; kind <= 5; ++kind) {
            for (float amount : {0.0f, 1.0f}) {
                auto m = piano({{"prep", static_cast<float>(kind)}, {"prepamt", amount}, {"prep at", amount}});
                for (int key = 21; key <= 108; key += 4) {
                    const auto x = strike(*m, static_cast<uint8_t>(key), 127, 0.4f, 0.3f);
                    for (float v : x) {
                        finite = finite && std::isfinite(v);
                        if (std::fabs(v) > peak) { peak = std::fabs(v); at = key; }
                    }
                }
            }
        }
        check(finite && peak < 1.0, "every preparation on every key stays put", fmt("peak %.2f at key %.0f", peak, at));
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
