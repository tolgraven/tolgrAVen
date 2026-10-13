// Run the actual release worker against bounded in-memory Cache/Fetch adapters.
// This checks response bytes/headers; browser isolation is a separate live check.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const listeners = {}, entries = new Map();
const key = value => typeof value === 'string' ? value : value.url;
const cache = {
  put: async (url, response) => entries.set(key(url), response),
  match: async url => entries.get(key(url))?.clone(),
  delete: async url => entries.delete(key(url)),
  keys: async () => [...entries.keys()].map(url => ({url}))
};
const origin = 'http://127.0.0.1:4127';
const sandbox = {
  console, URL, Response, Request, Headers, setTimeout, clearTimeout,
  importScripts: (...urls) => assert.equal(urls.length, 0),
  location: {href: origin + '/js/return/return-worker.js?build=neovim-fixture', origin},
  addEventListener: (event, callback) => listeners[event] = callback,
  caches: {open: async () => cache, keys: async () => [], delete: async () => true},
  fetch: async () => new Response('network'),
  clients: {claim: async () => {}}, skipWaiting: async () => {}
};
sandbox.self = sandbox;
const context = vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(process.argv[2] || 'resources/public/js/return/return-worker.js', 'utf8'), context);

async function save(path, html) {
  let work, reply;
  // js->clj recognizes browser-native plain objects in the worker's own realm.
  const json = JSON.stringify({op: 'save', build: 'neovim-fixture', url: origin + path, html});
  const data = vm.runInContext('JSON.parse(' + JSON.stringify(json) + ')', context);
  listeners.message({data,
    ports: [{postMessage: value => reply = value}], waitUntil: promise => work = promise});
  await work;
  assert.equal(reply.ok, true);
}
async function navigate(path) {
  let response;
  listeners.fetch({request: {url: origin + path, method: 'GET', mode: 'navigate'},
    respondWith: promise => response = promise});
  return await response;
}
(async () => {
  await save('/neovim', '<h1>Neovim</h1>');
  const editor = await navigate('/neovim');
  assert.equal(editor.headers.get('Cross-Origin-Opener-Policy'), 'same-origin');
  assert.equal(editor.headers.get('Cross-Origin-Embedder-Policy'), 'require-corp');
  assert.equal(await editor.text(), '<h1>Neovim</h1>');
  assert.equal(entries.has(origin + '/neovim'), false, 'cached document is consumed once');
  await save('/neovim/github/u/r/abcdef', '<h1>Diff</h1>');
  const diff = await navigate('/neovim/github/u/r/abcdef');
  assert.equal(diff.headers.get('Cross-Origin-Embedder-Policy'), 'require-corp');
  await save('/ordinary', '<h1>Ordinary</h1>');
  const ordinary = await navigate('/ordinary');
  assert.equal(ordinary.headers.get('Cross-Origin-Opener-Policy'), null);
  assert.equal(ordinary.headers.get('Cross-Origin-Embedder-Policy'), null);
  assert.equal(await ordinary.text(), '<h1>Ordinary</h1>');
  console.log('PASS: actual release worker preserves scoped isolation, bytes and one-time consumption');
})().catch(error => { console.error(error); process.exitCode = 1; });
