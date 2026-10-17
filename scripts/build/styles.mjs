/** Build the shell and independent feature sheets with the same locked tools. */
import fs from 'node:fs/promises';
import { execFileSync, spawn } from 'node:child_process';
import path from 'node:path';
import crypto from 'node:crypto';
import {readOverrides, checkedOverrides} from './style-overrides.mjs';

const root = 'resources/scss';
const output = process.env.CSS_OUTPUT_DIR || 'resources/public/css/website';
const watch = process.argv.includes('--watch');
const sassOnly = process.argv.includes('--sass-only');
const postcssOnly = process.argv.includes('--postcss-only');
const ifStale = watch || process.argv.includes('--if-stale');
const stamp = path.join(output, '.build-revision.json');

async function entries() {
  return ['main.scss', 'dev.scss', 'icons.scss', ...(await fs.readdir(`${root}/modules`))
    .filter(name => name.endsWith('.scss')).sort().map(name => `modules/${name}`)];
}

async function inputFiles(directory, extensions) {
  try {
    return (await fs.readdir(directory, {recursive: true}))
      .filter(name => extensions.some(extension => name.endsWith(extension)))
      .map(name => path.join(directory, name));
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
}

async function revision(buildEntries, overrides) {
  const inputs = [
    'scripts/build/styles.mjs', 'scripts/build/styles-lock.clj', 'scripts/build/neovim_workspace.clj', 'scripts/build/editor-styles.mjs', 'scripts/build/style-overrides.mjs',
    'package.json', 'package-lock.json', 'resources/icon-fonts.json',
    ...(buildEntries.includes('modules/markdown-editor.scss')
      ? ['node_modules/@mdxeditor/editor/dist/style.css'] : []),
    'resources/public/css/opensans.css',
    ...await inputFiles(root, ['.scss']),
    // Module/component icon declarations and their macros own the style inventory.
    ...await inputFiles('src', ['.clj', '.cljs', '.cljc', '.js', '.lua']),
    ...await inputFiles('env/dev', ['.cljs', '.cljc']),
    ...await inputFiles('resources', ['site.edn', 'site.overrides.edn']),
    ...await inputFiles('resources/public/vendor/neovim', ['.lua', '.vim']),
  ].sort();
  const hash = crypto.createHash('sha256');
  hash.update(overrides).update('\0');
  for (const file of inputs) {
    hash.update(file).update('\0').update(await fs.readFile(file)).update('\0');
  }
  return hash.digest('hex');
}

const digest = contents => crypto.createHash('sha256').update(contents).digest('hex');
async function outputHashes(buildEntries) {
  const hashes = {};
  for (const entry of buildEntries) {
    const name = entry.replace(/\.scss$/, '.css');
    for (const file of [name, name.replace(/\.css$/, '.min.css')]) {
      hashes[file] = digest(await fs.readFile(path.join(output, file)));
    }
  }
  // The same build owns the optional editor workspace/profile. A deleted or
  // overwritten generated asset must prevent the CSS fast path too.
  for (const file of ['resources/public/vendor/neovim/workspace.json',
                       'resources/public/vendor/neovim/profile.json']) {
    try { hashes[file] = digest(await fs.readFile(file)); }
    catch (error) { if (error.code !== 'ENOENT') throw error; hashes[file] = null; }
  }
  return hashes;
}

async function current(buildEntries, inputRevision) {
  try {
    const saved = JSON.parse(await fs.readFile(stamp, 'utf8'));
    return saved?.revision === inputRevision &&
      JSON.stringify(saved.outputs) === JSON.stringify(await outputHashes(buildEntries));
  } catch (error) {
    if (error.code === 'ENOENT' || error instanceof SyntaxError) return false;
    throw error;
  }
}

async function build() {
  const buildEntries = await entries();
  const overrides = checkedOverrides(await readOverrides());
  const inputRevision = await revision(buildEntries, overrides);
  if (ifStale && await current(buildEntries, inputRevision)) {
    if (!watch) console.log(`CSS is current in ${output}`);
    return;
  }
  if (!postcssOnly) {
    execFileSync('bb', ['-cp', 'scripts:src/cljc:resources', '-m', 'build.neovim-workspace'], {stdio: 'inherit'});
  }
  const [{compile}, {default: postcss}, {default: autoprefixer}, {editorStyles}] = await Promise.all([
    import('sass'), import('postcss'), import('autoprefixer'), import('./editor-styles.mjs'),
  ]);
  for (const entry of buildEntries) {
    const file = path.join(output, entry.replace(/\.scss$/, '.css'));
    await fs.mkdir(path.dirname(file), {recursive: true});
    if (!postcssOnly) {
      const result = compile(path.join(root, entry), {style: 'compressed', charset: false});
      const css = entry === 'modules/markdown-editor.scss'
        ? `${await editorStyles()}\n${result.css}` : entry === 'modules/maps.scss'
        ? result.css.replaceAll('url(images/', 'url(/vendor/leaflet/images/') : result.css;
      await atomicWrite(file, entry === 'main.scss' && overrides ? `${css}\n${overrides}` : css);
    }
    if (!sassOnly) {
      const result = await postcss([autoprefixer]).process(await fs.readFile(file, 'utf8'),
        {from: file, to: file.replace(/\.css$/, '.min.css'), map: false});
      await atomicWrite(file.replace(/\.css$/, '.min.css'), result.css);
    }
  }
  await fs.cp('node_modules/leaflet/dist/images', 'resources/public/vendor/leaflet/images', {recursive: true});
  if (!sassOnly && !postcssOnly) {
    if (inputRevision !== await revision(await entries(), checkedOverrides(await readOverrides()))) {
      return build();
    }
    await atomicWrite(stamp, JSON.stringify({revision: inputRevision, outputs: await outputHashes(buildEntries)}));
  } else {
    await fs.rm(stamp, {force: true});
  }
  console.log(`Built ${buildEntries.length} CSS entries in ${output}`);
}
async function atomicWrite(file, contents) {
  const temporary = `${file}.${process.pid}.tmp`;
  try {
    await fs.writeFile(temporary, contents);
    await fs.rename(temporary, file);
  } finally { await fs.rm(temporary, {force: true}); }
}

async function lockedBuild() {
  await fs.mkdir(output, {recursive: true});
  // The kernel releases this lock on either process exiting. The file remains;
  // neither a second watcher nor a recovery path can unlink an active lock.
  const owner = spawn('bb', ['scripts/build/styles-lock.clj', path.join(output, '.build.lock')],
    {stdio: ['pipe', 'pipe', 'inherit']});
  await new Promise((resolve, reject) => {
    owner.once('error', reject);
    owner.once('exit', code => reject(new Error(`CSS lock owner exited (${code})`)));
    owner.stdout.once('data', resolve);
  });
  try { await build(); }
  finally {
    owner.stdin.end();
    if (owner.exitCode === null) await new Promise(resolve => owner.once('exit', resolve));
  }
}

if (ifStale && (sassOnly || postcssOnly) && !watch) throw new Error('--if-stale requires the complete CSS build');
try { await lockedBuild(); }
catch (error) {
  if (!watch) throw error;
  console.error(error);
}
if (watch) {
  // Hash the complete build inputs and outputs: declarations, overrides and
  // branch switches count too. Failed builds stay stale and are retried.
  const partial = sassOnly || postcssOnly;
  const partialRevision = async () => postcssOnly
    ? digest((await Promise.all((await inputFiles(output, ['.css'])).filter(file => !file.endsWith('.min.css'))
        .sort().map(async file => `${file}:${await fs.readFile(file, 'utf8')}`))).join('\0'))
    : revision(await entries(), checkedOverrides(await readOverrides()));
  let previous = partial ? await partialRevision() : null;
  let building = false;
  setInterval(async () => {
    if (building) return;
    building = true;
    try {
      const latest = partial ? await partialRevision() : null;
      if (!partial || latest !== previous) { await lockedBuild(); previous = latest; }
    } catch (error) { console.error(error); }
    finally { building = false; }
  }, 1000);
}
