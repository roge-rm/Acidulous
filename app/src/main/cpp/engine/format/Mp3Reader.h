#pragma once
#include <engine/core/Sample.h>
#include <memory>
#include <string>

// Reads MPEG audio - layer III mostly, and layers I and II because the same
// decoder does them - mono or stereo, any rate. Not ours: LAME's `mpglib`,
// which is the decoder that comes with the encoder we already ship.
namespace acidulous {

class Mp3Reader {
  public:
    /** Null on failure, with [error] saying why. See WavReader::read. */
    static std::unique_ptr<SampleData> read(const std::string &path, int32_t targetRate, std::string &error);
};

} // namespace acidulous
