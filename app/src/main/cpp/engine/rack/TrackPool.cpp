#include "TrackPool.h"

#include <engine/dsp/Denormals.h>

#include <chrono>
#include <climits>
#include <cstdio>
#include <set>
#include <string>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__)
#include <linux/futex.h>
#include <pthread.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#if defined(__x86_64__) || defined(__i386__) || defined(_M_X64)
#include <immintrin.h>
#endif

namespace acidulous {

namespace {

inline void pause() {
#if defined(__x86_64__) || defined(__i386__) || defined(_M_X64)
    _mm_pause();
#elif defined(__aarch64__) || defined(__arm__)
    __asm__ __volatile__("yield");
#endif
}

// How long a worker keeps looking for the next block before it sleeps: long
// enough to cover the master and the sequencer between two blocks of one
// callback on a phone, so it stays up through the callback.
constexpr auto kSpin = std::chrono::microseconds(250);

// How long the audio thread spins waiting for a worker's job before it
// sleeps. It runs at real-time priority, so spinning on the core the worker
// was put aside on would keep the worker off it until the scheduler moved it.
constexpr auto kAudioSpin = std::chrono::microseconds(20);

uint32_t *word(std::atomic<uint32_t> &a) { return reinterpret_cast<uint32_t *>(&a); }

/** Wakes every thread asleep in sleepWhile on [a]. */
void wake(std::atomic<uint32_t> &a) {
#if defined(_WIN32)
    WakeByAddressAll(word(a));
#elif defined(__linux__)
    syscall(SYS_futex, word(a), FUTEX_WAKE_PRIVATE, INT_MAX, nullptr, nullptr, 0);
#else
    (void)a;
#endif
}

/** Sleeps while [a] holds [seen]; it may also return early. */
void sleepWhile(std::atomic<uint32_t> &a, uint32_t seen) {
#if defined(_WIN32)
    WaitOnAddress(word(a), &seen, sizeof(seen), INFINITE);
#elif defined(__linux__)
    syscall(SYS_futex, word(a), FUTEX_WAIT_PRIVATE, seen, nullptr, nullptr, 0);
#else
    if (a.load() == seen) std::this_thread::yield();
#endif
}

/**
 * As high as the system lets a worker go, so it isn't put aside for ordinary
 * work while the audio thread waits on its track. Where it isn't allowed it
 * stays as it was.
 */
void raisePriority() {
#if defined(_WIN32)
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
#elif defined(__linux__) && !defined(__ANDROID__)
    sched_param p{};
    p.sched_priority = sched_get_priority_min(SCHED_FIFO);
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &p);
#endif
}

} // namespace

int32_t TrackPool::physicalCores() {
#if defined(_WIN32)
    DWORD length = 0;
    GetLogicalProcessorInformationEx(RelationProcessorCore, nullptr, &length);
    if (length == 0) return 0;
    std::string buffer(length, '\0');
    auto *info = reinterpret_cast<SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *>(&buffer[0]);
    if (!GetLogicalProcessorInformationEx(RelationProcessorCore, info, &length)) return 0;
    int32_t cores = 0;
    for (DWORD at = 0; at < length;) {
        auto *item = reinterpret_cast<SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *>(&buffer[at]);
        if (item->Relationship == RelationProcessorCore) ++cores;
        at += item->Size;
    }
    return cores;
#elif defined(__linux__)
    // One entry per core: the hardware threads that share it.
    std::set<std::string> cores;
    for (int32_t cpu = 0; cpu < 1024; ++cpu) {
        char path[96];
        std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/topology/thread_siblings_list", cpu);
        FILE *f = std::fopen(path, "r");
        if (f == nullptr) break;
        char line[128] = {};
        if (std::fgets(line, sizeof(line), f) != nullptr) cores.insert(line);
        std::fclose(f);
    }
    return static_cast<int32_t>(cores.size());
#else
    return 0;
#endif
}

