#include "PerfHint.h"

#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "Acidulous.PerfHint"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace acidulous::platform {

bool PerfHint::load() {
    if (manager != nullptr) return true;
    // Already in the process - every Android app links it - so this is a
    // reference count rather than a read from disk.
    lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) return false;

    getManager = reinterpret_cast<decltype(getManager)>(dlsym(lib, "APerformanceHint_getManager"));
    createSession = reinterpret_cast<decltype(createSession)>(dlsym(lib, "APerformanceHint_createSession"));
    updateTarget =
        reinterpret_cast<decltype(updateTarget)>(dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
    reportActual =
        reinterpret_cast<decltype(reportActual)>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    closeSession = reinterpret_cast<decltype(closeSession)>(dlsym(lib, "APerformanceHint_closeSession"));

    // All five or none. A platform with half of them is not one this has been
    // thought about on, and a null call here would be a crash on the audio
    // thread of a device nobody testing this owns.
    if (getManager == nullptr || createSession == nullptr || updateTarget == nullptr ||
        reportActual == nullptr || closeSession == nullptr) {
        dlclose(lib);
        lib = nullptr;
        LOGI("no performance hints on this platform");
        return false;
    }
    manager = getManager();
    if (manager == nullptr) {
        LOGI("the platform has the hint API and no manager for it");
        return false;
    }
    status.store(State::Waiting, std::memory_order_relaxed);
    return true;
}

bool PerfHint::begin(int32_t tid, int64_t targetNanos, bool lastTry) {
    if (manager == nullptr || tid <= 0 || targetNanos <= 0) return false;
    if (session.load(std::memory_order_relaxed) != nullptr) return true;
    const int32_t ids[1] = {tid};
    void *made = createSession(manager, ids, 1, targetNanos);
    if (made == nullptr) {
        // Every requirement we can check is met - the manager exists, the
        // thread is ours and the target is positive - so this is the platform
        // declining. Only the last attempt says so, because a readout that
        // reads "refused" while a retry is still pending is wrong for as long
        // as the wait lasts.
        if (lastTry) status.store(State::Refused, std::memory_order_relaxed);
        LOGI("the platform refused a hint session for thread %d at %lld ns", tid,
             static_cast<long long>(targetNanos));
        return false;
    }
    target = targetNanos;
    session.store(made, std::memory_order_release);
    status.store(State::On, std::memory_order_relaxed);
    LOGI("hinting thread %d at %.2f ms a callback", tid, static_cast<double>(targetNanos) / 1e6);
    return true;
}

void PerfHint::retarget(int64_t targetNanos) {
    void *s = session.load(std::memory_order_acquire);
    if (s == nullptr || targetNanos <= 0 || targetNanos == target) return;
    target = targetNanos;
    updateTarget(s, targetNanos);
}

void PerfHint::report(int64_t actualNanos) {
    void *s = session.load(std::memory_order_acquire);
    // A duration of nought is not a measurement, and the platform treats a
    // non-positive one as an error rather than as "we were quick".
    if (s == nullptr || actualNanos <= 0) return;
    reportActual(s, actualNanos);
}

void PerfHint::end() {
    void *s = session.exchange(nullptr, std::memory_order_acq_rel);
    if (s != nullptr) closeSession(s);
    target = 0;
    if (manager != nullptr) status.store(State::Waiting, std::memory_order_relaxed);
}

} // namespace acidulous::platform
