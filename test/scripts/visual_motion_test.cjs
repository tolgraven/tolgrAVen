const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {test} = require('node:test');
const vm = require('node:vm');

function fixture() {
  const window = {addEventListener() {}};
  vm.runInNewContext(readFileSync('test/browser/visual.js', 'utf8'), {window});
  return window.visualFixture;
}

test('readiness waits eager and visible images while respecting the native offscreen lazy gate', () => {
  const {imageRequired} = fixture();
  const viewport = {innerWidth: 400, innerHeight: 800};
  const image = {loading: 'lazy', complete: false, currentSrc: '',
    getBoundingClientRect: () => ({width: 40, height: 40, top: 1800, bottom: 1840, left: 0, right: 40})};
  assert.equal(imageRequired(image, viewport), false);
  image.loading = 'eager';
  assert.equal(imageRequired(image, viewport), true);
  image.loading = 'lazy'; image.currentSrc = '/selected-cached.webp'; image.naturalWidth = 32;
  assert.equal(imageRequired(image, viewport), false, 'Chrome can select a cached candidate without releasing an offscreen lazy gate');
  image.currentSrc = ''; image.complete = true;
  assert.equal(imageRequired(image, viewport), true, 'Loaded or failed lazy images still receive decode/error checks');
  image.complete = false;
  image.getBoundingClientRect = () => ({width: 40, height: 40, top: 700, bottom: 740, left: 0, right: 40});
  assert.equal(imageRequired(image, viewport), true, 'A later scroll must reacquire readiness');
});

test('native CSS definitions are captured before settlement, with pseudo targets and complete cleanup', () => {
  const {createMotionObserver} = fixture();
  const element = {nodeType: 1, localName: 'article', tagName: 'ARTICLE', parentElement: null};
  const listeners = new Map(), frames = new Map();
  let active = [], disconnected = false, next = 0;
  class CSSAnimation {}
  class CSSTransition {}
  const doc = {getAnimations: () => active, querySelectorAll: () => [element],
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: (name, fn) => { assert.equal(listeners.get(name), fn); listeners.delete(name); }};
  const win = {document: doc, CSSAnimation, CSSTransition,
    MutationObserver: class {observe() {} disconnect() {disconnected = true;}},
    requestAnimationFrame: fn => { frames.set(++next, fn); return next; },
    cancelAnimationFrame: id => frames.delete(id),
    getComputedStyle: (_, pseudo) => ({getPropertyValue: name => name === 'animation-duration' && pseudo === '::before' ? '2s' : ''})};
  const observer = createMotionObserver(win);
  const animation = Object.assign(new CSSAnimation(), {animationName: 'entrance', playState: 'running', playbackRate: 1,
    currentTime: 19, startTime: 8000,
    effect: {target: element, pseudoElement: '::before', composite: 'replace', iterationComposite: 'replace',
      getTiming: () => ({duration: 2000, delay: 1500, iterations: Infinity, easing: 'ease-in'}),
      getKeyframes: () => [{offset: 0, opacity: '0', easing: 'linear'}, {offset: 1, opacity: '1', easing: 'linear'}]}});
  active = [animation];
  listeners.get('animationstart')({type: 'animationstart', target: element, animationName: 'entrance', pseudoElement: '::before'});
  // Once finished and removed, the original timing/keyframe contract survives.
  active = [];
  observer.phase('action');
  const transition = Object.assign(new CSSTransition(), {transitionProperty: 'height', playState: 'running', playbackRate: 1,
    effect: {...animation.effect, pseudoElement: null, getTiming: () => ({duration: 300, iterations: 1})}});
  active = [transition]; observer.sample(); observer.sample();
  const snapshot = JSON.parse(JSON.stringify(observer.snapshot()));
  assert.equal(snapshot.triggered.length, 2, 'Repeated observation is not a new trigger');
  const entry = snapshot.triggered.find(value => value.kind === 'animation');
  assert.equal(entry.pseudo, '::before'); assert.equal(entry.timing.delay, 1500);
  assert.equal(entry.timing.iterations, 'Infinity');
  assert.equal(entry.keyframes[1].opacity, '1');
  assert.equal('startTime' in entry, false); assert.equal('currentTime' in entry, false);
  assert.equal(snapshot.triggered.find(value => value.kind === 'transition').phase, 'action');
  assert.ok(snapshot.styles.some(values => values[snapshot.properties.indexOf('animation-duration')] === '2s'));
  assert.equal(snapshot.declarations.length, 9, 'Element plus supported native pseudo contracts');
  assert.deepEqual(snapshot.gaps, []);
  observer.stop(); assert.equal(disconnected, true); assert.equal(listeners.size, 0); assert.equal(frames.size, 0);
});

