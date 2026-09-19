#include "Mp3Reader.h"
#include "Decoded.h"
#include <algorithm>
#include <cstring>
#include <lame.h>

// The only reader here that is not ours, and the only one that should not be.
//
// An mp3 decoder is a large and fiddly thing - Huffman tables, a hybrid
// filter bank, bit reservoirs across frames - and we already ship the encoder
// that comes with one. `mpglib_interface.c` has been in the build all along
// doing nothing, gated on HAVE_MPGLIB; vendoring `mpglib/` beside it is what
// turns `hip_decode` from a declaration into a decoder. Same library, same
// .so, same LGPL story as the encoder.
//
// It is also the only lossy format here, so a round trip will not come back
// sample for sample and no test should ask it to.
namespace acidulous {

namespace {
/**
 * mpglib hands back interleaved 16-bit, in whatever sized pieces it likes.
 *
 * It needs the stream fed to it and drained repeatedly: one call in can
 * produce several frames out or none at all, because a frame's data may
 * finish in a later one than it started in. So every push is followed by
 * draining to empty, and the end of the file by one last drain - `hip_decode`
 * with nothing new holds whatever is still inside.
 */
constexpr int kOutSamples = 16384; // per channel, which is far more than a frame
} // namespace

namespace {

/**
 * The samples an encoder added and expects a decoder to throw away.
 *
 * **Every mp3 starts late otherwise.** An encoder has to prime its filter
 * bank before the first real sample can come out, so a file holds a few
 * hundred samples of nothing at the front and a few hundred more at the back
 * to fill the last frame. LAME writes both numbers into an `Info`/`Xing`
 * frame at the head of the file, and a decoder that ignores them hands back
 * about twenty-five milliseconds of silence followed by the music - which on
 * an imported break is a loop that does not start on the one.
 *
 * The tag sits at the top of the first frame, past the side information,
 * whose length depends on the version and whether it is mono. Then four bytes
 * of flags say which of the optional fields are present, and the LAME
 * extension follows them; the delay and padding are three bytes twenty-one
 * into it, twelve bits each.
 *
 * The 529 is the decoder's own share and is a constant of the format rather
 * than of this decoder: it is what mpglib, ffmpeg and LAME's own frontend all
 * add to the encoder's number.
 *
 * Leaves both at nought - which is what every mp3 without the tag gets, and
 * what this did for all of them until now.
 */
void lameTrim(const unsigned char *b, size_t n, size_t at, int32_t &skip, int32_t &trim) {
    skip = 0;
    trim = 0;
    if (at + 4 > n || b[at] != 0xFFu || (b[at + 1] & 0xE0u) != 0xE0u) return;
    const int version = (b[at + 1] >> 3) & 3;   // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
    const bool mono = ((b[at + 3] >> 6) & 3) == 3;
    const size_t side = version == 3 ? (mono ? 17u : 32u) : (mono ? 9u : 17u);
    const size_t xing = at + 4 + side;
    if (xing + 8 > n) return;
    if (std::memcmp(b + xing, "Xing", 4) != 0 && std::memcmp(b + xing, "Info", 4) != 0) return;

    const uint32_t flags = (static_cast<uint32_t>(b[xing + 4]) << 24) |
                           (static_cast<uint32_t>(b[xing + 5]) << 16) |
                           (static_cast<uint32_t>(b[xing + 6]) << 8) | b[xing + 7];
    size_t lame = xing + 8;
    if ((flags & 1u) != 0) lame += 4;   // the frame count
    if ((flags & 2u) != 0) lame += 4;   // the byte count
    if ((flags & 4u) != 0) lame += 100; // the seek table
    if ((flags & 8u) != 0) lame += 4;   // the quality
    if (lame + 24 > n) return;

    const int32_t delay = (static_cast<int32_t>(b[lame + 21]) << 4) | (b[lame + 22] >> 4);
    const int32_t padding = ((static_cast<int32_t>(b[lame + 22]) & 0x0F) << 8) | b[lame + 23];
    // A tag can say anything; a delay of half a second is a tag that is wrong
    // and trimming by it would take the start of the music with it.
    if (delay < 0 || delay > 3000 || padding < 0 || padding > 3000) return;
    skip = delay + 529;
    trim = padding > 529 ? padding - 529 : 0;
}

} // namespace

size_t Mp3Reader::audioStart(const unsigned char *b, size_t n) {
    if (n < 10 || std::memcmp(b, "ID3", 3) != 0) return 0;
    // A syncsafe length: four bytes of seven bits each, not counting the ten
    // byte header it sits in - and ten more if the footer flag is set.
    const size_t size = (static_cast<size_t>(b[6] & 0x7Fu) << 21) | (static_cast<size_t>(b[7] & 0x7Fu) << 14) |
                        (static_cast<size_t>(b[8] & 0x7Fu) << 7) | static_cast<size_t>(b[9] & 0x7Fu);
    const size_t at = 10 + size + ((b[5] & 0x10u) != 0 ? 10u : 0u);
    // A tag claiming to be longer than the file is a tag that is lying, and
    // starting at nought is a better guess than starting past the end.
    return at < n ? at : 0;
}

std::unique_ptr<SampleData> Mp3Reader::read(const std::string &path, int32_t targetRate, std::string &error,
                                           int32_t maxSeconds) {
    std::vector<unsigned char> bytes;
    if (!slurp(path, bytes, error, slurpCeilingFor(maxSeconds))) return nullptr;

    hip_t hip = hip_decode_init();
    if (hip == nullptr) { error = "no decoder"; return nullptr; }

    mp3data_struct info;
    std::memset(&info, 0, sizeof(info));
    std::vector<short> left(kOutSamples), right(kOutSamples);
    DecodedAudio got;
    bool any = false;
    int64_t cap = 0; // set once the rate is known

    const auto take = [&](int samples) {
        if (samples <= 0) return;
        if (!any && info.samplerate > 0) {
            got.rate = info.samplerate;
            got.stereo = info.stereo == 2;
            cap = static_cast<int64_t>(maxSeconds) * got.rate;
            any = true;
        }
        if (!any) return;
        if (static_cast<int64_t>(got.ch[0].size()) + samples > cap) got.truncated = true;
        for (int i = 0; i < samples && static_cast<int64_t>(got.ch[0].size()) < cap; ++i) {
            got.ch[0].push_back(left[static_cast<size_t>(i)] / 32768.0f);
            if (got.stereo) got.ch[1].push_back(right[static_cast<size_t>(i)] / 32768.0f);
        }
    };

    // Fed in pieces and drained after each.
    //
    // Not all at once: a call returns *one* frame's worth at most, and zero
    // whenever it has taken data without finishing a frame - which the very
    // first call always does, since it has a header to read first. A loop
    // that stops at zero therefore stops before any audio at all, which is
    // what "no audio in it" meant the first time this was written.
    int errors = 0;
    // **A kilobyte at a time, and it matters.**
    //
    // mpglib copies what it is given into `bsspace[2][MAXFRAMESIZE + 1024]`,
    // which is 3904 bytes, and quietly drops whatever does not fit. Measured
    // against a 22 kB file of 30000 frames: pushing 1024 at a time decoded
    // 32256 samples, 4096 decoded 29952 - two thousand samples gone with no
    // error returned at all - and 16384 decoded *nothing*, which is what "no
    // audio in it" meant the first time this was written. LAME's own frontend
    // reads 1024 and so does this.
    const size_t chunk = 1024;
    // From the audio, not from the front of the file - see audioStart.
    for (size_t off = audioStart(bytes.data(), bytes.size()); off <= bytes.size(); off += chunk) {
        const size_t len = std::min(chunk, bytes.size() - std::min(off, bytes.size()));
        int n = hip_decode1_headers(hip, len > 0 ? bytes.data() + off : nullptr,
                                    len, left.data(), right.data(), &info);
        while (n > 0) {
            take(n);
            if (cap > 0 && static_cast<int64_t>(got.ch[0].size()) >= cap) break;
            n = hip_decode1_headers(hip, nullptr, 0, left.data(), right.data(), &info);
        }
        // **An error is not the end.**
        //
        // A file off the internet is not a clean stream of frames: it carries
        // album art inside its ID3 tag, an APE or Lyrics3 block on the end,
        // a partial frame where somebody cut it. mpglib says -1 at each of
        // those and carries on perfectly well afterwards, so giving up on the
        // first one means giving up on most real mp3s - which is what "not
        // readable as MPEG audio" was, in front of a file that played
        // everywhere else. Keep feeding; judge it at the end by whether any
        // audio came out.
        if (n < 0) ++errors;
        if (cap > 0 && static_cast<int64_t>(got.ch[0].size()) >= cap) break;
        if (len == 0) break; // the last drain is done
    }
    hip_decode_exit(hip);
    if (!any || got.ch[0].empty()) {
        error = errors > 0 ? "no MPEG audio in it" : "no audio in it";
        return nullptr;
    }

    // The encoder's own padding, off both ends - see lameTrim.
    {
        int32_t skip = 0, trim = 0;
        lameTrim(bytes.data(), bytes.size(), audioStart(bytes.data(), bytes.size()), skip, trim);
        for (auto &ch : got.ch) {
            if (ch.empty()) continue;
            const auto have = static_cast<int32_t>(ch.size());
            const int32_t front = std::min(skip, have);
            const int32_t back = std::min(trim, have - front);
            if (back > 0) ch.resize(static_cast<size_t>(have - back));
            if (front > 0) ch.erase(ch.begin(), ch.begin() + front);
        }
    }

    got.frames = static_cast<int32_t>(got.ch[0].size());
    if (got.stereo && static_cast<int32_t>(got.ch[1].size()) < got.frames) {
        got.frames = static_cast<int32_t>(got.ch[1].size());
    }
    auto out = assemble(got, path, targetRate, maxSeconds);
    if (!out) error = "empty";
    return out;
}

} // namespace acidulous
