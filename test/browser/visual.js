/* Test-only measurement: native rendered DOM is never replaced or restyled. */
(() => {
  const errors = [];
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
  const MOTION = 'native-css-motion-v1';
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
    const doc = win.document, seen = new WeakSet(), triggered = [], gaps = [];
    const CSSAnimation = win.CSSAnimation, CSSTransition = win.CSSTransition;
    const requestFrame = win.requestAnimationFrame.bind(win), cancelFrame = win.cancelAnimationFrame.bind(win);
    let phase = initialPhase, stopped = false, request = null;
    const pseudoElements = new Set(['::before', '::after', '::marker', '::backdrop',
      '::first-letter', '::first-line', '::placeholder', '::file-selector-button']);
    function sample() {
      if (stopped) return;
      for (const animation of doc.getAnimations()) {
        const kind = animation instanceof CSSAnimation ? 'animation' :
          animation instanceof CSSTransition ? 'transition' : null;
        if (!kind || seen.has(animation)) continue;
        seen.add(animation);
        try {
          const effect = animation.effect;
          const target = effect.target?.element || effect.target;
          const pseudo = effect.pseudoElement || effect.target?.type || null;
          if (pseudo) pseudoElements.add(pseudo);
          if (triggered.length >= 4096) throw new Error('CSS motion observation exceeds 4096 instances');
          triggered.push(canonical({phase, target: elementPath(target), pseudo, kind,
            name: kind === 'animation' ? animation.animationName : animation.transitionProperty,
            timing: effect.getTiming(), keyframes: effect.getKeyframes(),
            composite: effect.composite, iterationComposite: effect.iterationComposite,
            playbackRate: animation.playbackRate}));
        } catch (error) { gaps.push(String(error)); }
      }
    }
    function tick() {
      request = null;
      sample();
      if (!stopped && doc.getAnimations().some(animation => animation.playState === 'running'))
        request = requestFrame(tick);
    }
    function wake(event) {
      sample();
      if (event && ['animationstart', 'transitionrun'].includes(event.type)) {
        const name = event.animationName || event.propertyName;
        const target = elementPath(event.target);
        if (!triggered.some(entry => entry.target === target && entry.name === name &&
          entry.pseudo === (event.pseudoElement || null)))
          gaps.push('Native CSS start had no observable definition: ' + target + ' ' + name);
      }
      if (!stopped && request === null) request = requestFrame(tick);
    }
    const events = ['animationstart', 'animationcancel', 'animationend',
      'transitionrun', 'transitioncancel', 'transitionend'];
    events.forEach(name => doc.addEventListener(name, wake, true));
    const mutations = new win.MutationObserver(wake);
    mutations.observe(doc, {subtree: true, childList: true, attributes: true});
    wake();
    return {
      sample,
      phase(value) { sample(); phase = value; },
      stop() {
        sample(); stopped = true;
        if (request !== null) cancelFrame(request);
        mutations.disconnect();
        events.forEach(name => doc.removeEventListener(name, wake, true));
      },
      snapshot() {
        sample();
        const styles = [], indexes = new Map(), declarations = [], sheets = new Set();
        const inspectRules = rules => {
          for (const rule of rules) {
            for (const match of (rule.selectorText || '').matchAll(/::[a-z-]+(?:\([^()]*\))?/gi))
              pseudoElements.add(match[0]);
            if (rule.cssRules) inspectRules(rule.cssRules);
            if (rule.styleSheet) inspectSheet(rule.styleSheet);
          }
        };
        const inspectSheet = sheet => {
          if (sheets.has(sheet)) return;
          sheets.add(sheet);
          try { inspectRules(sheet.cssRules); }
          catch (error) {
            const gap = 'Cannot inspect CSS pseudo declarations: ' + (sheet.href || 'inline') + ': ' + error.name;
            if (!gaps.includes(gap)) gaps.push(gap);
          }
        };
        [...doc.styleSheets || []].forEach(inspectSheet);
        for (const element of doc.querySelectorAll('*')) {
          if (['SCRIPT', 'STYLE', 'LINK', 'META', 'TITLE', 'HEAD'].includes(element.tagName)) continue;
          if (element.shadowRoot || element.tagName === 'IFRAME') {
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
        return {version: MOTION, properties: motionProperties, styles, declarations,
          triggered: [...triggered].sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))),
          gaps: [...gaps]};
      }
    };
  }
  function imageRequired(image, win = window) {
    if (image.loading !== 'lazy' || image.complete || image.currentSrc) return true;
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
  function generated(element, selector) {
    const style = getComputedStyle(element, selector);
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
  function geometry() {
    const selected = [...document.body.querySelectorAll('*')].filter(element =>
      !['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName));
    return selected.map(element => {
      const box = element.getBoundingClientRect();
      const parent = element.parentElement.getBoundingClientRect();
      const style = getComputedStyle(element);
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
        generated: {before: generated(element, '::before'), after: generated(element, '::after')}
      };
    });
  }
  async function ready() {
    const deadline = performance.now() + 15000;
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
    let stable = 0, previous = '';
    while (performance.now() < deadline) {
      motion?.sample();
      await Promise.all([...document.images].filter(image => imageRequired(image)).map(imageReady));
      const animations = document.getAnimations();
      const finite = animations.filter(animation =>
        animation.playState === 'running' && animation.effect.getTiming().iterations !== Infinity);
      if (!finite.length) {
        for (const animation of animations) {
          if (animation.effect.getTiming().iterations === Infinity && animation.playState === 'running') {
            animation.pause(); animation.currentTime = 0;
          }
        }
      }
      const current = JSON.stringify(geometry());
      const pendingFont = document.querySelector('[data-font-phase="initial"], [data-font-phase="fallback"], [data-font-phase="prepared"], [data-font-phase="crossfade"]');
      if (document.querySelector('[data-font-phase="failed"]')) throw new Error('Native fixture font upgrade failed');
      stable = current === previous && !finite.length && !pendingFont ? stable + 1 : 0;
      if (stable >= 4) break;
      previous = current;
      await pause(100);
    }
    if (stable < 4) throw new Error('Layout or native motion did not settle');
    await frame(); await frame();
    if (document.querySelector('[role="alert"], .component-error')) throw new Error('Native fixture contains an error fallback');
    if (errors.length) throw new Error(errors.join('\n'));
  }
  window.visualFixture = {
    ready, backgroundURLs, imageRequired, createMotionObserver, motion,
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
