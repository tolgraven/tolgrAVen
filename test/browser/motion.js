const frame = document.querySelector('#app'), results = document.querySelector('#results');
if (new URLSearchParams(location.search).has('phone')) frame.style.width = '24.375rem';
const doc = () => frame.contentDocument;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const wait = async predicate => {
  const end = performance.now() + 30000;
  while (performance.now() < end) {
    const failed = doc()?.querySelector('main .component-failed');
    if (failed) throw new Error(failed.textContent);
    if (await predicate()) return;
    await sleep(20);
  }
  throw new Error('Application did not settle');
};
const check = (value, label, details) => {
  const viewport = frame.getBoundingClientRect();
  if (viewport.top < 0 || viewport.left < 0 || viewport.bottom > innerHeight || viewport.right > innerWidth)
    throw new Error('Keep the complete application frame visible; use an outer viewport at least 1200 CSS pixels high');
  if (!value) throw new Error(label + (details ? ` ${JSON.stringify(details)}` : ""));
  const row = document.createElement('li'); row.className = 'passed'; row.textContent = label; results.append(row);
};
const sample = async (action, read, ms = 1400, ready) => {
  const values = [read()], deadline = performance.now() + 30000;
  let end = ready ? null : performance.now() + ms;
  action();
  while (end === null || performance.now() < end) {
    if (performance.now() >= deadline) throw new Error('Motion sampling did not reach its native content');
    await new Promise(resolve => requestAnimationFrame(resolve));
    const value = read(); values.push(value);
    if (end === null && ready(value)) end = performance.now() + ms;
  }
  return values;
};
// Evaluate measured layout and body motion, never the presence of a CSS class.
const replyMotionEvidence = samples => [...new Set(samples.flatMap(value => value.rows.map(row => row.id)))].map(id => {
  const captured = samples.flatMap(value => {
    const row = value.rows.find(row => row.id === id);
    return row ? [{...row, time: value.time}] : [];
  });
  const first = captured[0], final = captured.at(-1);
  const intermediate = captured.filter(row => row.height > 1 && row.height < final.height - 1);
  const steps = captured.map((row, index) => row.height - (captured[index - 1]?.height || 0));
  return {id, delay: final.delay, bodyDelay: final.bodyDelay,
    duration: final.duration, bodyDuration: final.bodyDuration,
    properties: final.properties, bodyProperties: final.bodyProperties,
    visible: first.inViewport && !first.settling,
    started: captured.find(row => row.height > 1)?.time,
    finalHeight: final.height, intermediateHeights: [...new Set(intermediate.map(row => Math.round(row.height * 10) / 10))],
    continuous: intermediate.length >= 2 && steps.every(step => step >= -1 && step < final.height * .75),
    fading: captured.some(row => row.opacity > .01 && row.opacity < .99),
    sliding: captured.some(row => Math.abs(row.translateY) > .1),
    settled: final.height > 1 && final.opacity >= .99 && Math.abs(final.translateY) < .1};
});
const settleLayout = async element => {
  await doc().fonts.ready;
  const animations = doc().getAnimations().filter(animation => {
    const target = animation.effect?.target;
    return target instanceof frame.contentWindow.Element &&
      (target.contains(element) || element.contains(target)) &&
      Number.isFinite(animation.effect.getComputedTiming().endTime);
  });
  await Promise.all(animations.map(animation => animation.finished.catch(() => {})));
};
const heroReady = () => {
  const hero = doc()?.querySelector('#app #top-banner');
  if (!hero) return false;
  const images = hero.matches('img') ? [hero] : [...hero.querySelectorAll('img')];
  const placeholder = hero.hasAttribute('data-image-placeholder') &&
    frame.contentWindow.getComputedStyle(hero).backgroundImage !== 'none';
  return placeholder || images.some(image => {
    let layer = image;
    while (layer && layer !== hero.parentElement) {
      const style = frame.contentWindow.getComputedStyle(layer);
      if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) <= 0) return false;
      layer = layer.parentElement;
    }
    return image.complete && image.naturalWidth > 0;
  });
};

