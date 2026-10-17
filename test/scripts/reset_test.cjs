const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {test} = require('node:test');
const vm = require('node:vm');

const advance = async () => { await Promise.resolve(); await Promise.resolve(); };

function fixture({worker = false, heldFrame = false, database = true, location = {}} = {}) {
  const calls = [], timers = new Map(), channels = [], requests = [];
  const databases = new Map([['tolgraven-component-cache', 'heavy data'], ['unrelated-cache', 'preserved']]);
  let serial = 0, closeFrame;
  const closed = new Promise(resolve => { closeFrame = resolve; });
  const frame = {contentDocument: {URL: 'previous'}, set src(value) {
    calls.push('frame:' + value); this.contentDocument.URL = value;
  }};
  const context = {
    location: {protocol: 'http:', hostname: '127.0.0.1', pathname: '/__tests/motion.html', ...location},
    navigator: {serviceWorker: worker ? {controller: {postMessage(message, ports) {
      assert.equal(message.op, 'clear'); assert.equal(ports[0], channels.at(-1).port2);
      calls.push('worker:clear');
    }}} : {}},
    MessageChannel: class {
      constructor() {
        this.port1 = {close() { calls.push('worker:port-closed'); }};
        this.port2 = {}; channels.push(this);
      }
    },
    setTimeout(fn, ms) {
      assert.equal(ms, 5000);
      const id = ++serial;
      timers.set(id, () => { timers.delete(id); fn(); }); return id;
    },
    clearTimeout(id) { timers.delete(id); },
    localStorage: {clear() { calls.push('local:clear'); }},
    sessionStorage: {clear() { calls.push('session:clear'); }},
    document: {set cookie(value) { calls.push('cookie:' + value); }}
  };
  if (database) context.indexedDB = {
    deleteDatabase(name) {
      calls.push('database:' + name);
      assert.equal(name, 'tolgraven-component-cache');
      const request = {complete() { databases.delete(name); this.onsuccess(); }};
      requests.push(request); return request;
    },
    databases() { throw new Error('Reset must not enumerate databases'); }
  };
  vm.runInNewContext(readFileSync('test/browser/reset.js', 'utf8'), context);
  const reset = () => context.resetTestOrigin(frame, async (predicate, description) => {
    assert.equal(description, 'previous document closes');
    if (heldFrame) await closed;
    assert.equal(predicate(), true); calls.push('frame:closed');
  });
  return {context, reset, calls, timers, channels, requests, databases, closeFrame};
}

test('cold reset closes the frame and drains worker writes before deleting heavy data and small stores', async () => {
  const f = fixture({worker: true, heldFrame: true});
  const reset = f.reset();
  assert.deepEqual(f.calls, ['frame:about:blank']);
  f.closeFrame(); await advance();
  assert.deepEqual(f.calls, ['frame:about:blank', 'frame:closed', 'worker:clear']);
  assert.equal(f.requests.length, 0, 'An outgoing worker write must finish first');
  f.channels[0].port1.onmessage({data: {ok: true}}); await advance();
  assert.deepEqual(f.calls.slice(-2), ['worker:port-closed', 'database:tolgraven-component-cache']);
  assert.equal(f.databases.has('tolgraven-component-cache'), true);
  f.requests[0].complete(); await reset;
  assert.equal(f.databases.has('tolgraven-component-cache'), false);
  assert.equal(f.databases.get('unrelated-cache'), 'preserved');
  assert.deepEqual(f.calls.slice(-3), ['local:clear', 'session:clear', 'cookie:tolgraven-return=; Max-Age=0; Path=/']);
  assert.equal(f.requests[0].onsuccess, null); assert.equal(f.requests[0].onerror, null);
  assert.equal(f.requests[0].onblocked, null); assert.equal(f.timers.size, 0);
});

test('deleting a missing heavy-cache database succeeds without a worker or touching another database', async () => {
  const f = fixture(); f.databases.delete('tolgraven-component-cache');
  const reset = f.reset(); await advance();
  assert.deepEqual(f.calls, ['frame:about:blank', 'frame:closed', 'database:tolgraven-component-cache']);
  f.requests[0].complete(); await reset;
  assert.deepEqual([...f.databases], [['unrelated-cache', 'preserved']]);
  assert.equal(f.calls.includes('local:clear'), true); assert.equal(f.timers.size, 0);
});

for (const failure of ['blocked', 'failed', 'timed out']) {
  test('cold reset rejects a heavy-cache deletion that is ' + failure, async () => {
    const f = fixture(), reset = f.reset();
    const rejection = assert.rejects(reset, new RegExp('Component cache clear ' + failure));
    await advance();
    const request = f.requests[0];
    if (failure === 'blocked') request.onblocked();
    else if (failure === 'failed') { request.error = new Error('disk failure'); request.onerror(); }
    else [...f.timers.values()][0]();
    await rejection;
    assert.equal(f.calls.includes('local:clear'), false, 'A partial reset cannot continue a cold check');
    assert.equal(f.databases.get('tolgraven-component-cache'), 'heavy data');
    assert.equal(request.onsuccess, null); assert.equal(request.onerror, null); assert.equal(request.onblocked, null);
    assert.equal(f.timers.size, 0);
  });
}

test('missing IndexedDB and synchronous deletion errors fail the cold prerequisite', async () => {
  const missing = fixture({database: false});
  await assert.rejects(missing.reset(), /requires IndexedDB/);
  assert.equal(missing.calls.includes('local:clear'), false); assert.equal(missing.timers.size, 0);
  const thrown = fixture(); thrown.context.indexedDB.deleteDatabase = () => { throw new Error('storage denied'); };
  await assert.rejects(thrown.reset(), /storage denied/);
  assert.equal(thrown.calls.includes('local:clear'), false); assert.equal(thrown.timers.size, 0);
});

test('failed or timed-out worker clearing never starts heavy database deletion', async () => {
  for (const timeout of [false, true]) {
    const f = fixture({worker: true}), reset = f.reset();
    const rejection = assert.rejects(reset, /Return cache clear (failed|timed out)/);
    await advance();
    if (timeout) [...f.timers.values()][0]();
    else f.channels[0].port1.onmessage({data: {ok: false}});
    await rejection;
    assert.equal(f.requests.length, 0); assert.equal(f.calls.includes('local:clear'), false);
    assert.equal(f.timers.size, 0);
  }
});

test('reset rejects origins outside the dedicated loopback test driver before changing storage or its frame', async () => {
  for (const location of [{hostname: 'tolgraven.se'}, {protocol: 'https:'}, {pathname: '/blog'}]) {
    const f = fixture({location});
    await assert.rejects(f.reset(), /dedicated loopback test origin/);
    assert.deepEqual(f.calls, []); assert.equal(f.requests.length, 0);
  }
});
