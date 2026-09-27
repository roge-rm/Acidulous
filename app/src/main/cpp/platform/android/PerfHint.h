#pragma once
#include <atomic>
#include <cstdint>

// Tells the scheduler the audio thread has a deadline (performance hints).
//
// A session says "this thread does N nanoseconds of work every period" and we
// report how long each callback actually took, so the governor keeps the
// clocks up and puts the thread on a core where it can finish in time. The
// driver already times every callback for the CPU meter, and that wall-clock
// figure is what gets reported here.
//
// APerformanceHint_* is NDK API 33 and minSdk is 27, so the symbols are looked
// up in libandroid.so with dlsym at runtime. On older devices this does
// nothing.
namespace acidulous::platform {

class PerfHint {
  public:
    /**
     * Find the symbols. Returns false when the platform doesn't have them.
     *
     * Not on the audio thread: dlopen and dlsym take locks and touch the
     * filesystem.
     */
    bool load();

    /**
     * Open a session for [tid], asking for [targetNanos] of work a period.
     *
     * Not on the audio thread either: creating a session allocates and talks
     * to a system service. The audio thread publishes its id and another
     * thread calls this.
     */
    bool begin(int32_t tid, int64_t targetNanos, bool lastTry = true);

    /** The budget changed, e.g. a different buffer size or sample rate. */
    void retarget(int64_t targetNanos);

    /**
     * How long the last callback actually took, in nanoseconds. Called on the
     * audio thread every callback, not just the late ones.
     */
    void report(int64_t actualNanos);

    void end();

    bool running() const { return session.load(std::memory_order_relaxed) != nullptr; }
    /** Whether the platform has the API at all, for the readout. */
    bool available() const { return manager != nullptr; }

    /**
     * Why it isn't running, when it isn't, so the readout can say more than
     * "not open".
     */
    enum class State { NoApi, Waiting, NoThread, Refused, On };
    State state() const { return status.load(std::memory_order_relaxed); }

    /** The waiter gave up before the audio thread ever named itself. */
    void gaveUp() { status.store(State::NoThread, std::memory_order_relaxed); }

  private:
    std::atomic<State> status{State::NoApi};
    void *lib = nullptr;
    void *manager = nullptr;
    std::atomic<void *> session{nullptr};
    int64_t target = 0;

    void *(*getManager)() = nullptr;
    void *(*createSession)(void *, const int32_t *, size_t, int64_t) = nullptr;
    int (*updateTarget)(void *, int64_t) = nullptr;
    int (*reportActual)(void *, int64_t) = nullptr;
    void (*closeSession)(void *) = nullptr;
};

} // namespace acidulous::platform
