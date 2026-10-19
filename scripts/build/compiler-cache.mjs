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
if (!['shadow', 'aot'].includes(mode) || !cache || !command.length) {
  throw new Error('Usage: compiler-cache.mjs shadow <cache> <command...> OR aot <cache> <classpath-file> <command...>');
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
const inputs = [...(await Promise.all(sourceRoots.map(files))).flat(),
  'project.clj', 'package.json', 'package-lock.json', 'shadow-cljs.edn'].sort();
const manifest = {};
for (const file of inputs) {
  const hash = crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
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
const javaRelease = mode === 'aot' && process.env.JAVA_HOME
  ? await fs.readFile(path.join(process.env.JAVA_HOME, 'release'), 'utf8') : null;
const identity = crypto.createHash('sha256').update(JSON.stringify({
  inputs: Object.fromEntries(Object.entries(manifest).map(([file, value]) => [file, value.hash])),
  classpath, dependencies, javaRelease, command, builder: process.env.COMPILER_IMAGE, java: process.env.JAVA_TOOL_OPTIONS,
})).digest('hex');
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
}
