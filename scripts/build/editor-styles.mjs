/** Scope the editor SDK's stylesheet; never publish its :root palette or resets. */
import fs from 'node:fs/promises';
import postcss from 'postcss';

export function scopedEditorStyles(source) {
  const root = postcss.parse(source);
  root.walkRules(rule => {
    if (rule.parent.type === 'atrule' && /keyframes$/i.test(rule.parent.name)) return;
    rule.selectors = rule.selectors.map(selector => selector.includes(':root')
      ? selector.replaceAll(':root', '.site-md-editor')
      : `.site-md-editor ${selector}`);
  });
  root.walkDecls(declaration => {
    declaration.value = declaration.value.replace(/(-?\d*\.?\d+)px\b/g,
      (_, value) => `${Number(value) / 16}rem`);
    // Keep Radix's blue/slate tokens from replacing the site's own color variables.
    declaration.prop = declaration.prop.replace(/^--(blue|slate)-/, '--md-editor-$1-');
    declaration.value = declaration.value.replace(/--(blue|slate)-/g, '--md-editor-$1-');
  });
  return root.toString();
}

export async function editorStyles() {
  return scopedEditorStyles(await fs.readFile('node_modules/@mdxeditor/editor/dist/style.css', 'utf8'));
}
