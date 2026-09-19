#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

// Playing a file once, to hear what it is.
//
// The browser lists what is in the samples folder and until now the only way
// to find out which one a name refers to was to put it on a machine and press
// a key. That is a long way to go to answer "is this the snare".
//
// **Not a machine and not a mount.** A machine is part of the song and a
// mount is a hand-over with a deleter and a queue; this is neither. It is one
// buffer the master mixes in when it is asked to, outside the song entirely -
// it is not recorded, not exported, not frozen, and it stops the moment
// anything else wants attention.
//
// **How it crosses the thread, without a queue.** Two buffers and an index.
// The audio thread reads `which` once and touches only that buffer for the
// whole block; the UI thread only ever writes the *other* one and publishes
// it afterwards. So there is no moment where one is reading what the other is
// resizing, and nothing has to be freed on the audio thread or at all.
namespace acidulous {

class Audition {
  public:
    /** UI thread. Interleaved stereo at the engine rate. Starts it playing. */
    void play(const float *interleaved, int64_t frames) {
        const int spare = 1 - which.load(std::memory_order_acquire);
        auto &buffer = pcm[static_cast<size_t>(spare < 0 ? 0 : spare)];
        buffer.assign(interleaved, interleaved + static_cast<size_t>(frames) * 2);
        position.store(0, std::memory_order_relaxed);
        which.store(spare < 0 ? 0 : spare, std::memory_order_release);
        playing.store(true, std::memory_order_release);
    }

    /** Either thread. */
    void stop() { playing.store(false, std::memory_order_release); }
    bool active() const { return playing.load(std::memory_order_relaxed); }

    /** Audio thread. Adds what is left into [out], and stops at the end. */
    void mix(float *out, int32_t frames) {
        if (!playing.load(std::memory_order_acquire)) return;
        const int slot = which.load(std::memory_order_acquire);
        if (slot < 0) return;
        const std::vector<float> &buffer = pcm[static_cast<size_t>(slot)];
        const auto total = static_cast<int64_t>(buffer.size() / 2);
        int64_t at = position.load(std::memory_order_relaxed);
        for (int32_t i = 0; i < frames && at < total; ++i, ++at) {
            out[i * 2] += buffer[static_cast<size_t>(at) * 2];
            out[i * 2 + 1] += buffer[static_cast<size_t>(at) * 2 + 1];
        }
        position.store(at, std::memory_order_relaxed);
        if (at >= total) playing.store(false, std::memory_order_release);
    }

  private:
    std::vector<float> pcm[2];
    std::atomic<int> which{-1};
    std::atomic<int64_t> position{0};
    std::atomic<bool> playing{false};
};

} // namespace acidulous
