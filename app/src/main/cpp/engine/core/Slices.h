#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <engine/core/Sample.h>
#include <engine/core/Take.h>
#include <vector>

// Where to cut one file so it lands across a set of pads.
//
// Not in EngineHost, though that is its only caller today: the host is JNI
// plumbing and this is a decision about audio, which means it wants a test
// and the host cannot have one - it does not link outside the app.
namespace acidulous::audio {

enum class SliceMode : int { Transients = 0, Even = 1 };

/**
 * [count] + 1 boundaries as fractions of the file's length, ascending, from
 * 0 to 1. Slice n runs from boundary n to boundary n + 1, so a caller has a
 * start and an end for every pad without arithmetic of its own.
 *
 * Transients are found with the same detector Dice and Pollen read. A
 * detector finds what it finds and a player asked for a number of pads, so:
 * more transients than pads keeps the loudest, which on a drum loop is the
 * kick and the snare rather than the ghost notes between them; fewer falls
 * back to an even division, because thirteen pads of which nine are empty is
 * not what anybody meant by "slice this".
 */
inline std::vector<float> slicePoints(const SampleData &s, SliceMode mode, int count, float sampleRate) {
    if (count < 1) count = 1;
    std::vector<float> out;
    if (s.frames <= 0) return out;

    std::vector<int32_t> cuts;
    if (mode == SliceMode::Transients) {
        Take take;
        take.frames = s.frames;
        take.left = s.left;
        take.right = s.stereo && !s.right.empty() ? s.right : s.left;
        take.detect(sampleRate);
        cuts = take.onsets;
        if (static_cast<int>(cuts.size()) > count) {
            const auto window = static_cast<int32_t>(sampleRate * 0.02f);
            std::vector<std::pair<float, int32_t>> byLevel;
            byLevel.reserve(cuts.size());
            for (int32_t at : cuts) {
                float peak = 0.0f;
                const int32_t to = std::min(s.frames, at + window);
                for (int32_t i = at; i < to; ++i) {
                    peak = std::max(peak, std::fabs(s.left[static_cast<size_t>(i)]));
                }
                byLevel.emplace_back(peak, at);
            }
            std::stable_sort(byLevel.begin(), byLevel.end(),
                             [](const auto &a, const auto &b) { return a.first > b.first; });
            byLevel.resize(static_cast<size_t>(count));
            cuts.clear();
            for (const auto &e : byLevel) cuts.push_back(e.second);
            std::sort(cuts.begin(), cuts.end());
        }
        if (static_cast<int>(cuts.size()) < count) cuts.clear();
    }
    if (cuts.empty()) {
        cuts.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            cuts.push_back(static_cast<int32_t>(static_cast<int64_t>(s.frames) * i / count));
        }
    }
    // The first slice starts at the top of the file whatever the detector
    // said: a loop whose first transient is two hundred samples in would
    // otherwise throw those away, and they are the attack.
    cuts[0] = 0;

    out.reserve(cuts.size() + 1);
    for (int32_t at : cuts) out.push_back(static_cast<float>(at) / static_cast<float>(s.frames));
    out.push_back(1.0f);
    return out;
}

} // namespace acidulous::audio
