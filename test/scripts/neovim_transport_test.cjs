const assert = require('node:assert/strict');
const { start } = require('../../src/frontend/website/modules/neovim/transport.js');
const { Packr } = require('../../src/frontend/website/modules/neovim/msgpackr.js');
const packer = new Packr({ useRecords: false });
let worker;
global.Worker = class {
  constructor() { worker = this; this.terminated = 0; }
  postMessage(message) { if (message.type === 'init') this.init = message; }
  terminate() { this.terminated++; }
  emit(data) { this.onmessage({ data }); }
};

(async () => {
  const redraws = [], errors = [];
  const session = start('fixture-worker', value => redraws.push(value), () => {}, () => {}, value => errors.push(value));
  worker.emit({ type: 'ready' });
  await session.ready;
  const reply = session.request('nvim_eval', ['42']);
  const encoded = packer.pack([1, 1, null, 42]);
  worker.emit({ type: 'stdout', bytes: encoded.slice(0, 2) });
  worker.emit({ type: 'stdout', bytes: encoded.slice(2) });
  assert.equal(await reply, 42, 'fragmented MessagePack responses reassemble');
  worker.emit({ type: 'stdout', bytes: packer.pack([2, 'redraw', [['flush', []]]]) });
  assert.deepEqual(redraws, [[['flush', []]]]);
  const state = new Int32Array(worker.init.sab, 0, 3);
  const previous = Atomics.load(state, 0);
  await assert.rejects(session.request('nvim_paste', ['x'.repeat(65536), true, -1]), /queue is full/);
  assert.equal(Atomics.load(state, 0), previous, 'oversized requests do not publish partial bytes');
  const pending = session.request('pending', []);
  session.stop(); session.stop();
  await assert.rejects(pending, /closed/);
  assert.equal(worker.terminated, 1, 'cleanup is idempotent');
  assert.equal(Atomics.load(state, 2), 1);
  assert.deepEqual(errors, []);
  const cancelled = start('fixture-worker', () => {}, () => {}, () => {}, () => {});
  const stopped = assert.rejects(cancelled.ready, /closed/);
  cancelled.stop(); await stopped;
  const failed = start('fixture-worker', () => {}, () => {}, () => {}, value => errors.push(value));
  const rejected = assert.rejects(failed.ready, /closed/);
  worker.emit({ type: 'profile-error', text: 'Profile unavailable' });
  await rejected;
  assert.equal(worker.terminated, 1);
  assert.equal(errors.at(-1), 'Profile unavailable');
  const shared = global.SharedArrayBuffer;
  try {
    delete global.SharedArrayBuffer;
    assert.throws(() => start('fixture-worker', () => {}, () => {}, () => {}, () => {}),
      error => error.name === 'Error' && /SharedArrayBuffer is unavailable/.test(error.message));
  } finally { global.SharedArrayBuffer = shared; }
  console.log('PASS: real codec, fragmented RPC, redraw, queue bounds and cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });
