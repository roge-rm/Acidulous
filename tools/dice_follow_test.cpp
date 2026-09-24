// A loop in Dice, played at a song's tempo rather than its own.
//
// Two halves. The guess: a loop's bars from its length and where its hits fall,
// which is what everything else is worked out from. And the playing: a slice
// that follows has to last its share of the song's bar, sing the note it was
// recorded at, and arrive with its attack - the stretcher's first hop fades in
// unless Dice puts back what the fade takes away.
#include <cmath>
#include <cstdio>
#include <vector>
#include <algorithm>
#include <engine/core/Take.h>
#include <engine/dsp/Wsola.h>
#include <engine/machine/dice/Dice.h>

using namespace acidulous;
using namespace acidulous::audio;
using acidulous::machine::Dice;

namespace {
int gChecks = 0, gFails = 0;
void check(bool ok, const char *what) {
    ++gChecks;
    if (!ok) { ++gFails; std::printf("  FAIL %s\n", what); }
    else std::printf("  ok   %s\n", what);
}

constexpr float kSr = 48000.0f;

/** [bars] of 4/4 at [bpm], with a burst on every [perBeat]th of a beat. */
Take hits(float bpm, float bars, int perBeat) {
    Take t;
    t.frames = static_cast<int32_t>(bars * 4.0f * 60.0f / bpm * kSr);
    t.left.assign(static_cast<size_t>(t.frames), 0.0f);
    const float step = 60.0f / bpm / static_cast<float>(perBeat) * kSr;
    for (float at = 0.0f; at < static_cast<float>(t.frames); at += step) {
        const int32_t s = static_cast<int32_t>(at);
        for (int32_t i = 0; i < 1200 && s + i < t.frames; ++i) {
            t.left[static_cast<size_t>(s + i)] = std::sin(i * 0.3f) * std::exp(-i / 200.0f);
        }
    }
    t.right = t.left;
    t.detect(kSr);
    return t;
}

/** [bars] of a steady tone at [hz], as if a loop at [bpm]. */
Take tone(float bpm, float bars, float hz) {
    Take t;
    t.frames = static_cast<int32_t>(bars * 4.0f * 60.0f / bpm * kSr);
    t.left.resize(static_cast<size_t>(t.frames));
    for (int32_t i = 0; i < t.frames; ++i) t.left[static_cast<size_t>(i)] = 0.5f * std::sin(6.2831853f * hz * i / kSr);
    t.right = t.left;
    t.detect(kSr);
    t.bars = bars; // a tone has no hits to guess from; say it
    return t;
}

void set(Dice &d, int32_t p, float native) {
    int32_t n = 0;
    const auto *defs = d.paramDefs(n);
    const auto &def = defs[p];
    float v01 = (native - def.min) / (def.max - def.min);
    d.params().set(p, v01);
}

/** One slice triggered, rendered until it stops; what came out. */
std::vector<float> play(Dice &d, int slice, float songBpm, int32_t maxFrames) {
    d.params().jumpAll();
    d.onBlock(0, 0, songBpm);
    std::vector<float> out;
    std::vector<float> L(256), R(256);
    d.render(L.data(), R.data(), 256); // slices are cut on the first render
    d.noteOn(static_cast<uint8_t>(Dice::kBaseNote + slice), 127);
    for (int32_t done = 0; done < maxFrames; done += 256) {
        d.onBlock(0, 0, songBpm);
        d.render(L.data(), R.data(), 256);
        out.insert(out.end(), L.begin(), L.end());
    }
    return out;
}

/** How long it sounds for: the last frame above a whisper. */
int32_t soundingFor(const std::vector<float> &x) {
    for (int32_t i = static_cast<int32_t>(x.size()) - 1; i >= 0; --i) {
        if (std::fabs(x[static_cast<size_t>(i)]) > 1e-3f) return i + 1;
    }
    return 0;
}

/** The frequency of a steady tone, from its upward zero crossings. */
float frequency(const std::vector<float> &x, int32_t from, int32_t to) {
    int32_t first = -1, last = -1, count = 0;
    for (int32_t i = from + 1; i < to; ++i) {
        if (x[static_cast<size_t>(i - 1)] < 0.0f && x[static_cast<size_t>(i)] >= 0.0f) {
            if (first < 0) first = i;
            last = i;
            ++count;
        }
    }
    if (count < 2) return 0.0f;
    return static_cast<float>(count - 1) * kSr / static_cast<float>(last - first);
}

Dice *fresh(const Take &t, int slices) {
    auto *d = new Dice();
    d->prepare(static_cast<int32_t>(kSr));
    d->swapObject(0, const_cast<Take *>(&t));
    set(*d, Dice::CutMode, 1.0f); // an even grid, so a slice is a known length
    set(*d, Dice::SliceCount, static_cast<float>(slices));
    set(*d, Dice::Volume, 1.0f);
    return d;
}
} // namespace

