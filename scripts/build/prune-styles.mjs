/** Production-only pruning, after release and before Optimus fingerprints CSS. */
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import postcss from 'postcss';
import valueParser from 'postcss-value-parser';
import {PurgeCSS} from 'purgecss';

const tokens = text => text.match(/[A-Za-z_][A-Za-z0-9_-]*/g) || [];
const variableNames = text => text.match(/--[A-Za-z_][A-Za-z0-9_-]*/g) || [];
const escaped = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function dynamicNames(content) {
  // Runtime concatenation in application and SDK code: "leaflet-" + kind,
  // "fa-" + glyph, or component + "-enter". Fixed names use the extractor.
  // A producer can supply several classes in one string, e.g.
  // "component-skeleton component-skeleton--" + kind. Inspect complete tokens
  // rather than assuming each string contains a single class.
  const prefixes = content.match(/(?<![A-Za-z0-9_-])[A-Za-z_][A-Za-z0-9_-]*-(?![A-Za-z0-9_-])/g) || [];
  const suffixes = content.match(/(?<![A-Za-z0-9_-])-+[A-Za-z_][A-Za-z0-9_-]*/g) || [];
  return [...new Set([...prefixes, ...suffixes])]
    .map(name => new RegExp(name.startsWith('-') ? `${escaped(name)}$` : `^${escaped(name)}`));
}

function parentAtRule(node, pattern) {
  for (let parent = node.parent; parent; parent = parent.parent) {
    if (parent.type === 'atrule' && pattern.test(parent.name)) return parent;
  }
}

function references(value, animations, includeAnimations = false) {
  const result = new Set();
  valueParser(value).walk(node => {
    if (node.type === 'function' && node.value.toLowerCase() === 'var') {
      const first = node.nodes.find(child => child.type !== 'space' && child.type !== 'comment');
      if (first?.type === 'word' && first.value.startsWith('--')) result.add(first.value);
    }
    if (includeAnimations && animations.has(node.value)) result.add(`@${node.value}`);
  });
  return result;
}

function pruneDefinitions(roots, content, families) {
  const definitions = new Map();
  const used = new Set(variableNames(content));
  const animations = new Set();
  for (const root of roots) root.walkAtRules(/keyframes$/i, rule => animations.add(rule.params.trim()));
  const sourceTokens = new Set(tokens(content));
  for (const name of animations) {
    if (sourceTokens.has(name) || families.some(pattern => pattern.test(name))) used.add(`@${name}`);
  }
  const connect = (name, dependencies) => {
    const previous = definitions.get(name) || new Set();
    for (const dependency of dependencies) previous.add(dependency);
    definitions.set(name, previous);
  };
  for (const root of roots) {
    root.walkDecls(declaration => {
      const animation = parentAtRule(declaration, /keyframes$/i);
      const registration = parentAtRule(declaration, /^property$/i);
      const custom = declaration.prop.startsWith('--');
      const refs = references(declaration.value, animations,
        custom || /(?:^|-)animation(?:-name)?$/.test(declaration.prop));
      if (animation) connect(`@${animation.params.trim()}`, refs);
      else if (registration) connect(registration.params.trim(), refs);
      else if (custom) connect(declaration.prop, refs);
      else for (const reference of refs) used.add(reference);
    });
    root.walkAtRules(rule => {
      if (rule.name !== 'property' && !/keyframes$/i.test(rule.name)) {
        for (const name of variableNames(rule.params)) used.add(name);
      }
    });
    root.walkRules(rule => {
      for (const name of variableNames(rule.selector)) used.add(name);
    });
  }
  // Inline JS styles/readers can refer to a whole family, e.g. "var(--bg-".
  const prefixes = [...used].filter(name => name.endsWith('-'));
  const shorthandPrefixes = [...sourceTokens].filter(name => name.endsWith('-'));
  for (const name of definitions.keys()) {
    // util/<-css-var and :css-var coeffects also accept bare names. Their
    // caller literals/keywords remain in released code without the "--".
    if (name.startsWith('--') && (sourceTokens.has(name.slice(2)) ||
        shorthandPrefixes.some(prefix => name.startsWith(`--${prefix}`)))) used.add(name);
    if (prefixes.some(prefix => name.startsWith(prefix))) used.add(name);
  }
  // One graph across all sheets, all theme overrides and fallbacks. A variable
  // in the shell may be used only by a lazy sheet; unused cycles stay unused.
  const pending = [...used];
  while (pending.length) {
    for (const dependency of definitions.get(pending.pop()) || []) {
      if (!used.has(dependency)) { used.add(dependency); pending.push(dependency); }
    }
  }
  let variables = 0;
  let keyframes = 0;
  for (const root of roots) {
    root.walkDecls(declaration => {
      if (declaration.prop.startsWith('--') && !used.has(declaration.prop)) {
        declaration.remove(); variables++;
      }
    });
    root.walkAtRules(rule => {
      if (rule.name === 'property' && !used.has(rule.params.trim())) rule.remove();
      if (/keyframes$/i.test(rule.name) && !used.has(`@${rule.params.trim()}`)) {
        rule.remove(); keyframes++;
      }
    });
    // Remove empty wrappers after selector/definition pruning.
    root.walkRules(rule => { if (!rule.nodes?.length) rule.remove(); });
    let removed;
    do {
      removed = false;
      root.walkAtRules(rule => {
        if (rule.nodes && !rule.nodes.length) { rule.remove(); removed = true; }
      });
    } while (removed);
  }
  return {variables, keyframes};
}

