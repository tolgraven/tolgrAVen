/** CMS-authored custom-property CSS, appended after the shell defaults. */
import fs from 'node:fs/promises';
import postcss from 'postcss';

export const overridesPath = 'resources/site.overrides.css';
export const maxOverrideBytes = 65536;

export async function readOverrides(file = overridesPath) {
  try {
    const bytes = await fs.readFile(file);
    if (bytes.length > maxOverrideBytes) throw new Error('Site CSS overrides exceed 64 KiB');
    return bytes.toString('utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return '';
    throw error;
  }
}

export function checkedOverrides(css, from = overridesPath) {
  const root = postcss.parse(css, {from});
  root.walkAtRules(rule => {
    if (['import', 'use', 'forward'].includes(rule.name.toLowerCase())) {
      throw rule.error('Site variable overrides cannot import stylesheets or Sass modules');
    }
  });
  root.walkDecls(declaration => {
    if (!declaration.prop.startsWith('--')) {
      throw declaration.error('Site variable overrides must declare CSS custom properties (--name)');
    }
    let parent = declaration.parent;
    while (parent && parent.type !== 'rule') parent = parent.parent;
    if (!parent) throw declaration.error('Put CSS variable declarations inside a selector, such as :root');
  });
  return root.toString();
}
