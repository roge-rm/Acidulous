// Renders the same song as fast as it can on a worker, so the figure is the
// engine's cost and not the audio system's.
//
// Timed in batches of eight callbacks because browsers round performance.now()
// to 0.1 ms or 1 ms, too coarse for one 2.67 ms callback.
importScripts('wasm-host.js');
onmessage = (event) => {
  const { module, counts, callbacks } = event.data;
  const engine = loadTestInstance(module);
  const budgetMs = 128 / 48000 * 1000;
  for (const n of counts) {
    const t0 = engine.now();
    engine.build(n);
    const buildMs = engine.now() - t0;
    engine.play(true);
    for (let i = 0; i < 200; i++) engine.render();
    const batches = [];
    const start = engine.now();
    for (let i = 0; i < callbacks; i += 8) {
      const b0 = engine.now();
      for (let j = 0; j < 8; j++) engine.render();
      batches.push((engine.now() - b0) / 8);
    }
    const mean = (engine.now() - start) / callbacks;
    batches.sort((a, b) => a - b);
    const p99 = batches[Math.floor(batches.length * 0.99)];
    postMessage({ tracks: n, mean, p99, meanPct: 100 * mean / budgetMs, p99Pct: 100 * p99 / budgetMs, buildMs });
  }
  postMessage({ done: true });
};
