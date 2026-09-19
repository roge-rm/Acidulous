#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

// Recording audio to a file, without the audio thread ever touching one.
//
// The callback pushes frames into a ring and returns. A writer thread drains
// the ring to disk. If the writer falls behind far enough to fill the ring,
// the callback drops what will not fit and sets a flag: a gap in a recording
// is bad, a glitch in the output is worse, and the flag means we can say
// which happened rather than guess.
namespace acidulous {

class Capture {
  public:
    enum Source : int32_t { FromInput = 0, FromMaster = 1 };

    ~Capture() { stop(); }

    bool start(const std::string &path, int32_t sampleRate, Source source, std::string &error);
    void stop();

    bool armed() const { return running.load(std::memory_order_acquire); }
    Source source() const { return which; }
    int64_t frames() const { return written.load(std::memory_order_relaxed); }
    float peak() const { return peakLevel.load(std::memory_order_relaxed); }
    bool overflowed() const { return overflow.load(std::memory_order_relaxed); }
    /**
     * True once an input capture has been asked to record with nothing
     * arriving - no stream open, or one that has gone away.
     *
     * It is a separate flag from `overflowed` because it is a different
     * sentence: one says the recording has a hole in it, this one says there
     * was never anything to record. Both are worth more than a file of
     * silence and no explanation.
     */
    bool deaf() const { return wasDeaf.load(std::memory_order_relaxed); }
    const std::string &file() const { return outPath; }

    // Audio thread. Interleaved stereo.
    void push(const float *interleaved, int32_t frames);
    /** Audio thread. As `push`, with nothing to push: see `deaf()`. */
    void pushSilence(int32_t frames);

  private:
    void drain();

    std::vector<float> ring;
    std::atomic<int64_t> writeIndex{0};
    std::atomic<int64_t> readIndex{0};
    std::atomic<bool> running{false};
    std::atomic<bool> overflow{false};
    std::atomic<bool> wasDeaf{false};
    std::atomic<int64_t> written{0};
    std::atomic<float> peakLevel{0.0f};
    std::thread worker;
    std::string outPath;
    int32_t rate = 48000;
    // Fixed when recording starts: changing the setting mid-take must not
    // change the format halfway down the file.
    int32_t depth = 24;
    Source which = FromInput;
};

} // namespace acidulous