void TrackPool::start(int32_t workers, void (*onStart)(int32_t)) {
    stop();
#if defined(__EMSCRIPTEN__)
    (void)workers;
    (void)onStart;
#else
    if (workers > kMaxWorkers) workers = kMaxWorkers;
    quit.store(false);
    for (int32_t i = 0; i < workers; ++i) threads[i] = std::thread(&TrackPool::workerLoop, this, i, onStart);
    workerCount = workers > 0 ? workers : 0;
    active.store(static_cast<uint32_t>(workerCount));
#endif
}

void TrackPool::stop() {
    if (workerCount == 0) return;
    active.store(0);
    quit.store(true);
    // A new round number gets a worker about to sleep past its check.
    round.fetch_add(1);
    wake(round);
    wake(active);
    for (int32_t i = 0; i < workerCount; ++i) threads[i].join();
    workerCount = 0;
}

void TrackPool::setActive(int32_t n) {
    active.store(static_cast<uint32_t>(n < 0 ? 0 : n > workerCount ? workerCount : n));
    wake(active);
}

int32_t TrackPool::claim(uint32_t r) {
    uint64_t t = taken.load(std::memory_order_acquire);
    for (;;) {
        if (static_cast<uint32_t>(t >> 32) != r) return -1;
        const uint64_t d = done.load(std::memory_order_acquire);
        if (static_cast<uint32_t>(d >> 32) != r) return -1;
        const uint32_t takenBits = static_cast<uint32_t>(t);
        const uint32_t doneBits = static_cast<uint32_t>(d);
        const int32_t count = jobCount.load(std::memory_order_relaxed);
        int32_t chosen = -1;
        for (int32_t i = 0; i < count && i < kMaxJobs; ++i) {
            const int32_t j = jobPick[i].load(std::memory_order_relaxed);
            if (j < 0 || j >= kMaxJobs) continue;
            if ((takenBits & (1u << j)) == 0 && (jobNeeds[j].load(std::memory_order_relaxed) & ~doneBits) == 0) {
                chosen = j;
                break;
            }
        }
        if (chosen < 0) return -1;
        if (taken.compare_exchange_weak(t, t | (1u << chosen), std::memory_order_acq_rel)) return chosen;
    }
}

void TrackPool::work(uint32_t r) {
    for (;;) {
        const int32_t j = claim(r);
        if (j < 0) return;
        // Holding a job of round r means the round can't end, so these are
        // still that round's.
        jobFn.load(std::memory_order_relaxed)(jobContext.load(std::memory_order_relaxed), j);
        done.fetch_or(1u << j, std::memory_order_acq_rel);
        finished.fetch_add(1, std::memory_order_seq_cst);
        if (waiter.load(std::memory_order_seq_cst) != 0) wake(finished);
    }
}

void TrackPool::wakeEarly() {
    // A woken worker finds the round unchanged and spins for kSpin.
    if (active.load(std::memory_order_relaxed) > 0 && sleepers.load(std::memory_order_seq_cst) > 0) wake(round);
}

