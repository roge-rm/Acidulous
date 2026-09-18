#pragma once
#include <cstdint>
#include <memory>
#include <string>

// What an offline render writes itself into.
//
// The render loop pulls blocks and hands them somewhere; until M33 that
// somewhere was always a WavWriter, named in the loop. It is the same three
// calls whatever the file turns out to be - open it, push interleaved stereo
// at it, close it and let it patch its own header - so the loop now holds one
// of these and does not know or care which.
//
// Every implementation here is ours, written from a published spec. Nothing
// in this directory links anybody else's encoder.

namespace acidulous {

enum class AudioFormat : int32_t {
    Wav = 0,
    Aiff = 1,
    Flac = 2,
    /** Somebody else's encoder, and the only one - see Mp3Writer. */
    Mp3 = 3,
};

class AudioSink {
  public:
    virtual ~AudioSink() = default;

    /** [bits]: 16 or 24 for PCM, 32 for float where the format allows it. */
    virtual bool open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) = 0;
    /** Interleaved stereo, [frames] frames of it. */
    virtual void write(const float *interleaved, int32_t frames) = 0;
    /** Patches the header and closes. Safe to call twice. */
    virtual bool close() = 0;
    virtual int64_t framesWritten() const = 0;
};

/** Null for a format that has no writer, which the caller must report. */
std::unique_ptr<AudioSink> makeSink(AudioFormat format);

/** ".wav", ".aiff", ".flac", ".mp3" - including the dot. */
const char *extensionFor(AudioFormat format);

/** True where [bits] == 32 means IEEE floats rather than PCM. */
bool supportsFloat(AudioFormat format);

} // namespace acidulous
