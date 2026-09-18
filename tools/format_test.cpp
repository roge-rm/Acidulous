// Everything we write, read back by us.
//
// The app wrote four formats and read one, which meant a stem exported
// yesterday could not be loaded today. These are the round trips: write with
// our own sink, decode with our own reader, compare. Three of the four are
// lossless and are asked for the samples back within a bit; MP3 is not and is
// asked for the right length at the right level, which is what sink_test asks
// ffmpeg for and is now answerable inside the repository.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <engine/format/AiffWriter.h>
#include <engine/format/AudioDecoder.h>
#include <engine/format/FlacWriter.h>
#include <engine/format/Mp3Writer.h>
#include <engine/format/WavWriter.h>

using namespace acidulous;

namespace {
int gChecks = 0, gFails = 0;
void check(bool ok, const std::string &what) {
    ++gChecks;
    if (!ok) { ++gFails; std::printf("  FAIL %s\n", what.c_str()); }
    else std::printf("  ok   %s\n", what.c_str());
}

constexpr int32_t kRate = 48000;
constexpr int32_t kFrames = 30000;

/**
 * Something with corners in it. A pure tone is decoded correctly by almost
 * anything that is nearly right, and a wrong channel order or an off-by-one
 * in a predictor would not show; a tone in one ear, a different one in the
 * other, a click and a burst of noise will.
 */
std::vector<float> signalFor() {
    std::vector<float> s(static_cast<size_t>(kFrames) * 2);
    uint32_t rng = 987654321u;
    for (int32_t i = 0; i < kFrames; ++i) {
        const float t = static_cast<float>(i) / kRate;
        float l = 0.6f * std::sin(2.0f * 3.14159265f * 220.0f * t);
        float r = 0.4f * std::sin(2.0f * 3.14159265f * 331.0f * t);
        if (i >= 10000 && i < 14000) {
            rng = rng * 1664525u + 1013904223u;
            const float n = static_cast<float>(rng >> 8) / 8388608.0f - 1.0f;
            l += n * 0.25f;
            r -= n * 0.25f;
        }
        if (i == 20000) { l = 0.95f; r = -0.95f; }
        s[static_cast<size_t>(i) * 2] = l;
        s[static_cast<size_t>(i) * 2 + 1] = r;
    }
    return s;
}

bool writeWith(AudioSink &sink, const std::string &path, int bits, const std::vector<float> &s) {
    std::string error;
    if (!sink.open(path, kRate, bits, error)) { std::printf("  FAIL open %s: %s\n", path.c_str(), error.c_str()); return false; }
    sink.write(s.data(), kFrames);
    return sink.close();
}

/** Round trip one lossless format and say how far the samples moved. */
void lossless(const std::string &label, AudioSink &sink, const std::string &path, int bits,
              AudioFormat expect, const std::vector<float> &s, float tolerance) {
    if (!writeWith(sink, path, bits, s)) { ++gFails; ++gChecks; return; }
    check(sniff(path) == expect, label + " is recognised as " + formatName(expect));
    std::string error;
    auto got = decodeAudio(path, kRate, error);
    if (!got) { check(false, label + " decodes (" + error + ")"); return; }
    check(got->frames == kFrames, label + " comes back " + std::to_string(got->frames) + " frames, wanted " +
                                      std::to_string(kFrames));
    check(got->stereo, label + " comes back stereo");
    if (got->frames != kFrames || !got->stereo) return;
    float worst = 0.0f;
    for (int32_t i = 0; i < kFrames; ++i) {
        worst = std::fmax(worst, std::fabs(got->left[static_cast<size_t>(i)] - s[static_cast<size_t>(i) * 2]));
        worst = std::fmax(worst, std::fabs(got->right[static_cast<size_t>(i)] - s[static_cast<size_t>(i) * 2 + 1]));
    }
    char msg[160];
    std::snprintf(msg, sizeof(msg), "%s is lossless to %.2g (one step is %.2g)", label.c_str(),
                  static_cast<double>(worst), static_cast<double>(tolerance));
    check(worst <= tolerance, msg);
}

float rmsOf(const std::vector<float> &s) {
    double sum = 0.0;
    for (float v : s) sum += static_cast<double>(v) * v;
    return static_cast<float>(std::sqrt(sum / (s.empty() ? 1 : s.size())));
}
} // namespace

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : ".";
    const std::vector<float> s = signalFor();

    // **A step and a half, not a step.** Every writer here quantises with
    // `lrint(v * 32767)` and every reader divides by 32768, which is the
    // ordinary convention - PCM runs -32768..+32767, so the negative end
    // reaches -1.0 exactly and the positive end stops just short. A round
    // trip therefore costs half a step of rounding *plus* the sample's own
    // size over 32768, which at full scale is another whole step. Asking for
    // one step would be asking the formats to be something they are not.
    const float step16 = 1.5f / 32768.0f, step24 = 1.5f / 8388608.0f;
    { WavWriter w;  lossless("wav16",  w, dir + "/rt16.wav",  16, AudioFormat::Wav,  s, step16); }
    { WavWriter w;  lossless("wav24",  w, dir + "/rt24.wav",  24, AudioFormat::Wav,  s, step24); }
    { WavWriter w;  lossless("wav32",  w, dir + "/rt32.wav",  32, AudioFormat::Wav,  s, 1e-7f); }
    { AiffWriter a; lossless("aiff16", a, dir + "/rt16.aiff", 16, AudioFormat::Aiff, s, step16); }
    { AiffWriter a; lossless("aiff24", a, dir + "/rt24.aiff", 24, AudioFormat::Aiff, s, step24); }
    { AiffWriter a; lossless("aiff32", a, dir + "/rt32.aiff", 32, AudioFormat::Aiff, s, 1e-7f); }
    { FlacWriter f; lossless("flac16", f, dir + "/rt16.flac", 16, AudioFormat::Flac, s, step16); }
    { FlacWriter f; lossless("flac24", f, dir + "/rt24.flac", 24, AudioFormat::Flac, s, step24); }

    // MP3: lossy, and asked only for what a lossy format can promise.
    {
        Mp3Writer m;
        const std::string path = dir + "/rt.mp3";
        if (writeWith(m, path, 16, s)) {
            check(sniff(path) == AudioFormat::Mp3, "mp3 is recognised as MP3");
            std::string error;
            auto got = decodeAudio(path, kRate, error);
            if (!got) {
                check(false, "mp3 decodes (" + error + ")");
            } else {
                check(got->stereo && got->rate == kRate, "mp3 comes back 48 kHz stereo");
                // A frame is 1152 samples and an encoder pads at both ends.
                const int32_t slack = 1152 * 3;
                char msg[160];
                std::snprintf(msg, sizeof(msg), "mp3 comes back %d frames, wanted %d within %d", got->frames,
                              kFrames, slack);
                check(std::abs(got->frames - kFrames) < slack, msg);
                std::vector<float> back;
                back.reserve(got->left.size() * 2);
                for (int32_t i = 0; i < got->frames; ++i) {
                    back.push_back(got->left[static_cast<size_t>(i)]);
                    back.push_back(got->right[static_cast<size_t>(i)]);
                }
                const float in = rmsOf(s), out = rmsOf(back);
                const float db = 20.0f * std::log10((out + 1e-9f) / (in + 1e-9f));
                std::snprintf(msg, sizeof(msg), "mp3 comes back within a decibel (%.2f dB)",
                              static_cast<double>(db));
                check(std::fabs(db) < 1.0f, msg);
            }
        } else { ++gFails; ++gChecks; }
    }

    // And the thing the front door is for: deciding by content, not by name.
    {
        const std::string named = dir + "/actually-a-flac.wav";
        FlacWriter f;
        writeWith(f, named, 16, s);
        check(sniff(named) == AudioFormat::Flac, "a FLAC named .wav is still a FLAC");
        std::string error;
        check(decodeAudio(named, kRate, error) != nullptr, "and decodes anyway");
        check(sniff(dir + "/nothing-here") == AudioFormat::Unknown, "a missing file is nothing");
    }

    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
