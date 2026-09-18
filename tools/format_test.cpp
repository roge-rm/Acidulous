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
#include <engine/format/Mp3Reader.h>
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

    // What a file off the internet looks like: a tag in front of the audio,
    // and something that is not audio glued on the end. Neither is unusual
    // and both used to be fatal.
    {
        std::vector<unsigned char> raw;
        FILE *f = std::fopen((dir + "/rt.mp3").c_str(), "rb");
        if (f != nullptr) {
            unsigned char buf[65536];
            size_t n = 0;
            while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) raw.insert(raw.end(), buf, buf + n);
            std::fclose(f);
        }
        // An ID3v2 header: "ID3", version, flags, then a syncsafe length -
        // four bytes of seven bits each. The body here is binary, standing in
        // for the album art that is usually what makes these large.
        //
        // The body is what a real tag carries: a picture. It is seeded with
        // the exact four bytes that broke this - `FF FE 42 00`, twenty-one
        // bytes into a Backstreet Boys mp3, which is a legal MPEG-1 **Layer
        // I** frame header. A decoder fed the file from byte nought locks
        // onto that and decodes the cover art as layer 1 audio: four hundred
        // kilobytes of bursts of noise, and the song never reached at all.
        // Large, too, because the fault needs the art to outlast a resync.
        std::vector<unsigned char> art(60000, 0xFF); // 0xFF: false frame syncs, deliberately
        const unsigned char falseSync[4] = {0xFF, 0xFE, 0x42, 0x00};
        std::memcpy(art.data() + 11, falseSync, 4); // where the real file had it
        std::vector<unsigned char> tagged = {'I', 'D', '3', 4, 0, 0};
        const size_t size = art.size();
        tagged.push_back(static_cast<unsigned char>((size >> 21) & 0x7F));
        tagged.push_back(static_cast<unsigned char>((size >> 14) & 0x7F));
        tagged.push_back(static_cast<unsigned char>((size >> 7) & 0x7F));
        tagged.push_back(static_cast<unsigned char>(size & 0x7F));
        tagged.insert(tagged.end(), art.begin(), art.end());
        tagged.insert(tagged.end(), raw.begin(), raw.end());
        // And a trailing APE-ish block, which is junk to a decoder.
        const char *junk = "APETAGEX and then some bytes that are not a frame";
        tagged.insert(tagged.end(), junk, junk + std::strlen(junk));

        const std::string path = dir + "/tagged.mp3";
        FILE *out = std::fopen(path.c_str(), "wb");
        std::fwrite(tagged.data(), 1, tagged.size(), out);
        std::fclose(out);

        check(sniff(path) == AudioFormat::Mp3, "an mp3 behind an ID3 tag is still an mp3");
        check(Mp3Reader::audioStart(tagged.data(), tagged.size()) == 10 + art.size(),
              "and the audio is found past the tag, not inside it");
        std::string error;
        auto got = decodeAudio(path, kRate, error);
        std::string plainError;
        auto plain = decodeAudio(dir + "/rt.mp3", kRate, plainError); // the same audio, untagged
        if (!got || !plain) {
            check(false, "a tagged mp3 with junk on the end decodes (" + error + ")");
        } else {
            char msg[200];
            // **Against the untagged decode of the same audio, not against a
            // length.** The old assertion here was `frames > kFrames / 2`,
            // which a garbage decode passes easily: layer 1 nonsense from the
            // art, a resync, and then the real audio, adds up to plenty of
            // frames. It has to be the same music, so it is asked to be the
            // same length and the same loudness as the file without the tag.
            std::snprintf(msg, sizeof(msg), "a tagged mp3 decodes to the same length as the untagged one "
                                            "(%d vs %d frames)", got->frames, plain->frames);
            check(std::abs(got->frames - plain->frames) < 2304, msg);
            const auto rms = [](const SampleData &s) {
                double sum = 0.0;
                for (int32_t i = 0; i < s.frames; ++i) sum += static_cast<double>(s.left[i]) * s.left[i];
                return s.frames > 0 ? std::sqrt(sum / s.frames) : 0.0;
            };
            const double a = rms(*got), b = rms(*plain);
            std::snprintf(msg, sizeof(msg), "and at the same level (rms %.4f vs %.4f)", a, b);
            check(b > 1e-4 && std::fabs(a - b) < b * 0.05, msg);
        }
    }

    // A file we can name but cannot read is said so by name, because being
    // told an m4a is a broken mp3 sends the player nowhere useful.
    {
        struct Case { const char *name; std::vector<unsigned char> head; const char *says; };
        const std::vector<Case> cases = {
            {"song.m4a", {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, "M4A"},
            {"song.ogg", {'O', 'g', 'g', 'S', 0, 2, 0, 0}, "Ogg"},
            {"song.wma", {0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11}, "WMA"},
        };
        for (const Case &c : cases) {
            std::string path = dir + "/" + c.name;
            std::vector<unsigned char> body = c.head;
            // Padded with the byte that looks like half a frame sync, which
            // is what used to make these decode as broken mp3s.
            body.resize(20000, 0xFF);
            FILE *f = std::fopen(path.c_str(), "wb");
            std::fwrite(body.data(), 1, body.size(), f);
            std::fclose(f);
            check(sniff(path) == AudioFormat::Unknown, std::string(c.name) + " is not mistaken for an mp3");
            std::string error;
            // Decoded first and checked second: the two are arguments to the
            // same call otherwise, and C++ does not say which runs first - so
            // the message was built from an `error` nothing had written yet
            // and reported every one of these as "()".
            const bool refused = decodeAudio(path, kRate, error) == nullptr;
            check(refused && error.find(c.says) != std::string::npos,
                  std::string(c.name) + " is named in the message (" + error + ")");
        }
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

    // --- the ceiling, and saying when it was hit ---------------------------
    //
    // A long file is cut to the cap, which is fine, and until now was cut in
    // silence, which was not: `truncated` lived on the shared tail and every
    // reader trimmed the planes before the tail ever saw them, so the flag
    // could never be true and the message behind it was unreachable. Asked of
    // all four, because that is four places the trimming happens.
    {
        // Three seconds of signal, asked for two: short enough to write four
        // times in a test and long enough that a cap of two cuts it.
        constexpr int32_t kLongFrames = kRate * 3;
        constexpr int32_t kCap = 2;
        std::vector<float> longer(static_cast<size_t>(kLongFrames) * 2);
        for (int32_t i = 0; i < kLongFrames; ++i) {
            const float v = 0.5f * std::sin(2.0f * 3.14159265f * 220.0f * static_cast<float>(i) / kRate);
            longer[static_cast<size_t>(i) * 2] = v;
            longer[static_cast<size_t>(i) * 2 + 1] = v;
        }
        struct Long { const char *name; AudioSink *sink; int bits; };
        WavWriter w; AiffWriter a; FlacWriter f; Mp3Writer m;
        const std::vector<Long> all = {{"long.wav", &w, 24}, {"long.aiff", &a, 24},
                                       {"long.flac", &f, 16}, {"long.mp3", &m, 16}};
        for (const Long &l : all) {
            const std::string path = dir + "/" + l.name;
            std::string error;
            if (!l.sink->open(path, kRate, l.bits, error)) { check(false, std::string(l.name) + " opens"); continue; }
            l.sink->write(longer.data(), kLongFrames);
            l.sink->close();

            auto cut = decodeAudio(path, kRate, error, kCap);
            if (!cut) { check(false, std::string(l.name) + " decodes (" + error + ")"); continue; }
            check(cut->truncated, std::string(l.name) + " says it was cut at " + std::to_string(kCap) + "s");
            // Within a frame either way: mp3 pads the front and the back, so
            // an exact count is the one thing not to ask of it.
            check(std::abs(cut->frames - kCap * kRate) < 2304,
                  std::string(l.name) + " kept " + std::to_string(cut->frames) + " frames, wanted " +
                      std::to_string(kCap * kRate));

            // And the whole thing under a ceiling that clears it, which is
            // what the slice source gets: nothing lost and nothing claimed.
            auto whole = decodeAudio(path, kRate, error, kMaxSliceSeconds);
            if (!whole) { check(false, std::string(l.name) + " decodes whole (" + error + ")"); continue; }
            check(!whole->truncated && std::abs(whole->frames - kLongFrames) < 2304,
                  std::string(l.name) + " comes back whole under the long ceiling (" +
                      std::to_string(whole->frames) + " frames)");
        }
    }

    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