// Sample the real document from its first paint through interactive startup.
// Do not substitute data or manipulate application-owned markup.
const sampleStartup = async path => {
  const samples = [], end = performance.now() + 45000;
  frame.src = path;
  let completeAt = null;
  while (performance.now() < end) {
    await new Promise(resolve => requestAnimationFrame(resolve));
    const mains = doc()?.querySelectorAll('#ssr-shell main, #app main');
    if (mains?.length && frame.contentWindow.location.pathname === '/') {
      // Include the flushed shell and completed page to check visibility
      // through their handoff, including a streamed cache miss.
      for (const main of mains) {
        const style = frame.contentWindow.getComputedStyle(main);
        const titleNode = doc().querySelector('#app .intro-title-letter');
        const logoAnimation = doc().querySelector('#app #logo-top')?.getAnimations().find(a => a.animationName === 'fade-in-logo');
        const titleAnimation = titleNode?.getAnimations().find(a => a.animationName === 'intro-letter-fade');
        samples.push({animation: style.animationName, opacity: Number(style.opacity),
                      scale: style.transform, shell: main.closest('#ssr-shell') !== null,
                      entranceStart: main.getAnimations().find(a => a.animationName === 'fade-in-site')?.startTime,
                      pending: doc().querySelector('#app')?.hasAttribute('data-document-pending'),
                      heroReady: heroReady(),
                      logoAnimation, logoStart: logoAnimation?.startTime,
                      logoDuration: logoAnimation?.effect.getTiming().duration,
                      titleNode, titleAnimation,
                      titleStart: titleAnimation?.startTime,
                      titleDelay: titleAnimation?.effect.getTiming().delay,
                      restored: !!doc().querySelector('#app')?.hasAttribute('data-restore')});
      }
      // Production has no dev console. Establish interactivity through an
      // ordinary control, as the live workflow checks do after hydration.
      if (!completeAt) {
        const search = doc().querySelector('button.search-ui-btn');
        if (doc().querySelector('.search-ui-open')) {
          search?.click();
          completeAt = performance.now();
        } else search?.click();
      }
      if (completeAt && performance.now() - completeAt > 600) return samples;
    }
  }
  throw new Error('Page did not reach interactive startup');
};
// The parent survives the unrelated, genuinely cross-origin document. Observe
// BFCache's retained document before departure and every returned document from
// its first accessible parent frame. This does not inject into the app response.
const returnMatches = (document, url) => document?.URL === url;
const returnReady = (document, selectors) => {
  const app = document?.querySelector('#app');
  return !!app?.querySelector('main') && document.readyState === 'complete' &&
    !app.hasAttribute('data-document-pending') &&
    selectors.every(selector => document.querySelector(selector)) &&
    !app.querySelector('main .component-spinner,main .loading-spinner');
};
const searchMotion = async label => {
  const document = doc(), win = frame.contentWindow;
  // First intent can replace the lazy fallback; act on the mounted control.
  const button = () => document.querySelector('button.search-ui-btn');
  check(!!button() && !document.querySelector('.search-ui-open'), `${label} Search starts closed`);
  let panel = document.querySelector('.search-ui');
  if (!panel) {
    // A restored document may acquire Search on first intent. Establish its
    // normal closed surface before comparing subsequent user transitions.
    button().click();
    await wait(() => document.querySelector('.search-ui-open'));
    button().click();
    await wait(() => !document.querySelector('.search-ui-open'));
    panel = document.querySelector('.search-ui');
    await settleLayout(panel);
  }
  const motion = [], frames = [];
  for (const open of [true, false]) {
    let transition;
    const capture = () => {
      transition ||= panel.getAnimations().find(animation => animation instanceof win.CSSTransition &&
        animation.transitionProperty === 'max-height' && animation.effect.target === panel);
      return transition;
    };
    // Retain the actual effect on its native start event too; a quick valid
    // transition need not remain listed until the next readiness poll.
    panel.addEventListener('transitionrun', capture);
    try {
      button().click();
      await wait(() => capture() && !!document.querySelector('.search-ui-open') === open);
    } finally { panel.removeEventListener('transitionrun', capture); }
    const effect = transition.effect, timing = effect.getTiming(), keyframes = effect.getKeyframes();
    check(Number.isFinite(effect.getComputedTiming().endTime) && Number(timing.duration) > 0 &&
      new Set(keyframes.map(value => value.maxHeight)).size > 1,
      `${label} Search ${open ? 'opening' : 'closing'} runs a finite native height transition`);
    motion.push({open, property: transition.transitionProperty, timing, keyframes,
      composite: effect.composite, iterationComposite: effect.iterationComposite, playbackRate: transition.playbackRate});
    let request, active = true, finished = false, failure;
    const read = () => {
      if (frames.length < 512) frames.push({open, height: panel.getBoundingClientRect().height});
      if (active) request = requestAnimationFrame(read);
    };
    transition.finished.then(() => { finished = true; }, error => { failure = error; });
    try {
      read();
      await wait(() => { if (failure) throw failure; return finished; });
    } finally { active = false; cancelAnimationFrame(request); }
    check(doc() === document && document.querySelector('.search-ui') === panel,
      `${label} Search retains its native surface through the transition`);
  }
  return {motion, frames};
};
// Exercise real shell scroll states, including their native CSS clocks. The
// long-post case leaves room between the header threshold and footer handover.
const shellScrollMotion = async label => {
  const document = doc(), win = frame.contentWindow;
  const header = document.querySelector('#app header'), footer = document.querySelector('#footer-sticky');
  const logo = header.querySelector('.header-logo');
  const read = () => ({height: header.getBoundingClientRect().height,
    hidden: header.classList.contains('hide') && footer.classList.contains('hide'),
    logoOpacity: Number(win.getComputedStyle(logo).opacity),
    footerOpacity: Number(win.getComputedStyle(footer.querySelector('.footer-content')).opacity),
    inert: footer.inert});
  win.scrollTo({top: 0, behavior: 'instant'});
  await wait(() => win.scrollY === 0 && !header.classList.contains('hide'));
  await settleLayout(document.body);
  // Prime the scroll listener's native page-height measurement before its
  // existing direction/150-pixel threshold; no application state is substituted.
  win.scrollTo({top: 1, behavior: 'instant'});
  await sleep(250);
  const observer = window.visualFixture.createMotionObserver(win, label);
  try {
    const height = read().height;
    const hidden = await sample(() => win.scrollTo({
      top: Math.min(700, (document.scrollingElement.scrollHeight - win.innerHeight) / 3), behavior: 'instant'
    }), read, 1900, value => value.hidden);
    check(hidden.at(-1).height === 0 && hidden.at(-1).logoOpacity === 0 &&
      hidden.at(-1).footerOpacity === 0 && hidden.at(-1).inert,
      `${label}: minimized header and footer leave no visible or interactive content`);
    const shown = await sample(() => win.scrollTo({top: win.scrollY / 2, behavior: 'instant'}),
      read, 1900, value => !value.hidden && value.height > 0);
    check(shown.at(-1).logoOpacity === 1 && shown.at(-1).footerOpacity === 1 && !shown.at(-1).inert,
      `${label}: scrolling up restores the shell content`);
    check(hidden.some(value => value.height > 0 && value.height < height - 1) &&
      shown.some(value => value.height > 0 && value.height < height - 1),
      `${label}: native header height changes continuously in both directions`);
    const measured = observer.snapshot();
    const motion = measured.triggered.filter(value => value.kind === 'animation' &&
      ['header-logo-anim', 'header-logo-anim-out'].includes(value.name));
    check(!measured.gaps.length && new Set(motion.map(value => value.name)).size === 2,
      `${label}: both native logo clocks run without observation gaps`, measured.gaps);
    return {motion: motion.map(({name, timing, keyframes}) => ({name, timing, keyframes}))
      .sort((a, b) => a.name.localeCompare(b.name)), hidden, shown};
  } finally { observer.stop(); }
};
const externalBack = async ({label = 'Home', selectors = ['#app #intro'], shellScroll = false} = {}) => {
  const previous = doc(), expectedURL = new URL(previous.URL).href;
  const beforeSearch = await searchMotion(label + ' before departure');
  const beforeShell = shellScroll ? await shellScrollMotion(label + ' before departure') : null;
  const observers = new Map(), evidence = [], cleanups = [];
  const attach = document => {
    if (!document || document.URL === 'about:blank' || observers.has(document)) return;
    const observer = window.visualFixture.createMotionObserver(document.defaultView, document === previous ? 'pre-departure' : 'external-back');
    observers.set(document, observer);
    const record = {url: document.URL, persisted: null, navigation:
      document.defaultView.performance.getEntriesByType('navigation')[0]?.type};
    const pageshow = event => { record.persisted = event.persisted; };
    document.defaultView.addEventListener('pageshow', pageshow, {once: true});
    cleanups.push(() => document.defaultView?.removeEventListener('pageshow', pageshow));
    evidence.push(record);
  };
  // Previously completed objects are not restarted motion. Keep observing this
  // document across BFCache; a newly created CSS object after Back is a failure.
  attach(previous);
  let request, active = true;
  const poll = () => {
    if (!active) return;
    const document = doc();
    if (returnMatches(document, expectedURL)) attach(document);
    request = requestAnimationFrame(poll);
  };
  try {
    const away = new URL('/__tests/away.html', location.href);
    away.hostname = location.hostname === 'localhost' ? '127.0.0.1' : 'localhost';
    check(away.origin !== location.origin, 'Back leaves for a genuinely different origin');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { frame.onload = null; reject(new Error('External document did not load')); }, 15000);
      frame.onload = () => { clearTimeout(timer); frame.onload = null; resolve(); };
      frame.src = away.href;
    });
    check(!doc(), 'External document is cross-origin, rather than substituted application state');
    request = requestAnimationFrame(poll);
    // Native session history is shared by this frame tree. Traverse the actual
    // away entry; do not forge PerformanceNavigationTiming or a restore flag.
    observers.get(previous).phase('external-back');
    history.back();
    await wait(() => returnMatches(doc(), expectedURL) && returnReady(doc(), selectors));
    await sleep(3500);
    const returned = doc(), observer = observers.get(returned);
    check(!!observer, 'Returned document was observed before post-return interactions');
    const measured = observer.snapshot();
    const triggered = measured.triggered.filter(entry => entry.phase === 'external-back');
    const running = returned.getAnimations().filter(animation =>
      (animation instanceof frame.contentWindow.CSSAnimation || animation instanceof frame.contentWindow.CSSTransition) &&
      animation.playState === 'running').map(animation => ({name: animation.animationName || animation.transitionProperty,
        timing: animation.effect.getTiming(), keyframes: animation.effect.getKeyframes()}));
    const observation = {documents: evidence, sameDocument: returned === previous,
      motion: {...measured, triggered}, running, observationWindowMs: 3500,
      observation: 'parent-frame first accessible document; BFCache listener retained across departure'};
    if (label === 'Home') results.dataset.externalBack = JSON.stringify(observation);
    const cases = JSON.parse(results.dataset.externalBackCases || '{}');
    cases[expectedURL] = observation;
    results.dataset.externalBackCases = JSON.stringify(cases);
    check(returnMatches(returned, expectedURL), `${label} Back restores the exact document URL`);
    check(returned === previous || evidence.some(value => value.url === returned.URL &&
      (value.persisted === true || value.navigation === 'back_forward')),
      `${label}: real native history return is evidenced by retained document or browser navigation timing`, evidence);
    check(!measured.gaps.length && !triggered.length && !running.length,
      `${label}: external Back starts no CSS animation or transition before any interaction`, {triggered, running, gaps: measured.gaps});
    // Policy is evidence to assert after observing the actual page, never a
    // prerequisite that conceals motion when the return policy itself breaks.
    check(returned.documentElement.hasAttribute('data-image-direct'), `${label} Back retains the native image return policy`);
    observer.phase('post-return-interaction');
    const afterSearch = await searchMotion(label + ' after Back');
    const interactions = JSON.parse(results.dataset.externalBackInteractions || '{}');
    const afterShell = shellScroll ? await shellScrollMotion(label + ' after Back') : null;
    interactions[expectedURL] = {before: beforeSearch, after: afterSearch, beforeShell, afterShell};
    if (shellScroll) check(JSON.stringify(beforeShell.motion) === JSON.stringify(afterShell.motion),
      `${label}: scrolling retains native logo keyframes and timing after Back`, {beforeShell, afterShell});
    results.dataset.externalBackInteractions = JSON.stringify(interactions);
    check(JSON.stringify(beforeSearch.motion) === JSON.stringify(afterSearch.motion),
      `${label} Search retains its native opening and closing CSS timing after Back`, interactions[expectedURL]);
  } finally {
    active = false; cancelAnimationFrame(request);
    for (const observer of observers.values()) observer.stop();
    cleanups.forEach(cleanup => cleanup());
  }
};
const externalBackCases = [
  {label: 'Blog shell', path: '/blog/post/17', selectors: ['#app .blog-post'], shellScroll: true},
  {label: 'Blog post', path: '/blog/post/New-features-27', selectors: ['#app .blog-post .blog-post-text']},
  {label: 'CV', path: '/cv', selectors: ['#app .cv-intro', '#app .cv-skills']},
  {label: 'Docs', path: '/docs', selectors: ['#app .codox h1']}
];
const externalBackRoutes = async () => {
  for (const test of externalBackCases) {
    await resetTestOrigin(frame, wait);
    const url = new URL(test.path, location.href);
    url.searchParams.set('motion-return', Date.now());
    frame.src = url.href;
    await wait(() => returnMatches(doc(), url.href) && returnReady(doc(), test.selectors));
    // Establish hydrated controls before departure. Returned pages receive no
    // interaction until externalBack has saved its complete observation window.
    await wait(() => {
      if (doc().querySelector('.search-ui-open')) return true;
      doc().querySelector('button.search-ui-btn')?.click(); return false;
    });
    doc().querySelector('button.search-ui-btn').click();
    await wait(() => !doc().querySelector('.search-ui-open'));
    await settleLayout(doc().body);
    await externalBack(test);

  }
  await resetTestOrigin(frame, wait);
};
document.querySelector('#run').onclick = async () => {
  results.replaceChildren();
  delete results.dataset.externalBack;
  results.dataset.externalBackCases = '{}';
  results.dataset.externalBackInteractions = '{}';
  try {
    await resetTestOrigin(frame, wait);
    const path = `/?motion-entrance=${Date.now()}`;
    const cold = await sampleStartup(path);
    check(!!doc().querySelector('#ssr-bootstrap'), 'Cold motion check receives a real SSR snapshot');
    const checkEntrance = (values, label) => {
      const completed = values.filter(value => !value.shell);
      const started = completed.filter(value => value.entranceStart != null && !value.pending);
      check(started.length && started.some(value => value.animation === 'fade-in-site'), `${label} runs the original site entrance`);
      check(new Set(started.map(value => value.entranceStart)).size === 1, `${label} keeps one animation clock through hydration`);
      check(completed.filter(value => !value.pending && value.opacity > 0).every(value => value.heroReady),
            `${label} reveals image pixels or the sampled gradient with the design`);
      const letters = completed.filter(value => !value.pending && value.titleStart != null);
      check(letters.length && letters.every(value => value.titleDelay === 2000 &&
        Math.abs(value.titleStart - value.entranceStart) <= 35),
            `${label} starts the first title letter two seconds after visible SSR`);
      check(new Set(letters.map(value => value.titleNode)).size === 1 &&
            new Set(letters.map(value => value.titleAnimation)).size === 1 &&
            new Set(letters.map(value => value.titleStart)).size === 1,
            `${label} keeps the native title clock through hydration`);
    };
    checkEntrance(cold, 'Cold SSR');
    const logos = cold.filter(value => !value.shell && !value.pending && value.logoStart != null);
    check(logos.length && logos.every(value => value.logoDuration === 3500) &&
      new Set(logos.map(value => value.logoAnimation)).size === 1,
          'Cold SSR preserves the native 3.5-second logo clock through hydration');
    const clock = cold.find(value => !value.shell && !value.pending && value.logoStart != null && value.titleStart != null);
    const rect = selector => doc().querySelector(selector)?.getBoundingClientRect().toJSON();
    results.dataset.geometry = JSON.stringify({intro:rect('#intro'),hero:rect('#top-banner'),
      logo:rect('#logo-top'),image:rect('#logo-top img')});
    results.dataset.clock = JSON.stringify({entranceStart:clock?.entranceStart,
      titleStart:clock?.titleStart, titleDelay:clock?.titleDelay,
      logoStart:clock?.logoStart, logoDuration:clock?.logoDuration});
    // Observe the real return worker output before exercising its document pair.
    await wait(async () => {
      for (const name of await caches.keys()) {
        if (name.startsWith('tolgraven-return-v1-') &&
            await (await caches.open(name)).match(new URL(path, location.href).href)) return true;
      }
      return false;
    });
    frame.src = 'about:blank';
    await wait(() => doc()?.URL === 'about:blank');
    const repeated = await sampleStartup(path);
    check(!!doc().querySelector('#local-page-bootstrap'), 'Repeat document load uses the saved HTML/state pair');
    check(repeated.length && repeated.every(value => value.animation === 'none' && value.opacity === 1),
          'Paired document restoration stays visible without replaying its entrance');
    check(!doc().querySelector('[data-image-preview]'),
          'Saved HTML/state pair contains only final image candidates');
    // Traverse real history from an unrelated cross-origin document. A saved
    // pair alone is not proof of native Back classification.
    await externalBack();
    await wait(() => {
      if (doc().querySelector('.search-ui-open')) return true;
      doc().querySelector('button.search-ui-btn')?.click(); return false;
    });
    doc().querySelector('button.search-ui-btn').click();
    await settleLayout(doc().body);
    check(!doc().querySelector('[data-image-preview]'),
          'Actual document Back retains final-only image candidates');
    const pairedDocument = doc(), pairedMain = doc().querySelector('#app main');
    const pairedTitle = doc().querySelector('#app .intro-title-letter');
    const documentClocks = () => doc().getAnimations().filter(animation => {
      const target = animation.effect?.target;
      return target === pairedMain || target?.closest?.('#intro');
    });
    const pairedClocks = documentClocks();
    check(pairedDocument.documentElement.hasAttribute('data-image-direct'),
          'Paired return starts with the final-only image policy');
    // A test-owned link outside React exercises the real query router handler.
    const queryLink = pairedDocument.createElement('a');
    const queryURL = new URL(frame.contentWindow.location.href);
    queryURL.searchParams.set('image-query-proof', 'changed');
    queryLink.href = queryURL.href; queryLink.textContent = 'Query navigation proof';
    pairedDocument.body.append(queryLink);
    queryLink.click();
    await wait(() => frame.contentWindow.location.search === queryURL.search &&
      !pairedDocument.documentElement.hasAttribute('data-image-direct'));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    check(doc() === pairedDocument && doc().querySelector('#app main') === pairedMain,
          'Same-path query navigation retains the document and ends final-only image policy');
    check(doc().querySelector('#app .intro-title-letter') === pairedTitle &&
      documentClocks().every(animation => pairedClocks.includes(animation)),
          'Query navigation does not restart the settled page or title clock',
          {titleSame: doc().querySelector('#app .intro-title-letter') === pairedTitle,
           previous: pairedClocks.map(a => ({name:a.animationName, property:a.transitionProperty})),
           current: documentClocks().map(a => ({name:a.animationName, property:a.transitionProperty,
             target:a.effect?.target?.className, start:a.startTime}))});
    const queryBack = pairedDocument.createElement('button');
    queryBack.textContent = 'Query Back proof';
    queryBack.onclick = () => frame.contentWindow.history.back();
    pairedDocument.body.append(queryBack); queryBack.click();
    await wait(() => frame.contentWindow.location.search === new URL(path, location.href).search);
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    check(doc() === pairedDocument, 'SPA query Back retains the existing document');
    check(!pairedDocument.documentElement.hasAttribute('data-image-direct'),
      'SPA query Back does not revive the external-return image policy');
    check(doc().querySelector('#app .intro-title-letter') === pairedTitle,
      'SPA query Back retains the existing title nodes');
    check(documentClocks().every(animation => pairedClocks.includes(animation)),
      'SPA query Back retains the existing page and title clocks');
    queryLink.remove(); queryBack.remove();
    await resetTestOrigin(frame, wait);
    const cached = await sampleStartup(path);
    check(!cached.some(value => value.shell), 'Fresh SSR cache bypasses the skeleton');
    checkEntrance(cached, 'Cached SSR');
    await externalBackRoutes();
    frame.src = '/blog/post/New-features-27';
    await wait(() => doc()?.querySelector('.blog-comment-collapsed-placeholder'));
    await wait(() => {
      const open = doc().querySelector('.search-ui-open');
      if (open) return true;
      doc().querySelector('button.search-ui-btn')?.click(); return false;
    });
    doc().querySelector('button.search-ui-btn').click();
    const placeholder = doc().querySelector('.blog-comment-collapsed-placeholder');
    const outer = placeholder.closest('.blog-comment-reply-outer');
    // Exercise a visible user action: offscreen replies intentionally settle
    // without entrances. Allow viewport owners to commit before enumerating
    // their native animation clocks, then wait for the parent source footprint.
    const parent = outer.previousElementSibling;
    const settleComment = async trigger => {
      trigger.scrollIntoView({block: 'center'});
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      await wait(() => parent.getBoundingClientRect().height > 1 &&
        !parent.querySelector('.module-load-placeholder,.component-skeleton,.component-render-skeleton'));
      await settleLayout(outer);
    };
    await settleComment(placeholder);
    const parentHeight = parent.getBoundingClientRect().height;
    const ids = new WeakMap(); let nextRow = 0;
    const milliseconds = value => parseFloat(value) * (value.trim().endsWith('ms') ? 1 : 1000);
    const read = () => {
      const win = frame.contentWindow, replies = outer.querySelector(':scope > .blog-comment-reply');
      return {time: performance.now(), parent: parent.getBoundingClientRect().height,
        outer: outer.getBoundingClientRect().height,
        outerChildren: [...outer.children].map(child => ({className: child.className,
          height: child.getBoundingClientRect().height,
          animations: child.getAnimations().map(animation => ({
            name: animation.animationName || animation.transitionProperty, playState: animation.playState}))})),
        nestedThreads: [...outer.querySelectorAll('.blog-comment-reply-outer')].map(thread => ({
          height: thread.getBoundingClientRect().height,
          children: [...thread.children].map(child => ({className: child.className,
            height: child.getBoundingClientRect().height,
            document: child.hasAttribute('data-document-appear'),
            animations: child.getAnimations({subtree: true}).map(animation => ({
              target: animation.effect?.target?.className,
              name: animation.animationName || animation.transitionProperty,
              playState: animation.playState, currentTime: animation.currentTime}))}))})),
        rows: [...replies.children].filter(row => row.matches('.blog-comment-reply-row')).map(row => {
          if (!ids.has(row)) ids.set(row, ++nextRow);
          const body = row.querySelector(':scope > .blog-comment-reply-body');
          const box = row.getBoundingClientRect(), style = win.getComputedStyle(row), bodyStyle = win.getComputedStyle(body);
          return {id: ids.get(row), height: box.height, opacity: Number(bodyStyle.opacity),
            translateY: bodyStyle.transform === 'none' ? 0 : new win.DOMMatrixReadOnly(bodyStyle.transform).m42,
            delay: milliseconds(style.transitionDelay), bodyDelay: milliseconds(bodyStyle.transitionDelay),
            duration: milliseconds(style.transitionDuration), bodyDuration: milliseconds(bodyStyle.transitionDuration),
            properties: style.transitionProperty.split(',').map(value => value.trim()),
            bodyProperties: bodyStyle.transitionProperty.split(',').map(value => value.trim()),
            inViewport: box.top < win.innerHeight && box.bottom > 0,
            settling: row.hasAttribute('data-appear-settling'),
            expanded: !row.classList.contains('collapsed'), hasContent: !!body.querySelector('.blog-comment-title')};
        })};
    };
    const ready = value => value.rows.some(row => row.expanded && row.hasContent);
    const verifyReplies = (samples, label) => {
      const evidence = replyMotionEvidence(samples), visible = evidence.filter(row => row.visible);
      window.replyMotionSamples ||= {};
      window.replyMotionSamples[label] = {samples, evidence};
      check(visible.length > 0, `${label} samples a visible native reply entrance`, evidence);
      check(visible.every(row => row.continuous && row.intermediateHeights.length >= 2 && row.settled),
        `${label} reply rows grow continuously through intermediate heights without teleporting`, evidence);
      check(visible.every(row => row.fading && row.sliding),
        `${label} reply bodies visibly fade and slide while their rows grow`, evidence);
      check(visible.every(row => row.duration > 0 && row.bodyDuration === row.duration && row.bodyDelay === row.delay &&
        row.properties.includes('grid-template-rows') && row.bodyProperties.includes('opacity') && row.bodyProperties.includes('transform')),
        `${label} row height and body motion share the native timing contract`, evidence);
      // A live branch may be a single-child chain. Only real siblings can
      // establish stagger order; the mounted three-sibling fixture covers it too.
      if (visible.length > 1) {
        const staggered = visible.slice(1).filter((row, index) => row.delay > visible[index].delay);
        check(staggered.length > 0 && visible.slice(1).every((row, index) =>
          row.delay <= visible[index].delay || row.started > visible[index].started),
          `${label} measured sibling growth starts in its stagger order`, evidence);
      }
    };
    const frames = await sample(() => placeholder.click(), read, 1400, ready);
    window.replyMotionSamples ||= {};
    window.replyMotionSamples['First expansion'] = {samples: frames, evidence: replyMotionEvidence(frames)};
    check(frames.every(value => value.parent >= parentHeight - 1), 'Expanding a thread never empties its parent comment');
    check(frames.every(value => value.outer >= frames[0].outer - 1), 'Reply loading never drops the collapsed thread footprint');
    await wait(() => outer.querySelector('.blog-comment-title'));
    verifyReplies(frames, 'First expansion');
    const border = parent.querySelector('.blog-comment-border');
    border.click(); await sleep(1200);
    const collapsed = outer.querySelector('.blog-comment-collapsed-placeholder');
    await settleComment(collapsed);
    const reopened = await sample(() => collapsed.click(), read, 1400, ready);
    window.replyMotionSamples['Cached reopening'] = {samples: reopened, evidence: replyMotionEvidence(reopened)};
    check(reopened.every(value => value.parent >= parentHeight - 1), 'Reopening a cached thread retains its parent layout');
    verifyReplies(reopened, 'Cached reopening');
    await wait(() => outer.querySelector('.blog-comment-title'));
    // Exercise the same application with the optional native capability absent.
    // Only the test driver changes API availability; app state/markup stay real.
    if (new URLSearchParams(location.search).has('fallback')) {
      Object.defineProperty(doc(), 'startViewTransition', {value: undefined, configurable: true});
    }
    const before = doc();
    doc().querySelector('header a[href="/"]').click();
    await wait(() => doc().querySelector('#intro'));
    check(doc() === before, 'Section navigation retains the document');
    await wait(() => !doc().querySelector('#main > .swapped') &&
      !doc().getAnimations().some(animation => animation.animationName === 'page-opacity-out' &&
                                              animation.playState !== 'finished'));
    await settleLayout(doc().querySelector('main'));
    const started = performance.now();
    const navFrames = await sample(() => doc().querySelector('header a[href="/blog"]').click(), () => {
      const main = doc().querySelector('main'), win = frame.contentWindow;
      const animation = name => doc().getAnimations().find(value => value.animationName === name);
      const old = animation('page-opacity-out'), incoming = animation('page-opacity-in-a');
      const oldTiming = old?.effect.getTiming(), newTiming = incoming?.effect.getTiming();
      const entering = main.querySelector('.swap-in'), leaving = main.querySelector('.swapped');
      const footer = doc().querySelector('#footer-sticky');
      const footerSnapshot = win.getComputedStyle(doc().documentElement, '::view-transition-new(sticky-footer)');
      return {path: win.location.pathname,
              opacity: parseFloat(win.getComputedStyle(entering || main).opacity),
              outgoing: leaving ? parseFloat(win.getComputedStyle(leaving).opacity) : 0,
              oldProgress: old?.effect.getComputedTiming().progress,
              newProgress: incoming?.effect.getComputedTiming().progress,
              nativeTiming: oldTiming && newTiming && [oldTiming, newTiming],
              fallbackTiming: entering?.classList.contains('swapped-in') &&
                leaving?.classList.contains('swapped-out') && [entering, leaving].map(element => {
                const style = win.getComputedStyle(element);
                return [style.transitionDuration, style.transitionTimingFunction, style.transitionDelay];
              }),
              footerOpacity: footer ? parseFloat(win.getComputedStyle(footer).opacity) : 1,
              footerSnapshotOpacity: parseFloat(footerSnapshot.opacity),
              footerSnapshotAnimation: footerSnapshot.animationName,
              nativeFade: typeof doc().startViewTransition === 'function' &&
                win.getComputedStyle(doc().documentElement, '::view-transition-old(page)').animationName === 'page-opacity-out'};
    }, 1800);
    check(navFrames.some(value => value.opacity < .95 || value.nativeFade), 'Section navigation uses an opacity fade');
    const native = navFrames.some(value => value.oldProgress != null);
    if (native) {
      check(navFrames.some(value => value.oldProgress > .05 && value.oldProgress < .95 &&
                                   value.newProgress > .05 && value.newProgress < .95),
            'Native outgoing and incoming pages crossfade simultaneously');
      check(navFrames.filter(value => value.nativeTiming).every(value =>
              value.nativeTiming.every(timing => timing.duration === 250 && timing.delay === 0 && timing.easing === 'linear')),
            'Both native fades use 250ms linear motion without delay');
      check(navFrames.filter(value => value.nativeTiming).every(value =>
              value.footerSnapshotOpacity === 1 && value.footerSnapshotAnimation === 'none'),
            'Native sticky footer snapshot stays opaque and unanimated');
    } else {
      check(navFrames.some(value => value.outgoing > .05 && value.outgoing < .95 &&
                                   value.opacity > .05 && value.opacity < .95),
            'Fallback outgoing and incoming pages crossfade simultaneously');
      check(navFrames.filter(value => value.fallbackTiming).every(value =>
              value.fallbackTiming.every(([duration, easing, delay]) => duration === '0.25s' && easing === 'linear' && delay === '0s')),
            'Both fallback fades use 250ms linear motion without delay');
    }
    check(navFrames.every(value => value.footerOpacity === 1), 'Sticky footer stays opaque during page navigation');
    check(navFrames.some(value => value.path === '/blog'), 'Landing → blog commits during the transition');
    check(performance.now() - started < 2500, 'Section motion is bounded independently of content loading');
    check(true, 'MOTION CHECKS PASSED');
  } catch (error) {
    const diagnostics = {
      worker: !!navigator.serviceWorker?.controller,
      resources: frame.contentWindow.performance.getEntriesByType('resource').map(entry => new URL(entry.name).pathname)
        .filter(path => /page-return|return-worker|page-render|page-capture|api\//.test(path)),
      loading: doc()?.querySelectorAll('[role=status]').length,
    };
    const row = document.createElement('li'); row.className = 'failed'; row.textContent = `FAILED: ${error.message} ${JSON.stringify(diagnostics)}`; results.append(row);
  }
};

if (new URLSearchParams(location.search).has('run')) document.querySelector('#run').click();
