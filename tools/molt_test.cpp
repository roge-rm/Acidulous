// Molt, proved on a voice nobody has to sing.
//
// A vowel is a train of glottal pulses through a couple of resonances, and
// that is cheap enough to build here - which means the whole machine can be
// checked against a source whose pitch and formants are known exactly rather
// than against a recording somebody has to make first.
//
// What is asserted, in order: the analyser finds a pitch it was given; a
// noise burst is not called pitched; and - the point of the machine - pitch
// and formant move independently, each leaving the other where it was.
#include <engine/core/Utterance.h>
#include <engine/dsp/Fft.h>
#include <engine/machine/molt/Molt.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

using namespace acidulous;
using namespace acidulous::audio;

namespace {

constexpr float kSr = 48000.0f;
int failures = 0;

void check(bool ok, const char *what, const char *detail = "") {
    std::printf("  %-52s %s %s\n", what, ok ? "ok" : "FAIL", detail);
    if (!ok) ++failures;
}

float cents(float a, float b) { return 1200.0f * std::log2(a / b); }

/** A two-pole resonator, which is all a formant is. */
void resonate(const std::vector<float> &in, std::vector<float> &out, float hz, float q, float gain) {
    const float w = 2.0f * static_cast<float>(M_PI) * hz / kSr;
    const float r = std::exp(-w / (2.0f * q));
    const float a1 = 2.0f * r * std::cos(w), a2 = -r * r;
    float y1 = 0.0f, y2 = 0.0f;
    for (size_t i = 0; i < in.size(); ++i) {
        const float y = in[i] + a1 * y1 + a2 * y2;
        y2 = y1;
        y1 = y;
        out[i] += gain * y;
    }
}

/** A sung vowel: pulses at [f0], shaped by two formants. */
std::vector<float> vowel(float f0, float seconds, float f1 = 700.0f, float f2 = 1220.0f) {
    const int32_t n = static_cast<int32_t>(kSr * seconds);
    std::vector<float> pulses(static_cast<size_t>(n), 0.0f);
    const float period = kSr / f0;
    for (float p = 0.0f; p < static_cast<float>(n); p += period) {
        pulses[static_cast<size_t>(p)] = 1.0f;
    }
    std::vector<float> out(static_cast<size_t>(n), 0.0f);
    resonate(pulses, out, f1, 12.0f, 1.0f);
    resonate(pulses, out, f2, 12.0f, 0.5f);
    float peak = 1e-9f;
    for (float v : out) peak = std::max(peak, std::abs(v));
    for (float &v : out) v *= 0.7f / peak;
    return out;
}

std::vector<float> noise(float seconds) {
    const int32_t n = static_cast<int32_t>(kSr * seconds);
    std::vector<float> out(static_cast<size_t>(n), 0.0f);
    uint32_t rng = 0x1234567u;
    for (auto &v : out) {
        rng = rng * 1664525u + 1013904223u;
        v = static_cast<float>(rng >> 8) * (1.0f / 8388608.0f) - 1.0f;
        v *= 0.5f;
    }
    return out;
}

/** The median of the voiced part of a pitch track - what the take "is". */
float medianVoiced(const PitchTrack &t) {
    std::vector<float> v;
    for (float f : t.hz) if (f > 0.0f) v.push_back(f);
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

float voicedShare(const PitchTrack &t) {
    if (t.hz.empty()) return 0.0f;
    int32_t n = 0;
    for (float f : t.hz) if (f > 0.0f) ++n;
    return static_cast<float>(n) / static_cast<float>(t.hz.size());
}


/**
 * Where the energy sits, in Hz: the magnitude-weighted mean over the band a
 * voice lives in. Moving the formants moves this; moving the pitch under a
 * fixed set of formants barely does, which is exactly the difference the
 * machine exists to make and so is exactly what to measure.
 */
float centroid(const std::vector<float> &x, int32_t from) {
    constexpr int32_t kN = 8192;
    dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from + i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < x.size() ? x[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    double num = 0.0, den = 0.0;
    for (int32_t k = 1; k < kN / 2; ++k) {
        const float hz = static_cast<float>(k) * kSr / static_cast<float>(kN);
        if (hz < 200.0f || hz > 5000.0f) continue;
        const double m = std::sqrt(static_cast<double>(re[static_cast<size_t>(k)] * re[static_cast<size_t>(k)] +
                                                      im[static_cast<size_t>(k)] * im[static_cast<size_t>(k)]));
        num += m * hz;
        den += m;
    }
    return den > 0.0 ? static_cast<float>(num / den) : 0.0f;
}

/** How much of [hz] is in there, for asking whether a chord has both notes. */
float magnitudeAt(const std::vector<float> &x, int32_t from, float hz) {
    constexpr int32_t kN = 8192;
    dsp::Fft fft(kN);
    std::vector<float> re(static_cast<size_t>(kN), 0.0f), im(static_cast<size_t>(kN), 0.0f);
    for (int32_t i = 0; i < kN; ++i) {
        const size_t at = static_cast<size_t>(from + i);
        const float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) *
                                               static_cast<float>(i) / static_cast<float>(kN - 1));
        re[static_cast<size_t>(i)] = (at < x.size() ? x[at] : 0.0f) * w;
    }
    fft.transform(re.data(), im.data(), false);
    const int32_t k = static_cast<int32_t>(hz * static_cast<float>(kN) / kSr + 0.5f);
    float best = 0.0f;
    for (int32_t j = k - 2; j <= k + 2; ++j) {
        if (j < 1 || j >= kN / 2) continue;
        best = std::max(best, std::sqrt(re[static_cast<size_t>(j)] * re[static_cast<size_t>(j)] +
                                        im[static_cast<size_t>(j)] * im[static_cast<size_t>(j)]));
    }
    return best;
}

/** Play a take through a Molt and hand back what came out, in mono. */
std::vector<float> play(audio::Utterance &u, float seconds, const std::vector<int> &notes,
                        const std::vector<std::pair<int32_t, float>> &overrides = {}) {
    machine::Molt m;
    m.prepare(static_cast<int32_t>(kSr));
    int32_t count = 0;
    const ParamDef *defs = m.paramDefs(count);
    auto setp = [&](int32_t p, float value) { m.params().set(p, defs[p].unmap(value)); };
    // A test wants to hear the machine, not its envelope or its correction.
    setp(machine::Molt::AmpAttack, 0.001f);
    setp(machine::Molt::AmpRelease, 0.01f);
    setp(machine::Molt::Tune, 1.0f);
    setp(machine::Molt::Rate, 0.0f);
    setp(machine::Molt::VelocityAmount, 0.0f);
    for (const auto &kv : overrides) setp(kv.first, kv.second);
    m.params().jumpAll();
    m.swapObject(0, &u);
    for (int note : notes) m.noteOn(static_cast<uint8_t>(note), 100);

    constexpr int32_t kBlock = 64;
    const int32_t total = static_cast<int32_t>(kSr * seconds);
    std::vector<float> out;
    out.reserve(static_cast<size_t>(total));
    float L[kBlock], R[kBlock];
    for (int32_t at = 0; at < total; at += kBlock) {
        for (int32_t i = 0; i < kBlock; ++i) { L[i] = 0.0f; R[i] = 0.0f; }
        m.render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(0.5f * (L[i] + R[i]));
    }
    return out;
}

/** The pitch of a rendered stretch, skipping its first tenth of a second. */
float pitchOf(const std::vector<float> &x) {
    PitchTrack t;
    std::vector<float> tail(x.begin() + std::min<size_t>(x.size(), static_cast<size_t>(kSr * 0.15f)),
                            x.end());
    t.find(tail, static_cast<int32_t>(tail.size()), kSr);
    return medianVoiced(t);
}

} // namespace