test('unobservable native starts are explicit gaps rather than motion passes', () => {
  const {createMotionObserver} = fixture();
  const callbacks = {};
  const win = {document: {getAnimations: () => [], querySelectorAll: () => [],
    addEventListener: (name, fn) => {callbacks[name] = fn;}, removeEventListener() {}},
    CSSAnimation: class {}, CSSTransition: class {},
    MutationObserver: class {observe() {} disconnect() {}}, requestAnimationFrame: () => 1, cancelAnimationFrame() {}};
  const observer = createMotionObserver(win);
  callbacks.transitionrun({type: 'transitionrun', target: {nodeType: 1, localName: 'p', parentElement: null}, propertyName: 'opacity'});
  assert.match(observer.snapshot().gaps[0], /no observable definition/);
  observer.stop();
});

test('dormant hover, conditional motion and keyframes remain comparable before activation', () => {
  const {createMotionObserver} = fixture();
  const style = values => Object.assign(Object.keys(values), {
    getPropertyValue: name => values[name]?.[0] || '',
    getPropertyPriority: name => values[name]?.[1] || ''
  });
  const keyframe = {keyText: '100%', style: style({opacity: ['1', '']})};
  const hover = {selectorText: 'article:hover::before', style: style({
    'animation-name': ['dormant', ''], 'animation-duration': ['2s', 'important']})};
  const media = {type: 4, conditionText: '(width > 40rem)', cssRules: [hover]};
  const sheet = {href: '/native.css', media: {mediaText: ''}, cssRules: [
    media, {type: 7, name: 'dormant', cssRules: [keyframe]}]};
  const win = {document: {styleSheets: [sheet], getAnimations: () => [], querySelectorAll: () => [],
    addEventListener() {}, removeEventListener() {}},
    CSSAnimation: class {}, CSSTransition: class {},
    MutationObserver: class {observe() {} disconnect() {}}, requestAnimationFrame: () => 1, cancelAnimationFrame() {}};
  const observer = createMotionObserver(win);
  const before = JSON.parse(JSON.stringify(observer.snapshot()));
  assert.deepEqual(before.triggered, [], 'No hover or animation needs to be fabricated');
  assert.deepEqual(before.gaps, []);
  assert.equal(before.sourceRules[0].selector, 'article:hover::before');
  assert.deepEqual(before.sourceRules[0].properties['animation-duration'], ['2s', 'important']);
  assert.equal(before.sourceRules[0].scope[1].condition, '(width > 40rem)');
  assert.equal(before.sourceRules[1].frames[0].properties.opacity[0], '1');
  keyframe.style = style({opacity: ['0.5', '']});
  const after = JSON.parse(JSON.stringify(observer.snapshot()));
  assert.deepEqual(after.declarations, before.declarations);
  assert.deepEqual(after.triggered, before.triggered);
  assert.notDeepEqual(after.sourceRules, before.sourceRules, 'A dormant keyframe edit cannot hide behind identical rendered motion');
  observer.stop();
});

