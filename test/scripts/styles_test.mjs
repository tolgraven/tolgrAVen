import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
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
  await write('scripts/build/styles.mjs', await fs.readFile(path.join(project, 'scripts/build/styles.mjs')));
  await write('scripts/build/editor-styles.mjs', await fs.readFile(path.join(project, 'scripts/build/editor-styles.mjs')));
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

  const partial = spawnSync(process.execPath, ['scripts/build/styles.mjs', '--if-stale', '--sass-only'],
    {cwd: fixture, encoding: 'utf8'});
  assert.notEqual(partial.status, 0);
  assert.match(partial.stderr, /requires the complete CSS build/);
  checks++;
  console.log(`${checks} stylesheet freshness checks passed`);
} finally {
  await fs.rm(fixture, {recursive: true, force: true});
}
