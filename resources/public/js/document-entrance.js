// Runs after the completed SSR mount container is parsed, before application
// code. Only the document-owned container changes; React retains every child.
(() => {
  const root = document.querySelector('#app[data-document-pending]');
  if (!root) return;
  const hero = root.querySelector('#top-banner');
  const candidates = !hero ? [] : hero.matches('img') ? [hero] :
    [hero.querySelector('[data-image-preview]'), hero.querySelector('[data-image-final]')].filter(Boolean);
  // The small same-candidate bucket suppresses its preview before JS. Only a
  // painted layer may release the entrance; the final remains the sole gate.
  const images = (candidates.length ? candidates : [...(hero?.querySelectorAll('img') || [])])
    .filter(image => getComputedStyle(image).display !== 'none');
  const reveal = () => requestAnimationFrame(() => requestAnimationFrame(() => {
    root.removeAttribute('data-document-pending');
  }));
  if (!images.length) { reveal(); return; }
  let finished = false;
  const failed = new Set();
  const bindings = [];
  const complete = () => {
    if (finished) return;
    finished = true;
    clearTimeout(deadline);
    for (const {image, load, error} of bindings) {
      image.removeEventListener('load', load);
      image.removeEventListener('error', error);
    }
    reveal();
  };
  const fail = image => {
    failed.add(image);
    if (failed.size === images.length) complete();
  };
  const decode = image => {
    if (!image.naturalWidth) { fail(image); return; }
    if (image.decode) image.decode().then(complete, () => fail(image));
    else complete();
  };
  // A decoded preview starts the entrance promptly; the responsive final image
  // keeps its native acquisition and paints over it without another entrance.
  // A stalled/broken image must never hide usable page content indefinitely.
  const deadline = setTimeout(complete, 10000);
  for (const image of images) {
    const load = () => decode(image);
    const error = () => fail(image);
    bindings.push({image, load, error});
    image.addEventListener('load', load, {once: true});
    image.addEventListener('error', error, {once: true});
  }
  // Install every binding before a synchronous no-decode completion can clean up.
  for (const image of images) {
    if (image.complete) decode(image);
  }
})();
