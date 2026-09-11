#pragma once
#include <cstdint>
#include <cstdio>
#include <string>

// Writes PCM stereo WAV, 24- or 16-bit, header patched on close. Ours; no
// dependency. 24 bits is the default because a recording is a master; 16 is
// there for a phone that is short of room, and halves the file.
namespace acidulous {

class WavWriter {
  public:
    ~WavWriter() { close(); }
    bool open(const std::string &path, int32_t sampleRate, std::string &error, int32_t bits = 24);
    // Interleaved stereo floats, clipped to -1..1.
    void write(const float *interleaved, int32_t frames);
    bool close();
    int64_t framesWritten() const { return frames; }

  private:
    void writeHeader();
    FILE *file = nullptr;
    int32_t rate = 48000;
    int32_t bytesPerSample = 3;
    int64_t frames = 0;
};

} // namespace acidulous
