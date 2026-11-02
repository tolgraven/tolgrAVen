import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const wrapper = path.resolve('scripts/build/compiler-cache.mjs');
test('Docker context retains the authored render worker without generated JS or private config', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'worker-context-'));
  const env = {...process.env, BUILDX_CONFIG: path.join(root, 'buildx')};
  for (const key of ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']) delete env[key];
  const docker = ['--host=unix:///var/run/docker.sock'];
  try {
    const available = spawnSync('docker', [...docker, 'info', '--format', '{{.ServerVersion}}'], {env, timeout: 10000});
    if (available.status !== 0) return t.skip('Local Docker socket is unavailable');
    const context = path.join(root, 'context');
    const output = path.join(root, 'output');
    const worker = 'resources/public/js/page-render-worker.js';
    for (const directory of ['resources/public/js/compiled/out', 'resources/public/css/website', 'env/dev/resources']) {
      await fs.mkdir(path.join(context, directory), {recursive: true});
    }
    await fs.copyFile('.dockerignore', path.join(context, '.dockerignore'));
    await fs.copyFile(worker, path.join(context, worker));
    for (const file of ['resources/public/js/compiled/out/stale.js', 'resources/public/css/website/stale.css', 'env/dev/resources/config.edn', '.env']) {
      await fs.writeFile(path.join(context, file), 'excluded fixture');
    }
    const built = spawnSync('docker', [...docker, 'build', '-f', '-', '--output', `type=local,dest=${output}`, context], {
      env, input: 'FROM scratch\nCOPY . /\n', encoding: 'utf8', timeout: 30000,
    });
    assert.equal(built.status, 0, built.stderr);
    assert.equal(await fs.readFile(path.join(output, worker), 'utf8'), await fs.readFile(worker, 'utf8'));
    for (const file of ['resources/public/js/compiled/out/stale.js', 'resources/public/css/website/stale.css', 'env/dev/resources/config.edn', '.env']) {
      await assert.rejects(fs.stat(path.join(output, file)), {code: 'ENOENT'});
    }
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});

test('compiler cache survives fresh checkouts and invalidates changes, deletion and failures', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'compiler-cache-'));
  try {
    for (const directory of ['src', 'resources/public', 'scripts/build', 'dependencies']) await fs.mkdir(path.join(root, directory), {recursive: true});
    for (const file of ['project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn', 'scripts/build/compiler-cache.mjs']) {
      await fs.writeFile(path.join(root, file), '{}');
    }
    await fs.writeFile(path.join(root, 'resources/public/image.png'), 'binary fixture');
    await fs.utimes(path.join(root, 'resources/public/image.png'), 1, 1);
    await fs.writeFile(path.join(root, 'src/macro.clj'), '(defmacro example [] 1)');
    await fs.writeFile(path.join(root, 'src/removed.clj'), '(ns removed)');
    const dependency = path.join(root, 'dependencies/fixture.jar');
    const secondDependency = path.join(root, 'dependencies/second.jar');
    const classpath = path.join(root, 'classpath');
    await fs.writeFile(dependency, 'dependency-v1');
    await fs.writeFile(secondDependency, 'second-v1');
    await fs.writeFile(classpath, [dependency, secondDependency].join(path.delimiter));
    const compile = `const f=require('fs'); f.mkdirSync('target/uberjar+provided/classes',{recursive:true}); f.writeFileSync('target/uberjar+provided/classes/output',f.readFileSync('src/macro.clj')); f.appendFileSync('runs','x'); if(f.existsSync('fail')) process.exit(1);`;
    const run = () => spawnSync(process.execPath, [wrapper, 'aot', path.join(root, 'cache'), classpath, process.execPath, '-e', compile], {cwd: root, encoding: 'utf8'});
    assert.equal(run().status, 0);
    assert.equal((await fs.stat(path.join(root, 'resources/public/image.png'))).mtimeMs, 1000);
    const originalTime = (await fs.stat(path.join(root, 'src/macro.clj'))).mtimeMs;
    await fs.utimes(path.join(root, 'src/macro.clj'), 1, 1);
    await fs.rm(path.join(root, 'target'), {recursive: true});
    assert.match(run().stdout, /restored classes/);
    assert.equal((await fs.stat(path.join(root, 'src/macro.clj'))).mtimeMs, originalTime);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'x');
    await fs.writeFile(path.join(root, 'src/macro.clj'), '(defmacro example [] 2)');
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'target/uberjar+provided/classes/output'), 'utf8'), '(defmacro example [] 2)');
    await fs.writeFile(path.join(root, 'target/uberjar+provided/classes/stale'), 'removed namespace');
    await fs.rm(path.join(root, 'src/removed.clj'));
    await fs.writeFile(path.join(root, 'fail'), '');
    assert.equal(run().status, 1);
    await assert.rejects(fs.stat(path.join(root, 'target/uberjar+provided/classes/stale')), {code: 'ENOENT'});
    await fs.rm(path.join(root, 'fail'));
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxx');
    await fs.writeFile(path.join(root, 'resources/site.edn'), '{:modules {}}');
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxxx');
    await fs.writeFile(dependency, 'dependency-v2');
    assert.equal(run().status, 0);
    await fs.writeFile(dependency, 'dependency-v1');
    assert.equal(run().status, 0);
    await fs.writeFile(classpath, [secondDependency, dependency].join(path.delimiter));
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxxxxxx');
    const extraSource = path.join(root, 'extra-source');
    await fs.mkdir(extraSource);
    await fs.writeFile(classpath, extraSource);
    assert.match(run().stdout, /outside captured inputs/);
    assert.match(run().stdout, /outside captured inputs/);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxxxxxxxx');
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});