int main() {
    std::printf("Molt\n");

    // --- the analyser ------------------------------------------------------
    std::printf("\nthe pitch track finds what it was given\n");
    for (float f0 : {98.0f, 147.0f, 220.0f, 330.0f}) {
        PitchTrack t;
        const std::vector<float> v = vowel(f0, 0.5f);
        t.find(v, static_cast<int32_t>(v.size()), kSr);
        const float got = medianVoiced(t);
        char detail[96];
        std::snprintf(detail, sizeof(detail), "(%.1f Hz asked, %.1f found, %+.1f cents)",
                      static_cast<double>(f0), static_cast<double>(got),
                      static_cast<double>(got > 0.0f ? cents(got, f0) : 0.0f));
        char what[64];
        std::snprintf(what, sizeof(what), "%.0f Hz within ten cents", static_cast<double>(f0));
        check(got > 0.0f && std::abs(cents(got, f0)) < 10.0f, what, detail);
    }

    {
        PitchTrack t;
        const std::vector<float> v = vowel(147.0f, 0.5f);
        t.find(v, static_cast<int32_t>(v.size()), kSr);
        char detail[64];
        std::snprintf(detail, sizeof(detail), "(%.0f%% of hops)", static_cast<double>(voicedShare(t) * 100.0f));
        check(voicedShare(t) > 0.9f, "a held vowel is voiced throughout", detail);
    }

    {
        PitchTrack t;
        const std::vector<float> n = noise(0.5f);
        t.find(n, static_cast<int32_t>(n.size()), kSr);
        char detail[64];
        std::snprintf(detail, sizeof(detail), "(%.0f%% of hops called voiced)",
                      static_cast<double>(voicedShare(t) * 100.0f));
        check(voicedShare(t) < 0.15f, "noise is not called pitched", detail);
    }

    // --- the epochs --------------------------------------------------------
    std::printf("\nthe pitch marks land one period apart\n");
    {
        Utterance u;
        u.mono = vowel(200.0f, 0.4f);
        u.analyse(kSr);
        const float want = kSr / 200.0f;
        int32_t voiced = 0;
        float worst = 0.0f;
        for (size_t i = 1; i < u.epochs.size(); ++i) {
            if (!u.epochs[i].voiced) continue;
            ++voiced;
            const float gap = static_cast<float>(u.epochs[i].at - u.epochs[i - 1].at);
            worst = std::max(worst, std::abs(gap - want));
        }
        char detail[96];
        std::snprintf(detail, sizeof(detail), "(%d marks, worst gap off by %.1f of %.0f frames)",
                      voiced, static_cast<double>(worst), static_cast<double>(want));
        check(voiced > 50 && worst < want * 0.35f, "a 200 Hz vowel is marked every period", detail);
    }

    {
        Utterance u;
        u.mono = noise(0.2f);
        u.analyse(kSr);
        int32_t voiced = 0;
        for (const auto &e : u.epochs) if (e.voiced) ++voiced;
        char detail[64];
        std::snprintf(detail, sizeof(detail), "(%d of %zu marks voiced)", voiced, u.epochs.size());
        check(!u.epochs.empty() && voiced * 10 < static_cast<int32_t>(u.epochs.size()),
              "noise still gets marks, and they are unvoiced", detail);
    }

    {
        Utterance u;
        u.mono = vowel(150.0f, 0.3f);
        u.analyse(kSr);
        bool ordered = true;
        for (size_t i = 1; i < u.epochs.size(); ++i) {
            if (u.epochs[i].at <= u.epochs[i - 1].at) ordered = false;
        }
        check(ordered, "marks are strictly in order");
        const int32_t mid = u.epochAt(static_cast<float>(u.frames) * 0.5f);
        check(mid > 0 && u.epochs[static_cast<size_t>(mid)].at <= u.frames / 2 &&
                  (mid + 1 >= static_cast<int32_t>(u.epochs.size()) ||
                   u.epochs[static_cast<size_t>(mid + 1)].at > u.frames / 2),
              "epochAt lands on the mark before a position");
    }

    // --- the machine -------------------------------------------------------
    std::printf("\nthe note written is the note sung\n");
    audio::Utterance take;
    take.mono = vowel(180.0f, 3.0f);
    take.analyse(kSr);
    {
        char detail[96];
        std::snprintf(detail, sizeof(detail), "(root found at %.1f Hz, %zu marks)",
                      static_cast<double>(take.rootHz), take.epochs.size());
        check(std::abs(cents(take.rootHz, 180.0f)) < 10.0f, "the take knows its own pitch", detail);
    }

    for (int note : {55, 60, 67}) {
        const std::vector<float> out = play(take, 1.2f, {note});
        const float want = 440.0f * std::exp2((static_cast<float>(note) - 69.0f) / 12.0f);
        const float got = pitchOf(out);
        char detail[112];
        std::snprintf(detail, sizeof(detail), "(%.1f Hz wanted, %.1f sung, %+.1f cents)",
                      static_cast<double>(want), static_cast<double>(got),
                      static_cast<double>(got > 0.0f ? cents(got, want) : 0.0f));
        char what[64];
        std::snprintf(what, sizeof(what), "note %d comes out at its own pitch", note);
        check(got > 0.0f && std::abs(cents(got, want)) < 15.0f, what, detail);
    }

    std::printf("\npitch and formant move independently\n");
    {
        const std::vector<float> low = play(take, 1.2f, {60});
        const std::vector<float> high = play(take, 1.2f, {67});
        const int32_t at = static_cast<int32_t>(kSr * 0.3f);
        const float pitchRatio = pitchOf(high) / pitchOf(low);
        const float centroidRatio = centroid(high, at) / centroid(low, at);
        char detail[128];
        std::snprintf(detail, sizeof(detail), "(pitch x%.3f, energy x%.3f)",
                      static_cast<double>(pitchRatio), static_cast<double>(centroidRatio));
        check(std::abs(pitchRatio - 1.4983f) < 0.03f, "a fifth up moves the pitch a fifth", detail);
        check(std::abs(centroidRatio - 1.0f) < 0.15f, "and leaves the formants where they were", detail);
    }
    {
        const std::vector<float> plain = play(take, 1.2f, {60});
        const std::vector<float> big = play(take, 1.2f, {60}, {{machine::Molt::Formant, 7.0f}});
        const int32_t at = static_cast<int32_t>(kSr * 0.3f);
        const float pitchRatio = pitchOf(big) / pitchOf(plain);
        const float centroidRatio = centroid(big, at) / centroid(plain, at);
        char detail[128];
        std::snprintf(detail, sizeof(detail), "(pitch x%.3f, energy x%.3f)",
                      static_cast<double>(pitchRatio), static_cast<double>(centroidRatio));
        check(std::abs(centroidRatio - 1.4983f) < 0.22f, "a fifth of formant moves the formants", detail);
        check(std::abs(1200.0f * std::log2(pitchRatio)) < 15.0f, "and leaves the pitch where it was", detail);
    }

    std::printf("\nconsonants are carried, not tuned\n");
    {
        audio::Utterance hiss;
        hiss.mono = noise(1.5f);
        hiss.analyse(kSr);
        const std::vector<float> out = play(hiss, 1.0f, {60});
        const int32_t at = static_cast<int32_t>(kSr * 0.3f);
        const float before = centroid(hiss.mono, at);
        const float after = centroid(out, at);
        char detail[112];
        std::snprintf(detail, sizeof(detail), "(%.0f Hz in, %.0f Hz out)",
                      static_cast<double>(before), static_cast<double>(after));
        check(after > before * 0.7f && after < before * 1.4f,
              "noise keeps its own spectrum under a note", detail);
    }

    std::printf("\none head, and a chord on it\n");
    {
        const float c = 440.0f * std::exp2((60.0f - 69.0f) / 12.0f);
        const float g = 440.0f * std::exp2((67.0f - 69.0f) / 12.0f);
        const int32_t at = static_cast<int32_t>(kSr * 0.3f);
        const std::vector<float> alone = play(take, 1.2f, {60});
        const std::vector<float> both = play(take, 1.2f, {60, 67});
        const float gAlone = magnitudeAt(alone, at, g);
        const float gBoth = magnitudeAt(both, at, g);
        const float cBoth = magnitudeAt(both, at, c);
        char detail[128];
        std::snprintf(detail, sizeof(detail), "(the fifth is x%.1f louder with both held)",
                      static_cast<double>(gAlone > 1e-6f ? gBoth / gAlone : 0.0f));
        check(gBoth > gAlone * 3.0f && cBoth > 0.0f, "two notes sing two pitches at once", detail);
    }

    // --- determinism, which reset_test cannot reach here --------------------
    //
    // That harness panics every machine and compares two renders, but a
    // machine with nothing mounted renders silence and silence proves
    // nothing. With a take in it there is a read head, four overlap-add
    // rings, a filter and an envelope to rewind, so it is worth doing here
    // where a take exists.
    std::printf("\nwhat a panic rewinds\n");
    {
        machine::Molt m;
        m.prepare(static_cast<int32_t>(kSr));
        int32_t count = 0;
        const ParamDef *defs = m.paramDefs(count);
        auto setp = [&](int32_t p, float value) { m.params().set(p, defs[p].unmap(value)); };
        setp(machine::Molt::Formant, 4.0f);
        setp(machine::Molt::Mega, 0.4f);
        setp(machine::Molt::Drive, 0.3f);
        setp(machine::Molt::Cutoff, 3000.0f);
        setp(machine::Molt::Tune, 0.6f);
        setp(machine::Molt::Rate, 60.0f);
        m.params().jumpAll();
        m.swapObject(0, &take);

        constexpr int32_t kBlock = 64;
        auto once = [&]() {
            std::vector<float> out;
            m.reset();
            m.params().jumpAll();
            m.noteOn(60, 100);
            m.noteOn(64, 90);
            float L[kBlock], R[kBlock];
            for (int32_t at = 0; at < static_cast<int32_t>(kSr * 0.8f); at += kBlock) {
                for (int32_t i = 0; i < kBlock; ++i) { L[i] = 0.0f; R[i] = 0.0f; }
                if (at == static_cast<int32_t>(kSr * 0.4f) / kBlock * kBlock) m.noteOff(64);
                m.render(L, R, kBlock);
                for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
            }
            return out;
        };
        const std::vector<float> first = once();
        const std::vector<float> second = once();
        int32_t differ = 0;
        float loudest = 0.0f;
        for (size_t i = 0; i < first.size(); ++i) {
            if (first[i] != second[i]) ++differ;
            loudest = std::max(loudest, std::abs(first[i]));
        }
        char detail[112];
        std::snprintf(detail, sizeof(detail), "(%d of %zu samples differ, peak %.3f)", differ,
                      first.size(), static_cast<double>(loudest));
        check(differ == 0 && loudest > 0.01f, "the same performance twice, bit for bit", detail);
    }

    std::printf("\n%s\n", failures == 0 ? "all ok" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
