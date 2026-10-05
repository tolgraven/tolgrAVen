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
// Sample the real document from its first paint through interactive startup.
// Do not substitute data or manipulate application-owned markup.
const entrance = async path => {
  const samples = [], end = performance.now() + 45000;
  frame.src = path;
  let completeAt = null;
  while (performance.now() < end) {
    await new Promise(resolve => requestAnimationFrame(resolve));
    const main = doc()?.querySelector('#app main');
    if (main && frame.contentWindow.location.pathname === '/') {
      const style = frame.contentWindow.getComputedStyle(main);
      samples.push({animation: style.animationName, opacity: Number(style.opacity),
                    scale: style.transform, restored: doc().querySelector('#app').hasAttribute('data-restore')});
      if (doc().querySelector('.dev-console__toggle')) completeAt ||= performance.now();
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
    const restored = await entrance(path);
    check(restored.some(value => value.restored), 'Normal repeat document load restores persisted content');
    check(restored.some(value => value.animation === 'fade-in-site' && value.opacity > 0 && value.opacity < .99),
          'Normal persisted reload retains the page entrance (not mistaken for browser Back)');
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
    const before = doc();
    doc().querySelector('header a[href="/"]').click();
    await wait(() => doc().querySelector('#intro'));
    check(doc() === before, 'Section navigation retains the document');
    const started = performance.now();
    const navFrames = await sample(() => doc().querySelector('header a[href="/blog"]').click(), () => {
      const main = doc().querySelector('main'), win = frame.contentWindow;
      const progress = name => doc().getAnimations().find(animation => animation.animationName === name)?.effect.getComputedTiming().progress;
      return {path:win.location.pathname, opacity:parseFloat(win.getComputedStyle(main.querySelector(".swap-in") || main).opacity),
              outgoing: main.querySelector('.swapped') ? parseFloat(win.getComputedStyle(main.querySelector('.swapped')).opacity) : 0,
              oldProgress: progress('page-opacity-out'), newProgress: progress('page-opacity-in-a'),
              nativeFade: typeof doc().startViewTransition === 'function' && win.getComputedStyle(doc().documentElement,'::view-transition-old(page)').animationName === 'page-opacity-out'};
    }, 1800);
    check(navFrames.some(value => value.opacity < .95 || value.nativeFade), 'Section navigation uses an opacity fade');
    const native = navFrames.some(value => value.oldProgress != null);
    if (native) {
      check(navFrames.some(value => value.oldProgress > .05 && value.oldProgress < .95 && value.newProgress === 0),
            'Incoming page remains hidden during the outgoing native fade');
      check(navFrames.some(value => value.oldProgress === 1 && value.newProgress > .05 && value.newProgress < .95),
            'Incoming native fade starts after the outgoing fade finishes');
      check(navFrames.every(value => !(value.oldProgress < .99 && value.newProgress > .01)),
            'Native outgoing and incoming fades never overlap');
    } else {
      check(navFrames.some(value => value.outgoing > .05 && value.outgoing < .95 && value.opacity < .01),
            'Fallback keeps the incoming page hidden during the outgoing fade');
      check(navFrames.some(value => value.outgoing < .01 && value.opacity > .05 && value.opacity < .95),
            'Fallback starts its incoming fade after outgoing opacity reaches zero');
    }
    check(navFrames.some(value => value.path === '/blog'), 'Landing → blog commits during the transition');
    check(performance.now() - started < 2500, 'Section motion is bounded independently of content loading');
    check(true, 'MOTION CHECKS PASSED');
  } catch (error) {
    const row = document.createElement('li'); row.className = 'failed'; row.textContent = `FAILED: ${error.message}`; results.append(row);
  }
};

if (new URLSearchParams(location.search).has('run')) document.querySelector('#run').click();
