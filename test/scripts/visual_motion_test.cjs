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

test('native form-control types are not pseudo targets, including parked start matching', () => {
  const {createMotionObserver} = fixture();
  const listeners = new Map();
  class CSSAnimation {}
  class CSSTransition {}
  const cases = [
    {element: {nodeType: 1, localName: 'button', type: 'button'}, pseudo: null},
    {element: {nodeType: 1, localName: 'input', type: 'text'}, pseudo: null},
    {element: {nodeType: 1, localName: 'span'}, wrapper: '::before', pseudo: '::before'},
    {element: {nodeType: 1, localName: 'figure'}, wrapper: '::before', explicit: '::after', pseudo: '::after'}
  ];
  const active = cases.map(value => Object.assign(new CSSTransition(), {
    transitionProperty: 'opacity', playState: 'paused', playbackRate: 1,
    effect: {target: value.wrapper ? {element: value.element, type: value.wrapper} : value.element,
      pseudoElement: value.explicit || null, composite: 'replace', iterationComposite: 'replace',
      getTiming: () => ({duration: 150, iterations: 1}),
      getKeyframes: () => [{offset: 0, opacity: '0'}, {offset: 1, opacity: '1'}]}
  }));
  const win = {document: {getAnimations: () => active, querySelectorAll: () => [],
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: name => listeners.delete(name)},
    CSSAnimation, CSSTransition,
    MutationObserver: class {observe() {} disconnect() {}},
    requestAnimationFrame: () => 1, cancelAnimationFrame() {}};
  const observer = createMotionObserver(win);
  const starts = () => cases.forEach(value => listeners.get('transitionrun')({
    type: 'transitionrun', target: value.element, propertyName: 'opacity', pseudoElement: value.pseudo || ''
  }));
  try {
    starts();
    assert.deepEqual(JSON.parse(JSON.stringify(observer.snapshot().gaps)), [],
      'An available parked definition matches the native start target');
    active.forEach(animation => { animation.playState = 'running'; });
    listeners.get('pointerover')();
    const snapshot = JSON.parse(JSON.stringify(observer.snapshot()));
    assert.deepEqual(snapshot.gaps, [], 'Captured native starts match their real element or pseudo wrapper');
    assert.deepEqual(snapshot.triggered.map(value => [value.target, value.pseudo]).sort(),
      cases.map(value => [value.element.localName + ':nth-child(1)', value.pseudo]).sort());
  } finally { observer.stop(); }
  assert.equal(listeners.size, 0);
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

function startFixture() {
  const {createMotionObserver} = fixture(), listeners = new Map(), frames = new Map();
  let disconnected = false, nextFrame = 0;
  class CSSAnimation {}
  class CSSTransition {}
  let active = [];
  const win = {document: {getAnimations: () => active, querySelectorAll: () => [],
    addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name)},
    CSSAnimation, CSSTransition,
    MutationObserver: class {observe() {} disconnect() { disconnected = true; }},
    requestAnimationFrame: callback => { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame: id => frames.delete(id)};
  const observer = createMotionObserver(win);
  const clock = (target, kind = 'transition', pseudo = null, state = 'running') => Object.assign(
    kind === 'animation' ? new CSSAnimation() : new CSSTransition(), {
      animationName: kind === 'animation' ? 'opacity' : undefined,
      transitionProperty: kind === 'transition' ? 'opacity' : undefined,
      playState: state, playbackRate: 1,
      effect: {target, pseudoElement: pseudo, getTiming: () => ({duration: 150, iterations: 1}),
        getKeyframes: () => [{offset: 0, opacity: '0'}, {offset: 1, opacity: '1'}]}
    });
  const event = (target, type = 'transitionrun', pseudoElement = '', elapsedTime = 0) => listeners.get(type)({
    type, target, pseudoElement, elapsedTime, animationName: type.startsWith('animation') ? 'opacity' : undefined,
    propertyName: type.startsWith('transition') ? 'opacity' : undefined
  });
  return {observer, clock, event, active(values) { active = values; }, listeners, frames,
    get disconnected() { return disconnected; }};
}

test('a captured native start cannot excuse a later missing occurrence at the same target and property', () => {
  for (const retained of [false, true]) {
    const f = startFixture(), target = {nodeType: 1, localName: 'button', type: 'button'};
    const kind = retained ? 'animation' : 'transition', event = retained ? 'animationstart' : 'transitionrun';
    const first = f.clock(target, kind);
    if (retained) first.effect.getTiming = () => ({duration: 150, iterations: 1, fill: 'both'});
    try {
      f.active([first]); f.event(target, event);
      first.playState = 'finished'; f.observer.sample();
      if (!retained) f.active([]);
      f.observer.phase('action');
      // The second short clock disappeared before its queued native start.
      // An old fill-both object can still be present at the identical target.
      f.event(target, event);
      const state = f.observer.snapshot();
      assert.equal(state.triggered.length, 1);
      assert.equal(state.gaps.length, 1, retained ? 'A retained finished object already consumed its start' : 'The old object disappeared');
      assert.match(state.gaps[0], /no observable definition.*button.*opacity/);
      if (retained) {
        const second = f.clock(target, kind);
        f.active([first, second]); f.event(target, event);
        assert.equal(f.observer.snapshot().triggered.length, 2, 'A new observed object has its own definition');
        assert.equal(f.observer.snapshot().gaps.length, 1, 'Its receipt is not confused with the retained old object');
      }
    } finally { f.observer.stop(); }
  }
});

test('queued native starts consume one captured object receipt despite changed paths, never another DOM instance', () => {
  const f = startFixture(), parent = {nodeType: 1, localName: 'section', children: []};
  const target = {nodeType: 1, localName: 'button', parentElement: parent}, other = {...target};
  parent.children = [target, other];
  try {
    f.active([f.clock(target)]); f.observer.sample(); f.active([]);
    parent.children = [other, target];
    f.observer.phase('action'); f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 0, 'A queued native event retains its actual DOM owner');
    f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 1, 'The receipt is consumed exactly once');
    f.active([f.clock(target)]); f.observer.sample(); f.active([]);
    const replacement = {...target}; parent.children = [other, replacement];
    f.event(replacement);
    assert.equal(f.observer.snapshot().gaps.length, 2, 'A replacement at the same path cannot borrow a receipt');
  } finally { f.observer.stop(); }
});

test('queued animation receipts cannot acknowledge transition or different pseudo events and retire on cancellation', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'article'};
  try {
    f.active([f.clock(target, 'animation', '::before')]); f.observer.sample(); f.active([]);
    f.event(target, 'transitionrun', '::before');
    f.event(target, 'animationstart', '::after');
    assert.equal(f.observer.snapshot().gaps.length, 2);
    f.event(target, 'animationstart', '::before');
    assert.equal(f.observer.snapshot().gaps.length, 2, 'The original queued event still has its own receipt');
    f.active([f.clock(target)]); f.observer.sample(); f.active([]);
    f.event(target, 'transitioncancel'); f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 3, 'Canceled unclaimed receipts cannot authorize later starts');
  } finally { f.observer.stop(); }
});

test('an old native cancel preserves the later captured reverse occurrence and unrelated receipts', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const other = {nodeType: 1, localName: 'aside'};
  const first = f.clock(target), reverse = f.clock(target);
  first.effect.getTiming = () => ({duration: 2200, iterations: 1});
  reverse.effect.getTiming = () => ({duration: 37.94676733614284, iterations: 1});
  try {
    f.active([first]); f.event(target);
    f.active([reverse, f.clock(other), f.clock(target, 'transition', '::before')]);
    f.observer.sample();
    reverse.playState = 'finished'; f.active([]);
    // Native Strava ordering: old start, reverse receipt, old cancel,
    // queued reverse start, reverse end. Its old cancel is not the reverse end.
    f.event(target, 'transitioncancel', '', 0.068215);
    f.event(target);
    f.event(target, 'transitionend', '', 0.03794676733614284);
    f.event(other); f.event(target, 'transitionrun', '::before');
    assert.equal(f.observer.snapshot().gaps.length, 0,
      'The reverse and unrelated target/pseudo receipts retain their own starts');
    assert.equal(f.observer.snapshot().triggered.length, 4);
    f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 1,
      'A later genuinely missing occurrence cannot reuse the reverse receipt');
  } finally { f.observer.stop(); }
  assert.equal(f.listeners.size, 0);
  assert.equal(f.frames.size, 0);
  assert.equal(f.disconnected, true);
});

