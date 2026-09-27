// Plays the load test on the browser's audio thread and times every callback.
//
// The budget is 128 frames, 2.67 ms at 48 kHz. A callback that takes longer
// makes the browser wait, and enough of them cause crackles.
class LoadTestProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.engine = loadTestInstance(options.processorOptions.module);
    this.engine.build(options.processorOptions.tracks);
    this.budgetMs = 128 / sampleRate * 1000;
    this.reset();
    this.port.onmessage = (event) => {
      const m = event.data;
      if (m.tracks) {
        const t0 = this.engine.now();
        this.engine.build(m.tracks);
        if (this.playing) this.engine.play(true);
        this.port.postMessage({ built: this.engine.tracks(), ms: this.engine.now() - t0 });
        this.reset();
      }
      if ('play' in m) { this.playing = m.play; this.engine.play(m.play); }
    };
  }
  reset() { this.count = 0; this.spent = 0; this.worst = 0; this.late = 0; this.since = currentTime; }
  process(inputs, outputs) {
    const t0 = this.engine.now();
    const block = this.engine.render();
    const t = this.engine.now() - t0;
    const [left, right] = outputs[0];
    for (let i = 0; i < 128; i++) { left[i] = block[i * 2]; right[i] = block[i * 2 + 1]; }
    this.count++;
    this.spent += t;
    if (t > this.worst) this.worst = t;
    if (t > this.budgetMs) this.late++;
    if (currentTime - this.since >= 0.5) {
      this.port.postMessage({
        load: this.spent / (this.count * this.budgetMs), worst: this.worst / this.budgetMs,
        late: this.late, count: this.count,
      });
      this.reset();
    }
    return true;
  }
}
registerProcessor('acidulous-loadtest', LoadTestProcessor);
