#pragma once
#include <algorithm>
#include <cstdint>
#include <engine/core/Sample.h>
#include <memory>
#include <string>
#include <vector>

// What every audio reader produces before it becomes a SampleData, and the
// one piece of arithmetic they all share.
//
// A container is a container: WAV, AIFF, FLAC and MP3 differ entirely in how
// they store the numbers and not at all in what the numbers mean afterwards.
// So each reader's job ends at "channel planes at the file's own rate", and
// the truncation, the resampling and the naming happen once, here, where a
// change to any of them reaches all four.
namespace acidulous {

/** Channel planes at the file's own rate, before anything is done to them. */
struct DecodedAudio {
    std::vector<float> ch[2];
    int32_t frames = 0;
    int32_t rate = 48000;
    bool stereo = false;
    /**
     * Set by a reader that stopped early because it hit the cap.
     *
     * The readers cap as they go rather than decoding a whole file and
     * throwing most of it away, so by the time `assemble` sees the planes
     * they are already the right length and it cannot tell that anything was
     * lost. Without this the "only the first N seconds arrived" message was
     * unreachable: every reader trimmed, so nothing was ever reported.
     */
    bool truncated = false;
};

/**
 * The longest a sample can be, in seconds.
 *
 * Two ceilings, because the two uses cost differently. A pad sample is one of
 * thirteen and thirty seconds of it is 11.5 MB; thirteen of those is already
 * 150 MB. A slice source is one file for the whole machine - it is mounted
 * once at Forage::kSharedSlot and every pad reads a region of it - so it can
 * afford to be a whole track. Ten minutes of stereo float at 48 kHz is
 * 230 MB, which is the price of slicing an album track and is knowingly paid.
 */
constexpr int32_t kMaxDecodeSeconds = 30;
constexpr int32_t kMaxSliceSeconds = 600;

/**
 * Planes in, a mounted sample out.
 *
 * A [targetRate] of zero or less keeps the file's own rate, which is what a
 * multisample wants: it takes the ratio into account when it pitches, so
 * resampling a whole instrument would only cost quality. Everything else
 * resamples, because every machine but Mosaic assumes the engine rate.
 */
inline std::unique_ptr<SampleData> assemble(DecodedAudio &in, const std::string &path, int32_t targetRate,
                                           int32_t maxSeconds = kMaxDecodeSeconds) {
    if (in.frames <= 0) return nullptr;
    const int64_t cap = static_cast<int64_t>(maxSeconds) * in.rate;
    const bool cut = in.frames > cap;
    if (cut) in.frames = static_cast<int32_t>(cap);

    auto out = std::make_unique<SampleData>();
    // Either this cut it, or the reader did on the way in and said so.
    out->truncated = cut || in.truncated;
    out->stereo = in.stereo;
    const size_t slash = path.find_last_of('/');
    out->name = slash == std::string::npos ? path : path.substr(slash + 1);

    if (targetRate <= 0) {
        out->rate = in.rate;
        out->frames = in.frames;
        out->left.assign(in.ch[0].begin(), in.ch[0].begin() + in.frames);
        if (out->stereo) out->right.assign(in.ch[1].begin(), in.ch[1].begin() + in.frames);
        out->measure();
        return out;
    }

    // Linear interpolation. Good enough for drums; a better interpolator can
    // replace this without touching a single caller.
    out->rate = targetRate;
    const double ratio = static_cast<double>(in.rate) / static_cast<double>(targetRate);
    const auto outFrames = static_cast<int32_t>(static_cast<double>(in.frames) / ratio);
    if (outFrames <= 0) return nullptr;
    out->frames = outFrames;
    out->left.resize(static_cast<size_t>(outFrames));
    if (out->stereo) out->right.resize(static_cast<size_t>(outFrames));
    for (int32_t i = 0; i < outFrames; ++i) {
        const double srcPos = i * ratio;
        const auto i0 = static_cast<int32_t>(srcPos);
        const int32_t i1 = i0 + 1 < in.frames ? i0 + 1 : i0;
        const float frac = static_cast<float>(srcPos - i0);
        out->left[static_cast<size_t>(i)] =
            in.ch[0][static_cast<size_t>(i0)] * (1.0f - frac) + in.ch[0][static_cast<size_t>(i1)] * frac;
        if (out->stereo) {
            out->right[static_cast<size_t>(i)] =
                in.ch[1][static_cast<size_t>(i0)] * (1.0f - frac) + in.ch[1][static_cast<size_t>(i1)] * frac;
        }
    }
    out->measure();
    return out;
}

/** The whole file in memory, or false and why not. [ceiling] is in bytes. */
bool slurp(const std::string &path, std::vector<unsigned char> &bytes, std::string &error,
           size_t ceiling = 64u * 1024u * 1024u);

/** At most [maxBytes] from the front of a file - enough to recognise it. */
bool slurpHead(const std::string &path, std::vector<unsigned char> &bytes, size_t maxBytes, std::string &error);

/** What a file of [maxSeconds] could weigh as a WAV, so slurp will take it. */
inline size_t slurpCeilingFor(int32_t maxSeconds) {
    // 48 kHz stereo at 24 bits is 288 kB a second, and a container adds
    // nothing that matters; half again for headroom over odd rates and depths.
    const size_t bytes = static_cast<size_t>(maxSeconds) * 288u * 1024u * 3u / 2u;
    return bytes < 64u * 1024u * 1024u ? 64u * 1024u * 1024u : bytes;
}

} // namespace acidulous
