/** Build the shell and independent feature sheets with the same locked tools. */
import fs from 'node:fs/promises';
import path from 'node:path';
import * as sass from 'sass';
import postcss from 'postcss';
import autoprefixer from 'autoprefixer';

const root = 'resources/scss';
const output = process.env.CSS_OUTPUT_DIR || 'resources/public/css/tolgraven';
const watch = process.argv.includes('--watch');
const sassOnly = process.argv.includes('--sass-only');
const postcssOnly = process.argv.includes('--postcss-only');
async function build() {
  const entries = ['main.scss', 'dev.scss', 'icons.scss', ...(await fs.readdir(`${root}/modules`))
    .filter(name => name.endsWith('.scss')).sort().map(name => `modules/${name}`)];
  for (const entry of entries) {
    const file = path.join(output, entry.replace(/\.scss$/, '.css'));
    await fs.mkdir(path.dirname(file), {recursive: true});
    if (!postcssOnly) {
      const result = sass.compile(path.join(root, entry), {style: 'compressed'});
      await fs.writeFile(file, entry === 'modules/maps.scss'
        ? result.css.replaceAll('url(images/', 'url(/vendor/leaflet/images/') : result.css);
    }
    if (!sassOnly) {
      const result = await postcss([autoprefixer]).process(await fs.readFile(file, 'utf8'),
        {from: file, to: file.replace(/\.css$/, '.min.css'), map: false});
      await fs.writeFile(file.replace(/\.css$/, '.min.css'), result.css);
    }
  }
  await fs.cp('node_modules/leaflet/dist/images', 'resources/public/vendor/leaflet/images', {recursive: true});
  console.log(`Built ${entries.length} CSS entries in ${output}`);
}
await build();
if (watch) {
  // Poll like the existing Sass watcher so bind mounts and editors behave alike.
  const watched = postcssOnly ? output : root;
  async function revision() {
    const files = await fs.readdir(watched, {recursive: true});
    const fontRevision = postcssOnly ? ''
      : `opensans:${(await fs.stat('resources/public/css/opensans.css')).mtimeMs}`;
    return fontRevision + (await Promise.all(files.filter(name => postcssOnly
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