test('a pre-observation receipt cannot survive its completion behind a later acknowledged animation', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const beforeObservation = f.clock(target, 'animation');
  const acknowledged = f.clock(target, 'animation');
  try {
    // Same-name CSS animations may overlap. A's start preceded observation;
    // B's queued start can precede A's queued end even when A is unavailable.
    f.active([beforeObservation]); f.observer.sample(); f.active([]);
    f.active([acknowledged]); f.event(target, 'animationstart');
    assert.equal(f.observer.snapshot().gaps.length, 0);
    f.active([]); f.event(target, 'animationend');
    f.event(target, 'animationstart');
    assert.equal(f.observer.snapshot().gaps.length, 1,
      'A later genuinely missing start cannot borrow the completed pre-observation receipt');
  } finally { f.observer.stop(); }
});

test('an unacknowledged pointer resume keeps its original receipt ahead of later occurrences', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const first = f.clock(target, 'animation'), later = f.clock(target, 'animation');
  try {
    f.active([first]); f.observer.sample();
    first.playState = 'paused'; f.listeners.get('pointerout')();
    f.active([later]); f.observer.sample();
    first.playState = 'running'; f.active([first]); f.listeners.get('pointerover')();
    f.active([later]); f.event(target, 'animationstart');
    f.active([]); f.event(target, 'animationend');
    f.event(target, 'animationstart');
    assert.equal(f.observer.snapshot().gaps.length, 1,
      'Resuming the unclaimed old clock cannot move its receipt behind a later acknowledged clock');
    assert.equal(f.observer.snapshot().triggered.length, 3,
      'The actual resume remains an observed activation');
  } finally { f.observer.stop(); }
});

test('a completion without an observed start retires only its oldest unavailable receipt', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  try {
    f.active([f.clock(target)]); f.observer.sample(); f.active([]);
    f.active([f.clock(target)]); f.observer.sample(); f.active([]);
    f.event(target, 'transitioncancel');
    f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 0,
      'One cancel cannot retire both an old unclaimed clock and a later captured clock');
    f.event(target, 'transitionend'); f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 1,
      'The retired old receipt cannot authorize another missing start');
  } finally { f.observer.stop(); }
});

test('a parked native start and later pointer resumes do not accumulate receipts for vanished clocks', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'figure'};
  const clock = f.clock(target, 'animation', null, 'paused');
  try {
    f.active([clock]); f.event(target, 'animationstart');
    assert.equal(f.observer.snapshot().triggered.length, 0);
    clock.playState = 'running'; f.listeners.get('pointerover')();
    clock.playState = 'paused'; f.listeners.get('pointerout')();
    clock.playState = 'running'; f.listeners.get('focusin')();
    assert.equal(f.observer.snapshot().triggered.length, 2);
    f.active([]); f.event(target, 'animationstart');
    assert.equal(f.observer.snapshot().gaps.length, 1, 'Resume records cannot conceal an unrelated missing clock');
  } finally { f.observer.stop(); }
});


function exactClock(f, target, kind = 'transition', state = 'running') {
  const animation = f.clock(target, kind, null, state);
  animation.effect.composite = 'replace';
  animation.effect.getTiming = () => ({duration: 150, delay: 0, endDelay: 0,
    direction: 'normal', easing: 'linear', fill: 'backwards', iterationStart: 0, iterations: 1});
  return animation;
}
function exactStart(f, target, animation, type = 'transitionrun', extra = {}) {
  f.listeners.get(type)({type, target, animation, propertyName: 'opacity', animationName: 'opacity',
    pseudoElement: '', ...extra});
}

test('exact native starts retain canceled definitions without borrowing the live replacement', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const canceled = exactClock(f, target, 'transition', 'idle'), replacement = exactClock(f, target);
  canceled.effect.getKeyframes = () => [{offset: 0, opacity: '0'}, {offset: 1, opacity: '0.4'}];
  try {
    f.active([replacement]);
    exactStart(f, target, canceled);
    let state = f.observer.snapshot();
    assert.equal(state.gaps.length, 0);
    assert.equal(state.triggered.length, 2);
    const canceledEntry = state.triggered.find(value => value.activation === 'canceled-before-observation');
    assert.equal(canceledEntry.keyframes[1].opacity, '0.4');
    assert.equal(canceled.playState, 'idle', 'Recording never mutates native playback');
    exactStart(f, target, canceled, 'transitioncancel');
    exactStart(f, target, replacement);
    exactStart(f, target, replacement, 'transitionend');
    assert.equal(f.observer.snapshot().gaps.length, 0, 'Exact old cancellation preserves the replacement receipt');
    exactStart(f, target, replacement);
    assert.match(f.observer.snapshot().gaps[0], /already consumed/);
    f.active([]); f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 2, 'A later missing legacy start remains a hard gap');
    assert.equal(/"animation":|startTime|currentTime|playState/.test(JSON.stringify(state.triggered)), false);
  } finally { f.observer.stop(); }
  assert.equal(f.listeners.size, 0); assert.equal(f.frames.size, 0); assert.equal(f.disconnected, true);
});

test('exact native running and finished starts can be absent from the live animation list', () => {
  for (const kind of ['animation', 'transition']) for (const state of ['running', 'finished']) {
    const f = startFixture(), target = {nodeType: 1, localName: 'article'};
    try {
      const animation = exactClock(f, target, kind, state);
      exactStart(f, target, animation, kind === 'animation' ? 'animationstart' : 'transitionrun');
      assert.equal(f.observer.snapshot().triggered.length, 1);
      assert.equal(f.observer.snapshot().gaps.length, 0);
      exactStart(f, target, animation, kind === 'animation' ? 'animationstart' : 'transitionrun');
      assert.match(f.observer.snapshot().gaps[0], /already consumed/);
    } finally { f.observer.stop(); }
  }
});

test('exact parked starts preserve actual resumes and report unobserved activation before completion', () => {
  for (const live of [false, true]) {
    const f = startFixture(), target = {nodeType: 1, localName: 'figure'};
    const animation = exactClock(f, target, 'animation', 'paused');
    try {
      if (live) f.active([animation]);
      exactStart(f, target, animation, 'animationstart');
      assert.equal(f.observer.snapshot().triggered.length, 0);
      animation.playState = 'running'; f.active([animation]); f.listeners.get('pointerover')();
      animation.playState = 'paused'; f.listeners.get('pointerout')();
      animation.playState = 'running'; f.listeners.get('focusin')();
      assert.equal(f.observer.snapshot().triggered.length, 2);
      animation.playState = 'finished'; f.active([]);
      exactStart(f, target, animation, 'animationend');
      assert.equal(f.observer.snapshot().gaps.length, 0);
    } finally { f.observer.stop(); }
  }
  for (const ending of ['animationend', 'animationcancel']) {
    const f = startFixture(), target = {nodeType: 1, localName: 'figure'};
    const animation = exactClock(f, target, 'animation', 'paused');
    try {
      exactStart(f, target, animation, 'animationstart');
      animation.playState = ending === 'animationend' ? 'finished' : 'idle';
      exactStart(f, target, animation, ending);
      assert.equal(f.observer.snapshot().triggered.length, 0, 'An unseen resume is not fabricated');
      assert.match(f.observer.snapshot().gaps[0], /unobserved activation/);
    } finally { f.observer.stop(); }
  }
  const f = startFixture(), target = {nodeType: 1, localName: 'figure'};
  try {
    exactStart(f, target, exactClock(f, target, 'animation', 'idle'), 'animationstart');
    assert.equal(f.observer.snapshot().triggered.length, 0);
    assert.match(f.observer.snapshot().gaps[0], /unobserved activation/);
  } finally { f.observer.stop(); }
});

test('a previous activation cannot conceal a later unobserved parked resume', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'figure'};
  const animation = exactClock(f, target, 'animation');
  try {
    f.active([animation]); exactStart(f, target, animation, 'animationstart');
    assert.equal(f.observer.snapshot().triggered.length, 1);
    animation.playState = 'paused'; f.listeners.get('pointerout')();
    // A later resume and finish happen without a sampled running state. The
    // finished fill-none object is already absent when its event is delivered.
    f.active([]); animation.playState = 'finished';
    exactStart(f, target, animation, 'animationend');
    assert.equal(f.observer.snapshot().triggered.length, 1, 'The missing resume is not invented');
    assert.match(f.observer.snapshot().gaps[0], /unobserved activation/);
  } finally { f.observer.stop(); }
});

function delayedBlink(f, target, options = {}) {
  const animation = exactClock(f, target, 'animation');
  const timing = {...animation.effect.getTiming(), duration: 2000, delay: 250,
    iterations: Infinity, fill: 'none', ...options};
  let pauses = 0;
  animation.animationName = 'blinking';
  animation.currentTime = 251.703;
  animation.startTime = 100;
  animation.pause = () => { pauses++; animation.playState = 'paused'; animation.startTime = null; };
  animation.effect.getTiming = () => ({...timing});
  return {animation, timing, pauses: () => pauses};
}
function blinkEvent(f, target, animation, type = 'animationend', extra = {}) {
  exactStart(f, target, animation, type, {animationName: 'blinking', elapsedTime: 0, ...extra});
}

