// Test-driver DOM operations stay here; application components retain ownership
// of their DOM and state. Only actual page loads, link clicks and history are used.
const frame = document.querySelector('#app');
const results = document.querySelector('#results');
const button = document.querySelector('#run');
const doc = () => frame.contentDocument;
const waitFor = async (predicate, description, timeout = 30000) => {
  const end = performance.now() + timeout;
  while (performance.now() < end) {
    const value = predicate();
    if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out: ${description}`);
};
const check = (condition, description) => {
  if (!condition) throw new Error(description);
  const row = document.createElement('li');
  row.className = 'passed'; row.textContent = description; results.append(row);
};
const app = () => doc()?.querySelector('#app');
const posts = () => [...(app()?.querySelectorAll('.blog-post') || [])];
const ready = () => {
  const failure = app()?.querySelector('main .component-failed');
  if (failure) throw new Error(failure.textContent);
  const validationFailure = [...(app()?.querySelectorAll('[role="alert"]') || [])]
    .find(element => element.textContent.includes('Schema validation:'));
  if (validationFailure) throw new Error(validationFailure.textContent);
  return app()?.querySelector('main') &&
  (!doc().querySelector('#ssr-shell') || doc().querySelector('#ssr-complete')) &&
  // GitHub's scroll-to-load footer intentionally keeps a spinner next to its
  // manual pagination button, including when the current page is fully loaded.
  ![...app().querySelectorAll('main .component-spinner, main .loading-spinner')]
    .some(element => !element.closest('.github-loading'));
};
const link = selector => app().querySelector(selector);
const navigate = async (anchor, path) => {
  if (!anchor) throw new Error(`Missing navigation link: ${path}`);
  const documentBefore = doc();
  anchor.click();
  await waitFor(() => frame.contentWindow.location.pathname === path, `immediate SPA route ${path}`, 1000);
  check(doc() === documentBefore, `${path}: navigation retains the document (no SSR document load)`);
};
button.onclick = async () => {
  button.disabled = true; results.replaceChildren();
  try {
    // Finish the previous app's pagehide before clearing this test-only origin.
    frame.src = 'about:blank';
    await waitFor(() => doc()?.URL === 'about:blank', 'previous document closes');
    localStorage.clear(); sessionStorage.clear();
    document.cookie = 'tolgraven-return=; Max-Age=0; Path=/';
    const bootstrap = await fetch('/api/content/bootstrap', {cache: 'no-store'});
    check(bootstrap.ok, 'Public content bootstrap succeeds');
    check(bootstrap.headers.get('X-Content-Source') === 'strapi',
      'Backend uses configured Strapi, not the offline seed');
    frame.src = '/blog';
    await waitFor(() => posts().length && ready(), 'real blog content');
    check(!!doc().querySelector('#ssr-bootstrap'), 'Cold blog load includes a real server-rendered snapshot');
    check(!app().querySelector('.search-ui.search-ui-open'), 'Search starts closed');
    // Establish interactivity with an eager control, without warming Search.
    // A deferred button must retain its first click while its code loads.
    await waitFor(() => {
      const root = app();
      if (root?.querySelector('.settings-panel.opened')) return true;
      // SSR can be visible before hydration. Its first link may perform a
      // document navigation; let that pending intent hydrate instead of clicking
      // it repeatedly and interrupting every reload before JavaScript can start.
      if (root && !new URL(frame.contentWindow.location.href).searchParams.has('settingsBox')) {
        root.querySelector('a.settings-btn')?.click();
      }
      return false;
    }, 'hydrated settings control');
    app().querySelector('a.settings-btn').click();
    await waitFor(() => !app().querySelector('.settings-panel.opened'), 'settings closes');
    app().querySelector('button.search-ui-btn').click();
    await waitFor(() => app().querySelector('.search-ui.search-ui-open'), 'first Search click opens the lazy UI');
    check(true, 'One Search click loads and opens the deferred UI');
    await waitFor(() => doc().activeElement?.id === 'search-input', 'deferred search input receives focus');
    check(true, 'Deferred search focuses its input after mounting');
    app().querySelector('button.search-ui-btn').click();
    await waitFor(() => !app().querySelector('.search-ui.search-ui-open'), 'search closes');
    // Exercise a different module before relying on the warm blog bindings.
    // The proxy can add transport latency to verify its cold shell too.
    await navigate(link('#menu-link-cv'), '/cv');
    await waitFor(() => !posts().length, 'CV replaces outgoing blog before its data is ready', 1000);
    check(true, 'CV commits its destination without retaining outgoing blog content');
    await waitFor(() => app().querySelector('.cv-intro') && ready(), 'CV content');
    check(!app().querySelector('.component-failed'), 'CV renders through its normal CMS subscription');
    await navigate(link('#menu-link-blog'), '/blog');
    await waitFor(() => link('.blog-post a[href*="/blog/post/"]') && ready(), 'hydrated post links');
    const heading = app().querySelector('.fading-bg-heading');
    check(!!heading, 'Blog heading is supplied with real CMS content');
    check(!app().querySelector('.component-failed'), 'Blog loads without a component fallback');
    const image = heading.querySelector('img');
    if (image) {
      await waitFor(() => image.complete && image.naturalWidth > 0, 'heading image');
      check(true, 'Blog heading image decodes');
    }
    // Clicking real links also verifies that hydration attached application handlers.
    const permalink = link('.blog-post a[href*="/blog/post/"]');
    if (!permalink) throw new Error('Published blog has no permalink link');
    const target = new URL(permalink.href).pathname;
    await navigate(permalink, target);
    await waitFor(() => posts().length === 1 && ready(), 'individual post');
    check(app().querySelector('.fading-bg-heading') === heading, 'Permalink navigation retains the shared blog heading');
    check(posts()[0].textContent.trim().length > 20, 'Individual post contains real Supabase content');
    check(!app().querySelector('.search-ui.search-ui-open'), 'Search does not open on initial load');
    const tag = link('.blog-post a[href*="/blog/tag/"]');
    if (!tag) throw new Error('Published post needs a tag to verify server-filtered navigation');
    await navigate(tag, new URL(tag.href).pathname);
    await waitFor(() => posts().length && ready(), 'tag-filtered posts');
    check(app().querySelector('.fading-bg-heading') === heading, 'Tag navigation retains the shared blog heading');
    frame.contentWindow.history.back();
    await waitFor(() => frame.contentWindow.location.pathname === target && posts().length === 1 && ready(), 'history return');
    check(true, 'Browser Back restores the post through the application');
    const home = [...app().querySelectorAll('header a[href]')]
      .find(anchor => new URL(anchor.href).pathname === '/');
    await navigate(home, '/');
    await waitFor(() => ready() && doc().querySelector('#intro h1'), 'landing page content');
    check(!!doc().querySelector('footer'), 'Landing page renders real content and footer');
    const blog = [...app().querySelectorAll('a[href]')]
      .find(anchor => new URL(anchor.href).pathname === '/blog');
    await navigate(blog, '/blog');
    await waitFor(() => posts().length && ready(), 'return to blog');
    check(!app().querySelector('.component-failed'), 'Landing → blog succeeds through regular SPA bindings');
    check(true, 'LIVE CHECKS PASSED');
  } catch (error) {
    const row = document.createElement('li'); row.className = 'failed';
    row.textContent = `FAILED: ${error.message}`; results.append(row);
  } finally { button.disabled = false; }
};

if (new URLSearchParams(location.search).has('run')) button.click();
