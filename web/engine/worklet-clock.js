// An AudioWorklet's global scope has no performance.now(), which Emscripten's
// monotonic clock is built on - so the engine's first std::chrono::steady_clock
// on the audio thread threw ("clock_gettime(CLOCK_MONOTONIC) failed") and the
// worklet aborted. This stands in for it: the time a thread of the audio
// driver's own writes every quarter millisecond (acid_clock_ms, in
// drivers/AudioDriver.cpp), and Date.now()'s whole milliseconds until it has.
// Both count from 1970, as Emscripten's clock does on every thread.
if (typeof globalThis.performance === 'undefined') {
  globalThis.performance = {
    timeOrigin: 0,
    now: () => {
      const t = typeof _acid_clock_ms === 'function' ? _acid_clock_ms() : 0;
      return t > 0 ? t : Date.now();
    },
  };
}
