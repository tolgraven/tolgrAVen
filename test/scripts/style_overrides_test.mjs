import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {checkedOverrides, readOverrides, maxOverrideBytes} from '../../scripts/build/style-overrides.mjs';

test('ordinary CSS variables retain selectors, comments, themes and native CSS values', () => {
  const css = `/* Site theme */
:root { --space: 1.5rem; --space-lg: calc(2 * var(--space)); --line-width: 0.125rem; }
html[data-theme="light"] { --bg-1: #f0eee8; }
@media (min-width: 50rem) { :root { --space: 2rem; } }
@supports (color: oklch(70% 0.1 120)) { :root { --accent: oklch(70% 0.1 120); } }`;
  assert.equal(checkedOverrides(css), css);
  assert.equal(checkedOverrides(''), '');
});

test('invalid CSS, ordinary declarations and external imports fail before shell output is touched', () => {
  for (const css of [':root { --space: 1rem;', ':root { color: red; }', '--space: 1rem;',
                      '@import "https://example.com/theme.css";', '@use "theme";', '@forward "theme";']) {
    assert.throws(() => checkedOverrides(css), /overrides|Unclosed/i);
  }
});

test('absent/empty snapshots use defaults and oversized snapshots fail', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'site-css-'));
  const file = path.join(directory, 'overrides.css');
  try {
    assert.equal(await readOverrides(file), '');
    await fs.writeFile(file, '');
    assert.equal(await readOverrides(file), '');
    await fs.writeFile(file, ':root { --space: 1rem; }');
    assert.equal(await readOverrides(file), ':root { --space: 1rem; }');
    await fs.writeFile(file, ' '.repeat(maxOverrideBytes + 1));
    await assert.rejects(readOverrides(file), /64 KiB/);
  } finally { await fs.rm(directory, {recursive: true, force: true}); }
});
