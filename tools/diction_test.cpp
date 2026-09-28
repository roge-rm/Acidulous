// Diction, the singer: in tune, the throat apart from the pitch, the
// vowels apart from each other, and legato that doesn't breathe between
// notes.
//
// Everything is measured from the output, as a listener would: the pitch by
// the same tracker Molt uses, the throat by the spectrum's centroid, a vowel by
// where its energy sits.
#include <engine/core/Utterance.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Diction.h>

#include "audition_measure.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

using namespace acidulous;
using acidulous::audio::PitchTrack;
using machine::Diction;
using audition::kSr;

namespace {

constexpr int32_t kBlock = 64;
int failures = 0, checks = 0;

void check(bool ok, const char *what, const std::string &detail = "") {
    ++checks;
    std::printf("  %-58s %s %s\n", what, ok ? "ok" : "FAIL", detail.c_str());
    if (!ok) ++failures;
}

float cents(float a, float b) { return 1200.0f * std::log2(a / b); }

/** A singer with the knobs at the given values and everything that wobbles turned off. */
std::unique_ptr<Machine> singer(std::initializer_list<std::pair<int32_t, float>> knobs) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
    m->prepare(kSr);
    auto set = [&](int32_t p, float native) { m->params().set(p, m->params().def(p).unmap(native)); };
    set(Diction::Vibrato, 0.0f);
    set(Diction::Drift, 0.0f);
    set(Diction::Breath, 0.0f);
    set(Diction::Glide, 0.0f);
    for (const auto &k : knobs) set(k.first, k.second);
    m->params().jumpAll();
    m->reset();
    return m;
}

/** [seconds] of the machine, mono, with [play] called before each block with the block's index. */
template <typename Play>
std::vector<float> render(Machine &m, float seconds, Play play) {
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    const int32_t blocks = static_cast<int32_t>(seconds * kSr / kBlock);
    for (int32_t b = 0; b < blocks; ++b) {
        play(b);
        std::fill(L, L + kBlock, 0.0f);
        std::fill(R, R + kBlock, 0.0f);
        m.render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(0.5f * (L[i] + R[i]));
    }
    return out;
}

std::vector<float> held(Machine &m, uint8_t note, float seconds) {
    return render(m, seconds, [&](int32_t b) { if (b == 0) m.noteOn(note, 100); });
}

/** The median pitch of [x] after its first 0.2 s. */
float pitchOf(const std::vector<float> &x) {
    std::vector<float> tail(x.begin() + std::min<size_t>(x.size(), static_cast<size_t>(kSr * 0.2f)), x.end());
    PitchTrack t;
    t.find(tail, static_cast<int32_t>(tail.size()), kSr);
    std::vector<float> v;
    for (float f : t.hz) if (f > 0.0f) v.push_back(f);
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

/** How far the pitch moves over [x] after its first 0.2 s, in cents, from the 10th to the 90th percentile. */
float pitchSpread(const std::vector<float> &x) {
    std::vector<float> tail(x.begin() + std::min<size_t>(x.size(), static_cast<size_t>(kSr * 0.2f)), x.end());
    PitchTrack t;
    t.find(tail, static_cast<int32_t>(tail.size()), kSr);
    std::vector<float> v;
    for (float f : t.hz) if (f > 0.0f) v.push_back(f);
    if (v.size() < 10) return 0.0f;
    std::sort(v.begin(), v.end());
    return cents(v[v.size() * 9 / 10], v[v.size() / 10]);
}

float throat(const std::vector<float> &x) { return audition::centroid(x, static_cast<int32_t>(kSr * 0.3f), 200.0f, 5000.0f); }

/** Energy between [lo] and [hi] Hz, summed over the spectrum's bins. */
float band(const std::vector<float> &x, float lo, float hi) {
    float sum = 0.0f;
    for (float hz = lo; hz <= hi; hz += 25.0f) sum += audition::magnitudeAt(x, static_cast<int32_t>(kSr * 0.3f), hz);
    return sum;
}

float rms(const std::vector<float> &x, size_t from, size_t n) {
    double s = 0.0;
    for (size_t i = from; i < from + n && i < x.size(); ++i) s += static_cast<double>(x[i]) * x[i];
    return static_cast<float>(std::sqrt(s / static_cast<double>(n)));
}

} // namespace

