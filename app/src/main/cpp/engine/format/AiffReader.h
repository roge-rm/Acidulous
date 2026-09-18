#pragma once
#include <engine/core/Sample.h>
#include <memory>
#include <string>

// Reads AIFF and AIFF-C - 8, 16, 24 and 32-bit PCM, and 32-bit float - mono
// or stereo, any rate. Ours; the mirror of AiffWriter.
namespace acidulous {

class AiffReader {
  public:
    /** Null on failure, with [error] saying why. See WavReader::read. */
    static std::unique_ptr<SampleData> read(const std::string &path, int32_t targetRate, std::string &error);
};

} // namespace acidulous
