#pragma once
#include <cstdint>
#include <string>
#include <vector>

// A decoded sample at the engine rate. Built on a normal thread, mounted into a
// machine's slot, never modified afterwards.
namespace acidulous {

struct SampleData {
    std::string name;
    std::vector<float> left;
    std::vector<float> right; // empty when mono
    int32_t frames = 0;
    bool stereo = false;
    /**
     * The rate the data is actually at. WavReader resamples to the engine
     * rate and leaves this equal to it; a multisample keeps its source rate
     * instead and lets playback take the ratio into account, which avoids
     * resampling a whole instrument and keeps its loop points exact.
     */
    int32_t rate = 48000;
    // Loop in frames, -1 for none. Set from the source file where it says so.
    int32_t loopStart = -1;
    int32_t loopEnd = -1;
    /**
     * The loudest sample in the file, 0..1, or 0 if nobody measured it.
     *
     * Kept here rather than worked out where it is wanted because the thing
     * that wants it is a panel polling twice a second, and scanning a second
     * of stereo audio to answer "how loud is this" is a hundred thousand
     * reads for a number that cannot change: a SampleData is never modified
     * after it is built.
     */
    float peak = 0.0f;

    /**
     * True when the file was longer than the decoder will take.
     *
     * Silence about this is the problem it exists to solve: import a four
     * minute mix, get thirty seconds of it, and nothing anywhere says so.
     */
    bool truncated = false;

    /** Fill [peak]. Call once, on the thread that built the data. */
    void measure() {
        float p = 0.0f;
        for (float v : left) p = v < 0.0f ? (-v > p ? -v : p) : (v > p ? v : p);
        for (float v : right) p = v < 0.0f ? (-v > p ? -v : p) : (v > p ? v : p);
        peak = p;
    }
};

} // namespace acidulous