test('finished releases survive CSS edits and interleaved sites but reject stale, missing and failed outputs', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'release-cache-'));
  const outputs = ['resources/public/js/compiled/out', 'resources/public/js/return',
    'resources/public/js/document', 'target/ssr'];
  try {
    for (const directory of ['src', 'resources/scss/modules', 'scripts/build']) {
      await fs.mkdir(path.join(root, directory), {recursive: true});
    }
    for (const file of ['project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn']) {
      await fs.writeFile(path.join(root, file), '{}');
    }
    await fs.writeFile(path.join(root, 'src/view.cljs'), 'original view');
    await fs.writeFile(path.join(root, 'resources/scss/modules/home.scss'), 'original styles');
    await fs.writeFile(path.join(root, 'resources/site.overrides.edn'), 'production');
    const compile = `const f=require('fs');f.appendFileSync('runs','x');
      for(const p of ${JSON.stringify(outputs)}) { f.mkdirSync(p,{recursive:true});
        f.writeFileSync(p+'/output.js',f.readFileSync('src/view.cljs')); }
      if(f.existsSync('fail'))process.exit(1);`;
    const run = (env = {}) => spawnSync(process.execPath,
      [wrapper, 'shadow', path.join(root, 'cache'), process.execPath, '-e', compile],
      {cwd: root, encoding: 'utf8', env: {...process.env, ...env}});
    assert.equal(run().status, 0);
    await fs.rm(path.join(root, 'target'), {recursive: true});
    await fs.writeFile(path.join(root, 'resources/scss/modules/home.scss'), 'new styles');
    await fs.writeFile(path.join(root, 'resources/site.overrides.css'), ':root { --color: red; }');
    // Adding a stylesheet path changes the inventory once; later byte edits do not.
    assert.equal(run().status, 0);
    await fs.writeFile(path.join(root, 'resources/site.overrides.css'), ':root { --color: blue; }');
    assert.match(run().stdout, /compiler skipped/);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xx');
    await fs.writeFile(path.join(root, 'resources/site.overrides.edn'), 'preview');
    assert.equal(run().status, 0);
    await fs.writeFile(path.join(root, 'resources/site.overrides.edn'), 'production');
    assert.match(run().stdout, /compiler skipped/);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxx');
    // Damage every saved SSR output: the next matching release must compile again.
    for (const name of await fs.readdir(path.join(root, 'cache/artifacts'))) {
      await fs.rm(path.join(root, 'cache/artifacts', name, 'target/ssr'), {recursive: true});
    }
    assert.match(run().stdout, /rebuilding/);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxx');
    await fs.writeFile(path.join(root, 'src/view.cljs'), 'edited view');
    await fs.writeFile(path.join(root, 'fail'), '');
    assert.equal(run().status, 1);
    await fs.rm(path.join(root, 'fail'));
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'target/ssr/output.js'), 'utf8'), 'edited view');
    assert.equal(run({COMPILER_IMAGE: 'different-prefab'}).status, 0);
    assert.equal((await fs.readdir(path.join(root, 'cache/artifacts'))).length, 3);
    await fs.rm(path.join(root, 'resources/scss/modules/home.scss'));
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xxxxxxxx');
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});

test('documentation cache restores published docs and rejects a successful command with missing output', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'docs-cache-'));
  try {
    await fs.mkdir(path.join(root, 'src'));
    for (const file of ['project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn']) {
      await fs.writeFile(path.join(root, file), '{}');
    }
    const compile = `const f=require('fs');f.appendFileSync('runs','x');
      f.mkdirSync('resources/docs/codox',{recursive:true});f.writeFileSync('resources/docs/codox/index.html','API reference');`;
    const run = command => spawnSync(process.execPath, [wrapper, 'docs', path.join(root, 'cache'),
      process.execPath, '-e', command], {cwd: root, encoding: 'utf8'});
    assert.equal(run(compile).status, 0);
    await fs.rm(path.join(root, 'resources/docs'), {recursive: true});
    assert.match(run(compile).stdout, /compiler skipped/);
    assert.equal(await fs.readFile(path.join(root, 'resources/docs/codox/index.html'), 'utf8'), 'API reference');
    assert.notEqual(run('process.exit(0)').status, 0);
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});

test('focused analysis commands run without requiring a complete application release', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'analysis-cache-'));
  try {
    await fs.mkdir(path.join(root, 'src'));
    for (const file of ['project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn']) {
      await fs.writeFile(path.join(root, file), '{}');
    }
    const command = "require('fs').appendFileSync('runs','x')";
    const run = () => spawnSync(process.execPath, [wrapper, 'shadow-analysis', path.join(root, 'cache'),
      process.execPath, '-e', command], {cwd: root, encoding: 'utf8'});
    assert.equal(run().status, 0);
    assert.equal(run().status, 0);
    assert.equal(await fs.readFile(path.join(root, 'runs'), 'utf8'), 'xx');
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});
