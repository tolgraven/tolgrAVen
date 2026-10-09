const frame = document.querySelector('#app'), results = document.querySelector('#results');
const doc = () => frame.contentDocument;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const wait = async predicate => {
  const end = performance.now() + 30000;
  while (performance.now() < end) {
    const failed = doc()?.querySelector('main .component-failed');
    if (failed) throw new Error(failed.textContent);
    if (predicate()) return;
    await sleep(20);
  }
  throw new Error('Application did not settle');
};
const check = (value, label) => {
  if (!value) throw new Error(label);
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
// Sample the real document from its first paint through interactive startup.
// Do not substitute data or manipulate application-owned markup.
const entrance = async path => {
  const samples = [], end = performance.now() + 45000;
  frame.src = path;
  let completeAt = null;
  while (performance.now() < end) {
    await new Promise(resolve => requestAnimationFrame(resolve));
    const mains = doc()?.querySelectorAll('#ssr-shell main, #app main');
    if (mains?.length && frame.contentWindow.location.pathname === '/') {
      // A cache miss animates its flushed shell; a cache hit animates #app.
      // Sampling only #app incorrectly misses the entire streaming entrance.
      for (const main of mains) {
        const style = frame.contentWindow.getComputedStyle(main);
        samples.push({animation: style.animationName, opacity: Number(style.opacity),
                      scale: style.transform, restored: !!doc().querySelector('#app')?.hasAttribute('data-restore')});
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
  throw new Error('First-load entrance did not reach interactive startup');
};
document.querySelector('#run').onclick = async () => {
  results.replaceChildren();
  try {
    const path = '/?motion-entrance';
    const cold = await entrance(path);
    check(cold.some(value => value.animation === 'fade-in-site' && value.opacity > 0 && value.opacity < .99),
          'Initial landing page visibly animates before hydration without needing a mount replay');
    // Finish pagehide/persistence before the next request. Starting it directly
    // from the old document can race the return cookie sent with that request.
    frame.src = 'about:blank';
    await wait(() => doc()?.URL === 'about:blank');
    const repeated = await entrance(path);
    // A normal repeat request uses network SSR when the return worker is active.
    // Saved HTML is armed by an external-link departure, covered by scroll.js.
    check(repeated.some(value => value.animation === 'fade-in-site' && value.opacity > 0 && value.opacity < .99),
          'Normal repeat document load retains the page entrance (not mistaken for browser Back)');
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
    const row = document.createElement('li'); row.className = 'failed'; row.textContent = `FAILED: ${error.message}`; results.append(row);
  }
};

if (new URLSearchParams(location.search).has('run')) document.querySelector('#run').click();
