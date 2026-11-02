/** Docker-only compiler reuse. The enclosing BuildKit mount owns the writer lock. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';

const [mode, cache, ...arguments_] = process.argv.slice(2);
const classpathFile = mode === 'aot' ? arguments_.shift() : null;
const command = arguments_;
const sourceRoots = ['src', 'env/prod', 'resources', 'scripts/build'];
const classes = 'target/uberjar+provided/classes';
const outputs = mode === 'docs' ? ['resources/docs/codox'] : mode === 'shadow'
  ? ['resources/public/js/compiled/out', 'resources/public/js/return', 'resources/public/js/document', 'target/ssr'] : [];
const generatedRoots = ['resources/docs/codox', 'resources/public/css/website', 'resources/optimus',
  'resources/public/js/compiled', 'resources/public/js/return', 'resources/public/js/document'];
const generatedFiles = new Set(['resources/public/vendor/neovim/workspace.json',
  'resources/public/vendor/neovim/profile.json']);
if (!['shadow', 'aot', 'docs'].includes(mode) || !cache || !command.length) {
  throw new Error('Usage: compiler-cache.mjs <shadow|docs> <cache> <command...> OR aot <cache> <classpath-file> <command...>');
}
async function files(directory) {
  let entries;
  try { entries = await fs.readdir(directory, {withFileTypes: true}); }
  catch (error) { if (error.code === 'ENOENT') return []; throw error; }
  const result = [];
  for (const entry of entries) {
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Compiler inputs must be regular files: ${file}`);
    if (entry.isDirectory()) result.push(...await files(file));
    else if (entry.isFile()) result.push(file);
  }
  return result;
}
async function readManifest(file) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return {}; throw error; }
}
await fs.mkdir(cache, {recursive: true});
const manifestFile = path.join(cache, 'inputs.json');
const previous = await readManifest(manifestFile);
const inputs = [...(await Promise.all(sourceRoots.map(files))).flat().filter(file =>
  !generatedFiles.has(file) && !generatedRoots.some(root => file === root || file.startsWith(root + '/'))),
  'project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn'].sort();
const manifest = {};
for (const file of inputs) {
  // Compiler inventories depend on stylesheet existence, not Sass/CSS bytes.
  // Styles and prepared assets still rebuild from the real files in their own steps.
  const stylesheet = file.endsWith('.scss') || file === 'resources/site.overrides.css';
  const hash = crypto.createHash('sha256').update(stylesheet ? 'stylesheet-present' : await fs.readFile(file)).digest('hex');
  const old = previous[file];
  // Fresh git clones reset macro/resource timestamps, which Shadow validates.
  // Changed bytes always get a distinct millisecond, even across quick rebuilds.
  const mtime = old?.hash === hash ? old.mtime : Math.max(Date.now(), (old?.mtime || 0) + 1000);
  // Keep media/font freshness and Last-Modified metadata under their own owners.
  if (/\.(?:clj[cs]?|edn|json|m?js|css|html)$/.test(file)) {
    await fs.utimes(file, mtime / 1000, mtime / 1000);
  }
  manifest[file] = {hash, mtime};
}
await fs.writeFile(manifestFile + '.new', JSON.stringify(manifest));
await fs.rename(manifestFile + '.new', manifestFile);
const classpath = classpathFile ? (await fs.readFile(classpathFile, 'utf8')).trim() : '';
const dependencies = {};
let reusable = true;
for (const file of classpath.split(path.delimiter).filter(Boolean)) {
  if (file.endsWith('.jar')) {
    dependencies[file] = crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
  } else {
    let stat;
    try { stat = await fs.stat(file); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    const absolute = path.resolve(file);
    const captured = sourceRoots.some(root => absolute === path.resolve(root)
      || absolute.startsWith(path.resolve(root) + path.sep));
    if (stat.isDirectory() && absolute !== path.resolve(classes) && !captured) reusable = false;
  }
}
if (!reusable) console.log('AOT cache: classpath directory outside captured inputs; compiling fresh.');
const javaRelease = process.env.JAVA_HOME
  ? await fs.readFile(path.join(process.env.JAVA_HOME, 'release'), 'utf8') : null;
const identity = crypto.createHash('sha256').update(JSON.stringify({
  inputs: Object.fromEntries(Object.entries(manifest).map(([file, value]) => [file, value.hash])),
  mode, classpath, dependencies, javaRelease, command, builder: process.env.COMPILER_IMAGE, java: process.env.JAVA_TOOL_OPTIONS,
})).digest('hex');
const artifacts = path.join(cache, 'artifacts');
const entry = path.join(artifacts, identity);
const outputHashes = async root => {
  const hashes = {};
  for (const output of outputs) {
    const names = await files(path.join(root, output));
    if (!names.length) throw new Error(`Compiler did not produce ${output}`);
    for (const name of names.sort()) {
      const relative = path.relative(root, name);
      hashes[relative] = crypto.createHash('sha256').update(await fs.readFile(name)).digest('hex');
    }
  }
  return hashes;
};
if (outputs.length) {
  const record = await readManifest(path.join(entry, 'outputs.json'));
  if (record.identity === identity) {
    let intact = false;
    try { intact = JSON.stringify(record.hashes) === JSON.stringify(await outputHashes(entry)); }
    catch (error) { if (error.code !== 'ENOENT' && !error.message.startsWith('Compiler did not produce')) throw error; }
    if (intact) {
      for (const output of outputs) {
        await fs.rm(output, {recursive: true, force: true});
        await fs.cp(path.join(entry, output), output, {recursive: true, preserveTimestamps: true});
      }
      await fs.utimes(entry, new Date(), new Date());
      console.log(`${mode} cache: restored verified finished outputs; compiler skipped.`);
      process.exit(0);
    }
    console.log(`${mode} cache: saved outputs are incomplete or changed; rebuilding.`);
  }
  // Fresh outputs must not retain chunks or documentation from removed sources.
  for (const output of outputs) await fs.rm(output, {recursive: true, force: true});
}
const saved = path.join(cache, 'classes');
const stamp = path.join(cache, 'aot.json');
const hit = mode === 'aot' && reusable && (await readManifest(stamp)).identity === identity;
if (hit) {
  await fs.rm(classes, {recursive: true, force: true});
  await fs.cp(saved, classes, {recursive: true, preserveTimestamps: true});
  console.log('AOT cache: unchanged complete compiler inputs; restored classes.');
} else {
  if (mode === 'aot') {
    await fs.rm(stamp, {force: true});
    await fs.rm(classes, {recursive: true, force: true});
  }
  const result = spawnSync(command[0], command.slice(1), {stdio: 'inherit'});
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
  if (mode === 'aot' && reusable) {
    await fs.rm(saved, {recursive: true, force: true});
    await fs.cp(classes, saved, {recursive: true, preserveTimestamps: true});
    await fs.writeFile(stamp, JSON.stringify({identity}));
  }
  if (outputs.length) {
    const hashes = await outputHashes('.');
    const temporary = entry + '.new';
    await fs.mkdir(artifacts, {recursive: true});
    await fs.rm(temporary, {recursive: true, force: true});
    for (const output of outputs) {
      await fs.cp(output, path.join(temporary, output), {recursive: true, preserveTimestamps: true});
    }
    await fs.writeFile(path.join(temporary, 'outputs.json'), JSON.stringify({identity, hashes}));
    await fs.rm(entry, {recursive: true, force: true});
    await fs.rename(temporary, entry);
    // The enclosing BuildKit mount serializes writers. Retain a small LRU for
    // interleaved preview/production configurations and source reversions.
    const entries = await Promise.all((await fs.readdir(artifacts)).map(async name =>
      ({name, time: (await fs.stat(path.join(artifacts, name))).mtimeMs})));
    for (const old of entries.sort((a, b) => b.time - a.time).slice(3)) {
      await fs.rm(path.join(artifacts, old.name), {recursive: true, force: true});
    }
    console.log(`${mode} cache: published verified finished outputs.`);
  }
}
