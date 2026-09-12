#pragma once
#include <cstdio>
#include <engine/core/AudioSink.h>

// Writes stereo AIFF, header patched on close. Ours; no dependency.
//
// The same job WavWriter does, the other way round: AIFF is big-endian, its
// chunks live inside a FORM, and its sample rate is stored as an 80-bit IEEE
// extended float, which is the only genuinely odd corner of the format.
//
// 16- and 24-bit PCM write plain AIFF. 32-bit float cannot: the original
// format has no way to say "these are floats", so that case writes AIFF-C
// instead, which is the same file with a FORM type of AIFC, a format-version
// chunk, and a compression type of 'fl32' meaning "not compressed at all,
// just floats". Both extensions stay .aiff, which is what every reader
// expects.

namespace acidulous {

class AiffWriter : public AudioSink {
  public:
    ~AiffWriter() override { close(); }

    bool open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) override;
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
