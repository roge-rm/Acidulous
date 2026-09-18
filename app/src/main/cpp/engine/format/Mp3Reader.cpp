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

std::unique_ptr<SampleData> Mp3Reader::read(const std::string &path, int32_t targetRate, std::string &error) {
    std::vector<unsigned char> bytes;
    if (!slurp(path, bytes, error)) return nullptr;

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
            cap = static_cast<int64_t>(kMaxDecodeSeconds) * got.rate;
            any = true;
        }
        if (!any) return;
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
    bool failed = false;
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
    for (size_t off = 0; off <= bytes.size() && !failed; off += chunk) {
        const size_t len = std::min(chunk, bytes.size() - std::min(off, bytes.size()));
        int n = hip_decode1_headers(hip, len > 0 ? bytes.data() + off : nullptr,
                                    len, left.data(), right.data(), &info);
        while (n > 0) {
            take(n);
            if (cap > 0 && static_cast<int64_t>(got.ch[0].size()) >= cap) break;
            n = hip_decode1_headers(hip, nullptr, 0, left.data(), right.data(), &info);
        }
        if (n < 0) failed = true;
        if (cap > 0 && static_cast<int64_t>(got.ch[0].size()) >= cap) break;
        if (len == 0) break; // the last drain is done
    }
    hip_decode_exit(hip);
    if (failed && !any) {
        error = "not readable as MPEG audio";
        return nullptr;
    }

    if (!any || got.ch[0].empty()) { error = "no audio in it"; return nullptr; }
    got.frames = static_cast<int32_t>(got.ch[0].size());
    if (got.stereo && static_cast<int32_t>(got.ch[1].size()) < got.frames) {
        got.frames = static_cast<int32_t>(got.ch[1].size());
    }
    auto out = assemble(got, path, targetRate);
    if (!out) error = "empty";
    return out;
}

} // namespace acidulous
