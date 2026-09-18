// Do the render sinks write what they were given?
//
// Lossless is the rare claim that can be checked absolutely: encode, let
// somebody else's decoder read it back, and compare every byte. This writes
// the .flac and, beside it, the exact PCM the encoder should have preserved;
// the shell step then decodes with ffmpeg and runs cmp. Anything but an
// identical file is a bug, with no judgement involved. WAV and AIFF get the
// same treatment: they are trivially lossless, so the thing actually under
// test is whether their headers say what the samples are - and a wrong
// header is exactly what a listening test would not catch.
//
// MP3 is the one sink here that cannot be held to that standard - it is
// lossy by design - so it is asked the questions it can answer: that it
// produces a file of about the size its bitrate promises, and (in the shell
// step) that a decoder reads back the right length at the right level.
//
// The signals are chosen to hit the corners: silence and a constant exercise
// the CONSTANT subframe, a ramp suits a high fixed order, noise suits none of
// them and should fall back gracefully, full-scale catches clipping, and a
// length that is not a multiple of the block size forces the short final
// frame - which is where encoders like this one usually break.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <engine/format/AiffWriter.h>
#include <engine/format/AudioSink.h>
#include <engine/format/FlacWriter.h>
#include <engine/format/WavWriter.h>

using namespace acidulous;

