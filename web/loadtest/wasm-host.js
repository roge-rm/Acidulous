// Runs the load test's WebAssembly module without Emscripten's JavaScript.
//
// The module is built standalone, so what it asks of its host is a handful of
// WASI calls - a clock, somewhere to print, random bytes - and nothing else it
// will actually use. Anything not provided here is a stub that returns 0, so a
// call that was never going to be made (a file open) costs nothing to satisfy.
// Shared by the benchmark worker and the AudioWorklet, which is why it is a
// classic script with one global rather than a module.
globalThis.loadTestInstance = function (module) {
  let memory = null;
  const now = (globalThis.performance && performance.now) ? () => performance.now() : () => Date.now();
  const text = [];
  const wasi = {
    clock_time_get(id, precision, out) {
      const ns = BigInt(Math.round(now() * 1e6));
      new DataView(memory.buffer).setBigUint64(out, ns, true);
      return 0;
    },
    fd_write(fd, iovs, count, written) {
      const view = new DataView(memory.buffer);
      let total = 0;
      for (let i = 0; i < count; i++) {
        const ptr = view.getUint32(iovs + i * 8, true), len = view.getUint32(iovs + i * 8 + 4, true);
        for (let j = 0; j < len; j++) {
          const c = view.getUint8(ptr + j);
          if (c === 10) { console.log('[engine] ' + text.join('')); text.length = 0; }
          else text.push(String.fromCharCode(c));
        }
        total += len;
      }
      view.setUint32(written, total, true);
      return 0;
    },
    random_get(buf, len) {
      const bytes = new Uint8Array(memory.buffer, buf, len);
      for (let i = 0; i < len; i++) bytes[i] = (Math.random() * 256) | 0;
      return 0;
    },
    proc_exit(code) { throw new Error('engine exited with ' + code); },
  };
  const stub = () => 0;
  const moduleProxy = (known) => new Proxy(known, { get: (t, name) => (name in t ? t[name] : stub) });
  const imports = new Proxy({}, {
    get: (_, mod) => moduleProxy(mod === 'wasi_snapshot_preview1' ? wasi : {}),
  });
  const instance = new WebAssembly.Instance(module, imports);
  memory = instance.exports.memory;
  if (instance.exports._initialize) instance.exports._initialize();
  const e = instance.exports;
  return {
    build: (tracks) => e.lt_build(tracks),
    play: (on) => e.lt_play(on ? 1 : 0),
    tracks: () => e.lt_tracks(),
    /** 128 frames, interleaved stereo, as a view that is only good until the next call. */
    render: () => new Float32Array(memory.buffer, e.lt_render128(), 256),
    now,
  };
};
