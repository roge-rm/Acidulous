#include "AudioSink.h"
#include "AiffWriter.h"
#include "FlacWriter.h"
#include "WavWriter.h"

namespace acidulous {

std::unique_ptr<AudioSink> makeSink(AudioFormat format) {
    switch (format) {
    case AudioFormat::Wav:
        return std::make_unique<WavWriter>();
    case AudioFormat::Aiff:
        return std::make_unique<AiffWriter>();
    case AudioFormat::Flac:
        return std::make_unique<FlacWriter>();
    }
    return nullptr;
}

const char *extensionFor(AudioFormat format) {
    switch (format) {
    case AudioFormat::Wav:
        return ".wav";
    case AudioFormat::Aiff:
        return ".aiff";
    case AudioFormat::Flac:
        return ".flac";
    }
    return ".wav";
}

bool supportsFloat(AudioFormat format) {
    // FLAC is integer by definition - it is lossless *about integers*, and
    // there is nowhere in the format to put an exponent.
    return format != AudioFormat::Flac;
}

} // namespace acidulous
