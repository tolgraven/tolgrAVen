const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const context = vm.createContext({
  SharedArrayBuffer, Int32Array, Uint8Array, Atomics,
  console: {log() {}}, self: {}, postMessage() {},
});
vm.runInContext(fs.readFileSync('resources/public/vendor/neovim/nvim-worker.js', 'utf8'), context);
vm.runInContext(`
  const input = new SharedArrayBuffer(12 + CAP);
  state = new Int32Array(input, 0, 3);
  ringData = new Uint8Array(input, 12, CAP);
  for (let i = 0; i < 200000; i++) {
    const head = Atomics.load(state, 0);
    ringData[head] = i % 256;
    Atomics.store(state, 0, (head + 1) % CAP);
    if (popByte() !== i % 256) throw new Error('Input byte changed');
  }
`, context);
assert.equal(vm.runInContext('totalBytesRead', context), 200000);
assert.equal(vm.runInContext('consumedBytes.length', context), 50);
assert.equal(vm.runInContext('consumedBytes.join(",")', context), Array.from({length: 50}, (_, i) => i).join(','));
console.log('PASS: real worker preserves all input with a bounded diagnostic sample');
