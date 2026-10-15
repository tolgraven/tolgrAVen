const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {test} = require('node:test');
const vm = require('node:vm');
const source = readFileSync('test/browser/visual-clock.js', 'utf8');

test('visual calendar stays fixed in a future host year while elapsed time advances', () => {
  let elapsed = 20;
  class HostDate extends Date {
    constructor(...args) { super(...(args.length ? args : [Date.UTC(2040, 0, 1)])); }
    static now() { return Date.UTC(2040, 0, 1); }
  }
  const context = {Date: HostDate, performance: {now: () => elapsed}};
  context.window = context;
  vm.runInNewContext(source, context);
  assert.equal(new context.Date().getUTCFullYear(), 2026);
  assert.equal(context.Date.now(), Date.UTC(2026, 9, 15, 12));
  elapsed += 1250;
  assert.equal(context.Date.now(), Date.UTC(2026, 9, 15, 12) + 1250);
  assert.equal(new context.Date().getTime(), context.Date.now());
  assert.equal(new context.Date(1609459200000).getUTCFullYear(), 2021);
  assert.equal(context.Date.parse('2021-01-01T00:00:00Z'), 1609459200000);
  assert.ok(new context.Date() instanceof context.Date);
  assert.equal(typeof context.Date(), 'string');
});

test('background readiness extracts actual computed CSS URLs', () => {
  const window = {addEventListener() {}};
  vm.runInNewContext(readFileSync('test/browser/visual.js', 'utf8'), {window});
  const urls = window.visualFixture.backgroundURLs;
  assert.deepEqual(Array.from(urls('url("http://localhost/image.png")')), ['http://localhost/image.png']);
  assert.deepEqual(Array.from(urls("url('/img/one.webp'), url(\"/img/two.avif\")")), ['/img/one.webp', '/img/two.avif']);
  assert.deepEqual(Array.from(urls('linear-gradient(35deg, red, blue)')), []);
});
