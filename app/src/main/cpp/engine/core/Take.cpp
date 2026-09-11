#include "Take.h"
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
}

} // namespace acidulous::audio