void TrackPool::run(int32_t count, const uint32_t *needs, const int32_t *pick, JobFn fn, void *context, bool useWorkers) {
    if (count <= 0) return;
    if (count > kMaxJobs) count = kMaxJobs;
    const uint32_t all = count == 32 ? 0xffffffffu : (1u << count) - 1;
    waitedUs = 0;
    if (!useWorkers || active.load(std::memory_order_relaxed) == 0) {
        // In pick order, as far as each job's needs allow.
        uint32_t doneBits = 0;
        while (doneBits != all) {
            for (int32_t i = 0; i < count; ++i) {
                const int32_t j = pick[i];
                if ((doneBits & (1u << j)) == 0 && (needs[j] & all & ~doneBits) == 0) {
                    fn(context, j);
                    doneBits |= 1u << j;
                    break;
                }
            }
        }
        return;
    }
    jobCount.store(count, std::memory_order_relaxed);
    allJobs.store(all, std::memory_order_relaxed);
    for (int32_t j = 0; j < count; ++j) {
        jobNeeds[j].store(needs[j] & all, std::memory_order_relaxed);
        jobPick[j].store(pick[j], std::memory_order_relaxed);
    }
    jobFn.store(fn, std::memory_order_relaxed);
    jobContext.store(context, std::memory_order_relaxed);
    const uint32_t r = round.load(std::memory_order_relaxed) + 1;
    taken.store(static_cast<uint64_t>(r) << 32, std::memory_order_relaxed);
    done.store(static_cast<uint64_t>(r) << 32, std::memory_order_relaxed);
    // seq_cst with the workers' sleepers count: either a worker sees the new
    // round before it sleeps or this sees it asleep and wakes it.
    round.store(r, std::memory_order_seq_cst);
    if (sleepers.load(std::memory_order_seq_cst) > 0) wake(round);
    // Work too, and wait only for jobs someone else is part way through.
    bool waiting = false;
    std::chrono::steady_clock::time_point waitFrom;
    while (static_cast<uint32_t>(done.load(std::memory_order_acquire)) != all) {
        const int32_t j = claim(r);
        if (j < 0) {
            const auto now = std::chrono::steady_clock::now();
            if (!waiting) {
                waiting = true;
                waitFrom = now;
            }
            if (now - waitFrom < kAudioSpin) {
                pause();
                continue;
            }
            // Asleep until a job finishes. Looked at again after saying so, so
            // a job finishing in between isn't missed.
            const uint32_t seen = finished.load(std::memory_order_seq_cst);
            waiter.store(1, std::memory_order_seq_cst);
            const int32_t ready = claim(r);
            if (ready < 0 && static_cast<uint32_t>(done.load(std::memory_order_seq_cst)) != all) {
                sleepWhile(finished, seen);
            }
            waiter.store(0, std::memory_order_seq_cst);
            if (ready < 0) continue;
            waitedUs += static_cast<int32_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - waitFrom).count());
            waiting = false;
            fn(context, ready);
            done.fetch_or(1u << ready, std::memory_order_acq_rel);
            continue;
        }
        if (waiting) {
            waiting = false;
            waitedUs += static_cast<int32_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - waitFrom).count());
        }
        fn(context, j);
        done.fetch_or(1u << j, std::memory_order_acq_rel);
    }
    if (waiting) {
        waitedUs += static_cast<int32_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - waitFrom).count());
    }
}

void TrackPool::workerLoop(int32_t index, void (*onStart)(int32_t)) {
    dsp::flushDenormalsOnce();
    raisePriority();
    if (onStart != nullptr) onStart(index);
    uint32_t seen = round.load(std::memory_order_acquire);
    while (!quit.load(std::memory_order_relaxed)) {
        // Set aside: asleep until that changes, and not counted as a sleeper,
        // so the audio thread doesn't wake it every block.
        const uint32_t inUse = active.load(std::memory_order_acquire);
        if (static_cast<uint32_t>(index) >= inUse) {
            if (!quit.load()) sleepWhile(active, inUse);
            seen = round.load(std::memory_order_acquire);
            continue;
        }
        // Wait for the next round, spinning briefly, then asleep.
        const auto until = std::chrono::steady_clock::now() + kSpin;
        uint32_t now = round.load(std::memory_order_acquire);
        while (now == seen && std::chrono::steady_clock::now() < until) {
            pause();
            now = round.load(std::memory_order_acquire);
        }
        if (now == seen) {
            sleepers.fetch_add(1, std::memory_order_seq_cst);
            if (round.load(std::memory_order_seq_cst) == seen && !quit.load()) sleepWhile(round, seen);
            sleepers.fetch_sub(1, std::memory_order_seq_cst);
            continue;
        }
        seen = now;
        if (static_cast<uint32_t>(index) >= active.load(std::memory_order_relaxed)) continue; // set aside since
        // Take jobs until the round is done or moves on. A job waiting on one
        // another thread holds is worth waiting a moment for.
        const uint32_t all = allJobs.load(std::memory_order_relaxed);
        for (;;) {
            work(seen);
            const uint64_t d = done.load(std::memory_order_acquire);
            if (static_cast<uint32_t>(d >> 32) != seen || static_cast<uint32_t>(d) == all) break;
            const uint64_t t = taken.load(std::memory_order_acquire);
            if (static_cast<uint32_t>(t) == all) break; // the rest are someone else's
            pause();
        }
    }
}

} // namespace acidulous