test('parked CSS clocks record actual pointer/focus activations and release every wake listener', () => {
  const {createMotionObserver} = fixture();
  const element = {nodeType: 1, localName: 'figure', parentElement: null};
  const listeners = new Map(), frames = new Map();
  let disconnected = false, next = 0;
  class CSSAnimation {}
  class CSSTransition {}
  const clock = (name, state, duration = 1000) => Object.assign(new CSSAnimation(), {
    animationName: name, playState: state, playbackRate: 1,
    effect: {target: element, pseudoElement: null, composite: 'replace', iterationComposite: 'replace',
      getTiming: () => ({duration, delay: 0, iterations: 1}),
      getKeyframes: () => [{offset: 0, opacity: '0'}, {offset: 1, opacity: '1'}]}
  });
  const hovered = clock('hover-backdrop', 'paused'), idle = clock('idle-clock', 'idle');
  const active = [hovered, idle];
  const doc = {getAnimations: () => active, querySelectorAll: () => [],
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: (name, fn) => { assert.equal(listeners.get(name), fn); listeners.delete(name); }};
  const win = {document: doc, CSSAnimation, CSSTransition,
    MutationObserver: class {observe() {} disconnect() {disconnected = true;}},
    requestAnimationFrame: fn => { frames.set(++next, fn); return next; },
    cancelAnimationFrame: id => frames.delete(id)};
  const flushFrame = () => {
    const pending = [...frames.values()]; frames.clear();
    pending.forEach(callback => callback());
  };
  const observer = createMotionObserver(win);
  const savedWake = listeners.get('pointerover');
  // Native animationstart can be dispatched when an initially paused object
  // is created; its available definition proves this is parked, not a gap.
  listeners.get('animationstart')({type: 'animationstart', target: element, animationName: 'hover-backdrop'});
  flushFrame();
  assert.equal(observer.snapshot().triggered.length, 0, 'Paused and idle creation is not activation');
  assert.equal(frames.size, 0, 'Parked clocks do not retain a frame loop');
  observer.phase('action');
  hovered.playState = 'running';
  listeners.get('pointerover')({type: 'pointerover', target: element});
  listeners.get('focusin')({type: 'focusin', target: element});
  observer.sample();
  assert.equal(observer.snapshot().triggered.length, 1, 'Repeated wakes while running are one activation');
  hovered.playState = 'paused';
  listeners.get('pointerout')({type: 'pointerout', target: element});
  flushFrame();
  assert.equal(frames.size, 0);
  hovered.playState = 'running';
  listeners.get('focusin')({type: 'focusin', target: element});
  assert.equal(observer.snapshot().triggered.length, 2, 'Focus resumes the same native object');
  hovered.playState = 'finished'; observer.sample(); observer.sample();
  assert.equal(observer.snapshot().triggered.length, 2, 'Finishing is not another activation');
  const instant = clock('instant-entrance', 'finished', 0);
  active.push(instant);
  listeners.get('animationstart')({type: 'animationstart', target: element, animationName: 'instant-entrance'});
  idle.playState = 'running';
  listeners.get('pointerdown')({type: 'pointerdown', target: element});
  const snapshot = JSON.parse(JSON.stringify(observer.snapshot()));
  assert.equal(snapshot.triggered.length, 4);
  assert.equal(snapshot.triggered.filter(entry => entry.name === 'hover-backdrop').length, 2);
  assert.ok(snapshot.triggered.every(entry => entry.phase === 'action'));
  assert.equal(snapshot.triggered.find(entry => entry.name === 'instant-entrance').timing.duration, 0);
  assert.deepEqual(snapshot.gaps, []);
  assert.equal(/currentTime|startTime|playState/.test(JSON.stringify(snapshot.triggered)), false,
    'Activation comparisons contain definitions without sampled progress or clocks');
  observer.stop();
  assert.equal(disconnected, true); assert.equal(listeners.size, 0); assert.equal(frames.size, 0);
  savedWake({type: 'pointerover', target: element});
  assert.equal(frames.size, 0, 'An already queued wake cannot resurrect a stopped observer');
});

function replyEvidence(samples) {
  const context = {document: {querySelector: () => ({style: {}})}, location: {search: ''}, URLSearchParams};
  vm.runInNewContext(readFileSync('test/browser/motion.js', 'utf8') + '\nthis.inspectReplies = replyMotionEvidence;', context);
  return JSON.parse(JSON.stringify(context.inspectReplies(samples)));
}

function replyFrames(heights, opacity, translation) {
  return heights.map((values, index) => ({time: index * 16, rows: values.map((height, row) => ({
    id: row, height, opacity: opacity[index], translateY: translation[index],
    delay: row * 48, bodyDelay: row * 48, duration: 180, bodyDuration: 180,
    properties: ['grid-template-rows'], bodyProperties: ['opacity', 'transform'], inViewport: true, settling: false
  }))}));
}

test('reply motion evidence requires real intermediate row growth and body fade/slide', () => {
  const evidence = replyEvidence(replyFrames([[0, 0], [5, 0], [25, 5], [65, 30], [100, 70], [100, 100]],
    [0, .1, .25, .65, 1, 1], [-12, -10, -8, -4, 0, 0]));
  assert.ok(evidence.every(row => row.visible && row.continuous && row.fading && row.sliding && row.settled));
  assert.ok(evidence[1].started > evidence[0].started);
  assert.ok(evidence.every(row => row.intermediateHeights.length >= 2));
  assert.equal(evidence[1].delay, 48);
});

test('reply motion evidence rejects height teleports and opacity-only layout jumps', () => {
  const jumped = replyEvidence(replyFrames([[0, 0], [100, 100], [100, 100]], [0, .5, 1], [-12, -6, 0]));
  assert.ok(jumped.every(row => !row.continuous));
  const reversed = replyEvidence(replyFrames([[0, 0], [5, 0], [25, 5], [20, 10], [65, 70], [100, 100]],
    [0, .1, .25, .65, 1, 1], [-12, -10, -8, -4, 0, 0]));
  assert.equal(reversed[0].continuous, false, 'A shrinking intrinsic box is not continuous expansion');
  assert.equal(reversed[1].continuous, true);
  const staticBody = replyEvidence(replyFrames([[0, 0], [5, 0], [25, 5], [65, 30], [100, 70], [100, 100]],
    [1, 1, 1, 1, 1, 1], [0, 0, 0, 0, 0, 0]));
  assert.ok(staticBody.every(row => row.continuous && !row.fading && !row.sliding));
});
