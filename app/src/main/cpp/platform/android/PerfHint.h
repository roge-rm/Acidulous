#pragma once
#include <atomic>
#include <cstdint>

// Telling the scheduler this work has a deadline.
//
// **There is no "give me the phone" call on Android, and this is not one.**
// What it does is stop *us* being the thread the governor deprioritises: a
// session says "this thread does N nanoseconds of work every period", and
// reporting how long each one actually took lets the governor hold the clocks
// up and place the thread somewhere it can finish. It is the sanctioned answer
// to the thing Dan saw when closing other apps helped but did not cure it.
//
// It is also the one API in this app whose input we already had. The driver
// times every callback for the meter, in wall clock and in CPU time; the wall
// figure is exactly what a hint session wants reported. The measurement and
// the hint are one measurement.
//
// **Resolved at runtime, not linked.** `APerformanceHint_*` is NDK API 33 and
// `minSdk` here is 27, so the symbols are looked up in `libandroid.so` with
// `dlsym` and the whole thing is simply absent on a device that predates them.
// Linking against them directly would raise the floor of the app by six years
// of phones to help the ones that need it least.
namespace acidulous::platform {

class PerfHint {
  public:
    /**
     * Find the symbols. Returns false when the platform does not have them.
     *
     * Not the audio thread: `dlopen` and `dlsym` take locks and touch the
     * filesystem, and a driver starting is a fine place for that.
     */
    bool load();

    /**
     * Open a session for [tid], asking for [targetNanos] of work a period.
     *
     * **Not the audio thread either**, for the same reason: creating a session
     * allocates and talks to a system service. The audio thread publishes its
     * own id and somebody else calls this.
     */
    bool begin(int32_t tid, int64_t targetNanos, bool lastTry = true);

    /** The budget changed - a different buffer size, a different rate. */
    void retarget(int64_t targetNanos);

    /**
     * How long the last callback actually took, in nanoseconds. **Audio
     * thread**, every callback: that is what the API is for, and reporting
     * only the late ones would describe a device that is always struggling.
     */
    void report(int64_t actualNanos);

    void end();

    bool running() const { return session.load(std::memory_order_relaxed) != nullptr; }
    /** Whether the platform has the API at all, for the readout to say so. */
    bool available() const { return manager != nullptr; }

    /**
     * Why it is not running, when it is not.
     *
     * A readout that can only say "not open" sends somebody to a logcat they
     * cannot easily read - and this went out to Dan's phone saying exactly
     * that while working on the emulator, which is the case a one-word answer
     * is least use for. Each of these is a different thing to do next.
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
