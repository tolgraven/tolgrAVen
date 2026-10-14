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
  if (!value) throw new Error(label + (details ? ` ${JSON.stringify(details)}` : ""));
  const row = document.createElement('li'); row.className = 'passed'; row.textContent = label; results.append(row);
};
const sample = async (action, read, ms = 1400) => {
  const values = [read()], end = performance.now() + ms;
  action();
  while (performance.now() < end) { await new Promise(resolve => requestAnimationFrame(resolve)); values.push(read()); }
  return values;
};
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
document.querySelector('#run').onclick = async () => {
  results.replaceChildren();
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
    // Leave the application document and use the unrelated document's native
    // Back button. A saved pair alone is not proof of native Back classification.
    frame.src = '/__tests/away.html';
    await wait(() => doc()?.location.pathname === '/__tests/away.html' &&
      doc()?.querySelector('button'));
    doc().querySelector('button').click();
    await wait(() => doc()?.location.pathname === '/' &&
      doc()?.documentElement.hasAttribute('data-image-direct') && doc()?.querySelector('#intro'));
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
    check(!doc().querySelector('#ssr-shell'), 'Fresh SSR cache bypasses the skeleton');
    checkEntrance(cached, 'Cached SSR');
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
    // Measure a stable footprint, not an in-flight document/font entrance.
    await settleLayout(outer);
    const parent = outer.previousElementSibling;
    const parentHeight = parent.getBoundingClientRect().height;
    const read = () => ({parent: parent.getBoundingClientRect().height, outer: outer.getBoundingClientRect().height});
    const frames = await sample(() => placeholder.click(), read);
    check(frames.every(value => value.parent >= parentHeight - 1), 'Expanding a thread never empties its parent comment');
    check(frames.every(value => value.outer >= frames[0].outer - 1), 'Reply loading never drops the collapsed thread footprint');
    await wait(() => outer.querySelector('.blog-comment-title'));
    const descendant = outer.querySelector('.blog-comment-around');
    check(descendant.classList.contains('slide-behind'), 'Replies retain their original slide-behind entrance');
    check(parseFloat(frame.contentWindow.getComputedStyle(descendant).transitionDelay) > 0, 'Nested replies have staggered entrance timing');
    const border = parent.querySelector('.blog-comment-border');
    border.click(); await sleep(1200);
    const reopened = await sample(() => outer.querySelector('.blog-comment-collapsed-placeholder').click(), read);
    check(reopened.every(value => value.parent >= parentHeight - 1), 'Reopening a cached thread retains its parent layout');
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