int main() {
    std::printf("\ndiction: in tune\n");
    for (uint8_t n : {45, 52, 57, 64, 69}) {
        auto m = singer({});
        const float want = 440.0f * std::exp2((static_cast<float>(n) - 69.0f) / 12.0f);
        const float got = pitchOf(held(*m, n, 1.2f));
        char what[64];
        std::snprintf(what, sizeof(what), "note %d sings at its pitch", n);
        check(std::fabs(cents(got, want)) < 15.0f, what, std::to_string(got) + " Hz, wanted " + std::to_string(want));
    }

    std::printf("\nthe throat and the pitch, apart\n");
    {
        const auto plain = held(*singer({}), 57, 1.2f);
        const auto up = held(*singer({{Diction::Formant, 7.0f}}), 57, 1.2f);
        const auto down = held(*singer({{Diction::Formant, -7.0f}}), 57, 1.2f);
        const float c0 = throat(plain), cu = throat(up), cd = throat(down);
        check(std::fabs(cents(pitchOf(up), pitchOf(plain))) < 15.0f, "formant up leaves the pitch where it was",
              std::to_string(pitchOf(up)) + " against " + std::to_string(pitchOf(plain)));
        // The first formant moves the whole way: where ah's peaks, on a low
        // note so the harmonics are close enough to show it. A fifth is x1.5.
        auto firstFormant = [](const std::vector<float> &x) {
            float best = 0.0f, at = 0.0f;
            for (float hz = 300.0f; hz <= 1500.0f; hz += 5.0f) {
                const float m = audition::magnitudeAt(x, static_cast<int32_t>(kSr * 0.3f), hz);
                if (m > best) { best = m; at = hz; }
            }
            return at;
        };
        const float f0 = firstFormant(held(*singer({}), 45, 1.2f));
        const float fu = firstFormant(held(*singer({{Diction::Formant, 7.0f}}), 45, 1.2f));
        const float fd = firstFormant(held(*singer({{Diction::Formant, -7.0f}}), 45, 1.2f));
        check(fu / f0 > 1.3f && fu / f0 < 1.7f, "and moves the throat up a fifth",
              "first formant " + std::to_string(f0) + " -> " + std::to_string(fu) + " Hz, centroid x" + std::to_string(cu / c0));
        check(fd / f0 > 0.55f && fd / f0 < 0.77f, "formant down moves it down a fifth",
              "first formant " + std::to_string(f0) + " -> " + std::to_string(fd) + " Hz, centroid x" + std::to_string(cd / c0));
        const float ring0 = band(plain, 2500.0f, 3500.0f), ringDown = band(down, 2500.0f, 3500.0f);
        const float ringDb = 20.0f * std::log10(ringDown / ring0);
        check(ringDb > -6.0f, "and a lowered voice keeps its ring near 3 kHz", std::to_string(ringDb) + " dB");
        const auto octave = held(*singer({}), 69, 1.2f);
        check(std::fabs(cents(pitchOf(octave), pitchOf(plain)) - 1200.0f) < 20.0f, "an octave up is an octave up");
        check(std::fabs(throat(octave) / c0 - 1.0f) < 0.2f, "and keeps the throat, near enough",
              "centroid x" + std::to_string(throat(octave) / c0));
    }

    std::printf("\nthe vowels, apart\n");
    {
        // Where each vowel's second formant sits against its first region.
        auto front = [](const std::vector<float> &x) { return band(x, 1900.0f, 2600.0f) / band(x, 600.0f, 1100.0f); };
        const float oo = front(held(*singer({{Diction::Vowel, 0.0f}}), 52, 1.0f));
        const float ah = front(held(*singer({{Diction::Vowel, 2.0f}}), 52, 1.0f));
        const float ee = front(held(*singer({{Diction::Vowel, 4.0f}}), 52, 1.0f));
        check(ee > ah * 2.0f, "ee is brighter than ah where the second formant is", std::to_string(ee) + " against " + std::to_string(ah));
        (void)oo;
        // oo and ah differ in the first formant: oo's is low, about 300 Hz,
        // and ah's open, about 730.
        auto open = [](const std::vector<float> &x) { return band(x, 600.0f, 900.0f) / band(x, 200.0f, 400.0f); };
        const float ooOpen = open(held(*singer({{Diction::Vowel, 0.0f}}), 52, 1.0f));
        const float ahOpen = open(held(*singer({{Diction::Vowel, 2.0f}}), 52, 1.0f));
        check(ahOpen > ooOpen * 2.0f, "ah is more open than oo in the first formant",
              std::to_string(ahOpen) + " against " + std::to_string(ooOpen));
        const float mid = front(held(*singer({{Diction::Vowel, 3.0f}}), 52, 1.0f));
        check(mid > ah && mid < ee, "eh, between them, is between them", std::to_string(mid));
    }

    std::printf("\nlegato, vibrato and letting go\n");
    {
        auto m = singer({});
        const int32_t change = static_cast<int32_t>(0.8f * kSr / kBlock);
        const auto x = render(*m, 1.6f, [&](int32_t b) {
            if (b == 0) m->noteOn(57, 100);
            if (b == change) m->noteOn(60, 100); // the first still held
        });
        const size_t at = static_cast<size_t>(change) * kBlock;
        const float before = rms(x, at - 4800, 4800);
        float lowest = 1e9f;
        for (size_t i = at; i < at + 9600; i += 480) lowest = std::min(lowest, rms(x, i, 480));
        check(lowest > before * 0.6f, "a note joined to the last doesn't start again", std::to_string(lowest / before));

        const float still = pitchSpread(held(*singer({}), 57, 2.0f));
        const float wide = pitchSpread(held(*singer({{Diction::Vibrato, 50.0f}, {Diction::VibratoDelay, 0.0f}}), 57, 2.0f));
        check(still < 20.0f, "no vibrato holds still", std::to_string(still) + " ct");
        check(wide > 60.0f && wide < 140.0f, "fifty cents of vibrato swings about a hundred", std::to_string(wide) + " ct");

        auto r = singer({{Diction::Release, 0.1f}});
        const auto y = render(*r, 1.5f, [&](int32_t b) {
            if (b == 0) r->noteOn(57, 100);
            if (b == static_cast<int32_t>(0.5f * kSr / kBlock)) r->noteOff(57);
        });
        const float after = rms(y, static_cast<size_t>(kSr * 1.2f), static_cast<size_t>(kSr * 0.3f));
        check(after == 0.0f, "silent once let go and released", std::to_string(after));
    }

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
