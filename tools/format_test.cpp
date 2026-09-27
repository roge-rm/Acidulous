// Round trips every format we write: write with our own sink, decode with our
// own reader, compare. The three lossless formats must give the samples back
// within a bit. MP3 must come back at the right length and level.
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
#include <engine/format/WavReader.h>
#include <engine/format/WavStream.h>
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
 * A test signal with some detail. A pure tone would hide a swapped channel
 * or an off-by-one, so this has a different tone in each ear, a click and a
 * burst of noise.
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

/** Round trips one lossless format and reports how far the samples moved. */
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

/**
 * The chunked `WavStream` reader against `WavReader`. `WavStream` lets a long
 * take be read without holding several copies in memory. It must return the
 * same samples at an offset, across a chunk boundary, and at every bit depth.
 */
void streamMatchesTheReader(const std::string &dir, const std::vector<float> &s) {
    for (const int bits : {16, 24, 32}) {
        const std::string path = dir + "/stream" + std::to_string(bits) + ".wav";
        WavWriter w;
        if (!writeWith(w, path, bits, s)) {
            check(false, "stream" + std::to_string(bits) + " wrote");
            continue;
        }
        std::string error;
        // Read at the file's own rate so neither side resamples.
        const auto whole = WavReader::read(path, 0, error, kMaxSliceSeconds);
        WavStream stream;
        if (!whole || !stream.open(path, error)) {
            check(false, "stream" + std::to_string(bits) + " opened (" + error + ")");
            continue;
        }
        check(stream.frames() == whole->frames && stream.channels() == (whole->stereo ? 2 : 1) &&
                  stream.rate() == whole->rate,
              "stream" + std::to_string(bits) + " agrees about the shape");

        // Three windows: the start, an odd offset well in, and the end, where
        // an off-by-one would run past the file.
        const int64_t n = whole->frames;
        bool same = true;
        int64_t worstAt = -1;
        for (const int64_t from : {static_cast<int64_t>(0), n / 3 + 37, n - 64}) {
            std::vector<float> got(256, 0.0f);
            const int64_t k = stream.read(from, 0, got.data(), 256);
            for (int64_t i = 0; i < k; ++i) {
                if (std::fabs(got[static_cast<size_t>(i)] -
                              whole->left[static_cast<size_t>(from + i)]) > 1e-6f) {
                    same = false;
                    if (worstAt < 0) worstAt = from + i;
                }
            }
        }
        check(same, "stream" + std::to_string(bits) + " reads what the reader read" +
                        (worstAt < 0 ? "" : " (first differing frame " + std::to_string(worstAt) + ")"));

        // Reading past the end returns nothing.
        std::vector<float> tail(16, 1.0f);
        check(stream.read(n, 0, tail.data(), 16) == 0, "stream" + std::to_string(bits) +
                                                           " reads nothing past the end");
        // The right channel isn't mixed up with the left.
        std::vector<float> right(64, 0.0f);
        const int64_t k = stream.read(100, 1, right.data(), 64);
        bool rightOk = whole->stereo && k == 64;
        for (int64_t i = 0; i < k && rightOk; ++i) {
            rightOk = std::fabs(right[static_cast<size_t>(i)] -
                                whole->right[static_cast<size_t>(100 + i)]) < 1e-6f;
        }
        check(rightOk, "stream" + std::to_string(bits) + " reads the other channel");
    }
}

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : ".";
    const std::vector<float> s = signalFor();

    // Allow a step and a half. Writers quantise with `lrint(v * 32767)` and
    // readers divide by 32768 (the usual convention), so a round trip loses
    // half a step to rounding plus up to another step at full scale.
    const float step16 = 1.5f / 32768.0f, step24 = 1.5f / 8388608.0f;
    streamMatchesTheReader(dir, s);
    { WavWriter w;  lossless("wav16",  w, dir + "/rt16.wav",  16, AudioFormat::Wav,  s, step16); }
    { WavWriter w;  lossless("wav24",  w, dir + "/rt24.wav",  24, AudioFormat::Wav,  s, step24); }
    { WavWriter w;  lossless("wav32",  w, dir + "/rt32.wav",  32, AudioFormat::Wav,  s, 1e-7f); }
    { AiffWriter a; lossless("aiff16", a, dir + "/rt16.aiff", 16, AudioFormat::Aiff, s, step16); }
    { AiffWriter a; lossless("aiff24", a, dir + "/rt24.aiff", 24, AudioFormat::Aiff, s, step24); }
    { AiffWriter a; lossless("aiff32", a, dir + "/rt32.aiff", 32, AudioFormat::Aiff, s, 1e-7f); }
    { FlacWriter f; lossless("flac16", f, dir + "/rt16.flac", 16, AudioFormat::Flac, s, step16); }
    { FlacWriter f; lossless("flac24", f, dir + "/rt24.flac", 24, AudioFormat::Flac, s, step24); }

    // MP3 is lossy, so only check length and level.
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
                // A frame is 1152 samples and the encoder pads both ends.
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

    // Real-world files often have a tag before the audio and junk after it.
    {
        std::vector<unsigned char> raw;
        FILE *f = std::fopen((dir + "/rt.mp3").c_str(), "rb");
        if (f != nullptr) {
            unsigned char buf[65536];
            size_t n = 0;
            while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) raw.insert(raw.end(), buf, buf + n);
            std::fclose(f);
        }
        // An ID3v2 header: "ID3", version, flags, then a syncsafe length (four
        // bytes of seven bits each). The body stands in for album art.
        //
        // It contains `FF FE 42 00`, which is a valid MPEG-1 Layer I frame
        // header. A decoder that starts at byte 0 can lock onto it and decode
        // the art as noise. It's large so the art outlasts a resync.
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
        // A trailing APE-like block, which is junk to a decoder.
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
            // Compared with the untagged decode of the same audio, same length
            // and loudness. Just checking the length would pass a decode that
            // included noise from the art.
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

    // A format we recognise but can't read is reported by name, not as a
    // broken mp3.
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
            // Padded with the byte that looks like half an mp3 frame sync.
            body.resize(20000, 0xFF);
            FILE *f = std::fopen(path.c_str(), "wb");
            std::fwrite(body.data(), 1, body.size(), f);
            std::fclose(f);
            check(sniff(path) == AudioFormat::Unknown, std::string(c.name) + " is not mistaken for an mp3");
            std::string error;
            // Decode first, then check. As arguments to the same call, C++
            // doesn't define which runs first, so `error` could be read before
            // it's written.
            const bool refused = decodeAudio(path, kRate, error) == nullptr;
            check(refused && error.find(c.says) != std::string::npos,
                  std::string(c.name) + " is named in the message (" + error + ")");
        }
    }

    // The decoder picks the format by content, not by file name.
    {
        const std::string named = dir + "/actually-a-flac.wav";
        FlacWriter f;
        writeWith(f, named, 16, s);
        check(sniff(named) == AudioFormat::Flac, "a FLAC named .wav is still a FLAC");
        std::string error;
        check(decodeAudio(named, kRate, error) != nullptr, "and decodes anyway");
        check(sniff(dir + "/nothing-here") == AudioFormat::Unknown, "a missing file is nothing");
    }

    // --- the length cap, and reporting when it was hit ---------------------
    //
    // A long file is cut to the cap and `truncated` must be set. Checked for
    // all four readers, since each does its own trimming.
    {
        // Three seconds of signal with a two second cap.
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
            // Within a frame either way, since mp3 pads both ends.
            check(std::abs(cut->frames - kCap * kRate) < 2304,
                  std::string(l.name) + " kept " + std::to_string(cut->frames) + " frames, wanted " +
                      std::to_string(kCap * kRate));

            // Under a cap it fits in (like the slice source uses), nothing is
            // lost and `truncated` isn't set.
            auto whole = decodeAudio(path, kRate, error, kMaxSliceSeconds);
            if (!whole) { check(false, std::string(l.name) + " decodes whole (" + error + ")"); continue; }
            check(!whole->truncated && std::abs(whole->frames - kLongFrames) < 2304,
                  std::string(l.name) + " comes back whole under the long ceiling (" +
                      std::to_string(whole->frames) + " frames)");
        }
    }

    // Headers that lie. None may read outside the file; the sanitizer is the
    // real check here.
    {
        auto put = [](std::vector<unsigned char> &v, uint32_t x, bool big) {
            for (int i = 0; i < 4; ++i) v.push_back(static_cast<unsigned char>(big ? x >> (24 - 8 * i) : x >> (8 * i)));
        };
        auto save = [&](const std::string &name, const std::vector<unsigned char> &v) {
            const std::string path = dir + "/" + name;
            FILE *f = std::fopen(path.c_str(), "wb");
            std::fwrite(v.data(), 1, v.size(), f);
            std::fclose(f);
            return path;
        };
        std::string error;
        // An AIFF whose SSND offset wraps 8 + offset round to 0.
        std::vector<unsigned char> a = {'F', 'O', 'R', 'M', 0, 0, 0, 0, 'A', 'I', 'F', 'F', 'C', 'O', 'M', 'M', 0, 0, 0, 18,
                                        0, 1, 0, 0, 3, 0xE8, 0, 16, 0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0,
                                        'S', 'S', 'N', 'D'};
        put(a, 72, true);
        put(a, 0xFFFFFFF8u, true);
        put(a, 0, true);
        a.resize(a.size() + 64, 0);
        check(decodeAudio(save("wrap.aiff", a), kRate, error) == nullptr, "an AIFF whose offset wraps is refused");
        // A WAV cut off mid-recording: its data length runs past the end.
        std::vector<unsigned char> w = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' '};
        put(w, 16, false);
        const unsigned char fmt[] = {1, 0, 1, 0, 0x80, 0xBB, 0, 0, 0, 0x77, 1, 0, 2, 0, 16, 0};
        w.insert(w.end(), fmt, fmt + 16);
        w.insert(w.end(), {'d', 'a', 't', 'a'});
        put(w, 0xFFFFFFFFu, false);
        w.resize(w.size() + 4800 * 2, 0);
        auto cut = decodeAudio(save("cut.wav", w), kRate, error);
        check(cut != nullptr && cut->frames == 4800, "a WAV cut off mid-recording plays what's there");
    }

    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
