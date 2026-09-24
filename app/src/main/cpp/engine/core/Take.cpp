#include "Take.h"
#include <algorithm>
#include <cmath>

namespace acidulous::audio {

void Take::detect(float sampleRate) {
    onsets.clear();
    if (frames <= 0) return;
    OnsetFinder finder;
    finder.reset(sampleRate);
    for (int32_t i = 0; i < frames; ++i) {
        const float l = left[static_cast<size_t>(i)];
        const float r = right.empty() ? l : right[static_cast<size_t>(i)];
        if (finder.push(l, r)) onsets.push_back(i);
        if (onsets.size() >= 512) break; // a cloud does not need more than that
    }
    bars = guessBars(sampleRate);
}

/**
 * How many bars a loop is, from how long it is and where its hits fall.
 *
 * A loop is trimmed to whole bars, so its length allows only a few tempos:
 * two bars of 120 is four seconds, and so is one bar of 60 and four of 240.
 * Only those between 70 and 180 are taken seriously. Of them, the one whose
 * sixteenths the hits land on wins - a loop cut at 120 puts its hits on 120's
 * grid and between the lines of 90's. Halving the grid is no test, since
 * every hit on a coarse grid is on the fine one too, so a tie goes to the
 * tempo nearer 120, and the bars knob is there for when that is wrong.
 */
float Take::guessBars(float sampleRate) const {
    if (frames <= 0 || sampleRate <= 0.0f) return 0.0f;
    static const float kCandidates[] = {0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f};
    const double seconds = static_cast<double>(frames) / sampleRate;
    float best = 0.0f;
    double bestScore = -1e9;
    for (const float b : kCandidates) {
        const double bpm = b * 4.0 * 60.0 / seconds;
        if (bpm < 70.0 || bpm >= 180.0) continue;
        // The share of hits within a fifth of a sixteenth of the grid. The
        // finder reports an onset at the end of the hop it rose in, up to five
        // milliseconds late, which is well inside that at any of these tempos.
        double onGrid = 1.0;
        if (!onsets.empty()) {
            const double step = static_cast<double>(frames) / (b * 16.0);
            int32_t near = 0;
            for (const int32_t at : onsets) {
                const double off = std::fmod(static_cast<double>(at), step);
                if (std::min(off, step - off) < step * 0.2) ++near;
            }
            onGrid = static_cast<double>(near) / static_cast<double>(onsets.size());
        }
        const double score = onGrid - 0.1 * std::fabs(std::log2(bpm / 120.0));
        if (score > bestScore) { bestScore = score; best = b; }
    }
    return best;
}

} // namespace acidulous::audio
