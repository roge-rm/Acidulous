#pragma once
#include <cstdint>
#include <cstdio>
#include <string>

// A WAV read a piece at a time, rather than all at once.
//
// `WavReader` slurps: the whole file becomes bytes, then float planes, then a
// `SampleData`, and for a five-minute take that peaked at 118 MB above the
// 28 MB it kept. That is fine for a drum hit and wrong for a recording that
// runs the length of a song, which is exactly what an audio track is for.
//
// So this reads the header once and then answers "frames [from, from+count) of
// channel c" out of the file, converting and resampling as it goes. Nothing it
// does is proportional to the length of the file.
//
// It is deliberately not a `Machine`'s idea of streaming: there is no thread
// here and no ring. What it feeds is the *converter*, which writes the engine's
// own flat format once so that the audio thread can memory-map it and let the
// kernel do the paging. Reading a file on the audio thread remains something
// this app does not do.
namespace acidulous {

class WavStream {
  public:
    ~WavStream() { close(); }
    WavStream() = default;
    WavStream(const WavStream &) = delete;
    WavStream &operator=(const WavStream &) = delete;

    /** Header only. False and why not if it is not a WAV this can read. */
    bool open(const std::string &path, std::string &error);
    void close();

    int32_t channels() const { return chans; }
    int32_t rate() const { return srcRate; }
    /** Frames **in the file**, at the file's own rate. */
    int64_t frames() const { return frameCount; }
    bool isOpen() const { return file != nullptr; }

    /**
     * [count] frames of channel [ch], starting at file frame [from].
     *
     * Reads at the file's own rate: resampling belongs to the caller, which
     * knows how the chunks join up. Returns how many frames were written,
     * which is short only at the end of the file.
     */
    int64_t read(int64_t from, int32_t ch, float *out, int64_t count);

  private:
    std::FILE *file = nullptr;
    int64_t dataOffset = 0;
    int64_t frameCount = 0;
    int32_t chans = 0;
    int32_t srcRate = 0;
    int32_t bytesPerSample = 0;
    bool floatFormat = false;
};

} // namespace acidulous