test('owned delayed infinite alignment acknowledges only its exact one-shot native seek end', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'label'};
  const clock = delayedBlink(f, target), {animation} = clock;
  try {
    f.active([animation]); blinkEvent(f, target, animation, 'animationstart');
    f.observer.alignInfinite(animation);
    assert.equal(clock.pauses(), 1);
    assert.equal(animation.playState, 'paused'); assert.equal(animation.currentTime, 0);
    assert.equal(animation.startTime, null);
    // A second ready()/phase call must preserve, not replace, its queued end.
    f.observer.alignInfinite(animation); f.observer.phase('capture');
    assert.equal(clock.pauses(), 1, 'An already parked clock is not realigned');
    blinkEvent(f, target, animation);
    assert.equal(f.observer.snapshot().gaps.length, 0);
    assert.equal(f.observer.snapshot().triggered.length, 1, 'Owned alignment is not a new activation');
    blinkEvent(f, target, animation);
    assert.match(f.observer.snapshot().gaps[0], /unobserved activation/,
      'One owned seek cannot excuse a second end');
  } finally { f.observer.stop(); }
});

test('consumed alignment retires its start and preserves a later actual delay crossing', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'label'};
  const {animation} = delayedBlink(f, target);
  try {
    f.active([animation]); blinkEvent(f, target, animation, 'animationstart');
    f.observer.alignInfinite(animation); blinkEvent(f, target, animation);
    animation.playState = 'running'; animation.currentTime = 260;
    f.listeners.get('focusin')();
    blinkEvent(f, target, animation, 'animationstart');
    f.observer.alignInfinite(animation); blinkEvent(f, target, animation);
    const state = f.observer.snapshot();
    assert.equal(state.gaps.length, 0, 'The new start is not already consumed');
    assert.equal(state.triggered.length, 2, 'Both actual activations remain in the contract');
    assert.ok(state.triggered.every(entry => entry.timing.delay === 250 && entry.timing.iterations === 'Infinity'));
    assert.equal(/currentTime|startTime|alignedEnd/.test(JSON.stringify(state.triggered)), false);
  } finally { f.observer.stop(); }
});

test('alignment cannot excuse another object or an unowned or non-crossing seek', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'label'};
  const {animation} = delayedBlink(f, target), other = delayedBlink(f, target).animation;
  try {
    f.active([animation]); blinkEvent(f, target, animation, 'animationstart');
    f.observer.alignInfinite(animation);
    other.playState = 'paused'; other.currentTime = 0;
    blinkEvent(f, target, other);
    assert.equal(f.observer.snapshot().gaps.length, 1);
    blinkEvent(f, target, animation);
    assert.equal(f.observer.snapshot().gaps.length, 1, 'Another object cannot consume the owned receipt');
  } finally { f.observer.stop(); }
  for (const scenario of ['unowned', 'before-delay', 'zero-delay', 'finite']) {
    const fresh = startFixture(), clock = delayedBlink(fresh, target);
    try {
      fresh.active([clock.animation]); blinkEvent(fresh, target, clock.animation, 'animationstart');
      if (scenario === 'before-delay') clock.animation.currentTime = 100;
      if (scenario === 'zero-delay') clock.timing.delay = 0;
      if (scenario === 'finite') clock.timing.iterations = 1;
      if (scenario !== 'unowned') fresh.observer.alignInfinite(clock.animation);
      clock.animation.playState = 'paused'; clock.animation.currentTime = 0;
      fresh.observer.sample(); blinkEvent(fresh, target, clock.animation);
      assert.match(fresh.observer.snapshot().gaps[0], /unobserved activation/, scenario);
    } finally { fresh.observer.stop(); }
  }
});

test('aligned ends fail closed for elapsed time, changed native timing, effect or seek destination', () => {
  const changes = [
    clock => ({elapsedTime: 0.001}),
    clock => { clock.timing.delay = 500; return {}; },
    clock => { clock.timing.duration = 1000; return {}; },
    clock => { clock.timing.iterations = 1; return {}; },
    clock => { clock.animation.effect = {...clock.animation.effect}; return {}; },
    clock => { clock.animation.currentTime = 1; return {}; }
  ];
  for (const [index, change] of changes.entries()) {
    const f = startFixture(), target = {nodeType: 1, localName: 'label'}, clock = delayedBlink(f, target);
    try {
      f.active([clock.animation]); blinkEvent(f, target, clock.animation, 'animationstart');
      f.observer.alignInfinite(clock.animation);
      blinkEvent(f, target, clock.animation, 'animationend', change(clock));
      assert.match(f.observer.snapshot().gaps[0], /unobserved activation/, 'Invalid aligned end ' + index);
    } finally { f.observer.stop(); }
  }
});

test('an actual observed resume or a different completion retires the owned seek acknowledgement', () => {
  for (const resumed of [false, true]) {
    const f = startFixture(), target = {nodeType: 1, localName: 'label'};
    const {animation} = delayedBlink(f, target);
    try {
      f.active([animation]); blinkEvent(f, target, animation, 'animationstart');
      f.observer.alignInfinite(animation);
      if (resumed) {
        animation.playState = 'running'; animation.currentTime = 100;
        f.listeners.get('focusin')();
        animation.playState = 'paused'; animation.currentTime = 0; f.observer.sample();
      } else blinkEvent(f, target, animation, 'animationcancel');
      blinkEvent(f, target, animation);
      assert.match(f.observer.snapshot().gaps[0], /unobserved activation/);
      assert.equal(f.observer.snapshot().triggered.length, resumed ? 2 : 1);
    } finally { f.observer.stop(); }
  }
});

test('an observed idle-to-running restart invalidates an older owned seek receipt', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'label'};
  const {animation} = delayedBlink(f, target);
  try {
    f.active([animation]); blinkEvent(f, target, animation, 'animationstart');
    f.observer.alignInfinite(animation);
    animation.playState = 'idle'; f.observer.sample();
    animation.playState = 'running'; animation.currentTime = 100; f.observer.sample();
    animation.playState = 'paused'; animation.currentTime = 0; f.observer.sample();
    blinkEvent(f, target, animation);
    assert.equal(f.observer.snapshot().triggered.length, 2, 'Both actual running periods were sampled');
    assert.match(f.observer.snapshot().gaps[0], /unobserved activation/,
      'An old alignment cannot authorize an end after intervening activity');
  } finally { f.observer.stop(); }
});

test('failed owned seeking cannot leave a receipt and stopped observers never align clocks', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'label'}, clock = delayedBlink(f, target);
  try {
    f.active([clock.animation]); blinkEvent(f, target, clock.animation, 'animationstart');
    Object.defineProperty(clock.animation, 'currentTime', {configurable: true,
      get: () => 251.703, set() { throw Error('Native seek failed'); }});
    assert.throws(() => f.observer.alignInfinite(clock.animation), /Native seek failed/);
    Object.defineProperty(clock.animation, 'currentTime', {value: 0, writable: true, configurable: true});
    f.observer.sample(); blinkEvent(f, target, clock.animation);
    assert.match(f.observer.snapshot().gaps[0], /unobserved activation/);
  } finally { f.observer.stop(); }
  const fresh = startFixture(), later = delayedBlink(fresh, target);
  const savedWake = fresh.listeners.get('animationend');
  fresh.observer.stop();
  fresh.observer.alignInfinite(later.animation);
  savedWake({type: 'animationend', target, animation: later.animation, animationName: 'blinking', elapsedTime: 0});
  assert.equal(later.pauses(), 0); assert.equal(later.animation.currentTime, 251.703);
  assert.equal(fresh.frames.size, 0); assert.equal(fresh.listeners.size, 0); assert.equal(fresh.disconnected, true);
});

