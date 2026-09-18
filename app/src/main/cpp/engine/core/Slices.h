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
 * enough transients means one pad per stretch of the file, each snapped to
 * the strongest onset in its own stretch; fewer than pads falls back to an
 * even division, because thirteen pads of which nine are empty is not what
 * anybody meant by "slice this".
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
        const std::vector<int32_t> &onsets = take.onsets;
        if (static_cast<int>(onsets.size()) >= count) {
            // **One cut per stretch of the file, not the loudest [count] in
            // it.** Taking the loudest overall is right about a drum loop and
            // wrong about anything longer: a whole song's loudest transients
            // are wherever the song is loudest, so a four minute track cut
            // into thirteen put twelve cuts in the first twenty seconds and
            // left the remaining two hundred as one slice. Measured on a real
            // one - the cuts came out at 0.0, 9.7, 10.2, 11.3 ... 20.0, 225.9.
            //
            // So the file is divided into [count] equal stretches and each
            // pad takes the strongest onset inside its own. A break loses
            // nothing by it, because hits that regular fall one to a stretch
            // anyway; a song gains the whole of itself. A stretch the
            // detector heard nothing in keeps its plain division, which is
            // the honest answer for a bar of silence.
            const auto window = static_cast<int32_t>(sampleRate * 0.02f);
            cuts.assign(static_cast<size_t>(count), 0);
            size_t at = 0;
            for (int i = 0; i < count; ++i) {
                const auto from = static_cast<int32_t>(static_cast<int64_t>(s.frames) * i / count);
                const auto to = static_cast<int32_t>(static_cast<int64_t>(s.frames) * (i + 1) / count);
                while (at < onsets.size() && onsets[at] < from) ++at;
                int32_t best = -1;
                float bestPeak = -1.0f;
                for (size_t j = at; j < onsets.size() && onsets[j] < to; ++j) {
                    float peak = 0.0f;
                    const int32_t until = std::min(s.frames, onsets[j] + window);
                    for (int32_t k = onsets[j]; k < until; ++k) {
                        peak = std::max(peak, std::fabs(s.left[static_cast<size_t>(k)]));
                    }
                    if (peak > bestPeak) {
                        bestPeak = peak;
                        best = onsets[j];
                    }
                }
                cuts[static_cast<size_t>(i)] = best >= 0 ? best : from;
            }
        }
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
