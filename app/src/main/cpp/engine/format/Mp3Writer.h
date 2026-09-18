#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "AudioSink.h"

// MP3, through LAME.
//
// The only format here that is somebody else's encoder. WAV, AIFF and FLAC
// are ours, written from their published specifications; AAC is the
// platform's. Android ships no MP3 *encoder* at all, and writing one is not
// an afternoon - so LAME is vendored, kept as its own shared library, and
// acknowledged. See third_party/lame/PROVENANCE.md.
namespace acidulous {

class Mp3Writer final : public AudioSink {
  public:
    ~Mp3Writer() override;

    /**
     * [bits] is the **bitrate in kbit**, not a bit depth: MP3 has no such
     * thing, so the number every other sink reads as a depth carries the
     * budget here instead. Anything outside 32..320 falls back to 256.
     */
    bool open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) override;
    void write(const float *interleaved, int32_t frames) override;
    bool close() override;
    int64_t framesWritten() const override { return frames; }

  private:
    void *lame = nullptr; // lame_t, kept opaque so lame.h stays out of the header
    std::FILE *file = nullptr;
    int64_t frames = 0;
    std::vector<unsigned char> buffer;
    bool closed = false;
};

} // namespace acidulous
