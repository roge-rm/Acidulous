#include "AudioDriver.h"

#include <chrono>

std::atomic<int64_t> AudioDriver::rendered{0};

bool AudioDriver::start() {
    if (running.load()) return true;
    if (!callback) return false;
    running.store(true);
    thread = std::thread([this] {
        using clock = std::chrono::steady_clock;
        const auto block = std::chrono::microseconds(1000000LL * acidulous::kBlockFrames / acidulous::kSampleRate);
        float out[acidulous::kBlockFrames * 2];
        auto next = clock::now();
        while (running.load(std::memory_order_relaxed)) {
            callback(nullptr, out, acidulous::kBlockFrames);
            rendered.fetch_add(1, std::memory_order_release);
            next += block;
            std::this_thread::sleep_until(next);
        }
    });
    return true;
}

void AudioDriver::stop() {
    if (!running.exchange(false)) return;
    if (thread.joinable()) thread.join();
}
