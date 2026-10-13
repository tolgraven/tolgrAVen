// Runs after the completed SSR mount container is parsed, before application
// code. Only the document-owned container changes; React retains every child.
(() => {
  const root = document.querySelector('#app[data-document-pending]');
  if (!root) return;
  const hero = root.querySelector('#top-banner');
  const reveal = () => requestAnimationFrame(() => requestAnimationFrame(() => {
    root.removeAttribute('data-document-pending');
  }));
  if (!hero) { reveal(); return; }
  let finished = false;
  const complete = () => {
    if (finished) return;
    finished = true;
    clearTimeout(deadline);
    hero.removeEventListener('load', decode);
    hero.removeEventListener('error', complete);
    reveal();
  };
  const decode = () => {
    if (hero.decode) hero.decode().then(complete, complete);
    else complete();
  };
  // A stalled/broken image must never hide usable page content indefinitely.
  const deadline = setTimeout(complete, 10000);
  hero.addEventListener('load', decode, {once: true});
  hero.addEventListener('error', complete, {once: true});
  if (hero.complete) decode();
})();
