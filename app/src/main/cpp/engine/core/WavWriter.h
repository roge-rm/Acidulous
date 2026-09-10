#pragma once
#include <cstdint>
#include <cstdio>
#include <string>

// Writes 24-bit PCM stereo WAV, header patched on close. Ours; no dependency.
namespace acidulous {

class WavWriter {
  public:
    ~WavWriter() { close(); }
    bool open(const std::string &path, int32_t sampleRate, std::string &error);
    // Interleaved stereo floats, clipped to -1..1.
    void write(const float *interleaved, int32_t frames);
    bool close();
    int64_t framesWritten() const { return frames; }

  private:
    void writeHeader();
    FILE *file = nullptr;
    int32_t rate = 48000;
    int64_t frames = 0;
};

} // namespace acidulous
