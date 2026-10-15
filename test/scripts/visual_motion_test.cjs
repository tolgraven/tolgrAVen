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
    starts();
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
  const {createMotionObserver} = fixture(), listeners = new Map();
  class CSSAnimation {}
  class CSSTransition {}
  let active = [];
  const win = {document: {getAnimations: () => active, querySelectorAll: () => [],
    addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name)},
    CSSAnimation, CSSTransition,
    MutationObserver: class {observe() {} disconnect() {}}, requestAnimationFrame: () => 1, cancelAnimationFrame() {}};
  const observer = createMotionObserver(win);
  const clock = (target, kind = 'transition', pseudo = null, state = 'running') => Object.assign(
    kind === 'animation' ? new CSSAnimation() : new CSSTransition(), {
      animationName: kind === 'animation' ? 'opacity' : undefined,
      transitionProperty: kind === 'transition' ? 'opacity' : undefined,
      playState: state, playbackRate: 1,
      effect: {target, pseudoElement: pseudo, getTiming: () => ({duration: 150, iterations: 1}),
        getKeyframes: () => [{offset: 0, opacity: '0'}, {offset: 1, opacity: '1'}]}
    });
  const event = (target, type = 'transitionrun', pseudoElement = '') => listeners.get(type)({
    type, target, pseudoElement, animationName: type.startsWith('animation') ? 'opacity' : undefined,
    propertyName: type.startsWith('transition') ? 'opacity' : undefined
  });
  return {observer, clock, event, active(values) { active = values; }, listeners};
}

test('a captured native start cannot excuse a later missing occurrence at the same target and property', () => {
  const f = startFixture(), target = {nodeType: 1, localName: 'button', type: 'button'};
  try {
    f.active([f.clock(target)]); f.event(target);
    f.active([]); f.observer.phase('action'); f.event(target);
    const state = f.observer.snapshot();
    assert.equal(state.triggered.length, 1);
    assert.equal(state.gaps.length, 1);
    assert.match(state.gaps[0], /no observable definition.*button.*opacity/);
  } finally { f.observer.stop(); }
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

function returnFixture({gaps = [], policy = true, readinessFailure = false, returnedDuration = 1800, disabledSearch = false, quickSearch = false, lazySearch = false} = {}) {
  const calls = [], frames = new Map(), listeners = new Map(), timers = new Map();
  const results = {dataset: {}, append() {}}, observers = [];
  const url = 'http://127.0.0.1:4114/cv?motion-return=proof#skills';
  let next = 0, now = 0, current;
  class CSSAnimation {}
  class CSSTransition {}
  const documentFor = navigation => {
    const app = {pending: false, spinner: false,
      hasAttribute: () => app.pending,
      querySelector: selector => selector === 'main' || app.spinner ? {} : null};
    const panel = {open: false, present: navigation === 'navigate' || !lazySearch, listeners: new Map(),
      getBoundingClientRect: () => ({height: panel.open ? 80 : 0}), getAnimations: () => panel.animation ? [panel.animation] : [],
      addEventListener: (name, callback) => panel.listeners.set(name, callback),
      removeEventListener: (name, callback) => { assert.equal(panel.listeners.get(name), callback); panel.listeners.delete(name); }};
    const button = {click() {
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
    }};
    const document = {URL: url, readyState: 'complete', app, present: true, fonts: {ready: Promise.resolve()}, getAnimations: () => [],
      querySelector: selector => selector === '#app' ? app : selector === '#app .cv-intro' && document.present ? {} :
        selector === '.search-ui' ? (panel.present ? panel : null) : selector === '.search-ui-open' ? (panel.open ? panel : null) :
        selector === 'button.search-ui-btn' ? button : null,
      documentElement: {hasAttribute(name) {calls.push('policy:' + name); return policy;}}};
    const win = {document, CSSAnimation, CSSTransition,
      performance: {getEntriesByType: () => [{type: navigation}]},
      addEventListener: (name, callback) => listeners.set(document, callback),
      removeEventListener: (name, callback) => {assert.equal(listeners.get(document), callback); listeners.delete(document);}};
    document.defaultView = win;
    return document;
  };
  const previous = documentFor('navigate'), returned = documentFor('back_forward');
  returned.readyState = 'loading'; current = previous;
  const frame = {style: {}, get contentDocument() {return current;}, get contentWindow() {return current?.defaultView;},
    set src(value) {current = null; queueMicrotask(() => frame.onload());}};
  const context = {URL, URLSearchParams, location: {href: 'http://127.0.0.1:4114/__tests/motion.html',
    origin: 'http://127.0.0.1:4114', hostname: '127.0.0.1', search: ''},
    document: {querySelector: selector => selector === '#app' ? frame : selector === '#results' ? results : {},
      createElement: () => ({})},
    performance: {now: () => readinessFailure || disabledSearch ? now += 10000 : 1},
    requestAnimationFrame(callback) {frames.set(++next, callback); return next;},
    cancelAnimationFrame(id) {frames.delete(id);},
    setTimeout(callback, ms) {
      const id = ++next; timers.set(id, callback);
      if (ms === 3500 || ms === 20) queueMicrotask(() => {
        if (ms === 3500) calls.push('observation-window');
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
    '\nthis.observeBack = externalBack; this.matchesReturn = returnMatches; this.readyReturn = returnReady; this.inspectSearch = searchMotion;', context);
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


test('home native Back retains its original proof shape and a failed readiness wait releases observers', async () => {
  const home = returnFixture();
  await home.context.observeBack({selectors: ['#app .cv-intro']});
  assert.deepEqual(Object.keys(JSON.parse(home.results.dataset.externalBack)).sort(),
    ['documents', 'motion', 'observation', 'observationWindowMs', 'running', 'sameDocument']);
  const failed = returnFixture({readinessFailure: true});
  await assert.rejects(failed.context.observeBack({label: 'CV', selectors: ['#app .cv-intro']}), /did not settle/);
  assert.equal(failed.calls.includes('observation-window'), false);
  assert.ok(failed.observers.every(observer => observer.stopped));
  assert.equal(failed.frames.size, 0); assert.equal(failed.timers.size, 0); assert.equal(failed.listeners.size, 0);
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
