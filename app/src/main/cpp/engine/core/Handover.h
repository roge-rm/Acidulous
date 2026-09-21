#pragma once
#include "RtQueue.h"
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>

// How objects cross into and out of the audio thread.
//
// Anything with a lifetime - a machine, a song snapshot - is built on a normal
// thread and *mounted* by pushing a Mount record; the audio thread applies one
// per block, at the block boundary, and never allocates. Whatever it displaces
// goes back out as a Retire record; a worker thread runs the deleter. The
// audio thread therefore never calls new or delete.
namespace acidulous {

struct Mount {
    enum class Kind : uint8_t { None, Machine, Song, Effect, InputMod, Object, Frozen, Send, Input };
    Kind kind = Kind::None;
    int32_t rack = 0;
    int32_t slot = 0;
    void *object = nullptr;
    // Object mounts: how to retire whatever the machine hands back (or this, if refused).
    void (*deleter)(void *) = nullptr;
};

struct Retire {
    void *object = nullptr;
    void (*deleter)(void *) = nullptr;
};

class Retirer {
  public:
    ~Retirer() { stop(); }

    void start() {
        if (running.exchange(true)) return;
        worker = std::thread([this] { run(); });
    }

    void stop() {
        if (!running.exchange(false)) return;
        {
            std::lock_guard<std::mutex> lock(mtx);
            wake = true;
        }
        cv.notify_one();
        if (worker.joinable()) worker.join();
        drain();
    }

    // Audio thread. Never blocks; a full queue leaks the object rather than stall.
    void retire(void *object, void (*deleter)(void *)) {
        if (object == nullptr) return;
        Retire r;
        r.object = object;
        r.deleter = deleter;
        queue.push(r);
        // The wake is a relaxed flag; the worker also polls, so a missed notify
        // only delays deletion by one poll interval.
        pendingSignal.store(true, std::memory_order_release);
    }

  private:
    void run() {
        while (running.load(std::memory_order_relaxed)) {
            drain();
            std::unique_lock<std::mutex> lock(mtx);
            cv.wait_for(lock, std::chrono::milliseconds(20), [&] { return wake || pendingSignal.load(std::memory_order_acquire); });
            wake = false;
            pendingSignal.store(false, std::memory_order_relaxed);
        }
    }

    void drain() {
        Retire r;
        while (queue.pop(r)) {
            if (r.deleter) r.deleter(r.object);
        }
    }

    RtQueue<Retire, 256> queue;
    std::atomic<bool> running{false};
    std::atomic<bool> pendingSignal{false};
    std::thread worker;
    std::mutex mtx;
    std::condition_variable cv;
    bool wake = false;
};

template <typename T>
void deleteAs(void *p) { delete static_cast<T *>(p); }

} // namespace acidulous
