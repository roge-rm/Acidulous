#pragma once
#include <cstdint>
#include <cstdio>
#include <engine/format/AudioSink.h>
#include <string>

// Writes stereo WAV, header patched on close. Ours; no dependency.
//
// 24-bit PCM by default, because a recording is a master; 16 is there for a
// phone short of room, and halves the file. 32 writes IEEE floats instead,
// which is what freezing uses: a rack's output before its fader can be well
// past full scale without being wrong, and clamping it there would print
// distortion into the render that nobody asked for.
namespace acidulous {

class WavWriter : public AudioSink {
  public:
    ~WavWriter() override { close(); }
    /** [bits]: 16 or 24 for PCM, 32 for float. */
    bool open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) override;
    // Interleaved stereo floats, clipped to -1..1.
    void write(const float *interleaved, int32_t frames) override;
    bool close() override;
    int64_t framesWritten() const override { return frames; }

  private:
    void writeHeader();
    FILE *file = nullptr;
    int32_t rate = 48000;
    int32_t bytesPerSample = 3;
    bool floatFormat = false;
    int64_t frames = 0;
};

} // namespace acidulous
