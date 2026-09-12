#pragma once
#include <cstdio>
#include <engine/core/AudioSink.h>
#include <vector>

// Writes stereo FLAC. Ours, from the published spec (RFC 9639); no
// dependency, and nothing patented in it.
//
// This is a *subset* encoder, which is a term from the format rather than an
// apology: FLAC's "Subset" is the profile every decoder must handle, and it
// is what streaming hardware expects. Within it we use fixed polynomial
// predictors of order nought to four rather than computed LPC. That is the
// deliberate trade - LPC buys perhaps five percent more compression for a
// Levinson-Durbin solve, a quantised coefficient table and a great deal more
// that can be subtly wrong, and a music app's export is not where those five
// percent matter. A typical mix lands near half the size of the WAV.
//
// Lossless means exactly that, so it is testable in a way lossy formats are
// not: encode, decode with somebody else's decoder, and the bytes either
// match or the encoder is broken. tools/flac_test.cpp does that.

namespace acidulous {

class FlacWriter : public AudioSink {
  public:
    ~FlacWriter() override { close(); }

    /** [bits]: 16 or 24. FLAC has nowhere to put a float, so 32 becomes 24. */
    bool open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) override;
    void write(const float *interleaved, int32_t frames) override;
    bool close() override;
    int64_t framesWritten() const override { return frames; }

  private:
    /** The Subset caps this at 4608 for 48 kHz and under; 4096 is usual. */
    static constexpr int32_t kBlock = 4096;

    void flushBlock();
    void writeStreamInfo();

    FILE *file = nullptr;
    int32_t rate = 48000;
    int32_t bps = 24;
    int64_t frames = 0;
    uint32_t frameNumber = 0;

    // One block's worth, de-interleaved, as integers.
    std::vector<int32_t> left, right;
    // Smallest and largest frame we actually wrote, for STREAMINFO.
    uint32_t minFrame = 0xffffffffu, maxFrame = 0;
    uint8_t md5Digest[16] = {};

    // MD5 of the unencoded samples, little-endian, which is what the format
    // asks for and what makes `flac -t` able to check the file against
    // itself. State is carried across blocks.
    struct Md5 {
        uint32_t h[4] = {0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u};
        uint64_t length = 0;
        uint8_t buffer[64] = {};
        size_t have = 0;
        void update(const uint8_t *data, size_t n);
        void finish(uint8_t out[16]);
        void block(const uint8_t *p);
    } md5;
};

} // namespace acidulous
