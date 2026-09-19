#pragma once
#include <cmath>
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

/**
 * A float to a PCM integer, the way the readers undo it.
 *
 * **Scaled by 2^(b-1), not by 2^(b-1) - 1.** The writers used the latter, so
 * a float of -1.0 became -32767 and never the one code that has no positive
 * partner, while every reader here divides by 32768. The two disagreed by a
 * single step at full scale - only there, and only on a sample that clipped,
 * but it meant a file written and read back was not quite the file that went
 * in, and `sink_test` could not ask for "every sample, exactly".
 *
 * The clamp is what keeps the asymmetry honest: +1.0 is genuinely not
 * representable and lands on the largest code there is, while -1.0 now lands
 * exactly on the smallest.
 */
inline int32_t quantise(float v, int32_t bits) {
    const auto scale = static_cast<float>(1 << (bits - 1));
    const int32_t lo = -(1 << (bits - 1));
    const int32_t hi = (1 << (bits - 1)) - 1;
    auto s = static_cast<int32_t>(std::lrint(v * scale));
    if (s < lo) s = lo;
    if (s > hi) s = hi;
    return s;
}


/**
 * One of the four, going either way.
 *
 * It began as "what a render writes itself into" and is now also what a
 * decoder decided a file is, because there is no useful sense in which the
 * FLAC we write and the FLAC we read are different formats. `Unknown` only
 * ever comes out of `sniff`: a writer is always asked for something.
 */
enum class AudioFormat : int32_t {
    Wav = 0,
    Aiff = 1,
    Flac = 2,
    /** Somebody else's codec, and the only one - see Mp3Writer and Mp3Reader. */
    Mp3 = 3,
    Unknown = -1,
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
