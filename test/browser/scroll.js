// Real browser history and app controls; no application-state access or mocks.
const frame = document.querySelector('#app');
const results = document.querySelector('#results');
const run = document.querySelector('#run');
const doc = () => frame.contentDocument;
const win = () => frame.contentWindow;
const waitFor = async (predicate, description) => {
  const deadline = performance.now() + 30000;
  while (performance.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw Error(`Timed out: ${description}`);
};
const record = (description, failed = false) => {
  const item = document.createElement('li');
  item.className = failed ? 'failed' : 'passed';
  item.textContent = description; results.append(item);
};
const check = (condition, description) => {
  if (!condition) throw Error(description);
  record(description);
};
const interactive = async () => {
  await waitFor(() => doc()?.querySelector('.blog-post'), 'post content');
  await waitFor(() => {
    if (doc().querySelector('.search-ui.search-ui-open')) return true;
    doc().querySelector('button.search-ui-btn')?.click();
    return false;
  }, 'hydration attaches controls');
  doc().querySelector('button.search-ui-btn').click();
  await waitFor(() => !doc().querySelector('.search-ui.search-ui-open'), 'search closes');
};
run.onclick = async () => {
  run.disabled = true; results.replaceChildren();
  try {
    frame.src = 'about:blank';
    await waitFor(() => doc()?.URL === 'about:blank', 'empty test frame');
    localStorage.clear(); sessionStorage.clear();
    document.cookie = 'tolgraven-return=; Max-Age=0; Path=/';
    frame.src = '/blog/post/17';
    await interactive();
    check(win().history.scrollRestoration === 'auto', 'Initial SSR leaves native restoration enabled');
    win().scrollTo({top: 700, behavior: 'instant'});
    await waitFor(() => win().scrollY > 100, 'scrollable post');
    const position = win().scrollY;
    const initial = doc();
    const oldTitle = doc().querySelector('.blog-post h1')?.textContent;
    record(`Saved position ${position}px`);
    const tag = [...doc().querySelectorAll('main a[href^="/blog/post/"]')]
      .find(anchor => new URL(anchor.href).pathname !== '/blog/post/17');
    check(!!tag, 'Post has a real adjacent-post link');
    const target = new URL(tag.href).pathname;
    record(`Navigating to ${target}`);
    tag.click();
    await waitFor(() => win().location.pathname === target && doc().querySelector('.blog-post h1')?.textContent !== oldTitle, 'adjacent post navigation');
    check(doc() === initial, 'SPA navigation retains the document');
    check(win().history.scrollRestoration === 'manual', 'SPA navigation takes scroll ownership');
    win().history.back();
    await waitFor(() => win().location.pathname === '/blog/post/17' && Math.abs(win().scrollY - position) <= 2, 'SPA Back position');
    record(`SPA Back restores ${position}px`);
    // A different document exercises pagehide and native document history.
    win().location.href = '/__tests/away.html';
    await waitFor(() => doc()?.title === 'Another document', 'leave application document');
    doc().querySelector('button').click();
    await waitFor(() => win().location.pathname === '/blog/post/17' && doc()?.querySelector('.blog-post') && Math.abs(win().scrollY - position) <= 2, 'document Back position');
    record(`Document Back restores ${position}px (${doc() === initial ? 'retained document' : 'new document'})`);
    check(!doc().querySelector('.component-failed'), 'Returned page has no component failure');
    await new Promise(resolve => setTimeout(resolve, 1000));

    check(Math.abs(win().scrollY - position) <= 2, 'Returned layout keeps its saved position after settling');
    record('SCROLL CHECKS PASSED');
  } catch (error) { record(`FAILED: ${error.message}; path=${win().location.pathname}; scroll=${win().scrollY}`, true); }
  finally { run.disabled = false; }
};
