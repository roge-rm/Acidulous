// An AudioWorklet's global scope has no performance.now(), which Emscripten's
// monotonic clock is built on - so the engine's first std::chrono::steady_clock
// on the audio thread threw ("clock_gettime(CLOCK_MONOTONIC) failed") and the
// worklet aborted. Date.now() is there: a millisecond's resolution, and the
// engine only times its own callbacks with it.
if (typeof globalThis.performance === 'undefined') {
  globalThis.performance = { timeOrigin: 0, now: () => Date.now() };
}
