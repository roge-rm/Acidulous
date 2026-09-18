#pragma once
#include <engine/core/Sample.h>
#include <engine/format/Decoded.h>
#include <memory>
#include <string>

// Reads FLAC - any bit depth to 32, mono or stereo, any rate. Ours, and the
// mirror of FlacWriter, except that it has to understand rather more than we
// write: our encoder uses fixed predictors only, and every other encoder in
// the world uses LPC.
namespace acidulous {

class FlacReader {
  public:
    /**
     * Null on failure, with [error] saying why. See WavReader::read.
     *
     * [maxSeconds] is how much of a long file to take; the rest is dropped
     * and `truncated` is set on what comes back.
     */
    static std::unique_ptr<SampleData> read(const std::string &path, int32_t targetRate, std::string &error,
                                           int32_t maxSeconds = kMaxDecodeSeconds);
};

} // namespace acidulous
