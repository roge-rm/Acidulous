// An AudioWorklet has no performance.now(), which Emscripten's monotonic
// clock needs, so std::chrono::steady_clock on the audio thread would abort.
// This replaces it with acid_clock_ms, which a driver thread updates every
// quarter millisecond (see drivers/AudioDriver.cpp), and Date.now() until
// that has started. Both count from 1970, like Emscripten's clock.
if (typeof globalThis.performance === 'undefined') {
  globalThis.performance = {
    timeOrigin: 0,
    now: () => {
      const t = typeof _acid_clock_ms === 'function' ? _acid_clock_ms() : 0;
      return t > 0 ? t : Date.now();
    },
  };
}
