// What a recording needs doing to it, checked one operation at a time.
//
// These run on a buffer built here rather than on a file, because a file is a
// second thing to be wrong about: every claim below is about arithmetic, and
// the reader and the writer already have `format_test` next door.
//
// Each check is named for the way the operation can go wrong rather than for
// what it does, so a failure says what happened.
#include <engine/core/SampleEdit.h>
#include <engine/dsp/MultiFilter.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace acidulous;
using namespace acidulous::audio;

namespace {

constexpr int32_t kSr = 48000;
int failures = 0;

void check(bool ok, const char *what, const char *detail = "") {
    std::printf("  %-54s %s %s\n", what, ok ? "ok" : "FAIL", detail);
    if (!ok) ++failures;
}

/** A sine at [hz], [seconds] long, at [level]. Stereo unless asked otherwise. */
SampleData tone(float hz, float seconds, float level = 0.5f, bool stereo = true) {
    SampleData d;
    d.frames = static_cast<int32_t>(static_cast<float>(kSr) * seconds);
    d.rate = kSr;
    d.stereo = stereo;
    d.left.resize(static_cast<size_t>(d.frames));
    if (stereo) d.right.resize(static_cast<size_t>(d.frames));
    for (int32_t i = 0; i < d.frames; ++i) {
        const auto v = level * std::sin(2.0f * 3.14159265f * hz * static_cast<float>(i) /
                                        static_cast<float>(kSr));
        d.left[static_cast<size_t>(i)] = v;
        if (stereo) d.right[static_cast<size_t>(i)] = v;
    }
    d.measure();
    return d;
}

float peakOf(const SampleData &d) {
    float p = 0.0f;
    for (float v : d.left) p = std::max(p, std::abs(v));
    for (float v : d.right) p = std::max(p, std::abs(v));
    return p;
}

float rmsOf(const SampleData &d) {
    double sum = 0.0;
    for (float v : d.left) sum += static_cast<double>(v) * v;
    return d.left.empty() ? 0.0f
                          : static_cast<float>(std::sqrt(sum / static_cast<double>(d.left.size())));
}

/** How much of [hz] is in there, by correlating against it. */
float amountOf(const SampleData &d, float hz) {
    double re = 0.0, im = 0.0;
    for (int32_t i = 0; i < d.frames; ++i) {
        const double t = 2.0 * 3.14159265358979 * hz * i / kSr;
        re += d.left[static_cast<size_t>(i)] * std::cos(t);
        im += d.left[static_cast<size_t>(i)] * std::sin(t);
    }
    return static_cast<float>(2.0 * std::sqrt(re * re + im * im) / std::max(1, d.frames));
}

float crestOf(const SampleData &d) {
    const float r = rmsOf(d);
    return r > 1e-9f ? peakOf(d) / r : 0.0f;
}

} // namespace

