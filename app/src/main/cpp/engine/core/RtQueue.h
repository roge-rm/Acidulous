#pragma once
#include <atomic>
#include <cstddef>

// Single-producer, single-consumer ring. Wait-free on both sides. This is the
// only cross-thread structure the audio thread touches, in either direction.
namespace acidulous {

template <typename T, size_t N>
class RtQueue {
    static_assert((N & (N - 1)) == 0, "N must be a power of two");

  public:
    bool push(const T &item) {
        const size_t w = wr.load(std::memory_order_relaxed);
        const size_t next = (w + 1) & (N - 1);
        if (next == rd.load(std::memory_order_acquire)) {
            return false;
        }
        buf[w] = item;
        wr.store(next, std::memory_order_release);
        return true;
    }

    bool pop(T &out) {
        const size_t r = rd.load(std::memory_order_relaxed);
        if (r == wr.load(std::memory_order_acquire)) {
            return false;
        }
        out = buf[r];
        rd.store((r + 1) & (N - 1), std::memory_order_release);
        return true;
    }

    bool empty() const { return rd.load(std::memory_order_acquire) == wr.load(std::memory_order_acquire); }

  private:
    T buf[N];
    std::atomic<size_t> wr{0};
    std::atomic<size_t> rd{0};
};

} // namespace acidulous
