/** Build the shell and independent feature sheets with the same locked tools. */
import fs from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import crypto from 'node:crypto';
import {readOverrides, checkedOverrides} from './style-overrides.mjs';

const root = 'resources/scss';
const output = process.env.CSS_OUTPUT_DIR || 'resources/public/css/website';
const watch = process.argv.includes('--watch');
const sassOnly = process.argv.includes('--sass-only');
const postcssOnly = process.argv.includes('--postcss-only');
const ifStale = process.argv.includes('--if-stale');
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
    'scripts/build/styles.mjs', 'scripts/build/editor-styles.mjs', 'scripts/build/style-overrides.mjs',
    'package.json', 'package-lock.json', 'resources/icon-fonts.json',
    ...(buildEntries.includes('modules/markdown-editor.scss')
      ? ['node_modules/@mdxeditor/editor/dist/style.css'] : []),
    'resources/public/css/opensans.css',
    ...await inputFiles(root, ['.scss']),
    // Module/component icon declarations and their macros own the style inventory.
    ...await inputFiles('src/frontend', ['.cljs', '.cljc']),
    ...await inputFiles('env/dev', ['.cljs', '.cljc']),
    ...await inputFiles('src/cljc', ['.cljc']),
    ...await inputFiles('src/backend', ['.clj']),
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
  if (!postcssOnly) {
    execFileSync('bb', ['-cp', 'scripts:src/cljc:resources', '-m', 'build.neovim-workspace'], {stdio: 'inherit'});
  }
  const buildEntries = await entries();
  const overrides = checkedOverrides(await readOverrides());
  const inputRevision = await revision(buildEntries, overrides);
  if (ifStale && await current(buildEntries, inputRevision)) {
    console.log(`CSS is current in ${output}`);
    return;
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
      await fs.writeFile(file, entry === 'main.scss' && overrides ? `${css}\n${overrides}` : css);
    }
    if (!sassOnly) {
      const result = await postcss([autoprefixer]).process(await fs.readFile(file, 'utf8'),
        {from: file, to: file.replace(/\.css$/, '.min.css'), map: false});
      await fs.writeFile(file.replace(/\.css$/, '.min.css'), result.css);
    }
  }
  await fs.cp('node_modules/leaflet/dist/images', 'resources/public/vendor/leaflet/images', {recursive: true});
  if (!sassOnly && !postcssOnly) {
    await fs.writeFile(stamp, JSON.stringify({revision: inputRevision, outputs: await outputHashes(buildEntries)}));
  } else {
    await fs.rm(stamp, {force: true});
  }
  console.log(`Built ${buildEntries.length} CSS entries in ${output}`);
}
if (ifStale && (sassOnly || postcssOnly)) throw new Error('--if-stale requires the complete CSS build');
await build();
if (watch) {
  // Poll like the existing Sass watcher so bind mounts and editors behave alike.
  const watched = postcssOnly ? output : root;
  async function revision() {
    const files = await fs.readdir(watched, {recursive: true});
    const fontRevision = postcssOnly ? ''
      : `opensans:${(await fs.stat('resources/public/css/opensans.css')).mtimeMs}`;
    const overrides = await readOverrides();
    return fontRevision + overrides + (await Promise.all(files.filter(name => postcssOnly
      ? name.endsWith('.css') && !name.endsWith('.min.css') : name.endsWith('.scss'))
      .map(async name => `${name}:${(await fs.stat(path.join(watched, name))).mtimeMs}`)))
      .sort().join('|');
  }
  let previous = await revision();
  let building = false;
  setInterval(async () => {
    if (building) return;
    building = true;
    try {
      const current = await revision();
      if (current !== previous) {
        await build();
        previous = current;
      }
    } catch (error) { console.error(error); }
    finally { building = false; }
  }, 1000);
}