int main() {
    char detail[160];

    std::printf("a crop keeps what it was asked for\n");
    {
        SampleData d = tone(440.0f, 1.0f);
        const float at = d.left[static_cast<size_t>(kSr / 4)];
        cropTo(d, kSr / 4, kSr / 2);
        std::snprintf(detail, sizeof(detail), "(%d frames, wanted %d)", d.frames, kSr / 4);
        check(d.frames == kSr / 4, "the length is the range, exactly", detail);
        check(std::abs(d.left[0] - at) < 1e-6f, "and it starts where it was cut");
        check(d.right.size() == d.left.size(), "both channels the same length");
    }
    {
        // `to` at or before `from` is "to the end", which is what a screen
        // hands over before anybody has dragged the right-hand handle.
        SampleData d = tone(440.0f, 0.5f);
        cropTo(d, kSr / 10, 0);
        std::snprintf(detail, sizeof(detail), "(%d frames)", d.frames);
        check(d.frames == kSr / 2 - kSr / 10, "an open-ended crop runs to the end", detail);
    }
    {
        // An end at or before the start is the same "to the end" the screen
        // hands over, not an error: the handles cannot cross, so the only way
        // to get here is by not having set one.
        SampleData d = tone(440.0f, 0.2f);
        const int32_t was = d.frames;
        cropTo(d, 5000, 4000);
        std::snprintf(detail, sizeof(detail), "(%d frames, wanted %d)", d.frames, was - 5000);
        check(d.frames == was - 5000, "an end before the start means the end", detail);
    }
    {
        SampleData d = tone(440.0f, 0.2f);
        const int32_t was = d.frames;
        cropTo(d, -100, 1 << 30);
        check(d.frames == was, "a range outside the file is clamped to it");
    }

    std::printf("\na fade reaches nought and does not eat the sound\n");
    {
        SampleData d = tone(440.0f, 1.0f);
        fadeEnds(d, kSr / 10, kSr / 10);
        check(d.left[0] == 0.0f, "the first sample is silent");
        check(std::abs(d.left[static_cast<size_t>(d.frames - 1)]) < 1e-3f,
              "and so is the last");
        bool rises = true;
        float worst = 0.0f;
        for (int32_t i = 1; i < kSr / 10; ++i) {
            const float a = std::abs(d.left[static_cast<size_t>(i)]);
            worst = std::max(worst, a);
            if (worst > 0.5f + 1e-4f) rises = false;
        }
        check(rises, "the envelope never overshoots what it started at");
        check(std::abs(peakOf(d) - 0.5f) < 1e-3f, "and the middle is untouched");
    }
    {
        // Two fades longer than the file would otherwise multiply in the
        // middle and leave a hole where the sound is.
        SampleData d = tone(440.0f, 0.1f);
        fadeEnds(d, d.frames, d.frames);
        check(peakOf(d) > 0.0f, "two fades that overlap do not cancel the file");
    }

    std::printf("\nnormalise puts the peak where it was asked\n");
    {
        SampleData d = tone(440.0f, 0.5f, 0.1f);
        normalisePeak(d, 0.9f);
        std::snprintf(detail, sizeof(detail), "(peak %.4f)", static_cast<double>(peakOf(d)));
        check(std::abs(peakOf(d) - 0.9f) < 1e-3f, "exactly 0.9, from 0.1", detail);
    }
    {
        SampleData d = tone(440.0f, 0.1f, 0.0f);
        normalisePeak(d, 0.9f);
        check(peakOf(d) == 0.0f, "silence normalises to silence, not to noise");
    }

    std::printf("\nreverse is its own undo\n");
    {
        SampleData a = tone(440.0f, 0.3f);
        SampleData b = a;
        reverseInPlace(b);
        reverseInPlace(b);
        bool same = true;
        for (size_t i = 0; i < a.left.size(); ++i) {
            if (a.left[i] != b.left[i]) { same = false; break; }
        }
        check(same, "twice is the file it started as");
        SampleData c = a;
        reverseInPlace(c);
        check(c.left.front() == a.left.back() && c.left.back() == a.left.front(),
              "and once turns it end for end");
    }

    std::printf("\nthe low cut takes the room and leaves the voice\n");
    {
        // The case this exists for, measured: Dan's own take was 56% of its
        // energy under seventy hertz.
        SampleData d = tone(30.0f, 1.0f, 0.5f);
        for (int32_t i = 0; i < d.frames; ++i) {
            const float v = 0.5f * std::sin(2.0f * 3.14159265f * 300.0f * static_cast<float>(i) /
                                            static_cast<float>(kSr));
            d.left[static_cast<size_t>(i)] += v;
            d.right[static_cast<size_t>(i)] += v;
        }
        const float lowBefore = amountOf(d, 30.0f), highBefore = amountOf(d, 300.0f);
        filterInPlace(d, 70.0f, 0.0f, dsp::MultiFilter::HP12);
        const float lowAfter = amountOf(d, 30.0f), highAfter = amountOf(d, 300.0f);
        std::snprintf(detail, sizeof(detail), "(30 Hz %.3f -> %.3f, 300 Hz %.3f -> %.3f)",
                      static_cast<double>(lowBefore), static_cast<double>(lowAfter),
                      static_cast<double>(highBefore), static_cast<double>(highAfter));
        check(lowAfter < lowBefore * 0.25f, "thirty hertz is most of the way gone", detail);
        check(highAfter > highBefore * 0.8f, "three hundred is still there", detail);
    }

    std::printf("\nthe compressor squeezes and nothing else does\n");
    {
        // A tone with one loud burst in it: the thing a compressor is for, and
        // the thing a gain cannot do.
        SampleData d = tone(440.0f, 1.0f, 0.2f);
        for (int32_t i = kSr / 2; i < kSr / 2 + kSr / 20; ++i) {
            d.left[static_cast<size_t>(i)] *= 4.0f;
            d.right[static_cast<size_t>(i)] *= 4.0f;
        }
        const float before = crestOf(d);
        compressInPlace(d, 0.8f, 5.0f, 80.0f);
        const float after = crestOf(d);
        std::snprintf(detail, sizeof(detail), "(crest %.2f -> %.2f)",
                      static_cast<double>(before), static_cast<double>(after));
        check(after < before * 0.85f, "the crest factor comes down", detail);
        check(peakOf(d) > 0.1f, "and the makeup puts the level back");
        // The check the look-ahead exists for: a feed-forward compressor lets
        // the front of the burst through at full height and reads *worse*
        // than the file it was given.
        float front = 0.0f;
        for (int32_t i = kSr / 2; i < kSr / 2 + kSr / 200; ++i) {
            front = std::max(front, std::abs(d.left[static_cast<size_t>(i)]));
        }
        std::snprintf(detail, sizeof(detail), "(first 5 ms of the burst peaks %.3f of %.3f)",
                      static_cast<double>(front), static_cast<double>(peakOf(d)));
        check(front < peakOf(d) * 1.02f, "the attack does not overshoot into the burst", detail);
    }
    {
        SampleData a = tone(440.0f, 0.2f);
        SampleData b = a;
        compressInPlace(b, 0.0f, 10.0f, 100.0f);
        bool same = true;
        for (size_t i = 0; i < a.left.size(); ++i) {
            if (a.left[i] != b.left[i]) { same = false; break; }
        }
        check(same, "at nought it is not in the signal at all");
    }

    std::printf("\nan empty edit is a copy, and the order is the order\n");
    {
        SampleData a = tone(440.0f, 0.3f);
        SampleData b = a;
        std::string error;
        check(applyEdit(b, SampleOps{}, error), "a default SampleOps succeeds");
        bool same = b.frames == a.frames;
        for (size_t i = 0; same && i < a.left.size(); ++i) {
            if (a.left[i] != b.left[i]) same = false;
        }
        check(same, "and changes nothing at all");
    }
    {
        // Fades last: a fade applied before the level was set would be scaled
        // back up by the normalise and the ends would not be silent.
        SampleData d = tone(440.0f, 1.0f, 0.05f);
        SampleOps ops;
        ops.normaliseTo = 0.9f;
        ops.fadeInMs = 50.0f;
        ops.fadeOutMs = 50.0f;
        std::string error;
        check(applyEdit(d, ops, error), "normalise and fade together");
        std::snprintf(detail, sizeof(detail), "(first %.6f, peak %.3f)",
                      static_cast<double>(d.left[0]), static_cast<double>(peakOf(d)));
        check(d.left[0] == 0.0f && std::abs(peakOf(d) - 0.9f) < 1e-3f,
              "the ends are still silent and the peak is still right", detail);
    }
    {
        SampleData d = tone(440.0f, 0.2f);
        SampleOps ops;
        ops.from = 1000;
        ops.to = 900;
        std::string error;
        d.frames = 0;
        d.left.clear();
        d.right.clear();
        check(!applyEdit(d, ops, error) && !error.empty(), "an empty file is an error, not a crash");
    }

    std::printf("\na mono file stays mono\n");
    {
        SampleData d = tone(440.0f, 0.3f, 0.5f, false);
        SampleOps ops;
        ops.normaliseTo = 0.8f;
        ops.reverse = true;
        ops.from = 1000;
        std::string error;
        check(applyEdit(d, ops, error), "the lot, on one channel");
        check(d.right.empty() && !d.stereo, "and the right channel was not invented");
        check(std::abs(peakOf(d) - 0.8f) < 1e-3f, "with the peak where it was asked");
    }

    std::printf("\n%s\n", failures == 0 ? "all ok" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
