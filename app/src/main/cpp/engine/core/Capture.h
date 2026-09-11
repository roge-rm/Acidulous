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
    const std::string &file() const { return outPath; }

    // Audio thread. Interleaved stereo.
    void push(const float *interleaved, int32_t frames);

  private:
    void drain();

    std::vector<float> ring;
    std::atomic<int64_t> writeIndex{0};
    std::atomic<int64_t> readIndex{0};
    std::atomic<bool> running{false};
    std::atomic<bool> overflow{false};
    std::atomic<int64_t> written{0};
    std::atomic<float> peakLevel{0.0f};
    std::thread worker;
    std::string outPath;
    int32_t rate = 48000;
    Source which = FromInput;
};

} // namespace acidulous
