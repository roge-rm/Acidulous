#include "WavReader.h"
#include <cstdio>
#include <cstring>

namespace acidulous {

namespace {
uint32_t u32(const unsigned char *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
uint16_t u16(const unsigned char *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
} // namespace

std::unique_ptr<SampleData> WavReader::read(const std::string &path, int32_t targetRate, std::string &error) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) { error = "cannot open"; return nullptr; }
    std::vector<unsigned char> bytes;
    unsigned char buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) {
        bytes.insert(bytes.end(), buf, buf + n);
        if (bytes.size() > 64u * 1024u * 1024u) { std::fclose(f); error = "file too large"; return nullptr; }
    }
    std::fclose(f);
    if (bytes.size() < 12 || std::memcmp(bytes.data(), "RIFF", 4) != 0 || std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        error = "not a RIFF/WAVE file";
        return nullptr;
    }

    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t rate = 0;
    const unsigned char *data = nullptr;
    uint32_t dataLen = 0;
    size_t pos = 12;
    while (pos + 8 <= bytes.size()) {
        const unsigned char *id = bytes.data() + pos;
        const uint32_t len = u32(bytes.data() + pos + 4);
        const unsigned char *body = bytes.data() + pos + 8;
        if (pos + 8 + len > bytes.size()) break;
        if (std::memcmp(id, "fmt ", 4) == 0 && len >= 16) {
            format = u16(body);
            channels = u16(body + 2);
            rate = u32(body + 4);
            bits = u16(body + 14);
            if (format == 0xFFFE && len >= 26) format = u16(body + 24); // WAVE_FORMAT_EXTENSIBLE: the sub-format's first word
        } else if (std::memcmp(id, "data", 4) == 0) {
            data = body;
            dataLen = len;
        }
        pos += 8 + len + (len & 1);
    }
    if (data == nullptr || channels == 0 || rate == 0) { error = "missing fmt or data"; return nullptr; }
    if (channels > 2) { error = "more than two channels"; return nullptr; }
    const bool isFloat = format == 3;
    if (!(format == 1 || isFloat)) { error = "compressed WAV"; return nullptr; }
    if (!((isFloat && bits == 32) || (!isFloat && (bits == 8 || bits == 16 || bits == 24 || bits == 32)))) {
        error = "unsupported bit depth";
        return nullptr;
    }
    const uint32_t bytesPerSample = bits / 8;
    const uint32_t frameBytes = bytesPerSample * channels;
    uint32_t frames = dataLen / frameBytes;
    if (frames == 0) { error = "empty"; return nullptr; }
    const uint32_t maxFrames = static_cast<uint32_t>(kMaxSeconds) * rate;
    if (frames > maxFrames) frames = maxFrames;

    // Decode to float at the source rate.
    std::vector<float> src[2];
    for (uint16_t c = 0; c < channels; ++c) src[c].resize(frames);
    for (uint32_t i = 0; i < frames; ++i) {
        for (uint16_t c = 0; c < channels; ++c) {
            const unsigned char *p = data + (i * channels + c) * bytesPerSample;
            float v;
            if (isFloat) {
                uint32_t bitsv = u32(p);
                std::memcpy(&v, &bitsv, 4);
            } else if (bits == 8) {
                v = (static_cast<int>(p[0]) - 128) / 128.0f;
            } else if (bits == 16) {
                v = static_cast<int16_t>(u16(p)) / 32768.0f;
            } else if (bits == 24) {
                int32_t s = (p[0] << 8) | (p[1] << 16) | (p[2] << 24);
                v = static_cast<float>(s >> 8) / 8388608.0f;
            } else {
                v = static_cast<int32_t>(u32(p)) / 2147483648.0f;
            }
            src[c][i] = v;
        }
    }

    auto out = std::make_unique<SampleData>();
    out->stereo = channels == 2;
    if (targetRate <= 0) {
        // Keep the file's own rate. A multisample player takes the ratio into
        // account when it pitches, so resampling would only cost quality.
        out->rate = static_cast<int32_t>(rate);
        out->frames = static_cast<int32_t>(frames);
        out->left.assign(src[0].begin(), src[0].begin() + frames);
        if (out->stereo) out->right.assign(src[1].begin(), src[1].begin() + frames);
        const size_t cut = path.find_last_of('/');
        out->name = cut == std::string::npos ? path : path.substr(cut + 1);
        out->measure();
        return out;
    }

    // Resample to the engine rate by linear interpolation. Good enough for
    // drums; a better interpolator can replace this without touching callers.
    out->rate = targetRate;
    const double ratio = static_cast<double>(rate) / static_cast<double>(targetRate);
    const auto outFrames = static_cast<int32_t>(static_cast<double>(frames) / ratio);
    out->frames = outFrames;
    out->left.resize(static_cast<size_t>(outFrames));
    if (out->stereo) out->right.resize(static_cast<size_t>(outFrames));
    for (int32_t i = 0; i < outFrames; ++i) {
        const double srcPos = i * ratio;
        const auto i0 = static_cast<uint32_t>(srcPos);
        const uint32_t i1 = i0 + 1 < frames ? i0 + 1 : i0;
        const float frac = static_cast<float>(srcPos - i0);
        out->left[static_cast<size_t>(i)] = src[0][i0] * (1.0f - frac) + src[0][i1] * frac;
        if (out->stereo) out->right[static_cast<size_t>(i)] = src[1][i0] * (1.0f - frac) + src[1][i1] * frac;
    }
    const size_t slash = path.find_last_of('/');
    out->name = slash == std::string::npos ? path : path.substr(slash + 1);
    out->measure();
    return out;
}

} // namespace acidulous
