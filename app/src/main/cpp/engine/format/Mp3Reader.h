#pragma once
#include <engine/core/Sample.h>
#include <engine/format/Decoded.h>
#include <memory>
#include <string>

// Reads MPEG audio - layer III mostly, and layers I and II because the same
// decoder does them - mono or stereo, any rate. Not ours: LAME's `mpglib`,
// which is the decoder that comes with the encoder we already ship.
namespace acidulous {

class Mp3Reader {
  public:
    /**
     * Where the MPEG audio starts: past an ID3v2 tag, if there is one.
     *
     * Not a nicety. An mp3 with album art carries a tag of hundreds of
     * kilobytes of JPEG, and JPEG is full of 0xFF bytes - so a decoder handed
     * the file from byte zero finds a "frame sync" almost immediately, and it
     * is whatever the picture happened to say. One real file began
     * `FF FE 42 00` twenty-one bytes in, which is a legal MPEG-1 **Layer I**
     * header, so four hundred kilobytes of cover art were decoded as layer 1
     * audio: bursts of noise where the song should be, with the song itself
     * never reached.
     */
    static size_t audioStart(const unsigned char *bytes, size_t size);

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