function pruneFonts(roots, content) {
  let references = content.toLowerCase();
  for (const root of roots) root.walkDecls(declaration => {
    if (!parentAtRule(declaration, /^font-face$/i) &&
        (declaration.prop.startsWith('--') || /^(?:font|font-family)$/.test(declaration.prop))) {
      references += '\n' + declaration.value.toLowerCase();
    }
  });
  const normalized = references.replace(/["']/g, '').replace(/\s+/g, ' ');
  let fonts = 0;
  for (const root of roots) root.walkAtRules(/^font-face$/i, rule => {
    const family = rule.nodes.find(node => node.type === 'decl' && node.prop === 'font-family');
    const name = family?.value.toLowerCase().replace(/["']/g, '').replace(/\s+/g, ' ').trim();
    if (name && !normalized.includes(name)) { rule.remove(); fonts++; }
  });
  return fonts;
}

export async function pruneStyles(sheets, sources) {
  const content = sources.join('\n');
  const families = dynamicNames(content);
  // Attribute values are lifecycle/application data (theme, presence, ARIA).
  // Retain those states while pruning their unused class/ID owners normally.
  const attributes = [...new Set(sheets.flatMap(sheet =>
    [...sheet.css.matchAll(/\[([A-Za-z_][A-Za-z0-9_-]*)/g)].map(match => match[1])))];
  const purged = await new PurgeCSS().purge({
    content: [{raw: content, extension: 'js'}],
    css: sheets.map(sheet => ({raw: sheet.css})),
    defaultExtractor: tokens,
    // Pseudo-only :is/:where branches and document roots exist independently
    // of component class literals. Keep states, including the universal selector.
    safelist: {standard: ['html', 'body', '*', /^:/, ...families]},
    dynamicAttributes: attributes,
    rejected: true,
    // PurgeCSS's per-file variable pass can remove shell definitions before
    // later sheets reference them. Resolve definitions across all sheets below.
    variables: false,
    keyframes: false,
    fontFace: false,
  });
  const roots = purged.map((sheet, index) => postcss.parse(sheet.css, {from: sheets[index].file}));
  const counts = pruneDefinitions(roots, content, families);
  const fonts = pruneFonts(roots, content);
  // Definitions used only by a removed font face can now become unreachable.
  const remaining = pruneDefinitions(roots, content, families);
  counts.variables += remaining.variables;
  counts.keyframes += remaining.keyframes;
  return {sheets: roots.map((root, index) => ({...sheets[index], css: root.toString()})),
    ...counts, fonts, selectors: purged.flatMap(sheet => sheet.rejected || [])};
}

function publicFile(root, asset, extension) {
  if (typeof asset !== 'string' || !asset.startsWith('/') || !asset.endsWith(extension)) {
    throw new Error(`Invalid production ${extension} asset path`);
  }
  const publicRoot = path.resolve(root, 'public');
  const file = path.resolve(publicRoot, `.${asset}`);
  if (!file.startsWith(publicRoot + path.sep)) throw new Error('Asset escapes the production resource root');
  return file;
}

async function serverSources() {
  const result = [];
  for (const directory of ['src/backend', 'env/prod/clj']) {
    const files = await fs.readdir(directory, {recursive: true}).catch(error => {
      if (error.code === 'ENOENT') return [];
      throw error;
    });
    for (const file of files.filter(file => /\.clj[c]?$/.test(file)).sort()) {
      result.push(await fs.readFile(path.join(directory, file), 'utf8'));
    }
  }
  return result;
}

export async function pruneProduction(root, plan) {
  if (!Array.isArray(plan.styles) || !plan.styles.length ||
      !Array.isArray(plan.scripts) || !plan.scripts.length ||
      typeof plan.renderer !== 'string' || !plan.renderer.endsWith('.js')) {
    throw new Error('Production pruning requires selected CSS, browser JavaScript and the SSR renderer');
  }
  const sheets = await Promise.all([...new Set(plan.styles)].map(async asset => {
    const file = publicFile(root, asset, '.css');
    return {file, css: await fs.readFile(file, 'utf8')};
  }));
  const sources = await Promise.all(plan.scripts.map(asset => fs.readFile(publicFile(root, asset, '.js'), 'utf8')));
  const renderer = await fs.readFile(plan.renderer, 'utf8');
  if (!renderer.trim()) throw new Error('Released SSR renderer is empty; refusing CSS pruning');
  sources.push(renderer, ...await serverSources());
  if (!sources[0]?.trim()) throw new Error('Released JavaScript is empty; refusing CSS pruning');
  const result = await pruneStyles(sheets, sources);
  // Finish parsing/analysis before replacing any build output. The caller only
  // publishes its Optimus manifest after this command succeeds.
  for (const sheet of result.sheets) await fs.writeFile(sheet.file, sheet.css);
  const before = sheets.reduce((total, sheet) => total + Buffer.byteLength(sheet.css), 0);
  const after = result.sheets.reduce((total, sheet) => total + Buffer.byteLength(sheet.css), 0);
  const report = {before, after, ...Object.fromEntries(['variables', 'keyframes', 'fonts', 'selectors'].map(key => [key, result[key]]))};
  await fs.mkdir(path.join(root, 'optimus'), {recursive: true});
  await fs.writeFile(path.join(root, 'optimus', 'css-pruning.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(`Pruned production CSS: ${before} -> ${after} bytes; ${result.variables} variable declarations, ${result.keyframes} keyframes, ${result.fonts} font faces, ${result.selectors.length} selectors removed`);
  return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 4) throw new Error('Expected resource root and JSON production asset plan');
  await pruneProduction(process.argv[2], JSON.parse(process.argv[3]));
}