namespace {
int failures = 0, checks = 0;
uint32_t rng = 22222;

float frand() {
    rng = rng * 1664525u + 1013904223u;
    return (rng >> 8) * (2.0f / 16777216.0f) - 1.0f;
}

void ok(const char *what, bool good, const char *detail = "") {
    ++checks;
    if (!good) ++failures;
    printf("  %s %-44s %s\n", good ? "ok  " : "FAIL", what, detail);
}

/** The integer the encoder is required to preserve, for the raw reference. */
int32_t quantise(float v, int bits) {
    const float scale = bits == 16 ? 32767.0f : 8388607.0f;
    const int32_t lo = bits == 16 ? -32768 : -8388608;
    const int32_t hi = bits == 16 ? 32767 : 8388607;
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    auto s = static_cast<int32_t>(std::lrint(v * scale));
    if (s < lo) s = lo;
    if (s > hi) s = hi;
    return s;
}

/** A mix-like signal: correlated channels, which is where FLAC earns its keep. */
std::vector<float> makeSignal(int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    for (int32_t i = 0; i < frames; ++i) {
        const double t = i / 48000.0;
        float v = 0.0f;
        if (i < frames / 5) {
            v = 0.0f; // silence
        } else if (i < 2 * frames / 5) {
            v = 0.5f; // a constant
        } else if (i < 3 * frames / 5) {
            v = static_cast<float>((i % 1000) / 1000.0 * 2.0 - 1.0); // a ramp
        } else if (i < 4 * frames / 5) {
            v = 0.4f * std::sin(2.0 * M_PI * 220.0 * t) + 0.2f * std::sin(2.0 * M_PI * 331.0 * t);
        } else {
            v = 0.9f * frand(); // noise, which no predictor helps
        }
        out[static_cast<size_t>(i) * 2] = v;
        // The right channel is nearly the left, as a real mix is: this is
        // what the stereo decorrelation is supposed to exploit.
        out[static_cast<size_t>(i) * 2 + 1] = v * 0.97f + 0.01f * frand();
    }
    // A full-scale pair, to prove the clamp does not wrap.
    out[0] = 1.5f;
    out[1] = -1.5f;
    return out;
}

void writeRaw(const std::string &path, const std::vector<float> &pcm, int bits) {
    FILE *f = std::fopen(path.c_str(), "wb");
    for (float v : pcm) {
        if (bits == 32) {
            // Floats go through untouched - not clamped, which is the whole
            // reason a float render exists.
            std::fwrite(&v, 1, 4, f);
            continue;
        }
        const int32_t s = quantise(v, bits);
        uint8_t b[3] = {static_cast<uint8_t>(s), static_cast<uint8_t>(s >> 8), static_cast<uint8_t>(s >> 16)};
        std::fwrite(b, 1, bits == 16 ? 2 : 3, f);
    }
    std::fclose(f);
}

long fileSize(const std::string &path) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return -1;
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fclose(f);
    return n;
}
} // namespace

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : ".";

    // Deliberately not a multiple of 4096: the last FLAC frame is short.
    const int32_t frames = 4096 * 5 + 1234;
    const std::vector<float> pcm = makeSignal(frames);

    struct Case {
        const char *name;
        AudioFormat format;
        int bits;
    };
    const Case cases[] = {
        {"wav24", AudioFormat::Wav, 24},  {"wav16", AudioFormat::Wav, 16},
        {"aiff24", AudioFormat::Aiff, 24}, {"aiff16", AudioFormat::Aiff, 16},
        {"flac24", AudioFormat::Flac, 24}, {"flac16", AudioFormat::Flac, 16},
        // 32 means IEEE floats, which AIFF can only say by becoming AIFF-C.
        {"wav32", AudioFormat::Wav, 32},   {"aiff32", AudioFormat::Aiff, 32},
        // MP3's "bits" is a bitrate in kbit - see Mp3Writer.h.
        {"mp3256", AudioFormat::Mp3, 256}, {"mp3128", AudioFormat::Mp3, 128},
    };

    for (const Case &c : cases) {
        printf("--- %s ---\n", c.name);
        const std::string base = dir + "/" + c.name;
        const std::string path = base + extensionFor(c.format);
        std::unique_ptr<AudioSink> sink = makeSink(c.format);
        std::string error;
        ok("opens", sink && sink->open(path, 48000, c.bits, error), error.c_str());
        // Fed in 64-frame blocks, exactly as the render loop does, so an
        // encoder's own block boundary never lines up with ours.
        for (int32_t at = 0; at < frames; at += 64) {
            const int32_t n = frames - at < 64 ? frames - at : 64;
            sink->write(pcm.data() + static_cast<size_t>(at) * 2, n);
        }
        ok("frame count", sink->framesWritten() == frames, std::to_string(sink->framesWritten()).c_str());
        ok("closes", sink->close());

        // MP3 has no depth, so its reference is the float PCM it was fed.
        writeRaw(base + ".raw", pcm, c.format == AudioFormat::Mp3 ? 32 : c.bits);
        const long bytes = fileSize(path);
        const long rawBytes = fileSize(base + ".raw");
        char note[96];
        snprintf(note, sizeof note, "%ld vs %ld raw, %.1f%%", bytes, rawBytes,
                 100.0 * static_cast<double>(bytes) / static_cast<double>(rawBytes));
        if (c.format == AudioFormat::Mp3) {
            // Constant bitrate, so the size is arithmetic: the bitrate times
            // the duration, plus the Xing frame. A quarter either side allows
            // for that frame and the encoder's own padding on a short file.
            const double seconds = frames / 48000.0;
            const double expect = c.bits * 1000.0 / 8.0 * seconds;
            snprintf(note, sizeof note, "%ld bytes, %.0f expected at %d kbit", bytes, expect, c.bits);
            ok("size matches the bitrate", bytes > expect * 0.75 && bytes < expect * 1.25 + 2000, note);
        } else if (c.format == AudioFormat::Flac) {
            // A real mix should land well under raw. At or above it means
            // the encoder fell back to VERBATIM everywhere.
            ok("smaller than raw", bytes > 0 && bytes < rawBytes * 9 / 10, note);
        } else {
            // Uncompressed: the payload plus a header of some tens of bytes.
            ok("raw plus a header", bytes >= rawBytes && bytes < rawBytes + 200, note);
        }
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    printf("(now decode with ffmpeg and compare - see tools/sink_test.sh)\n");
    return failures == 0 ? 0 : 1;
}
