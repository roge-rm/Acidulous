// Tests slicing a file across Forage's pads.
//
// Three cases matter: a loop with exactly the right number of hits should
// land on the hits, one with far more hits than pads should spread its cuts
// across the file instead of crowding the loudest part, and one with almost no
// hits should divide evenly instead of leaving pads empty.
#include <cmath>
#include <cstdio>
#include <engine/core/Slices.h>

using namespace acidulous;
using namespace acidulous::audio;

namespace {
int gChecks = 0, gFails = 0;
void check(bool ok, const char *what) {
    ++gChecks;
    if (!ok) { ++gFails; std::printf("  FAIL %s\n", what); }
    else std::printf("  ok   %s\n", what);
}

constexpr float kSr = 48000.0f;

/** A loop of [hits] drum hits, evenly spaced, each a decaying burst. */
SampleData loop(int hits, float seconds, float gainOfNth = 1.0f, int quietOne = -1) {
    SampleData s;
    s.frames = static_cast<int32_t>(kSr * seconds);
    s.left.assign(static_cast<size_t>(s.frames), 0.0f);
    s.stereo = false;
    if (hits <= 0) return s;
    const int32_t step = s.frames / hits;
    uint32_t rng = 12345u;
    for (int h = 0; h < hits; ++h) {
        const int32_t at = h * step;
        const float g = (h == quietOne) ? gainOfNth : 1.0f;
        for (int32_t i = 0; i < step && at + i < s.frames; ++i) {
            rng = rng * 1664525u + 1013904223u;
            const float noise = static_cast<float>(rng >> 8) / 8388608.0f - 1.0f;
            const float env = std::exp(-static_cast<float>(i) / (kSr * 0.03f));
            s.left[static_cast<size_t>(at + i)] = noise * env * 0.8f * g;
        }
    }
    s.measure();
    return s;
}

bool ascending(const std::vector<float> &p) {
    for (size_t i = 1; i < p.size(); ++i) if (p[i] < p[i - 1]) return false;
    return p.front() == 0.0f && p.back() == 1.0f;
}
} // namespace

int main() {
    // Eight hits, eight pads asked for: the cuts should land on them.
    {
        const SampleData s = loop(8, 4.0f);
        const auto p = slicePoints(s, SliceMode::Transients, 8, kSr);
        check(p.size() == 9, "eight slices give nine boundaries");
        check(ascending(p), "boundaries ascend from 0 to 1");
        float worst = 0.0f;
        for (int i = 0; i < 8; ++i) worst = std::max(worst, std::fabs(p[static_cast<size_t>(i)] - i / 8.0f));
        std::printf("       worst cut is %.4f of the file from the hit\n", static_cast<double>(worst));
        check(worst < 0.02f, "every cut lands on a hit");
    }
    // Thirty-two hits, thirteen pads: the loudest thirteen, still in order.
    {
        const SampleData s = loop(32, 8.0f);
        const auto p = slicePoints(s, SliceMode::Transients, 13, kSr);
        check(p.size() == 14, "thirteen slices from a busier loop");
        check(ascending(p), "still in order after picking the loudest");
    }
    // Two hits, thirteen pads: too few to slice, so divide evenly.
    {
        const SampleData s = loop(2, 4.0f);
        const auto p = slicePoints(s, SliceMode::Transients, 13, kSr);
        check(p.size() == 14, "too few transients still fills the pads");
        float worst = 0.0f;
        for (int i = 0; i < 13; ++i) worst = std::max(worst, std::fabs(p[static_cast<size_t>(i)] - i / 13.0f));
        check(worst < 0.001f, "and falls back to an even division");
    }
    // Even mode ignores the audio entirely.
    {
        const SampleData s = loop(8, 4.0f);
        const auto p = slicePoints(s, SliceMode::Even, 5, kSr);
        check(p.size() == 6 && ascending(p), "even mode gives equal pieces");
        check(std::fabs(p[2] - 0.4f) < 1e-5f, "and they are actually equal");
    }
    // Degenerate input that should give nothing.
    {
        SampleData empty;
        check(slicePoints(empty, SliceMode::Transients, 13, kSr).empty(), "an empty file gives nothing");
        const SampleData s = loop(4, 2.0f);
        check(slicePoints(s, SliceMode::Even, 0, kSr).size() == 2, "a count of zero is treated as one");
        check(slicePoints(s, SliceMode::Transients, 1, kSr).size() == 2, "one slice is the whole file");
    }
    // A whole song. Sixty hits over four minutes, with the first quarter twice
    // as loud, like an intro or chorus. Keeping just the loudest thirteen put
    // every cut in that quarter and left the other three minutes as one slice.
    {
        SampleData s;
        s.frames = static_cast<int32_t>(kSr * 240.0f);
        s.left.assign(static_cast<size_t>(s.frames), 0.0f);
        s.stereo = false;
        const int hits = 60;
        const int32_t step = s.frames / hits;
        uint32_t rng = 999u;
        for (int h = 0; h < hits; ++h) {
            const float g = h < hits / 4 ? 1.0f : 0.45f; // the loud opening
            for (int32_t i = 0; i < step && h * step + i < s.frames; ++i) {
                rng = rng * 1664525u + 1013904223u;
                const float noise = static_cast<float>(rng >> 8) / 8388608.0f - 1.0f;
                const float env = std::exp(-static_cast<float>(i) / (kSr * 0.03f));
                s.left[static_cast<size_t>(h * step + i)] = noise * env * 0.8f * g;
            }
        }
        s.measure();
        const auto p = slicePoints(s, SliceMode::Transients, 13, kSr);
        check(p.size() == 14 && ascending(p), "a four minute file gives thirteen slices in order");
        // Every cut falls inside its own thirteenth of the file.
        bool spread = true;
        for (int i = 0; i < 13; ++i) {
            const float lo = static_cast<float>(i) / 13.0f, hi = static_cast<float>(i + 1) / 13.0f;
            if (p[static_cast<size_t>(i)] < lo - 1e-4f || p[static_cast<size_t>(i)] > hi + 1e-4f) spread = false;
        }
        check(spread, "and each one falls inside its own stretch of the file");
        // No slice is more than twice its fair share. The bug left one slice
        // holding nine tenths of the track.
        float longest = 0.0f;
        for (size_t i = 1; i < p.size(); ++i) longest = std::max(longest, p[i] - p[i - 1]);
        char msg[120];
        std::snprintf(msg, sizeof(msg), "and no slice runs away with the song (longest %.1f%% of it)",
                      longest * 100.0f);
        check(longest < 2.0f / 13.0f, msg);
        // Cuts still land on hits, not on the plain division.
        int onHits = 0;
        for (size_t i = 1; i < p.size() - 1; ++i) {
            const auto at = static_cast<int32_t>(p[i] * static_cast<float>(s.frames));
            if (std::abs(at % step) < static_cast<int32_t>(kSr * 0.02f) ||
                std::abs(at % step - step) < static_cast<int32_t>(kSr * 0.02f)) {
                ++onHits;
            }
        }
        std::snprintf(msg, sizeof(msg), "and still lands on transients (%d of 12)", onHits);
        check(onHits >= 10, msg);
    }

    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
