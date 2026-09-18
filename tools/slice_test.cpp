// Cutting a file across Forage's pads.
//
// The interesting cases are not "does it return numbers" but the three the
// player will actually hit: a loop with exactly the right number of hits, one
// with far more than the pads can hold, and one with almost none. The first
// should land on the hits; the second should keep the loudest and stay in
// order; the third should quietly divide evenly rather than leave nine pads
// empty.
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
    // The degenerate ones nothing should survive.
    {
        SampleData empty;
        check(slicePoints(empty, SliceMode::Transients, 13, kSr).empty(), "an empty file gives nothing");
        const SampleData s = loop(4, 2.0f);
        check(slicePoints(s, SliceMode::Even, 0, kSr).size() == 2, "a count of zero is treated as one");
        check(slicePoints(s, SliceMode::Transients, 1, kSr).size() == 2, "one slice is the whole file");
    }
    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
