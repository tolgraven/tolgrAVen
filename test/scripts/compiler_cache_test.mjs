import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const wrapper = path.resolve('scripts/build/compiler-cache.mjs');
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
