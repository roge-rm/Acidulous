#include "Mp3Writer.h"

#include <lame.h>

namespace acidulous {

namespace {

/**
 * What LAME asks for: a fifth again as many bytes as there are samples, plus
 * seven thousand two hundred for the worst a frame can be.
 */
int32_t encodeRoom(int32_t frames) { return static_cast<int32_t>(frames * 1.25f) + 7200; }

constexpr int32_t kDefaultBitrate = 256;

} // namespace

Mp3Writer::~Mp3Writer() { close(); }

bool Mp3Writer::open(const std::string &path, int32_t sampleRate, int32_t bits, std::string &error) {
    // "w+b", not "wb": closing rewinds to rewrite the first frame with the
    // Xing header, and LAME reads what it is about to replace. A write-only
    // handle gets as far as "could not update LAME tag" and leaves a file
    // with no duration in it.
    file = std::fopen(path.c_str(), "w+b");
    if (file == nullptr) {
        error = "could not open " + path;
        return false;
    }
    lame_t g = lame_init();
    if (g == nullptr) {
        error = "the MP3 encoder would not start";
        std::fclose(file);
        file = nullptr;
        return false;
    }
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, 2);
    // The caller's "bits" is kbit here - see the header. A number that is
    // plainly not a bitrate means somebody wired a bit depth through by
    // accident, so it takes the default rather than encoding at 24 kbit.
    lame_set_brate(g, (bits >= 32 && bits <= 320) ? bits : kDefaultBitrate);
    lame_set_mode(g, JOINT_STEREO);
    // 2 of 0..9. Nought is barely better and several times slower, and this
    // runs while somebody waits for an export rather than in the background.
    lame_set_quality(g, 2);
    if (lame_init_params(g) < 0) {
        error = "the MP3 encoder refused those settings";
        lame_close(g);
        std::fclose(file);
        file = nullptr;
        return false;
    }
    lame = g;
    frames = 0;
    closed = false;
    return true;
}

void Mp3Writer::write(const float *interleaved, int32_t count) {
    if (lame == nullptr || file == nullptr || count <= 0) return;
    buffer.resize(static_cast<size_t>(encodeRoom(count)));
    // The *ieee* float entry point, which takes plus or minus one. Its
    // neighbour `lame_encode_buffer_float` differs by a word and wants plus
    // or minus 32768, and handing our samples to that one is silence.
    const int written = lame_encode_buffer_interleaved_ieee_float(
        static_cast<lame_t>(lame), interleaved, count, buffer.data(),
        static_cast<int>(buffer.size()));
    if (written > 0) std::fwrite(buffer.data(), 1, static_cast<size_t>(written), file);
    frames += count;
}

bool Mp3Writer::close() {
    if (closed) return true;
    closed = true;
    if (lame != nullptr && file != nullptr) {
        // The last frame, and then the Xing header that says how long the
        // whole thing is - which is what lets a player show a duration and
        // seek without reading to the end first. It rewrites the first frame,
        // so the file has to still be open and seekable.
        buffer.resize(7200);
        const int flushed = lame_encode_flush(static_cast<lame_t>(lame), buffer.data(),
                                              static_cast<int>(buffer.size()));
        if (flushed > 0) std::fwrite(buffer.data(), 1, static_cast<size_t>(flushed), file);
        lame_mp3_tags_fid(static_cast<lame_t>(lame), file);
    }
    if (lame != nullptr) {
        lame_close(static_cast<lame_t>(lame));
        lame = nullptr;
    }
    if (file != nullptr) {
        std::fclose(file);
        file = nullptr;
    }
    return true;
}

} // namespace acidulous