int main() {
    std::printf("-- the guess\n");
    {
        Take a = hits(90.0f, 2.0f, 2);
        check(a.bars == 2.0f, "two bars of eighths at 90 is two bars");
        Take b = hits(126.0f, 1.0f, 4);
        check(b.bars == 1.0f, "a bar of sixteenths at 126 is one bar");
        Take c = hits(140.0f, 4.0f, 2);
        check(c.bars == 4.0f, "four bars at 140 is four bars");
        Take d = hits(100.0f, 0.5f, 4);
        check(d.bars == 0.5f, "half a bar at 100 is half a bar");
        std::printf("       (a %.1f, b %.1f, c %.1f, d %.1f)\n", a.bars, b.bars, c.bars, d.bars);
    }

    std::printf("-- following\n");
    const float loopBpm = 90.0f, songBpm = 126.0f;
    Take t = tone(loopBpm, 2.0f, 440.0f);
    const int32_t sliceLen = t.frames / 8;
    {
        Dice *d = fresh(t, 8);
        set(*d, Dice::Follow, 1.0f);
        const std::vector<float> out = play(*d, 2, songBpm, sliceLen * 2);
        const int32_t len = soundingFor(out);
        const float want = static_cast<float>(sliceLen) * loopBpm / songBpm;
        std::printf("       lasts %d frames, its share of the bar is %.0f\n", len, want);
        check(std::fabs(len - want) < want * 0.03f, "a following slice lasts its share of the song's bar");
        const float hz = frequency(out, len / 5, len * 4 / 5);
        std::printf("       sings %.1f Hz\n", hz);
        check(std::fabs(hz - 440.0f) < 4.0f, "and sings the note it was recorded at");
        delete d;
    }
    {
        Dice *d = fresh(t, 8);
        set(*d, Dice::Follow, 1.0f);
        set(*d, Dice::RootPitch, 12.0f);
        const std::vector<float> out = play(*d, 2, songBpm, sliceLen * 2);
        const int32_t len = soundingFor(out);
        const float want = static_cast<float>(sliceLen) * loopBpm / songBpm;
        const float hz = frequency(out, len / 5, len * 4 / 5);
        std::printf("       an octave up: %d frames, %.1f Hz\n", len, hz);
        check(std::fabs(len - want) < want * 0.03f && std::fabs(hz - 880.0f) < 8.0f,
              "up an octave, it is an octave up and still its share of the bar");
        delete d;
    }
    {
        Dice *d = fresh(t, 8);
        set(*d, Dice::Follow, 0.0f);
        const std::vector<float> out = play(*d, 2, songBpm, sliceLen * 2);
        const int32_t len = soundingFor(out);
        const float hz = frequency(out, len / 5, len * 4 / 5);
        std::printf("       not following: %d frames, %.1f Hz\n", len, hz);
        check(std::fabs(len - sliceLen) < sliceLen * 0.02f && std::fabs(hz - 440.0f) < 4.0f,
              "not following, it plays as it always did");
        delete d;
    }
    {
        // The downbeat: slice nought starts on the loop's first frame, where
        // there is no audio before it to fade in from.
        Take h = hits(loopBpm, 2.0f, 2);
        h.bars = 2.0f;
        // Against the same slice not following, which has Dice's own gain and
        // its half-millisecond ramp in and nothing of the stretcher's.
        auto first = [&](float follow) {
            Dice *d = fresh(h, 16);
            set(*d, Dice::Follow, follow);
            const std::vector<float> out = play(*d, 0, songBpm, 2400);
            delete d;
            float peak = 0.0f;
            for (int32_t i = 0; i < 200; ++i) peak = std::fmax(peak, std::fabs(out[static_cast<size_t>(i)]));
            return peak;
        };
        const float following = first(1.0f), straight = first(0.0f);
        std::printf("       first 4 ms: %.3f following, %.3f not\n", following, straight);
        check(std::fabs(following - straight) < straight * 0.05f, "the downbeat arrives with its attack");
    }

    std::printf("-- a loop through the stretcher's seam\n");
    {
        // A second of 440 Hz, a whole number of cycles so the loop itself is
        // seamless, stretched to follow a faster song through three seams.
        const int32_t n = 48000;
        std::vector<float> l(n), r(n);
        for (int32_t i = 0; i < n; ++i) l[static_cast<size_t>(i)] = r[static_cast<size_t>(i)] = 0.5f * std::sin(6.2831853f * 440.0f * i / kSr);
        dsp::StereoStretch st;
        st.prepare();
        st.setLoop(true);
        st.seek(0);
        const float rate = 1.4f;
        const int32_t outFrames = static_cast<int32_t>(3.5f * n / rate);
        std::vector<float> outL(static_cast<size_t>(outFrames)), outR(static_cast<size_t>(outFrames));
        int32_t made = 0;
        while (made < outFrames) {
            float *dst[2] = {outL.data() + made, outR.data() + made};
            const float *src[2] = {l.data(), r.data()};
            const int32_t got = st.fill(dst, src, 0, n, std::min(256, outFrames - made), rate);
            if (got <= 0) break;
            made += got;
        }
        check(made == outFrames, "a looping stretcher never runs out");
        // Quietest 10 ms anywhere after the first hop: a seam that dropped
        // audio would show here as a hole.
        float quietest = 1e9f;
        for (int32_t at = 720; at + 480 < made; at += 240) {
            float e = 0.0f;
            for (int32_t i = 0; i < 480; ++i) e += outL[static_cast<size_t>(at + i)] * outL[static_cast<size_t>(at + i)];
            quietest = std::fmin(quietest, std::sqrt(e / 480.0f));
        }
        std::printf("       quietest 10 ms: %.3f rms (a steady 0.5 sine is 0.354)\n", quietest);
        check(quietest > 0.3f, "and has no hole at the seam");
        const float hz = frequency(outL, 720, made);
        std::printf("       sings %.1f Hz across %d seams\n", hz, static_cast<int>(made * rate / n));
        check(std::fabs(hz - 440.0f) < 2.0f, "and keeps its pitch across them");
    }

    std::printf("%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
