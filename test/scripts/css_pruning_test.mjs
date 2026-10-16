import assert from 'node:assert/strict';
import {test} from 'node:test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {pruneStyles, pruneProduction} from '../../scripts/build/prune-styles.mjs';

const sheet = (file, css) => ({file, css});

test('selectors, dynamic SDK families, states and cross-sheet variable dependencies', async () => {
  const result = await pruneStyles([
    sheet('shell.css', ':root { --alias: var(--base); --base: red; --dead: 1; --cycle-a: var(--cycle-b); --cycle-b: var(--cycle-a); --inline: var(--inline-base); --inline-base: 2; --bg-1: blue; --bg-2: green; } html[data-theme="dark"] { --base: black; } .obsolete { color: var(--dead); } .used:not(.disabled) { display: flex; } @media (min-width: 10rem) { .obsolete-too { display: grid; } }'),
    sheet('lazy.css', '.lazy { color: var( --alias, var(--fallback)); } :root { --fallback: pink; } .leaflet-popup { display: block; } .fa-house { display: inline; } .motion-enter { opacity: 0; } .component-skeleton--box { height: 1rem; } .neovim__cursor--vertical { width: 1em; } .never { display: none; }'),
  ], ['[:div.used.lazy {:style {"--inline" "1"}}]', '"leaflet-" + kind; "fa-" + name; component + "-enter"; "var(--bg-" + level; "component-skeleton component-skeleton--" + kind; "neovim__cursor neovim__cursor--" + shape;']);
  const [shell, lazy] = result.sheets.map(value => value.css);
  assert.doesNotMatch(shell, /--dead|--cycle-|obsolete|@media/);
  assert.match(shell, /--alias/); assert.match(shell, /--base: black/);
  assert.match(shell, /--inline-base/); assert.match(shell, /--bg-1/); assert.match(shell, /--bg-2/);
  assert.match(shell, /data-theme="dark"/); assert.match(shell, /\.used/);
  assert.match(lazy, /--fallback/); assert.match(lazy, /leaflet-popup/);
  assert.match(lazy, /fa-house/); assert.match(lazy, /motion-enter/);
  assert.match(lazy, /component-skeleton--box/); assert.match(lazy, /neovim__cursor--vertical/);
  assert.doesNotMatch(lazy, /\.never/);
});

test('keyframes and registrations resolve through variables across theme overrides', async () => {
  const result = await pruneStyles([
    sheet('first.css', ':root { --animation: var(--motion); --motion: fade 1s; --removed: unused 1s; --opacity: 1; } @property --opacity { syntax: "<number>"; inherits: false; initial-value: 0; } @property --unregistered { syntax: "<number>"; inherits: false; initial-value: 0; } @keyframes fade { from { opacity: var(--opacity); } } @keyframes unused { to { opacity: var(--unused-keyframe-var); } } @keyframes script-animation { to { opacity: 1; } }'),
    sheet('second.css', '.animated { animation: var(--animation); } html[data-theme="dark"] { --motion: dark-fade 1s; } @keyframes dark-fade { from { opacity: 0; } }'),
  ], ['[:div.animated] "script-animation"']);
  const css = result.sheets.map(value => value.css).join('\n');
  assert.match(css, /@keyframes fade/); assert.match(css, /@keyframes dark-fade/);
  assert.match(css, /@keyframes script-animation/); assert.match(css, /@property --opacity/);
  assert.doesNotMatch(css, /--removed|@keyframes unused|--unused-keyframe-var|--unregistered/);
});

test('data attributes, pseudo functions, escaped selectors and ignore annotations', async () => {
  const result = await pruneStyles([sheet('selectors.css', '.used:is(:hover, :focus) { color: red; } .used[data-status="pending"] { opacity: .5; } .unused { color: blue; } /*! purgecss start ignore */ .opaque-runtime { color: green; } /*! purgecss end ignore */')], ['"used"']);
  const css = result.sheets[0].css;
  assert.match(css, /:is/); assert.match(css, /data-status="pending"/);
  assert.match(css, /opaque-runtime/); assert.doesNotMatch(css, /\.unused/);
});

test('production IO rejects incomplete inputs and never touches development sheets', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'css-pruning-'));
  try {
    await fs.mkdir(path.join(root, 'public'), {recursive: true});
    await fs.writeFile(path.join(root, 'public/main.css'), '.used { color: red; } .ssr-only { color: green; } .obsolete { color: blue; }');
    await fs.writeFile(path.join(root, 'public/dev.css'), '.dev-only { color: green; }');
    const plan = {styles: ['/main.css'], scripts: ['/main.js'], renderer: path.join(root, 'renderer.js')};
    await assert.rejects(pruneProduction(root, plan), /ENOENT/);
    assert.match(await fs.readFile(path.join(root, 'public/main.css'), 'utf8'), /obsolete/);
    await fs.writeFile(path.join(root, 'public/main.js'), '"used"');
    await fs.writeFile(plan.renderer, '"ssr-only"');
    await assert.rejects(pruneProduction(root, {...plan, styles: ['/../outside.css']}), /escapes/);
    const result = await pruneProduction(root, plan);
    assert.ok(result.after < result.before);
    assert.doesNotMatch(await fs.readFile(path.join(root, 'public/main.css'), 'utf8'), /obsolete/);
    assert.match(await fs.readFile(path.join(root, 'public/main.css'), 'utf8'), /ssr-only/);
    assert.match(await fs.readFile(path.join(root, 'public/dev.css'), 'utf8'), /dev-only/);
  } finally { await fs.rm(root, {recursive: true, force: true}); }
});


test('font faces keep CSS aliases and runtime font requests, dropping unused faces', async () => {
  const result = await pruneStyles([
    sheet('fonts.css', '@font-face { font-family: "Used Font"; src: url(used.woff2); } @font-face { font-family: "Runtime Font"; src: url(runtime.woff2); } @font-face { font-family: "Unused Font"; src: url(unused.woff2); } :root { --face: "Used Font"; } .used { font-family: var(--face); }'),
  ], ['"used"; document.fonts.load("1rem Runtime Font")']);
  assert.match(result.sheets[0].css, /used\.woff2/);
  assert.match(result.sheets[0].css, /runtime\.woff2/);
  assert.doesNotMatch(result.sheets[0].css, /unused\.woff2/);
  assert.equal(result.fonts, 1);
});


test('existing CSS-var helpers retain bare names and dynamically selected bare prefixes', async () => {
  const result = await pruneStyles([sheet('vars.css', ':root { --reader-only: var(--reader-base); --reader-base: 2rem; --range-1: 1; --range-2: 2; --unreferenced: 3; }')],
    ['(inject-cofx :css-var [:reader-only])', 'readCssVar("range-" + index)']);
  assert.match(result.sheets[0].css, /--reader-only/);
  assert.match(result.sheets[0].css, /--reader-base/);
  assert.match(result.sheets[0].css, /--range-1/);
  assert.match(result.sheets[0].css, /--range-2/);
  assert.doesNotMatch(result.sheets[0].css, /--unreferenced/);
});
