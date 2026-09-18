#pragma once
#include <engine/core/Sample.h>
#include <memory>
#include <string>

// Reads uncompressed WAV - PCM 8/16/24/32-bit and 32-bit float, mono or
// stereo, any rate - and resamples to the engine rate. Ours; no dependency.
namespace acidulous {

class WavReader {
  public:
    // Returns nullptr on any failure; `error` says why. A targetRate of 0 or
    // less keeps the file's own rate, which is what a multisample wants.
    static std::unique_ptr<SampleData> read(const std::string &path, int32_t targetRate, std::string &error);
    // Kept as a name for callers; the number itself lives with the shared
    // decode tail now, because every reader has to agree on it.
    static constexpr int32_t kMaxSeconds = 30;
};

} // namespace acidulous
