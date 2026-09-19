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
#include <engine/format/AudioDecoder.h>
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

// The integer the encoder is required to preserve is `acidulous::quantise`
// from AudioSink.h - the same function the writers use, rather than a second
// copy of the rule that could agree with the wrong thing.

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

/**
 * Read it back with **our own** decoder and compare every sample.
 *
 * This was ffmpeg, in the shell step: decode to raw PCM and `cmp`. That was
 * the right answer when the app could write four formats and read one, and
 * M49 ended that - so the loop closes inside the repository, and the harness
 * stops needing a program the machine may not have. It also checks twice as
 * much as it did: the decoder is now under test alongside the encoder, and a
 * matched pair of bugs is the only thing that can hide.
 *
 * [bits] is what the file claims to hold, so the reference is the value the
 * encoder was required to preserve rather than the float that went in.
 */
void readBack(const std::string &path, const std::vector<float> &pcm, int32_t frames, int bits,
              const char *name) {
    std::string error;
    const std::unique_ptr<SampleData> got = decodeAudio(path, 48000, error);
    if (got == nullptr) {
        ok("decodes", false, error.c_str());
        return;
    }
    char note[128];
    snprintf(note, sizeof note, "%d frames, wanted %d", got->frames, frames);
    ok("decodes to the right length", got->frames == frames, note);
    if (got->frames != frames) return;
    ok("comes back in stereo", got->stereo);

    // The scale the writer used, so the comparison is exact rather than
    // within a tolerance: both sides are the same integer over the same
    // power of two.
    const auto scale = static_cast<float>(1 << (bits - 1));
    int32_t worstAt = -1;
    float worst = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        for (int ch = 0; ch < 2; ++ch) {
            const float in = pcm[static_cast<size_t>(i) * 2 + static_cast<size_t>(ch)];
            // A float render keeps what it was given, clamp and all; a PCM
            // one keeps the integer it quantised to.
            const float want = bits == 32 ? in : static_cast<float>(quantise(in, bits)) / scale;
            const float have = ch == 0 ? got->left[static_cast<size_t>(i)]
                                       : got->right[static_cast<size_t>(i)];
            const float off = std::fabs(have - want);
            if (off > worst) {
                worst = off;
                worstAt = i;
            }
        }
    }
    // Exactly nought, at every frame, including the deliberately over-range
    // pair this signal starts with: the writers scale by 2^(b-1) and clamp,
    // and every reader divides by 2^(b-1), so the two are inverses.
    snprintf(note, sizeof note, "worst %.9f at frame %d", static_cast<double>(worst), worstAt);
    ok("every sample comes back", worst == 0.0f, note);
}

/** The mean level in dB of a decoded file, for the one format that is lossy. */
float meanLevel(const std::vector<float> &l, const std::vector<float> &r) {
    double sum = 0.0;
    for (size_t i = 0; i < l.size(); ++i) {
        sum += static_cast<double>(l[i]) * l[i];
        if (i < r.size()) sum += static_cast<double>(r[i]) * r[i];
    }
    const double n = static_cast<double>(l.size() + r.size());
    const double rms = n > 0.0 ? std::sqrt(sum / n) : 0.0;
    return rms > 1e-9 ? static_cast<float>(20.0 * std::log10(rms)) : -200.0f;
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

        if (c.format == AudioFormat::Mp3) {
            // Lossy, so the questions are the ones it can answer: the right
            // length, which is really a question about the Xing header the
            // writer adds, and the right level, which a dropped channel or a
            // gain wrong by a factor of two would fail and a file size would
            // not.
            std::string error;
            const std::unique_ptr<SampleData> back = decodeAudio(path, 48000, error);
            if (back == nullptr) {
                ok("decodes", false, error.c_str());
            } else {
                char note2[128];
                snprintf(note2, sizeof note2, "%d frames, wanted %d", back->frames, frames);
                // One frame either way, which is the natural tolerance: an
                // mp3 is made of 1152-sample frames and cannot end anywhere
                // else. In practice this comes back *exact*, because
                // `Mp3Reader` trims the encoder delay and padding the LAME
                // tag declares - the thing that used to leave twenty-five
                // milliseconds of silence on the front of every import.
                ok("decodes to the right length",
                   back->frames > frames - 1152 && back->frames < frames + 1152, note2);
                const float in = meanLevel(pcm, {});
                const float out = meanLevel(back->left, back->right);
                snprintf(note2, sizeof note2, "%.2f dB out, %.2f dB in", static_cast<double>(out),
                         static_cast<double>(in));
                // A decibel and a half either way. The fifth of this signal
                // that is noise loses its top octave at 128 kbit, which is
                // the encoder working rather than failing.
                ok("comes back at the level it went in", std::fabs(out - in) < 1.5f, note2);
            }
        } else {
            readBack(path, pcm, frames, c.bits, c.name);
        }
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
