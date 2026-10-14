// Browser boundary for Neovim's upstream SAB ring and MessagePack RPC protocol.
// UI/state ownership stays with Reagent. See doc/neovim.md for pinned provenance.
const { Packr, Unpackr, addExtension, unpack } = require('./msgpackr.js');
const CAP = 1 << 16;

exports.start = function start(workerPath, onRedraw, onStatus, onExit, onFailure) {
  if (typeof SharedArrayBuffer !== 'function')
    throw new Error('SharedArrayBuffer is unavailable; Neovim requires an isolated browser context with shared memory enabled');
  const packer = new Packr({ useRecords: false });
  const unpacker = new Unpackr({ mapsAsObjects: true });
  const sab = new SharedArrayBuffer(12 + CAP);
  const state = new Int32Array(sab, 0, 3);
  const ring = new Uint8Array(sab, 12, CAP);
  const worker = new Worker(workerPath);
  const pending = new Map();
  const listeners = new Map();
  let sharedState, nextId = 1, buffer = new Uint8Array(0), stopped = false;
  let resolveReady, rejectReady;
  const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
  const bootTimeout = setTimeout(() => rejectReady(new Error('Neovim startup timed out')), 30000);

  function send(bytes) {
    if (stopped) throw new Error('Neovim session is closed');
    const head = Atomics.load(state, 0), tail = Atomics.load(state, 1);
    const free = (tail - head - 1 + CAP) % CAP;
    // Never publish a partial RPC message if the ring cannot hold it.
    if (bytes.length > free) throw new Error('Neovim input queue is full; try a smaller paste');
    for (let i = 0; i < bytes.length; i++) ring[(head + i) % CAP] = bytes[i];
    const next = (head + bytes.length) % CAP;
    Atomics.store(state, 0, next);
    if (sharedState) {
      // Upstream's duplicated stdin descriptor is 9, as in src/wasm/rpc.js.
      Atomics.store(sharedState, 10, (next - Atomics.load(state, 1) + CAP) % CAP);
      Atomics.add(sharedState, 0, 1);
      Atomics.notify(sharedState, 0, 1);
    }
    // The hint covers the startup race before nvim enters its futex wait.
    worker.postMessage({ type: 'readable-hint' });
  }

  function request(method, params) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      const timeout = setTimeout(() => {
        pending.delete(id);
        const error = new Error('Neovim request timed out: ' + method);
        error.pendingInputBytes = (Atomics.load(state, 0) - Atomics.load(state, 1) + CAP) % CAP;
        reject(error);
      }, 30000);
      pending.set(id, { resolve, reject, timeout });
      try { send(packer.pack([0, id, method, params])); }
      catch (error) { clearTimeout(timeout); pending.delete(id); reject(error); }
    });
  }

  function stop() {
    if (stopped) return;
    stopped = true;
    clearTimeout(bootTimeout);
    rejectReady(new Error('Neovim session is closed'));
    Atomics.store(state, 2, 1);
    Atomics.notify(state, 0);
    worker.terminate(); // Also interrupts nvim when its synchronous poll loop blocks.
    for (const entry of pending.values()) {
      clearTimeout(entry.timeout);
      entry.reject(new Error('Neovim session is closed'));
    }
    pending.clear();
    listeners.clear();
    worker.onmessage = worker.onerror = null;
  }

  worker.onmessage = ({ data }) => {
    if (stopped) return;
    if (data.type === 'shared-info') {
      sharedState = new Int32Array(data.memory, data.statePtr, 513);
    } else if (data.type === 'ready') {
      clearTimeout(bootTimeout);
      resolveReady();
    } else if (data.type === 'exited') {
      onExit(data.code);
      stop();
    } else if (data.type === 'profile-error') {
      onFailure(data.text);
      stop();
    } else if (data.type === 'status') {
      if (!/^(FS\.write|WRITE HOOK|\[stderr\])/.test(data.text)) onStatus(data.text);
    } else if (data.type === 'stdout') {
      const bytes = new Uint8Array(buffer.length + data.bytes.length);
      bytes.set(buffer); bytes.set(data.bytes, buffer.length);
      let consumed = 0;
      try {
        unpacker.unpackMultiple(bytes, (message, _start, end) => {
          consumed = end;
          const [type, id, error, result] = message;
          if (type === 1) {
            const entry = pending.get(id);
            if (entry) {
              clearTimeout(entry.timeout); pending.delete(id);
              if (error) entry.reject(new Error(JSON.stringify(error)));
              else entry.resolve(result);
            }
          } else if (type === 2) {
            if (id === 'redraw') onRedraw(error);
            for (const listener of listeners.get(id) || []) listener(error);
          }
        });
      } catch (error) {
        if (!error.incomplete) { onFailure('RPC decode failed: ' + error.message); stop(); }
      }
      buffer = bytes.slice(consumed);
    }
  };
  worker.onerror = (error) => {
    rejectReady(new Error(error.message || 'Neovim worker failed'));
    onFailure('Neovim worker failed');
    stop();
  };
  worker.postMessage({ type: 'init', sab, cols: 80, rows: 24 });

  return {
    ready, request, stop,
    diagnostics: () => ({ pendingInputBytes: (Atomics.load(state, 0) - Atomics.load(state, 1) + CAP) % CAP }),
    listen(method, callback) {
      if (stopped) throw new Error('Neovim session is closed');
      let group = listeners.get(method);
      if (!group) listeners.set(method, group = new Set());
      group.add(callback);
      return () => {
        group.delete(callback);
        if (!group.size && listeners.get(method) === group) listeners.delete(method);
      };
    },
    registerTypes(types) {
      for (const info of Object.values(types)) {
        addExtension({ type: info.id, unpack: payload => unpack(payload) });
      }
    }
  };
};
