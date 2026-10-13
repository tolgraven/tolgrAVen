const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = 'resources/public/vendor/neovim/';
const profile = JSON.parse(fs.readFileSync(root + 'profile.json', 'utf8'));
const workspace = JSON.parse(fs.readFileSync(root + 'workspace.json', 'utf8'));
const source = fs.readFileSync(root + 'nvim-profile-worker.js', 'utf8');
for (const file of profile.files) {
  assert.equal(crypto.createHash('sha256').update(file.text).digest('hex'), file.sha256, file.path);
}
assert.equal(new Set(profile.files.map(file => file.path)).size, profile.files.length);
assert.equal(profile.files.find(file => file.path === 'init.lua').text, fs.readFileSync(root + 'init.lua', 'utf8'));
assert(profile.files.some(file => file.path === 'metabuffer/lua/metabuffer/nfnl/fennel.lua'));
assert(profile.files.some(file => file.path === 'metabuffer/LICENSE'));
assert(profile.files.some(file => file.path === 'bruvbox/README.md'));

function fixture(bundle, ok = true) {
  const writes = new Map(), messages = [];
  let starts = 0, upstreamSetup = false;
  const sandbox = { fetch: async path => ({ ok, json: async () => path.endsWith('workspace.json') ? workspace : bundle }) };
  sandbox.self = sandbox;
  sandbox.postMessage = message => messages.push(message);
  sandbox.importScripts = path => {
    if (path === './nvim.js') {
      sandbox.createNvim = async options => {
        const module = { FS: { mkdirTree() {}, writeFile: (path, text) => writes.set(path, text) } };
        for (const hook of options.preRun) hook(module);
        assert(upstreamSetup, 'retain upstream preRun before plugin installation');
        assert.equal(writes.get('/home/user/.config/nvim/wasm/init.lua'), profile.files[0].text);
        return module;
      };
    } else {
      assert.equal(path, './nvim-worker.js');
      sandbox.onmessage = async event => {
        if (event.data.type !== 'init') return;
        sandbox.importScripts('./nvim.js');
        await sandbox.createNvim({ preRun: [() => { upstreamSetup = true; }] });
        starts++;
      };
    }
  };
  vm.runInNewContext(source, sandbox);
  return { sandbox, writes, messages, starts: () => starts };
}
(async () => {
  const valid = fixture(profile);
  await valid.sandbox.onmessage({ data: { type: 'init' } });
  assert.equal(valid.starts(), 1);
  assert.equal(valid.writes.size, profile.files.length + workspace.files.length);
  for (const file of profile.files) assert.equal(valid.writes.get('/home/user/.config/nvim/wasm/' + file.path), file.text);
  for (const file of workspace.files) assert.equal(valid.writes.get('/workspace/tolgrAVen/' + file.path), file.text);
  for (const path of ['../outside.lua', '/outside.lua', 'nested/../outside.lua', 'nested\\outside.lua', 'nested//outside.lua']) {
    const invalid = fixture({ version: 1, files: [{ path, text: '' }] });
    await invalid.sandbox.onmessage({ data: { type: 'init' } });
    assert.equal(invalid.starts(), 0);
    assert.equal(invalid.writes.size, 0);
    assert.equal(invalid.messages.at(-1).type, 'profile-error');
  }
  const missing = fixture(profile, false);
  await missing.sandbox.onmessage({ data: { type: 'init' } });
  assert.equal(missing.starts(), 0);
  assert.equal(missing.messages.at(-1).type, 'profile-error');
  console.log('PASS: plugin hashes, licenses, exact filesystem bytes, preRun ordering and rejected paths/downloads');
})().catch(error => { console.error(error); process.exitCode = 1; });
