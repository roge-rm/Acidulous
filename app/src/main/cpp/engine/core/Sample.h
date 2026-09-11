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
};

} // namespace acidulous
