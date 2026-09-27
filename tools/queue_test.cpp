// Several threads pushing into one engine queue at once.
//
// Notes arrive from the screen on the UI thread and from each MIDI port on
// that port's own thread, mounts from any worker that loads a sample, and
// parameters from the UI, the mapping and the song sync. The audio thread
// pops. RtQueue was built for one producer: two pushing at once can write the
// same slot, and one message is lost - a note-off, and a note sticks.
//
// This pushes from four threads while a fifth pops, and counts what arrives.
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include <engine/core/RtQueue.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;
void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    std::printf("  %s %-50s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int kProducers = 4;
constexpr int kEach = 200000;

/** Pushes kEach numbered items from each producer, and returns how many distinct ones came out. */
template <typename Q>
int64_t delivered(Q &q) {
    std::atomic<int> done{0};
    std::vector<uint8_t> seen(static_cast<size_t>(kProducers) * kEach, 0);
    int64_t got = 0;
    std::thread consumer([&] {
        uint32_t v = 0;
        while (done.load() < kProducers || !q.empty()) {
            while (q.pop(v)) {
                if (v < seen.size() && !seen[v]) { seen[v] = 1; ++got; }
            }
        }
    });
    std::vector<std::thread> producers;
    for (int p = 0; p < kProducers; ++p) {
        producers.emplace_back([&, p] {
            for (int i = 0; i < kEach; ++i) {
                const auto v = static_cast<uint32_t>(p * kEach + i);
                while (!q.push(v)) std::this_thread::yield(); // full: wait, as the host does
            }
            done.fetch_add(1);
        });
    }
    for (auto &t : producers) t.join();
    consumer.join();
    return got;
}
} // namespace

int main() {
    std::printf("queue_test\n");
    const int64_t want = static_cast<int64_t>(kProducers) * kEach;
    {
        static SharedQueue<uint32_t, 256> q;
        const int64_t got = delivered(q);
        ok("every push from four threads arrives", got == want,
           std::to_string(got) + " of " + std::to_string(want));
    }
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
