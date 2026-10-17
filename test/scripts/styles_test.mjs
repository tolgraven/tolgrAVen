import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawn, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const project = fileURLToPath(new URL('../..', import.meta.url));
const fixture = await fs.mkdtemp(path.join(os.tmpdir(), 'tolgraven-styles-'));
const write = async (name, contents) => {
  await fs.mkdir(path.dirname(path.join(fixture, name)), {recursive: true});
  await fs.writeFile(path.join(fixture, name), contents);
};
const read = name => fs.readFile(path.join(fixture, name), 'utf8');
let checks = 0;
const build = (...args) => {
  const result = spawnSync(process.execPath, ['scripts/build/styles.mjs', '--if-stale', ...args],
    {cwd: fixture, encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
  return result.stdout;
};
const rebuilt = () => { assert.match(build(), /Built \d+ CSS entries/); checks++; };

try {
  await fs.symlink(path.join(project, 'node_modules'), path.join(fixture, 'node_modules'), 'dir');
  await write('scripts/build/styles-lock.clj', await fs.readFile(path.join(project, 'scripts/build/styles-lock.clj')));
  await write('scripts/build/styles.mjs', await fs.readFile(path.join(project, 'scripts/build/styles.mjs')));
  await write('scripts/build/editor-styles.mjs', await fs.readFile(path.join(project, 'scripts/build/editor-styles.mjs')));
  await write('scripts/build/style-overrides.mjs', await fs.readFile(path.join(project, 'scripts/build/style-overrides.mjs')));
  // This suite isolates CSS freshness; the real workspace builder has its own checks.
  await write('scripts/build/neovim_workspace.clj', '(ns build.neovim-workspace) (defn -main [& _])');
  await write('package.json', '{"type":"module"}');
  await write('package-lock.json', '{}');
  await write('resources/icon-fonts.json', '{}');
  await write('resources/public/css/opensans.css', '');
  await write('src/frontend/example/module.cljs', '(ns example.module)');
  await write('resources/scss/_pictures.scss', '.progressive-image__final { width: 100%; }');
  await write('resources/scss/main.scss', '@use "pictures";');
  await write('resources/scss/dev.scss', '.dev { display: block; }');
  await write('resources/scss/icons.scss', '.icon { display: inline; }');
  await write('resources/scss/modules/home.scss', '.logo-picture { width: 100%; }');
  await write('resources/public/css/website/main.min.css', '.old-picture { width: auto; }');

  rebuilt();
  assert.match(await read('resources/public/css/website/main.min.css'), /progressive-image__final/);
  const originalTime = (await fs.stat(path.join(fixture, 'resources/public/css/website/main.min.css'))).mtimeMs;
  assert.match(build(), /CSS is current/);
  assert.equal((await fs.stat(path.join(fixture, 'resources/public/css/website/main.min.css'))).mtimeMs, originalTime);
  checks++;

  await write('resources/scss/_pictures.scss', '.progressive-image__final { width: 95%; }');
  rebuilt();
  assert.match(await read('resources/public/css/website/main.min.css'), /width:95%/);
  await fs.rm(path.join(fixture, 'resources/public/css/website/modules/home.min.css'));
  rebuilt();
  assert.match(await read('resources/public/css/website/modules/home.min.css'), /logo-picture/);
  await write('resources/public/css/website/main.min.css', '.old-picture { width: auto; }');
  rebuilt();
  assert.match(await read('resources/public/css/website/main.min.css'), /progressive-image__final/);
  await write('resources/public/css/website/.build-revision.json', '{');
  rebuilt();
  await write('resources/public/css/website/.build-revision.json', 'null');
  rebuilt();

  await write('src/frontend/example/module.cljs', '(ns example.module) ; :icons ["solid/house"]');
  rebuilt();
  await write('env/dev/cljs/example/module.cljs', '(ns example.dev.module) ; :icons ["solid/code"]');
  rebuilt();
  await write('resources/public/css/opensans.css', '@font-face { font-family: Example; }');
  rebuilt();
  await write('package-lock.json', '{"lockfileVersion": 3}');
  rebuilt();
  await fs.appendFile(path.join(fixture, 'scripts/build/styles.mjs'), '\n// Tool revision fixture.\n');
  rebuilt();
  await fs.appendFile(path.join(fixture, 'scripts/build/editor-styles.mjs'), '\n// Editor stylesheet revision fixture.\n');
  rebuilt();
  await write('resources/scss/modules/new-owner.scss', '.new-owner { display: grid; }');
  rebuilt();
  assert.match(await read('resources/public/css/website/modules/new-owner.min.css'), /new-owner/);

  await write('resources/site.overrides.css', ':root { --space: 1.5rem; --line-width: 0.125rem; }');
  rebuilt();
  const overridden = await read('resources/public/css/website/main.min.css');
  assert.match(overridden, /--space:\s*1.5rem/);
  assert.ok(overridden.indexOf('--space:') > overridden.indexOf('progressive-image__final'));
  assert.doesNotMatch(await read('resources/public/css/website/modules/home.min.css'), /--space:/);
  assert.match(build(), /CSS is current/);
  checks++;
  await write('resources/site.overrides.css', ':root { --space: 2rem; }');
  rebuilt();
  assert.match(await read('resources/public/css/website/main.min.css'), /--space:\s*2rem/);
  await write('resources/site.overrides.css', ':root { --space: 3rem; ');
  const invalid = spawnSync(process.execPath, ['scripts/build/styles.mjs', '--if-stale'], {cwd: fixture, encoding: 'utf8'});
  assert.notEqual(invalid.status, 0);
  assert.match(await read('resources/public/css/website/main.min.css'), /--space:\s*2rem/);
  checks++;
  await fs.rm(path.join(fixture, 'resources/site.overrides.css'));
  rebuilt();
  assert.doesNotMatch(await read('resources/public/css/website/main.min.css'), /--space:/);

  await fs.appendFile(path.join(fixture, 'resources/scss/main.scss'), '\n.concurrent { display: grid; }');
  const concurrent = () => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['scripts/build/styles.mjs', '--if-stale'], {cwd: fixture});
    let output = '', error = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { error += chunk; });
    child.once('error', reject);
    child.once('exit', code => resolve({code, output, error}));
  });
  const writers = await Promise.all([concurrent(), concurrent()]);
  assert.ok(writers.every(result => result.code === 0), JSON.stringify(writers));
  assert.equal(writers.filter(result => result.output.includes('Built ')).length, 1);
  assert.match(await read('resources/public/css/website/main.min.css'), /concurrent/);
  checks++;

  const watcher = spawn(process.execPath, ['scripts/build/styles.mjs', '--watch'], {cwd: fixture, stdio: 'ignore'});
  const waitFor = async predicate => {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      if (await predicate()) return;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.fail('CSS watcher did not repair its output');
  };
  try {
    await write('resources/scss/_pictures.scss', '.progressive-image__final { width: 91%; }');
    await waitFor(async () => (await read('resources/public/css/website/main.min.css')).includes('width:91%'));
    checks++;
    await write('resources/public/css/website/main.min.css', '.stale { display: none; }');
    await waitFor(async () => (await read('resources/public/css/website/main.min.css')).includes('width:91%'));
    checks++;
    await write('resources/scss/_pictures.scss', '.broken {');
    await new Promise(resolve => setTimeout(resolve, 1500));
    await write('resources/scss/_pictures.scss', '.progressive-image__final { width: 92%; }');
    await waitFor(async () => (await read('resources/public/css/website/main.min.css')).includes('width:92%'));
    checks++;
  } finally {
    const exited = new Promise(resolve => watcher.once('exit', resolve));
    watcher.kill('SIGTERM');
    await exited;
  }

  const partial = spawnSync(process.execPath, ['scripts/build/styles.mjs', '--if-stale', '--sass-only'],
    {cwd: fixture, encoding: 'utf8'});
  assert.notEqual(partial.status, 0);
  assert.match(partial.stderr, /requires the complete CSS build/);
  checks++;
  console.log(`${checks} stylesheet freshness checks passed`);
} finally {
  await fs.rm(fixture, {recursive: true, force: true});
}