test('native definitions preserve unsupported optional fields and valid no-op keyframes', () => {
  for (const frames of [[], [{offset: 0, computedOffset: 0, easing: 'linear', composite: 'auto'}]]) {
    const f = startFixture(), target = {nodeType: 1, localName: 'article'};
    const animation = exactClock(f, target, 'animation');
    animation.effect.getKeyframes = () => frames;
    try {
      assert.equal(animation.effect.iterationComposite, undefined, 'Chrome does not expose this optional API');
      exactStart(f, target, animation, 'animationstart');
      let snapshot = JSON.parse(JSON.stringify(f.observer.snapshot()));
      assert.equal(snapshot.gaps.length, 0);
      assert.deepEqual(snapshot.triggered[0].keyframes, frames, 'An actual empty/no-op definition stays exact');
      assert.equal('iterationComposite' in snapshot.triggered[0], false, 'Unsupported data is not fabricated');
    } finally { f.observer.stop(); }
  }
  const f = startFixture(), target = {nodeType: 1, localName: 'article'};
  const animation = exactClock(f, target, 'animation');
  animation.effect.iterationComposite = 'accumulate';
  try {
    exactStart(f, target, animation, 'animationstart');
    assert.equal(f.observer.snapshot().triggered[0].iterationComposite, 'accumulate', 'Supported optional values remain captured');
  } finally { f.observer.stop(); }
});

test('an exact native event rejects mismatches and incomplete effects instead of borrowing a replacement', () => {
  const changes = [
    a => ({}),
    a => { a.animationName = 'opacity'; return a; },
    a => { a.transitionProperty = 'height'; return a; },
    a => { a.effect.target = {nodeType: 1, localName: 'section'}; return a; },
    a => { a.effect.pseudoElement = '::before'; return a; },
    a => { a.effect = null; return a; },
    a => { Object.defineProperty(a, 'effect', {get() { throw Error('effect unavailable'); }}); return a; },
    a => { a.effect.getTiming = () => ({}); return a; },
    a => { a.effect.getTiming = () => { throw Error('timing unavailable'); }; return a; },
    a => { delete a.effect.composite; return a; },
    a => { a.playbackRate = NaN; return a; },
    a => { a.effect.getKeyframes = () => null; return a; },
    a => { a.effect.getKeyframes = () => ({}); return a; },
    a => { a.effect.getKeyframes = () => [null]; return a; },
    a => { a.effect.getKeyframes = () => [[]]; return a; },
    a => { a.effect.getKeyframes = () => { throw Error('keyframes unavailable'); }; return a; }
  ];
  for (const [index, change] of changes.entries()) {
    const f = startFixture(), target = {nodeType: 1, localName: 'section'};
    const valid = exactClock(f, target);
    try {
      f.active([valid]);
      const invalid = change(exactClock(f, target, index === 1 ? 'animation' : 'transition', 'idle'));
      exactStart(f, target, invalid);
      assert.equal(f.observer.snapshot().gaps.length, 1, 'Invalid case ' + index + ' is explicit');
      exactStart(f, target, valid);
      assert.equal(f.observer.snapshot().gaps.length, 1, 'Invalid case ' + index + ' cannot consume the valid receipt');
    } finally { f.observer.stop(); }
  }
});

test('exact cancellation isolates native pseudo clocks and accessor failures retain a hard gap', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const first = exactClock(f, target), pseudo = exactClock(f, target), other = exactClock(f, {nodeType: 1, localName: 'aside'});
  pseudo.effect.pseudoElement = '::before';
  try {
    f.active([first]); exactStart(f, target, first);
    f.active([pseudo, other]); f.observer.sample();
    first.playState = 'idle'; exactStart(f, target, first, 'transitioncancel');
    exactStart(f, target, pseudo, 'transitionrun', {pseudoElement: '::before'});
    exactStart(f, other.effect.target, other);
    assert.equal(f.observer.snapshot().gaps.length, 0);
    const event = {type: 'transitionrun', target, propertyName: 'opacity',
      get animation() { throw Error('accessor unavailable'); }};
    f.listeners.get('transitionrun')(event);
    assert.match(f.observer.snapshot().gaps[0], /Cannot read native CSS event animation/);
  } finally { f.observer.stop(); }
});

test('exact receipts survive effect removal, retire by identity, and preserve null legacy starts', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'section'};
  const old = exactClock(f, target), next = exactClock(f, target);
  try {
    f.active([old]); f.observer.sample(); f.active([next]); f.observer.sample();
    old.effect = null;
    exactStart(f, target, old);
    exactStart(f, target, old, 'transitioncancel');
    exactStart(f, target, next);
    assert.equal(f.observer.snapshot().gaps.length, 0);
    f.active([]); f.event(target);
    assert.equal(f.observer.snapshot().gaps.length, 1);
    const fallback = exactClock(f, target); f.active([fallback]);
    exactStart(f, target, null);
    assert.equal(f.observer.snapshot().gaps.length, 1, 'Null takes the unchanged legacy attribution path');
  } finally { f.observer.stop(); }
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
  const media = {type: 4, conditionText: '(width > 40rem)', cssRules: [
    {type: 1, selectorText: '.native-owner', style: style({}), cssRules: [hover]}]};
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
  assert.equal(before.sourceRules[0].scope[2].selector, '.native-owner');
  assert.equal(before.sourceRules[1].frames[0].properties.opacity[0], '1');
  keyframe.style = style({opacity: ['0.5', '']});
  const after = JSON.parse(JSON.stringify(observer.snapshot()));
  assert.deepEqual(after.declarations, before.declarations);
  assert.deepEqual(after.triggered, before.triggered);
  assert.notDeepEqual(after.sourceRules, before.sourceRules, 'A dormant keyframe edit cannot hide behind identical rendered motion');
  observer.stop();
});

