import test from 'node:test';
import assert from 'node:assert/strict';
import postcss from 'postcss';
import {scopedEditorStyles, editorStyles} from '../../scripts/build/editor-styles.mjs';

test('vendor selectors stay inside the editor and retain keyframe semantics', () => {
  const result = scopedEditorStyles(':root,.dark{--blue-1:#fff;width:32px} @keyframes move{0%{top:16px}}');
  assert.match(result, /\.site-md-editor,\.site-md-editor \.dark/);
  assert.match(result, /--md-editor-blue-1/);
  assert.match(result, /width:2rem/);
  assert.match(result, /0%\{top:1rem/);
});

test('actual vendor CSS cannot overwrite document palette or publish pixel sizing', async () => {
  const css = await editorStyles();
  assert.doesNotMatch(css, /:root|--(?:blue|slate)-\d|\dpx\b/);
  postcss.parse(css).walkRules(rule => {
    if (rule.parent.type === 'atrule' && /keyframes$/i.test(rule.parent.name)) return;
    for (const selector of rule.selectors) assert.ok(selector.startsWith('.site-md-editor'), selector);
  });
});
