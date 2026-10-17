/* Test-only measurement: native rendered DOM is never replaced or restyled. */
(() => {
  const errors = [];
  if (window.console?.error) {
    const original = window.console.error;
    const observed = function (...args) {
      errors.push(args.filter(value => typeof value === 'string').join(' ') || 'Native console.error');
      return Reflect.apply(original, this, args);
    };
    window.console.error = observed;
    window.addEventListener('pagehide', () => {
      if (window.console.error === observed) window.console.error = original;
    }, {once: true});
  }
  window.addEventListener('error', event => errors.push(event.message ||
    ('Resource failed: ' + (event.target.src || event.target.href || event.target.tagName))), true);
  window.addEventListener('unhandledrejection', event => errors.push(String(event.reason)));
  const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
  const timeout = async (promise, label) => {
    let timer;
    try {
      return await Promise.race([promise, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label)), 15000);
      })]);
    } finally { clearTimeout(timer); }
  };
  function backgroundURLs(value) {
    return [...value.matchAll(/url\(["']?(.*?)["']?\)/g)].map(match => match[1]);
  }
  const MOTION = 'native-css-motion-v2';
  const motionProperties = [
    'animation-name', 'animation-duration', 'animation-delay', 'animation-timing-function',
    'animation-iteration-count', 'animation-direction', 'animation-fill-mode',
    'animation-play-state', 'animation-composition', 'animation-timeline',
    'animation-range-start', 'animation-range-end', 'transition-property',
    'transition-duration', 'transition-delay', 'transition-timing-function', 'transition-behavior'
  ];
  const canonical = value => {
    if (typeof value === 'number' && !Number.isFinite(value)) return String(value);
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object') return Object.fromEntries(
      Object.keys(value).sort().map(key => [key, canonical(value[key])]));
    return value;
  };
  function elementPath(element) {
    if (!element) throw new Error('CSS motion has no native element target');
    const parts = [];
    while (element?.nodeType === 1) {
      const siblings = element.parentElement ? [...element.parentElement.children] : [element];
      parts.unshift(element.localName + ':nth-child(' + (siblings.indexOf(element) + 1) + ')');
      element = element.parentElement;
    }
    return parts.join('>');
  }
  // Observation starts before fixture init, rather than after finite entrances
  // have vanished. Read native definitions; never compare wall-clock progress.
  function createMotionObserver(win, initialPhase = 'initial') {
    const doc = win.document, states = new WeakMap(), triggered = [], gaps = [];
    const children = new Map(), retiredDocuments = [];
    const gap = message => { if (!gaps.includes(message)) gaps.push(message); };
    function childRecord(entry) {
      return {frame: entry.path, source: entry.source, generation: entry.generation,
        geometry: geometry(entry.window, entry.document), motion: entry.observer.snapshot()};
    }
    function validateChild(entry, child) {
      const sandbox = entry.frame.getAttribute('sandbox'), path = entry.path;
      // A load listener cannot observe CSS that started and vanished during
      // parsing. Only a script-disabled, fully inspected, motion-free static
      // document can prove that late attachment missed no native CSS clocks.
      // Any dormant motion declaration also rejects this proof.
      const scriptless = sandbox !== null && !sandbox.split(/\s+/).includes('allow-scripts');
      const noMotion = !child.motion.triggered.length && !child.motion.sourceRules.length &&
        !entry.observer.animations().length && child.motion.declarations.every(([, , index]) => {
          const values = child.motion.styles[index];
          return motionProperties.every((name, index) => {
            if (name === 'animation-name') return values[index].split(',').every(value => !value.trim() || value.trim() === 'none');
            if (['transition-duration', 'transition-delay'].includes(name)) return values[index].split(',').every(value => !value.trim() || Number.parseFloat(value) === 0);
            return true;
          });
        });
      if (!entry.early && !(scriptless && noMotion))
        gap('Child document missed parser-time motion observation: ' + path);
      if (entry.document.readyState !== 'complete') gap('Child document has not completed loading: ' + path);
      for (const message of child.motion.gaps) gap('Child document ' + path + ': ' + message);
    }
    function retireChild(entry) {
      if (entry.observer) {
        try {
          const child = childRecord(entry); validateChild(entry, child); retiredDocuments.push(child);
        }
        catch (error) { gap('Cannot retain child document observation: ' + String(error)); }
        entry.observer.stop(); entry.observer = null;
      }
    }
    function observeFrame(childWindow, early = true) {
      if (stopped) throw new Error('Cannot observe a child document after disposal');
      let frame, childDocument;
      try {
        frame = childWindow.frameElement; childDocument = childWindow.document;
        if (!frame || frame.ownerDocument !== doc || frame.contentWindow !== childWindow ||
            frame.contentDocument !== childDocument) throw new Error('Child window does not belong to this document');
      } catch (error) { throw new Error('Cannot inspect child document: ' + String(error)); }
      let entry = children.get(frame);
      if (!entry) {
        entry = {frame, observer: null, generation: 0, loaded: () => syncFrames()};
        children.set(frame, entry); frame.addEventListener('load', entry.loaded);
      }
      if (entry.document === childDocument && entry.observer) return entry;
      retireChild(entry);
      entry.path = elementPath(frame);
      entry.document = childDocument; entry.window = childWindow;
      entry.source = frame.getAttribute('src'); entry.generation++;
      entry.early = early && childDocument.readyState === 'loading';
      entry.observer = createMotionObserver(childWindow, phase);
      return entry;
    }
    function syncFrames() {
      if (stopped) return;
      const frames = [...doc.querySelectorAll('iframe')].filter(element => element.tagName === 'IFRAME');
      for (const [frame, entry] of children) if (!frames.includes(frame)) {
        retireChild(entry); frame.removeEventListener('load', entry.loaded); children.delete(frame);
      }
      for (const frame of frames) {
        let entry = children.get(frame);
        if (!entry) {
          entry = {frame, observer: null, generation: 0, loaded: () => syncFrames()};
          children.set(frame, entry); frame.addEventListener('load', entry.loaded);
        }
        try {
          const childDocument = frame.contentDocument;
          if (!childDocument || !frame.contentWindow) continue;
          // Insertion first exposes an unrelated initial about:blank document.
          // Its observer cannot establish coverage of the eventual network page.
          if (childDocument.URL === 'about:blank' && frame.getAttribute('src') &&
              frame.getAttribute('src') !== 'about:blank') continue;
          if (childDocument !== entry.document) observeFrame(frame.contentWindow, false);
          else entry.path = elementPath(frame);
        } catch (_) { /* Snapshot reports inaccessible/pending children fail closed. */ }
      }
    }
    const pendingStarts = new Map(), started = new WeakSet(), openStarts = [];
    const definitions = new WeakMap(), activated = new WeakSet(), alignedEnds = new Map();
    const CSSAnimation = win.CSSAnimation, CSSTransition = win.CSSTransition;
    const requestFrame = win.requestAnimationFrame.bind(win), cancelFrame = win.cancelAnimationFrame.bind(win);
    let phase = initialPhase, stopped = false, request = null, nextOccurrence = 0;
    const pseudoElements = new Set(['::before', '::after', '::marker', '::backdrop',
      '::first-letter', '::first-line', '::placeholder', '::file-selector-button']);
    function definition(animation, kind) {
      const effect = animation.effect;
      if (!effect) throw new Error('CSS motion has no native effect');
      const target = effect.target?.element || effect.target;
      // Native form-control type attributes are not pseudo selectors.
      const pseudo = effect.pseudoElement || (effect.target?.element && effect.target.type) || null;
      const name = kind === 'animation' ? animation.animationName : animation.transitionProperty;
      return {animation, target, pseudo, kind, name, value: canonical({
        phase, target: elementPath(target), pseudo, kind, name,
        timing: effect.getTiming(), keyframes: effect.getKeyframes(),
        composite: effect.composite, iterationComposite: effect.iterationComposite,
        playbackRate: animation.playbackRate})};
    }
    function complete(entry) {
      const value = entry.value;
      return ['delay', 'direction', 'duration', 'easing', 'endDelay', 'fill',
        'iterationStart', 'iterations'].every(key => value.timing?.[key] !== undefined) &&
        Array.isArray(value.keyframes) &&
        value.keyframes.every(frame => frame && typeof frame === 'object' && !Array.isArray(frame)) &&
        value.composite !== undefined &&
        Number.isFinite(value.playbackRate);
    }
    function capture(animation, entry, canceled = false) {
      if (triggered.length >= 4096) throw new Error('CSS motion observation exceeds 4096 instances');
      if (entry.pseudo) pseudoElements.add(entry.pseudo);
      triggered.push(canceled ? {...entry.value, activation: 'canceled-before-observation'} : entry.value);
      definitions.set(animation, entry);
      activated.add(animation);
    }
    function sample() {
      if (stopped) return;
      syncFrames();
      for (const entry of children.values()) entry.observer?.sample();
      for (const animation of doc.getAnimations()) {
        const kind = animation instanceof CSSAnimation ? 'animation' :
          animation instanceof CSSTransition ? 'transition' : null;
        if (!kind) continue;
        const previous = states.get(animation), state = animation.playState;
        states.set(animation, state);
        if (state === 'running') alignedEnds.delete(animation);
        // Parked creation is not activation. Preserve every actual resume, and
        // finished clocks that ran between samples, including zero duration.
        if (!['running', 'finished'].includes(state) || state === previous ||
          (state === 'finished' && previous === 'running')) continue;
        try {
          const entry = definition(animation, kind);
          capture(animation, entry);
          if (previous === 'finished') started.delete(animation);
          if (!started.has(animation) && !pendingStarts.has(animation))
            pendingStarts.set(animation, {...entry, order: ++nextOccurrence});
        } catch (error) { gaps.push(String(error)); }
      }
    }
    function exactEvent(event, animation, kind, name, pseudo, starts) {
      const gap = message => gaps.push('Native CSS event has ' + message + ': ' + elementPath(event.target) + ' ' + name);
      try {
        if (!(kind === 'animation' ? animation instanceof CSSAnimation : animation instanceof CSSTransition))
          throw new Error('a mismatched animation kind');
        let entry;
        try { entry = definition(animation, kind); }
        catch (error) {
          // A complete definition already captured from this exact object can
          // survive effect removal. It cannot authorize a different object.
          let effect;
          try { effect = animation.effect; } catch (_) { /* Retained receipt below. */ }
          if (effect) throw error;
          entry = definitions.get(animation);
          if (!entry) throw error;
        }
        if (entry.target !== event.target || entry.kind !== kind || entry.name !== name || entry.pseudo !== pseudo)
          throw new Error('a mismatched native target/name/pseudo');
        if (!complete(entry)) throw new Error('an incomplete native definition');
        if (starts) {
          if (started.has(animation) && !pendingStarts.has(animation))
            throw new Error('an already consumed native start');
          const state = animation.playState, previous = states.get(animation);
          if (!activated.has(animation) && state === 'idle') {
            // transitionrun identifies a real CSS transition even if it was
            // canceled while pending. CSS animationstart can also occur while
            // initially paused, so an unknown canceled animation stays a gap.
            if (kind !== 'transition' || previous === 'paused')
              throw new Error('an unobserved activation after cancellation');
            capture(animation, entry, true);
          } else if (!activated.has(animation) && ['running', 'finished'].includes(state)) {
            if (previous === 'paused') throw new Error('an unobserved activation after a parked start');
            capture(animation, entry);
          }
          definitions.set(animation, entry);
          states.set(animation, state);
          openStarts.push({...entry, order: pendingStarts.get(animation)?.order || ++nextOccurrence});
          pendingStarts.delete(animation);
          started.add(animation);
        } else {
          const aligned = alignedEnds.get(animation);
          const alignedEnd = aligned && event.type === 'animationend' && event.elapsedTime === 0 &&
            animation.playState === 'paused' && animation.currentTime === 0 &&
            animation.effect === aligned.effect && JSON.stringify(entry.value.timing) === aligned.timing;
          alignedEnds.delete(animation);
          if (alignedEnd) started.delete(animation);
          else if (!activated.has(animation) && event.type.endsWith('end') ||
            states.get(animation) === 'paused' && (event.type.endsWith('end') || animation.playState === 'idle'))
            gap('an unobserved activation before completion');
          pendingStarts.delete(animation);
          const index = openStarts.findIndex(entry => entry.animation === animation);
          if (index !== -1) openStarts.splice(index, 1);
        }
      } catch (error) { gap(String(error)); }
    }
    function alignInfinite(animation) {
      if (stopped) return;
      for (const entry of children.values()) {
        if (entry.observer?.animations().includes(animation)) return entry.observer.alignInfinite(animation);
      }
      sample();
      const timing = animation.effect.getTiming();
      if (animation.playState !== 'running' || timing.iterations !== Infinity) return;
      // Seeking an active, delayed CSS animation into its before phase emits a
      // native animationend, even with infinite iterations. Acknowledge only
      // this observer-owned backwards seek; actual unobserved resumes still fail.
      if (animation instanceof CSSAnimation && activated.has(animation) &&
          timing.delay > 0 && Number.isFinite(animation.currentTime) && animation.currentTime >= timing.delay) {
        alignedEnds.set(animation, {effect: animation.effect, timing: JSON.stringify(canonical(timing))});
      }
      try {
        animation.pause(); animation.currentTime = 0;
        states.set(animation, animation.playState);
      } catch (error) {
        alignedEnds.delete(animation);
        throw error;
      }
    }
    function tick() {
      request = null;
      sample();
      if (!stopped && doc.getAnimations().some(animation => animation.playState === 'running'))
        request = requestFrame(tick);
    }
    function wake(event) {
      if (stopped) return;
      sample();
      if (event && /^(animation|transition)(start|run|cancel|end)$/.test(event.type)) {
        const kind = event.type.startsWith('animation') ? 'animation' : 'transition';
        const name = event.animationName || event.propertyName;
        const pseudo = event.pseudoElement || null;
        const matches = entry => entry.target === event.target && entry.kind === kind &&
          entry.name === name && entry.pseudo === pseudo;
        const starts = ['animationstart', 'transitionrun'].includes(event.type);
        let exact, failed = false;
        try { exact = event.animation; }
        catch (error) { gaps.push('Cannot read native CSS event animation: ' + String(error)); failed = true; }
        if (failed || exact != null) {
          if (!failed) exactEvent(event, exact, kind, name, pseudo, starts);
          if (!stopped && request === null) request = requestFrame(tick);
          return;
        }
        const current = doc.getAnimations();
        const matching = current.filter(animation => {
          const effect = animation.effect;
          return matches({target: effect?.target?.element || effect?.target,
            kind: animation instanceof CSSAnimation ? 'animation' :
              animation instanceof CSSTransition ? 'transition' : null,
            name: animation.animationName || animation.transitionProperty,
            pseudo: effect?.pseudoElement || (effect?.target?.element && effect.target.type) || null});
        });
        if (['animationstart', 'transitionrun'].includes(event.type)) {
          // A retained fill-both object has already acknowledged its start;
          // it cannot stand in for a later short clock that disappeared.
          const available = matching.find(animation => pendingStarts.has(animation)) ||
            matching.find(animation => !started.has(animation));
          const queued = available ? null : [...pendingStarts].find(([, entry]) => matches(entry));
          const animation = available || queued?.[0];
          openStarts.push(pendingStarts.get(animation) ||
            {animation, target: event.target, kind, name, pseudo, order: ++nextOccurrence});
          if (animation) {
            pendingStarts.delete(animation);
            started.add(animation);
          } else gaps.push('Native CSS start had no observable definition: ' + elementPath(event.target) + ' ' + name);
        } else {
          // Native Strava queues an old cancel before a later reverse start,
          // after the reverse finishes. Preserve its receipt behind the older
          // occurrence, including when the older start preceded observation.
          const observed = openStarts.filter(matches).sort((a, b) => a.order - b.order)[0];
          const retired = [...pendingStarts].filter(([animation, entry]) =>
            matches(entry) && !current.includes(animation))
            .sort((a, b) => a[1].order - b[1].order)[0];
          if (retired && (!observed || retired[1].order < observed.order))
            pendingStarts.delete(retired[0]);
          else if (observed) openStarts.splice(openStarts.indexOf(observed), 1);
        }
      }
      if (!stopped && request === null) request = requestFrame(tick);
    }
    const events = ['animationstart', 'animationcancel', 'animationend',
      'transitionrun', 'transitioncancel', 'transitionend',
      'pointerover', 'pointerout', 'pointerdown', 'pointerup', 'pointercancel',
      'focusin', 'focusout', 'keydown', 'keyup'];
    events.forEach(name => doc.addEventListener(name, wake, true));
    const mutations = new win.MutationObserver(wake);
    mutations.observe(doc, {subtree: true, childList: true, attributes: true});
    wake();
    return {
      sample, alignInfinite, observeFrame,
      animations() {
        syncFrames();
        return [...doc.getAnimations(), ...[...children.values()].flatMap(entry => entry.observer?.animations() || [])];
      },
      documents() {
        syncFrames();
        return [doc, ...[...children.values()].flatMap(entry => entry.observer?.documents() || [])];
      },
      pendingDocuments() {
        syncFrames();
        return [...children.values()].some(entry => {
          try {
            return !entry.observer || entry.frame.contentDocument !== entry.document ||
              entry.document.readyState !== 'complete' || entry.observer.pendingDocuments();
          } catch (_) { return true; }
        });
      },
      phase(value) { sample(); phase = value; for (const entry of children.values()) entry.observer?.phase(value); },
      stop() {
        sample(); stopped = true;
        if (request !== null) cancelFrame(request);
        mutations.disconnect();
        for (const [frame, entry] of children) {
          retireChild(entry); frame.removeEventListener('load', entry.loaded);
        }
        children.clear();
        pendingStarts.clear(); openStarts.length = 0; alignedEnds.clear();
        events.forEach(name => doc.removeEventListener(name, wake, true));
      },
      snapshot() {
        sample();
        const styles = [], indexes = new Map(), declarations = [], sourceRules = [], sheets = new Set();
        const declared = (style, properties) => Object.fromEntries(properties
          .filter(name => style.getPropertyValue(name))
          .map(name => [name, [style.getPropertyValue(name), style.getPropertyPriority(name)]]));
        const inspectRules = (rules, scope) => {
          for (const rule of rules) {
            for (const match of (rule.selectorText || '').matchAll(/::[a-z-]+(?:\([^()]*\))?/gi))
              pseudoElements.add(match[0]);
            if (win.CSSPropertyRule && rule instanceof win.CSSPropertyRule) {
              // Registrations control interpolation/inheritance even when
              // keyframe endpoints and the final painted layout stay equal.
              sourceRules.push({scope, property: rule.name, syntax: rule.syntax,
                inherits: rule.inherits, initialValue: rule.initialValue});
            } else if (rule.type === 7) {
              // Include dormant keyframes too: their next hover or viewport
              // activation must not escape a technical-change comparison.
              sourceRules.push({scope, keyframes: rule.name, frames: [...rule.cssRules].map(frame =>
                ({offset: frame.keyText, properties: declared(frame.style, [...frame.style])}))});
            } else {
              if (rule.selectorText && rule.style) {
                const properties = declared(rule.style, ['animation', 'transition', ...motionProperties]);
                if (Object.keys(properties).length) sourceRules.push({scope, selector: rule.selectorText, properties});
              }
              if (rule.cssRules) inspectRules(rule.cssRules,
                [...scope, {type: rule.type, kind: rule.constructor.name,
                  selector: rule.selectorText || null, condition: rule.conditionText || null, name: rule.name || null}]);
            }
            if (rule.styleSheet) inspectSheet(rule.styleSheet, [...scope, {import: rule.href,
              media: rule.media?.mediaText || '', supports: rule.supportsText || null,
              layer: rule.layerName ?? null}]);
          }
        };
        const inspectSheet = (sheet, scope = []) => {
          if (sheets.has(sheet)) return;
          sheets.add(sheet);
          try { inspectRules(sheet.cssRules, [...scope, {sheet: sheet.href || 'inline',
            media: sheet.media?.mediaText || '', disabled: sheet.disabled === true}]); }
          catch (error) {
            const gap = 'Cannot inspect CSS motion declarations: ' + (sheet.href || 'inline') + ': ' + error.name;
            if (!gaps.includes(gap)) gaps.push(gap);
          }
        };
        [...doc.styleSheets || [], ...doc.adoptedStyleSheets || []].forEach(sheet => inspectSheet(sheet));
        for (const element of doc.querySelectorAll('*')) {
          if (['SCRIPT', 'STYLE', 'LINK', 'META', 'TITLE', 'HEAD'].includes(element.tagName)) continue;
          if (element.shadowRoot) {
            const gap = 'Separate document/shadow root requires its own motion observer: ' + elementPath(element);
            if (!gaps.includes(gap)) gaps.push(gap);
          }
          for (const pseudo of [null, ...pseudoElements].sort()) {
            let style;
            try { style = win.getComputedStyle(element, pseudo); }
            catch (error) {
              const gap = 'Cannot inspect pseudo declaration: ' + pseudo + ': ' + error.name;
              if (!gaps.includes(gap)) gaps.push(gap);
              continue;
            }
            const values = motionProperties.map(name => style.getPropertyValue(name));
            const key = JSON.stringify(values);
            if (!indexes.has(key)) { indexes.set(key, styles.length); styles.push(values); }
            declarations.push([elementPath(element), pseudo, indexes.get(key)]);
          }
        }
        const documents = [...retiredDocuments];
        for (const [frame, entry] of children) {
          const path = elementPath(frame);
          let readable = false;
          try { readable = frame.contentDocument === entry.document && !!entry.observer; } catch (_) {}
          if (!readable) { gap('Inaccessible or pending child document requires its own motion observer: ' + path); continue; }
          const child = childRecord(entry);
          validateChild(entry, child);
          documents.push(child);
        }
        return {version: MOTION, properties: motionProperties, styles, declarations, sourceRules,
          triggered: [...triggered].sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))),
          ...(documents.length ? {documents} : {}), gaps: [...gaps]};
      }
    };
  }
  function imageRequired(image, win = window) {
    // A selected/cached currentSrc does not release Chrome's native lazy-load gate.
    // Each visible tile waits again after scrolling brings the image into view.
    if (image.loading !== 'lazy' || image.complete) return true;
    const box = image.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && box.bottom > 0 && box.right > 0 &&
      box.top < win.innerHeight && box.left < win.innerWidth;
  }
  const decodedImages = new WeakMap();
  async function imageReady(image) {
    if (decodedImages.get(image) === image.currentSrc && image.complete && image.naturalWidth) return;
    if (!image.complete) await new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); image.removeEventListener('load', loaded); image.removeEventListener('error', failed); };
      const loaded = () => { cleanup(); resolve(); };
      const failed = () => { cleanup(); reject(new Error('Fixture image failed: ' + image.getAttribute('src'))); };
      const timer = setTimeout(() => { cleanup(); reject(new Error('Fixture image did not load: ' + image.getAttribute('src'))); }, 15000);
      image.addEventListener('load', loaded, {once: true});
      image.addEventListener('error', failed, {once: true});
      if (image.complete) loaded();
    });
    if (!image.naturalWidth) throw new Error('Fixture image failed: ' + image.getAttribute('src'));
    await image.decode();
    decodedImages.set(image, image.currentSrc);
  }
  const motion = typeof document !== 'undefined' && document.getAnimations ? createMotionObserver(window) : null;
  function generated(element, selector, win = window) {
    const style = win.getComputedStyle(element, selector);
    if (style.content === 'none' || style.content === 'normal') return null;
    return {
      content: style.content, display: style.display, position: style.position,
      width: style.width, height: style.height,
      offsets: [style.top, style.right, style.bottom, style.left],
      padding: [style.paddingTop, style.paddingRight, style.paddingBottom, style.paddingLeft],
      margin: [style.marginTop, style.marginRight, style.marginBottom, style.marginLeft],
      border: [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth],
      borderColor: style.borderColor, borderRadius: style.borderRadius,
      color: style.color, backgroundColor: style.backgroundColor,
      backgroundImage: style.backgroundImage,
      mask: [style.maskImage, style.maskSize, style.maskPosition],
      font: [style.fontFamily, style.fontSize, style.fontWeight, style.lineHeight, style.letterSpacing],
      opacity: style.opacity, visibility: style.visibility, zIndex: style.zIndex,
      transform: style.transform, filter: style.filter,
      boxShadow: style.boxShadow, textShadow: style.textShadow
    };
  }
  function geometry(win = window, doc = win.document) {
    const selected = [...doc.body.querySelectorAll('*')].filter(element =>
      !['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName));
    return selected.map(element => {
      const box = element.getBoundingClientRect();
      const parent = element.parentElement.getBoundingClientRect();
      const style = win.getComputedStyle(element);
      return {
        tag: element.tagName, id: element.id, class: element.className,
        selector: element.id ? '#' + element.id : element.tagName.toLowerCase() +
          (typeof element.className === 'string' && element.className.trim() ? '.' + element.className.trim().split(/\s+/).join('.') : ''),
        role: element.getAttribute('role'),
        text: element.children.length ? null : element.textContent,
        box: [box.x, box.y, box.width, box.height],
        relative: [box.x - parent.x, box.y - parent.y],
        scroll: [element.scrollWidth, element.scrollHeight, element.clientWidth, element.clientHeight],
        scrollPosition: [element.scrollLeft, element.scrollTop],
        padding: [style.paddingTop, style.paddingRight, style.paddingBottom, style.paddingLeft],
        margin: [style.marginTop, style.marginRight, style.marginBottom, style.marginLeft],
        border: [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth],
        display: style.display, position: style.position,
        font: [style.fontFamily, style.fontSize, style.fontWeight, style.lineHeight],
        gap: [style.rowGap, style.columnGap],
        overflow: [style.overflowX, style.overflowY],
        appearance: {
          color: style.color, backgroundColor: style.backgroundColor,
          backgroundImage: style.backgroundImage, borderColor: style.borderColor,
          boxShadow: style.boxShadow, textShadow: style.textShadow,
          opacity: style.opacity, visibility: style.visibility,
          transform: style.transform, filter: style.filter,
          alignItems: style.alignItems, justifyContent: style.justifyContent,
          flexDirection: style.flexDirection, textAlign: style.textAlign
        },
        generated: {before: generated(element, '::before', win), after: generated(element, '::after', win)}
      };
    });
  }
  async function ready() {
    const startedAt = performance.now(), deadline = startedAt + 15000;
    while (!document.querySelector('#app main') && performance.now() < deadline) await frame();
    if (!document.querySelector('#app main')) throw new Error('Native fixture did not mount');
    const minimum = Number(new URLSearchParams(location.search).get('settle') || 0);
    if (minimum > 0) await pause(minimum);
    await timeout(document.fonts.ready, 'Fonts did not become ready');
    const fonts = new Set([...document.body.querySelectorAll('*')].map(element => getComputedStyle(element).font).filter(Boolean));
    await timeout(Promise.all([...fonts].map(font => document.fonts.load(font))), 'Consumer fonts did not load');
    await Promise.all([...document.images].filter(image => imageRequired(image)).map(imageReady));
    const backgrounds = new Set([...document.body.querySelectorAll('*')].flatMap(element =>
      [null, '::before', '::after'].flatMap(selector => backgroundURLs(getComputedStyle(element, selector).backgroundImage))));
    await timeout(Promise.all([...backgrounds].map(src => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => image.decode().then(resolve, reject);
      image.onerror = () => reject(new Error('Fixture background failed: ' + src));
      image.src = src;
    }))), 'Background images did not decode');
    // Finish native finite entrances first, then align infinite clocks before
    // comparing computed appearance: a fading caret never has stable opacity.
    let stable = 0, previous = '', penultimate = '';
    while (performance.now() < deadline) {
      motion?.sample();
      await Promise.all([...document.images].filter(image => imageRequired(image)).map(imageReady));
      const childDocuments = (motion?.documents() || [document]).slice(1);
      for (const child of childDocuments) {
        const childWindow = child.defaultView;
        await timeout(child.fonts.ready, 'Child document fonts did not become ready');
        const childFonts = new Set([...child.querySelectorAll('*')].map(element => childWindow.getComputedStyle(element).font).filter(Boolean));
        await timeout(Promise.all([...childFonts].map(font => child.fonts.load(font))), 'Child consumer fonts did not load');
        await Promise.all([...child.images].filter(image => imageRequired(image, childWindow)).map(imageReady));
        const childBackgrounds = new Set([...child.querySelectorAll('*')].flatMap(element =>
          [null, '::before', '::after'].flatMap(selector => backgroundURLs(childWindow.getComputedStyle(element, selector).backgroundImage))));
        await timeout(Promise.all([...childBackgrounds].map(src => new Promise((resolve, reject) => {
          const image = new Image();
          image.onload = () => image.decode().then(resolve, reject);
          image.onerror = () => reject(new Error('Child fixture background failed: ' + src));
          image.src = new URL(src, child.baseURI).href;
        }))), 'Child background images did not decode');
      }
      const animations = motion?.animations() || document.getAnimations();
      const finite = animations.filter(animation =>
        animation.playState === 'running' && animation.effect.getTiming().iterations !== Infinity);
      if (!finite.length) {
        for (const animation of animations) {
          if (animation.effect.getTiming().iterations === Infinity && animation.playState === 'running') {
            motion.alignInfinite(animation);
          }
        }
      }
      const current = JSON.stringify(childDocuments.length ?
        [geometry(), childDocuments.map(child => geometry(child.defaultView))] : geometry());
      const pendingFont = document.querySelector('[data-font-phase="initial"], [data-font-phase="fallback"], [data-font-phase="prepared"], [data-font-phase="crossfade"]');
      if (document.querySelector('[data-font-phase="failed"]')) throw new Error('Native fixture font upgrade failed');
      stable = current === previous && !finite.length && !pendingFont && !motion?.pendingDocuments() ? stable + 1 : 0;
      if (stable >= 4) break;
      penultimate = previous; previous = current;
      await pause(100);
    }
    if (stable < 4) {
      const before = penultimate ? JSON.parse(penultimate) : [];
      const after = previous ? JSON.parse(previous) : [];
      let changed = before.length === after.length ? null :
        {field: 'element-count', before: before.length, after: after.length};
      for (let index = 0; !changed && index < after.length; index++) {
        for (const field of Object.keys(after[index])) {
          if (JSON.stringify(before[index]?.[field]) !== JSON.stringify(after[index][field])) {
            changed = {selector: after[index].selector, index, field,
              before: before[index]?.[field], after: after[index][field]};
            break;
          }
        }
      }
      const finite = (motion?.animations() || document.getAnimations()).filter(animation => animation.playState === 'running' &&
        animation.effect.getTiming().iterations !== Infinity);
      const pending = [...document.querySelectorAll('[data-font-phase]')].filter(element =>
        ['initial', 'fallback', 'prepared', 'crossfade'].includes(element.dataset.fontPhase));
      throw new Error('Layout or native motion did not settle: ' + JSON.stringify({
        elapsedMs: performance.now() - startedAt, stableSamples: stable, sampled: !!previous,
        visibility: document.visibilityState,
        finiteCount: finite.length,
        finite: finite.slice(0, 8).map(animation => ({name: animation.animationName || animation.transitionProperty,
          target: elementPath(animation.effect.target?.element || animation.effect.target),
          timing: canonical(animation.effect.getTiming())})),
        pendingFontCount: pending.length,
        pendingFonts: pending.slice(0, 8).map(element => ({selector: elementPath(element), phase: element.dataset.fontPhase})),
        changed
      }));
    }
    await frame(); await frame();
    if (document.querySelector('[role="alert"], .component-error')) throw new Error('Native fixture contains an error fallback');
    if (errors.length) throw new Error(errors.join('\n'));
  }
  window.visualFixture = {
    ready, backgroundURLs, imageRequired, createMotionObserver, motion,
    observeFrame: childWindow => motion.observeFrame(childWindow),
    snapshot: () => ({
      viewport: [innerWidth, innerHeight, devicePixelRatio],
      document: [scrollX, scrollY, document.scrollingElement.scrollHeight],
      environment: {userAgent: navigator.userAgent, platform: navigator.platform,
        calendarEpoch: window.visualClockEpoch,
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        language: navigator.language, viewport: [innerWidth, innerHeight, devicePixelRatio],
        fonts: [...document.fonts].map(font => [font.family, font.style, font.weight, font.status]).sort()},
      geometry: geometry(), motion: motion?.snapshot(), errors
    })
  };
})();