test('native custom-property registrations retain interpolation and inheritance contracts', () => {
  const {createMotionObserver} = fixture();
  class CSSPropertyRule {}
  const registration = Object.assign(new CSSPropertyRule(), {
    name: '--logo-image-width', syntax: '<percentage>', inherits: true, initialValue: '100%'
  });
  const media = {type: 4, conditionText: '(width > 40rem)', cssRules: [registration]};
  const win = {document: {styleSheets: [{href: '/home.css', cssRules: [media]}],
    getAnimations: () => [], querySelectorAll: () => [], addEventListener() {}, removeEventListener() {}},
    CSSAnimation: class {}, CSSTransition: class {}, CSSPropertyRule,
    MutationObserver: class {observe() {} disconnect() {}}, requestAnimationFrame: () => 1, cancelAnimationFrame() {}};
  const observer = createMotionObserver(win);
  try {
    const before = JSON.parse(JSON.stringify(observer.snapshot()));
    assert.equal(before.sourceRules.length, 1);
    assert.deepEqual(before.sourceRules[0], {scope: [
      {sheet: '/home.css', media: '', disabled: false},
      {type: 4, kind: 'Object', selector: null, condition: '(width > 40rem)', name: null}],
      property: '--logo-image-width', syntax: '<percentage>', inherits: true, initialValue: '100%'});
    for (const [key, changed] of [['name', '--different-width'], ['syntax', '*'],
      ['inherits', false], ['initialValue', '85%']]) {
      const original = registration[key]; registration[key] = changed;
      const after = JSON.parse(JSON.stringify(observer.snapshot()));
      assert.deepEqual(after.declarations, before.declarations);
      assert.deepEqual(after.triggered, before.triggered);
      assert.notDeepEqual(after.sourceRules, before.sourceRules, key + ' changes the native registration contract');
      registration[key] = original;
    }
    media.cssRules = [];
    assert.notDeepEqual(JSON.parse(JSON.stringify(observer.snapshot().sourceRules)), before.sourceRules,
      'Removing registration cannot pass behind identical final layout and keyframes');
  } finally { observer.stop(); }
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

function returnFixture({gaps = [], policy = true, readinessFailure = false, returnedDuration = 1800, disabledSearch = false, quickSearch = false, lazySearch = false, replaceSearchButton = false, delayedScroll = false, delayedFocus = false, missedReturnAuto = false, stuckPostIntentAuto = false} = {}) {
  const calls = [], frames = new Map(), listeners = new Map(), timers = new Map();
  const results = {dataset: {}, append() {}}, observers = [];
  const url = 'http://127.0.0.1:4114/cv?motion-return=proof#skills';
  let next = 0, now = 0, current;
  class CSSAnimation {}
  class CSSTransition {}
  const documentFor = navigation => {
    let interacted = false;
    const app = {pending: false, spinner: false,
      hasAttribute: () => app.pending,
      querySelector: selector => selector === 'main' || app.spinner ? {} : null};
    const panel = {open: false, present: navigation === 'navigate' || !lazySearch, listeners: new Map(),
      getBoundingClientRect: () => ({height: panel.open ? 80 : 0}), getAnimations: () => panel.animation ? [panel.animation] : [],
      addEventListener: (name, callback) => panel.listeners.set(name, callback),
      removeEventListener: (name, callback) => { assert.equal(panel.listeners.get(name), callback); panel.listeners.delete(name); }};
    let currentButton;
    const clickSearch = () => {
      interacted = true;
      calls.push((navigation === 'navigate' ? 'before' : 'after') + ':search-click');
      panel.present = true; panel.open = !panel.open;
      const duration = quickSearch ? 1 : panel.open ? (navigation === 'navigate' ? 1800 : returnedDuration) : 400;
      panel.animation = disabledSearch ? null : Object.assign(new CSSTransition(), {
        transitionProperty: 'max-height', playbackRate: 1,
        effect: {target: panel, getTiming: () => ({duration, delay: 0, iterations: 1, easing: 'ease'}),
          getComputedTiming: () => ({endTime: duration}),
          getKeyframes: () => panel.open ? [{maxHeight: '0px'}, {maxHeight: '80px'}] : [{maxHeight: '80px'}, {maxHeight: '0px'}]},
        finished: Promise.resolve()
      });
      if (quickSearch) {
        panel.listeners.get('transitionrun')?.({target: panel, propertyName: 'max-height'});
        panel.animation = null;
      }
    };
    const button = {click() {
      if (currentButton !== button) return;
      clickSearch();
      if (replaceSearchButton && navigation !== 'navigate') currentButton = {click: clickSearch};
    }};
    currentButton = button;
    const document = {URL: url, readyState: 'complete', activeElement: {localName: 'body', id: ''}, app, present: true, fonts: {ready: Promise.resolve()}, getAnimations: () => [],
      querySelector: selector => selector === '#app' ? app : selector === '#app .cv-intro' && document.present ? {} :
        selector === '.search-ui' ? (panel.present ? panel : null) : selector === '.search-ui-open' ? (panel.open ? panel : null) :
        selector === 'button.search-ui-btn' ? currentButton : null,
      documentElement: {hasAttribute(name) {calls.push('policy:' + name); return policy;}}};
    const win = {document, CSSAnimation, CSSTransition, scrollX: 0, scrollY: 0,
      getComputedStyle(element) {
        assert.equal(element, document.documentElement, 'Return scrolling policy is measured on the actual document root');
        const scrollBehavior = navigation === 'navigate' ? 'smooth' :
          interacted ? (stuckPostIntentAuto ? 'auto' : 'smooth') : (missedReturnAuto ? 'smooth' : 'auto');
        calls.push(`scroll-style:${navigation === 'navigate' ? 'before' : 'after'}:${scrollBehavior}`);
        return {scrollBehavior};
      },
      performance: {getEntriesByType: () => [{type: navigation}]},
      addEventListener: (name, callback) => listeners.set(document, callback),
      removeEventListener: (name, callback) => {assert.equal(listeners.get(document), callback); listeners.delete(document);}};
    document.defaultView = win;
    return document;
  };
  const previous = documentFor('navigate'), returned = documentFor('back_forward');
  returned.readyState = 'loading'; current = previous;
  const frame = {style: {}, get contentDocument() {return current;}, get contentWindow() {return current?.defaultView;},
    getBoundingClientRect: () => ({left: 16, top: 150, right: 1424, bottom: 1000}),
    set src(value) {current = null; queueMicrotask(() => frame.onload());}};
  const context = {URL, URLSearchParams, innerWidth: 1440, innerHeight: 1200,
    location: {href: 'http://127.0.0.1:4114/__tests/motion.html',
    origin: 'http://127.0.0.1:4114', hostname: '127.0.0.1', search: ''},
    document: {querySelector: selector => selector === '#app' ? frame : selector === '#results' ? results : {},
      createElement: () => ({})},
    performance: {now: () => readinessFailure || disabledSearch || replaceSearchButton ? now += 10000 : now + 1},
    requestAnimationFrame(callback) {frames.set(++next, callback); return next;},
    cancelAnimationFrame(id) {frames.delete(id);},
    setTimeout(callback, ms) {
      const id = ++next; timers.set(id, callback);
      if (ms === 3500 || ms === 6500 || ms === 20) queueMicrotask(() => {
        if (ms !== 20) {
          for (const time of [4500, 5500, ms].filter(time => time <= ms)) {
            now = time;
            if (time === 4500 && delayedScroll) returned.defaultView.scrollY = 37;
            if (time === 5500 && delayedFocus) returned.activeElement = {localName: 'button', id: 'fullscreen-btn'};
            const pending = [...frames]; frames.clear();
            pending.forEach(([, frame]) => frame());
          }
          calls.push('observation-window');
        }
        timers.delete(id); callback();
      });
      return id;
    },
    clearTimeout(id) {timers.delete(id);},
    history: {back() {
      current = returned;
      const [id, callback] = frames.entries().next().value; frames.delete(id); callback();
      assert.ok(calls.includes('attach-returned'), 'Attach occurs while route readiness is still false');
      calls.push('ready'); if (!readinessFailure) returned.readyState = 'complete';
    }},
    window: {visualFixture: {createMotionObserver(win, phase) {
      calls.push(win.document === previous ? 'attach-previous' : 'attach-returned');
      const observer = {stopped: false, phase() {}, stop() {observer.stopped = true;},
        snapshot() {calls.push('snapshot'); return {triggered: [], gaps};}};
      observers.push(observer); return observer;
    }}}};
  vm.runInNewContext(readFileSync('test/browser/motion.js', 'utf8') +
    '\nthis.observeBack = externalBack; this.matchesReturn = returnMatches; this.readyReturn = returnReady; this.inspectSearch = searchMotion; this.returnCases = externalBackCases;', context);
  return {context, returned, previous, frames, timers, listeners, observers, calls, results, url};
}

test('native Back matches the complete departing URL and readiness never depends on policy flags', () => {
  const fixture = returnFixture(), {context, returned, url} = fixture;
  assert.equal(context.matchesReturn(returned, url), true);
  assert.equal(context.matchesReturn(returned, url.replace('proof', 'different')), false);
  assert.equal(context.matchesReturn(returned, url.replace('#skills', '#other')), false);
  assert.equal(context.matchesReturn(null, url), false);
  assert.equal(context.readyReturn(returned, ['#app .cv-intro']), false);
  returned.readyState = 'complete';
  assert.equal(context.readyReturn(returned, ['#app .cv-intro']), true);
  returned.app.pending = true;
  assert.equal(context.readyReturn(returned, ['#app .cv-intro']), false);
  returned.app.pending = false; returned.app.spinner = true;
  assert.equal(context.readyReturn(returned, ['#app .cv-intro']), false);
  returned.app.spinner = false; returned.present = false;
  assert.equal(context.readyReturn(returned, ['#app .cv-intro']), false);
  assert.deepEqual(fixture.calls, [], 'Readiness does not inspect return-policy attributes or interact');
});

test('native Back observes before readiness, saves route evidence, and cleans successful and failed checks', async () => {
  for (const options of [{}, {gaps: ['unobservable start']}, {policy: false}]) {
    const fixture = returnFixture(options), {context, calls, results, url} = fixture;
    const run = context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
    if (options.gaps) await assert.rejects(run, /external Back starts no CSS/);
    else if (options.policy === false) await assert.rejects(run, /native image return policy/);
    else await run;
    assert.ok(calls.indexOf('attach-returned') < calls.indexOf('ready'));
    assert.ok(calls.indexOf('observation-window') < calls.indexOf('snapshot'));
    if (options.policy !== undefined) assert.ok(calls.indexOf('snapshot') < calls.indexOf('policy:data-image-direct'));
    const evidence = JSON.parse(results.dataset.externalBackCases)[url];
    assert.equal(evidence.documents[1].navigation, 'back_forward');
    assert.equal(evidence.observationWindowMs, 3500);
    const firstReturnedClick = calls.indexOf('after:search-click');
    if (firstReturnedClick !== -1) {
      assert.ok(firstReturnedClick > calls.indexOf('observation-window'));
      assert.ok(firstReturnedClick > calls.indexOf('policy:data-image-direct'));
    }
    assert.equal(results.dataset.externalBack, undefined, 'Route checks preserve the separate home proof');
    assert.ok(fixture.observers.every(observer => observer.stopped));
    assert.equal(fixture.frames.size, 0); assert.equal(fixture.timers.size, 0); assert.equal(fixture.listeners.size, 0);
  }
});


test('native Back measures instant restoration before input and restores authored smooth scrolling after intent', async () => {
  for (const options of [{}, {missedReturnAuto: true}, {stuckPostIntentAuto: true}]) {
    const fixture = returnFixture(options), {context, calls, results, url} = fixture;
    const run = context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
    if (options.missedReturnAuto) await assert.rejects(run, /native scroll restoration instant before interaction/);
    else if (options.stuckPostIntentAuto) await assert.rejects(run, /first interaction restores the authored smooth scrolling/);
    else await run;
    const initialStyle = calls.findIndex(value => value.startsWith('scroll-style:after:'));
    const firstClick = calls.indexOf('after:search-click');
    assert.ok(initialStyle > calls.indexOf('observation-window'), 'Root policy cannot conceal pre-input motion observation');
    assert.ok(initialStyle > calls.indexOf('snapshot'), 'The real motion result is retained before checking root scrolling policy');
    assert.ok(JSON.parse(results.dataset.externalBackCases)[url].positions.samples.some(value => value.ready));
    if (options.missedReturnAuto) {
      assert.equal(calls[initialStyle], 'scroll-style:after:smooth');
      assert.equal(firstClick, -1, 'A missing return guard fails before any post-return input');
    } else {
      assert.equal(calls[initialStyle], 'scroll-style:after:auto');
      assert.ok(firstClick > initialStyle, 'The return policy is measured before the first ordinary Search click');
      const finalStyle = calls.findLastIndex(value => value.startsWith('scroll-style:after:'));
      assert.ok(finalStyle > calls.lastIndexOf('after:search-click'), 'The released policy is checked after ordinary Search interaction');
      assert.equal(calls[finalStyle], `scroll-style:after:${options.stuckPostIntentAuto ? 'auto' : 'smooth'}`);
      assert.equal(fixture.returned.querySelector('.search-ui').open, false);
      assert.equal(fixture.returned.querySelector('.search-ui').listeners.size, 0);
    }
    assert.ok(fixture.observers.every(observer => observer.stopped));
    assert.equal(fixture.frames.size, 0); assert.equal(fixture.timers.size, 0); assert.equal(fixture.listeners.size, 0);
  }
});

test('home native Back retains its original proof shape and a failed readiness wait releases observers', async () => {
  const home = returnFixture();
  await home.context.observeBack({selectors: ['#app .cv-intro']});
  assert.deepEqual(Object.keys(JSON.parse(home.results.dataset.externalBack)).sort(),
    ['documents', 'motion', 'observation', 'observationWindowMs', 'positions', 'running', 'sameDocument']);
  const failed = returnFixture({readinessFailure: true});
  await assert.rejects(failed.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']}), /did not settle/);
  assert.equal(failed.calls.includes('observation-window'), false);
  assert.ok(failed.observers.every(observer => observer.stopped));
  assert.equal(failed.frames.size, 0); assert.equal(failed.timers.size, 0); assert.equal(failed.listeners.size, 0);
});


test('CV Back observes known delayed scroll and focus deadlines before any controls', async () => {
  for (const mutation of [{}, {delayedScroll: true}, {delayedFocus: true}]) {
    const fixture = returnFixture(mutation);
    const cvCase = fixture.context.returnCases.find(value => value.label === 'CV');
    assert.equal(cvCase.observationWindowMs, 6500, 'The real CV route extends the observation window');
    assert.ok(fixture.context.returnCases.filter(value => value.label !== 'CV').every(value => value.observationWindowMs === undefined));
    const run = fixture.context.observeBack({...cvCase, selectors: ['#app .cv-intro']});
    if (mutation.delayedScroll || mutation.delayedFocus)
      await assert.rejects(run, /scroll and focus unchanged/);
    else await run;
    const evidence = JSON.parse(fixture.results.dataset.externalBackCases)[fixture.url];
    assert.equal(evidence.observationWindowMs, 6500);
    assert.ok(evidence.positions.samples.some(value => value.elapsed === 4500));
    assert.ok(evidence.positions.samples.some(value => value.elapsed === 5500));
    if (mutation.delayedScroll)
      assert.ok(evidence.positions.samples.some(value => value.scroll[1] === 37));
    if (mutation.delayedFocus)
      assert.ok(evidence.positions.samples.some(value => value.activeElement?.id === 'fullscreen-btn'));
    if (mutation.delayedScroll || mutation.delayedFocus)
      assert.equal(fixture.calls.includes('after:search-click'), false, 'Autonomous movement fails before post-return controls');
    assert.ok(fixture.observers.every(observer => observer.stopped));
    assert.equal(fixture.frames.size, 0); assert.equal(fixture.timers.size, 0); assert.equal(fixture.listeners.size, 0);
  }
  const ordinary = returnFixture({delayedScroll: true, delayedFocus: true});
  await ordinary.context.observeBack({label: 'Blog', selectors: ['#app .cv-intro']});
  assert.equal(JSON.parse(ordinary.results.dataset.externalBackCases)[ordinary.url].observationWindowMs, 3500,
    'Other routes retain the existing observation duration');
});

test('after-Back Search captures actual finite CSS definitions and rejects changed timing or disabled motion', async () => {
  const good = returnFixture();
  await good.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
  const evidence = JSON.parse(good.results.dataset.externalBackInteractions)[good.url];
  assert.deepEqual(evidence.before.motion, evidence.after.motion);
  assert.deepEqual(evidence.after.motion.map(value => value.timing.duration), [1800, 400]);
  assert.ok(evidence.after.frames.length > 0, 'Native frame samples accompany timing evidence without a minimum frame-count gate');
  assert.equal(/currentTime|startTime|progress/.test(JSON.stringify(evidence.after.motion)), false);
  assert.equal(good.returned.querySelector('.search-ui').listeners.size, 0);
  assert.equal(good.previous.querySelector('.search-ui').listeners.size, 0);
  const quick = returnFixture({quickSearch: true});
  await quick.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
  const quickEvidence = JSON.parse(quick.results.dataset.externalBackInteractions)[quick.url];
  assert.deepEqual(quickEvidence.after.motion.map(value => value.timing.duration), [1, 1],
    'A native finite effect captured on transitionrun remains valid after it disappears before the next poll');
  const lazy = returnFixture({lazySearch: true});
  await lazy.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
  assert.equal(lazy.calls.filter(value => value === 'after:search-click').length, 4,
    'First intent acquires a restored lazy panel before measuring its ordinary open/close transitions');
  const instant = returnFixture({returnedDuration: 0});
  await assert.rejects(instant.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']}), /finite native height transition/);
  const changed = returnFixture({returnedDuration: 100});
  await assert.rejects(changed.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']}), /CSS timing after Back/);
  assert.ok(changed.observers.every(observer => observer.stopped)); assert.equal(changed.frames.size, 0);
  const disabled = returnFixture({disabledSearch: true});
  await assert.rejects(disabled.context.inspectSearch('Disabled'), /did not settle/);
  assert.equal(disabled.frames.size, 0);
  assert.equal(disabled.previous.querySelector('.search-ui').listeners.size, 0);
});

test('lazy Search reacquires its real control after replacing the disconnected fallback', async () => {
  const lazy = returnFixture({lazySearch: true, replaceSearchButton: true});
  await lazy.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']});
  const evidence = JSON.parse(lazy.results.dataset.externalBackInteractions)[lazy.url];
  assert.deepEqual(evidence.before.motion, evidence.after.motion);
  assert.equal(lazy.calls.filter(value => value === 'after:search-click').length, 4);
  assert.equal(lazy.returned.querySelector('.search-ui').listeners.size, 0);
});

function geometryDriver(options = {}) {
  const calls = [], frames = [], active = new Set();
  const parking = {getBoundingClientRect: () => ({left: 0, right: 1440, top: options.overlap ? 968 : 1068, bottom: options.overlap ? 1000 : 1100}),
    matches: selector => selector === ':hover' && !options.pointerMoved};
  const window = {innerWidth: 1440, innerHeight: 1100, location: {search: options.standalone ? '' : '?geometry-parking=1'}};
  const document = {body: {dataset: {}, appendChild(frame) {active.add(frame); queueMicrotask(() => frame.onload());}},
    querySelector: selector => selector === 'body > h1' ? parking : null,
    createElement(tag) {
      assert.equal(tag, 'iframe');
      const target = {devicePixelRatio: 1, visualFixture: {
        ready: async () => {calls.push('ready'); if (options.readyError) throw Error('Native fixture failed');},
        motion: {phase: phase => calls.push(phase), stop: () => calls.push('stop')},
        snapshot: () => ({viewport: [frame.width, frame.height, 1]})},
        eval: async source => {
          if (source.includes('assertNativeSurface')) {
            calls.push('capture-assert');
            if (options.assertError) throw Error('Native surface disappeared');
          } else {
            calls.push('action'); if (options.afterAction) options.afterAction(parking, target);
          }
        }};
      const frame = {style: {}, contentWindow: target,
        getBoundingClientRect: () => ({left: 0, top: 0, right: Number(frame.width), bottom: Number(frame.height)}),
        matches: selector => selector === ':hover' && Boolean(options.frameHovered), remove: () => active.delete(frame)};
      Object.defineProperties(target, {innerWidth: {get: () => options.wrongWidth || Number(frame.width)}, innerHeight: {get: () => Number(frame.height)}});
      frames.push(frame); return frame;
    }};
  vm.runInNewContext(readFileSync('test/browser/visual-driver.js', 'utf8'),
    {window, document, URLSearchParams, setTimeout, clearTimeout});
  return {driver: window.visualDriver, calls, frames, active, document};
}

test('geometry driver preserves exact child viewport and validates genuinely parked pointer outside its bounds', async () => {
  const fixture = geometryDriver();
  const results = await fixture.driver.capture([{id: 'wide', viewport: [1440, 1000], path: '/native', action: 'nativeAction()'},
    {id: 'phone', viewport: [400, 800], path: '/native'}]);
  assert.equal(results[0].error, undefined); assert.equal(results[1].error, undefined);
  assert.deepEqual(JSON.parse(JSON.stringify(results.map(value => value.state.viewport))), [[1440, 1000, 1], [400, 800, 1]]);
  assert.equal(fixture.document.body.dataset.geometryParking, 'true');
  assert.equal(fixture.frames[0].style.top, '0'); assert.equal(fixture.frames[0].style.left, '0');
  assert.equal(fixture.active.size, 0); assert.equal(fixture.calls.filter(value => value === 'stop').length, 2);
  assert.ok(fixture.calls.includes('action'));
  const standalone = geometryDriver({standalone: true});
  assert.equal(standalone.document.body.dataset.geometryParking, undefined, 'Standalone hover heading retains its original position');
});

test('geometry driver rejects pointer contamination, overlapping parking and wrong viewport while cleaning its frame', async () => {
  for (const [options, expected] of [[{pointerMoved: true}, /pointer/], [{frameHovered: true}, /pointer/],
      [{overlap: true}, /parking/], [{wrongWidth: 1439}, /viewport/], [{readyError: true}, /Native fixture failed/]]) {
    const fixture = geometryDriver(options);
    const [result] = await fixture.driver.capture([{id: 'wide', viewport: [1440, 1000], path: '/native'}]);
    assert.match(result.error || '', expected); assert.equal(fixture.active.size, 0);
    assert.equal(fixture.calls.filter(value => value === 'stop').length, 1);
  }
});

test('geometry driver rechecks pointer and viewport after native case actions', async () => {
  for (const [afterAction, expected] of [[parking => {parking.matches = () => false;}, /pointer/],
      [(_, target) => {target.devicePixelRatio = 2;}, /viewport/]]) {
    const fixture = geometryDriver({afterAction});
    const [result] = await fixture.driver.capture([{id: 'wide', viewport: [1440, 1000], path: '/native', action: 'nativeAction()'}]);
    assert.match(result.error || '', expected); assert.equal(fixture.active.size, 0);
    assert.ok(fixture.calls.includes('action'));
  }
});

test('geometry driver verifies the intended surface after readiness and rejects missing transient content', async () => {
  for (const assertError of [false, true]) {
    const fixture = geometryDriver({assertError});
    const [result] = await fixture.driver.capture([{id: 'popup', viewport: [400, 800], path: '/native',
      action: 'nativeAction()', capture_assert: 'assertNativeSurface()'}]);
    assert.deepEqual(fixture.calls.slice(0, 5), ['ready', 'action', 'action', 'ready', 'capture-assert']);
    if (assertError) {
      assert.match(result.error, /Native surface disappeared/);
      assert.equal(result.state, undefined);
    } else assert.equal(result.error, undefined);
    assert.equal(fixture.active.size, 0);
    assert.equal(fixture.calls.filter(value => value === 'stop').length, 1);
  }
});

function framedMotionFixture({sandbox = 'allow-same-origin', readyState = 'complete', initialURL} = {}) {
  const {createMotionObserver} = fixture();
  function realm() {
    const listeners = new Map(), frames = new Map(), observers = [];
    let next = 0;
    class CSSAnimation {}
    class CSSTransition {}
    const body = {nodeType: 1, localName: 'body', children: [], parentElement: null,
      getBoundingClientRect: () => ({x: 0, y: 0, width: 320, height: 192})};
    const doc = {URL: 'http://fixture.test/document', readyState, styleSheets: [], active: [], elements: [],
      body: {querySelectorAll: () => body.children},
      getAnimations: () => doc.active,
      querySelectorAll: selector => selector === 'iframe' ? doc.elements.filter(element => element.tagName === 'IFRAME') : doc.elements,
      addEventListener: (name, callback) => listeners.set(name, callback),
      removeEventListener: name => listeners.delete(name)};
    const win = {document: doc, CSSAnimation, CSSTransition,
      MutationObserver: class {
        constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
        observe() {} disconnect() { this.disconnected = true; }
      },
      requestAnimationFrame: callback => { frames.set(++next, callback); return next; },
      cancelAnimationFrame: id => frames.delete(id),
      getComputedStyle: () => ({content: 'none', getPropertyValue: name =>
        name === 'animation-name' ? 'none' : ['transition-duration', 'transition-delay'].includes(name) ? '0s' : ''})};
    doc.defaultView = win;
    const element = {nodeType: 1, localName: 'article', tagName: 'ARTICLE', id: 'native-child',
      className: '', children: [], parentElement: body, textContent: 'Native child content',
      getAttribute: () => null,
      getBoundingClientRect: () => ({x: 8, y: 16, width: 288, height: 160})};
    body.children = [element]; doc.elements = [element];
    return {win, doc, listeners, frames, observers, element, body};
  }
  const parent = realm(), child = realm(), frameListeners = new Map();
  const frame = {nodeType: 1, localName: 'iframe', tagName: 'IFRAME', parentElement: null,
    ownerDocument: parent.doc, contentDocument: child.doc, contentWindow: child.win,
    getAttribute: name => name === 'sandbox' ? sandbox : name === 'src' ? '/document' : null,
    addEventListener: (name, callback) => frameListeners.set(name, callback),
    removeEventListener: name => frameListeners.delete(name)};
  child.win.frameElement = frame; parent.doc.elements = [frame];
  if (initialURL) child.doc.URL = initialURL;
  const observer = createMotionObserver(parent.win);
  const snapshot = () => JSON.parse(JSON.stringify(observer.snapshot()));
  return {parent, child, frame, frameListeners, observer, snapshot, realm};
}

test('script-disabled static same-origin frames retain actual scoped geometry and full motion definitions', () => {
  const f = framedMotionFixture();
  try {
    const first = f.snapshot();
    assert.deepEqual(first.gaps, []);
    assert.equal(first.documents.length, 1);
    const child = first.documents[0];
    assert.equal(child.frame, 'iframe:nth-child(1)');
    assert.equal(child.source, '/document');
    assert.equal(child.generation, 1);
    assert.deepEqual(child.geometry[0].box, [8, 16, 288, 160]);
    assert.equal(child.geometry[0].text, 'Native child content');
    assert.equal(child.motion.declarations.length, 9);
    assert.equal(f.observer.documents()[1], f.child.doc);
    f.child.element.getBoundingClientRect = () => ({x: 9, y: 16, width: 287, height: 160});
    assert.notDeepEqual(f.snapshot().documents[0].geometry, child.geometry, 'Child layout is measured rather than waived');
  } finally { f.observer.stop(); }
  for (const realm of [f.parent, f.child]) {
    assert.equal(realm.listeners.size, 0); assert.equal(realm.frames.size, 0);
    assert.equal(realm.observers.every(observer => observer.disconnected), true);
  }
  assert.equal(f.frameListeners.size, 0);
});

test('late script-capable or unsandboxed frames fail closed even when currently motion-free', () => {
  for (const sandbox of [null, 'allow-same-origin allow-scripts']) {
    const f = framedMotionFixture({sandbox});
    try { assert.match(f.snapshot().gaps.join('\n'), /missed parser-time motion observation/); }
    finally { f.observer.stop(); }
  }
});

test('late static proof rejects dormant CSS motion, inaccessible stylesheets and named paused animations', () => {
  for (const variant of ['dormant', 'inaccessible', 'parked']) {
    const f = framedMotionFixture();
    try {
      if (variant === 'dormant') f.child.doc.styleSheets = [{href: '/native.css', cssRules: [{
        selectorText: 'article:hover', style: {getPropertyValue: name => name === 'transition' ? 'opacity 1s' : '',
          getPropertyPriority: () => ''}
      }]}];
      if (variant === 'inaccessible') f.child.doc.styleSheets = [{href: '/private.css',
        get cssRules() { throw Object.assign(new Error('blocked'), {name: 'SecurityError'}); }}];
      if (variant === 'parked') f.child.win.getComputedStyle = () => ({content: 'none',
        getPropertyValue: name => name === 'animation-name' ? 'parked-entrance' : ''});
      const result = f.snapshot();
      assert.notDeepEqual(result.gaps, []);
      assert.ok(result.documents[0].motion.sourceRules.length || result.documents[0].motion.gaps.length || variant === 'parked');
    } finally { f.observer.stop(); }
  }
});

test('parser-time cooperative child observation captures short native clocks with propagated phases and alignment', () => {
  const f = framedMotionFixture({sandbox: 'allow-same-origin allow-scripts', readyState: 'loading'});
  try {
    // A real cooperating parser calls this before stylesheet activation. Mere
    // loading readyState without that explicit call cannot claim early coverage.
    const late = f.snapshot(); assert.match(late.gaps.join('\n'), /missed parser-time/);
  } finally { f.observer.stop(); }
  const g = framedMotionFixture({sandbox: 'allow-same-origin allow-scripts', readyState: 'loading', initialURL: 'about:blank'});
  try {
    // Replace the initial unobserved about:blank before the parent sees a network
    // document, so observeFrame is the first acquisition of this document.
    const next = g.realm(); next.doc.readyState = 'loading'; next.win.frameElement = g.frame;
    g.frame.contentDocument = next.doc; g.frame.contentWindow = next.win;
    g.observer.observeFrame(next.win);
    next.doc.readyState = 'complete';
    g.observer.phase('action');
    const animation = Object.assign(new next.win.CSSAnimation(), {
      animationName: 'child-entrance', playState: 'running', playbackRate: 1, currentTime: 0,
      effect: {target: next.element, pseudoElement: null, composite: 'replace', iterationComposite: 'replace',
        getTiming: () => ({duration: 0, delay: 0, endDelay: 0, fill: 'none', direction: 'normal',
          easing: 'linear', iterations: 1, iterationStart: 0}), getKeyframes: () => [{opacity: '0'}, {opacity: '1'}]}
    });
    next.doc.active = [animation]; next.listeners.get('animationstart')({type: 'animationstart',
      target: next.element, animationName: 'child-entrance', animation});
    next.doc.active = [];
    const result = g.snapshot(), child = result.documents.at(-1);
    assert.equal(child.motion.triggered.length, 1);
    assert.equal(child.motion.triggered[0].phase, 'action');
    assert.equal(child.motion.triggered[0].timing.duration, 0);
    assert.deepEqual(child.motion.gaps, []);
    assert.equal(result.gaps.some(message => message.includes('missed parser-time')), false);
    const infinite = Object.assign(new next.win.CSSAnimation(), {...animation, animationName: 'child-caret',
      playState: 'running', currentTime: 400,
      effect: {...animation.effect, getTiming: () => ({duration: 2000, delay: 0, iterations: Infinity})},
      pause() { this.playState = 'paused'; }});
    next.doc.active = [infinite];
    assert.equal(g.observer.animations().includes(infinite), true);
    g.observer.alignInfinite(infinite);
    assert.equal(infinite.playState, 'paused'); assert.equal(infinite.currentTime, 0);
  } finally { g.observer.stop(); }
});

test('frame navigation and removal retire child records and release owned document wakeups', () => {
  const f = framedMotionFixture();
  const next = f.realm(); next.win.frameElement = f.frame;
  try {
    f.frame.contentDocument = next.doc; f.frame.contentWindow = next.win;
    f.frameListeners.get('load')();
    assert.equal(f.child.listeners.size, 0); assert.equal(f.child.frames.size, 0);
    let result = f.snapshot();
    assert.deepEqual(result.gaps, []); assert.equal(result.documents.length, 2);
    assert.deepEqual(result.documents.map(record => record.generation), [1, 2]);
    f.parent.doc.elements = []; f.observer.sample();
    assert.equal(next.listeners.size, 0); assert.equal(next.frames.size, 0);
    assert.equal(f.frameListeners.size, 0);
    result = f.snapshot(); assert.equal(result.documents.length, 2);
    assert.equal(result.documents[1].frame, 'iframe:nth-child(1)');
  } finally { f.observer.stop(); }
});

test('cross-origin, pending replacement and child shadow roots remain observation gaps', () => {
  for (const variant of ['cross-origin', 'pending', 'shadow']) {
    const f = framedMotionFixture();
    try {
      if (variant === 'cross-origin') f.frame.contentDocument = null;
      if (variant === 'pending') {
        f.frame.contentDocument = {...f.child.doc, URL: 'about:blank'};
        f.frame.contentWindow = {...f.child.win, document: f.frame.contentDocument};
      }
      if (variant === 'shadow') f.child.element.shadowRoot = {};
      const result = f.snapshot();
      assert.notDeepEqual(result.gaps, [], variant + ' cannot inherit static document coverage');
    } finally { f.observer.stop(); }
  }
});

test('a missing child native start propagates to the parent instead of a passing host-only snapshot', () => {
  const f = framedMotionFixture();
  try {
    f.child.listeners.get('animationstart')({type: 'animationstart', target: f.child.element,
      animationName: 'unobserved-child', pseudoElement: ''});
    assert.match(f.snapshot().gaps.join('\n'), /Child document.*no observable definition/);
  } finally { f.observer.stop(); }
});

test('child pending state cannot settle behind unchanged host geometry', () => {
  const f = framedMotionFixture({readyState: 'loading'});
  try {
    assert.equal(f.observer.pendingDocuments(), true);
    f.child.doc.readyState = 'complete';
    assert.equal(f.observer.pendingDocuments(), false);
    f.frame.contentDocument = null;
    assert.equal(f.observer.pendingDocuments(), true);
  } finally { f.observer.stop(); }
});

test('retired geometry uses the captured document rather than a navigated WindowProxy document', () => {
  const f = framedMotionFixture();
  try {
    const next = f.realm(); next.element.textContent = 'Replacement document';
    // Browser WindowProxy identity survives navigation while its document changes.
    f.child.win.document = next.doc; f.frame.contentDocument = next.doc;
    f.frameListeners.get('load')();
    const records = f.snapshot().documents;
    assert.equal(records[0].geometry[0].text, 'Native child content');
    assert.equal(records[1].geometry[0].text, 'Replacement document');
  } finally { f.observer.stop(); }
});

test('removing an unsupported child cannot erase a missed parser-time observation gap', () => {
  const f = framedMotionFixture({sandbox: 'allow-same-origin allow-scripts'});
  try {
    f.parent.doc.elements = []; f.observer.sample();
    const result = f.snapshot();
    assert.equal(result.documents.length, 1);
    assert.match(result.gaps.join('\n'), /missed parser-time motion observation/);
  } finally { f.observer.stop(); }
});

test('non-frame native motion snapshots retain the previous record shape exactly', () => {
  const f = startFixture();
  try {
    const result = JSON.parse(JSON.stringify(f.observer.snapshot()));
    assert.deepEqual(Object.keys(result), ['version', 'properties', 'styles', 'declarations', 'sourceRules', 'triggered', 'gaps']);
    assert.equal('documents' in result, false);
  } finally { f.observer.stop(); }
});

test('native console errors fail readiness while preserving the original logger and pagehide ownership', async () => {
  const listeners = new Map(), calls = [];
  const original = function (...args) { calls.push({owner: this, args}); };
  const console = {error: original};
  const window = {console, addEventListener: (name, handler) => listeners.set(name, handler)};
  const fonts = Object.assign([], {ready: Promise.resolve(), load: () => Promise.resolve([])});
  const document = {body: {querySelectorAll: () => []}, fonts, images: [],
    querySelector: selector => selector === '#app main' ? {} : null};
  window.document = document;
  vm.runInNewContext(readFileSync('test/browser/visual.js', 'utf8'), {
    window, document, performance, URLSearchParams, location: {search: ''},
    setTimeout: callback => setTimeout(callback, 0), clearTimeout,
    requestAnimationFrame: callback => setTimeout(callback, 0)
  });
  // Install after construction so this focused check owns no CSS observer.
  document.getAnimations = () => [];
  const nativeArgs = ['Encountered two children with the same key, `%s`.', 'gpt-message-collision', {private: 'not captured'}];
  console.error(...nativeArgs);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].owner, console);
  assert.deepEqual(calls[0].args, nativeArgs, 'Native diagnostics must still reach the original console');
  await assert.rejects(window.visualFixture.ready(), /Encountered two children.*gpt-message-collision/);
  listeners.get('pagehide')();
  assert.equal(console.error, original);

  const nextOwner = () => {};
  console.error = nextOwner;
  listeners.get('pagehide')();
  assert.equal(console.error, nextOwner, 'Disposal must preserve a later owner');
});
