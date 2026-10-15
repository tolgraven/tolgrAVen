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
