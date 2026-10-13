// The document owns only #app attributes. Native image acquisition and React's
// image nodes stay intact, including hydration during the 100ms upgrade.
(() => {
  const root = document.querySelector('#app');
  if (!root) return;
  const pending = root.hasAttribute('data-document-pending');
  const hero = root.querySelector('#top-banner');
  const preview = hero?.querySelector('[data-image-preview]');
  const final = hero?.querySelector('[data-image-final]');
  const progressive = !!(preview && final);
  if (!pending && !progressive) return;
  const src = final?.getAttribute('src');
  const images = progressive ? [preview, final] : hero?.matches('img') ? [hero] : [];
  let disposed = false;
  let staging = progressive;
  let previewPainted = false;
  let upgradeDeadline;
  let upgradeSelected;
  let revealed = !pending;
  let previewReady = false;
  let finalReady = false;
  let finalSelected;
  let frame;
  const bindings = [];
  const failed = new Set();
  const terminal = new Set(['ready', 'single', 'failed']);
  const ownedSource = () => !disposed && root.contains(hero) &&
    (!progressive || final.getAttribute('src') === src);
  const current = () => staging && ownedSource() && !hero.hasAttribute('data-image-managed');
  const reveal = () => {
    if (revealed) return;
    revealed = true;
    clearTimeout(deadline);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      root.removeAttribute('data-document-pending');
      frame = requestAnimationFrame(() => {
        previewPainted = ownedSource() && previewReady && !finalReady && visiblePreview();
      });
      if (!staging) cleanup();
    }));
  };
  const phase = () => root.getAttribute('data-document-image-phase');
  const visiblePreview = () => root.contains(preview) &&
    getComputedStyle(preview).display !== 'none' &&
    getComputedStyle(preview.closest('.progressive-image__preview-layer') || preview).display !== 'none';
  const stage = value => {
    if (!current()) return;
    root.setAttribute('data-document-image-phase', value);
    if (terminal.has(value)) {
      root.dispatchEvent(new Event('tolgraven:document-image-ready'));
    }
  };
  const releaseStage = () => {
    staging = false;
    clearTimeout(upgradeDeadline);
    cancelAnimationFrame(frame);
    root.removeAttribute('data-document-image-owner');
    root.removeAttribute('data-document-image-phase');
    root.removeAttribute('data-document-image-src');
    if (root.__progressiveHero === hero) delete root.__progressiveHero;
  };
  const cleanup = () => {
    if (disposed) return;
    disposed = true;
    clearTimeout(deadline);
    releaseStage();
    for (const [image, load, error] of bindings) {
      image.removeEventListener('load', load);
      image.removeEventListener('error', error);
    }
    observer?.disconnect();
    window.removeEventListener('pageshow', restored);
    final?.removeEventListener('transitionend', transitionDone);
    final?.removeEventListener('transitioncancel', transitionDone);
  };
  const same = () => progressive &&
    ((final.currentSrc && final.currentSrc === preview.currentSrc) ||
      (!final.currentSrc && hero.getAttribute('data-image-sizes') === '100vw' &&
        innerWidth * devicePixelRatio <= Number(hero.getAttribute('data-preview-width'))));
  const finishUpgrade = selected => {
    if (current() && phase() === 'upgrading' && final.currentSrc === selected) {
      clearTimeout(upgradeDeadline);
      stage('ready');
    }
  };
  const transitionDone = event => {
    if (event.target === final && event.propertyName === 'opacity') finishUpgrade(upgradeSelected);
  };
  const ready = image => {
    if (!ownedSource() || !root.contains(image)) return;
    if (!current()) { if (image !== preview || visiblePreview()) reveal(); return; }
    if (image === final) {
      if (finalReady && finalSelected === final.currentSrc) return;
      finalReady = true;
      finalSelected = final.currentSrc;
      if (phase() === 'upgrading') {
        upgradeSelected = finalSelected;
        if (Number(getComputedStyle(final).opacity) >= 1) finishUpgrade(upgradeSelected);
        return;
      }
      if (same()) stage('single');
      else if (previewPainted && phase() === 'waiting' &&
               !matchMedia('(prefers-reduced-motion: reduce)').matches) {
        upgradeSelected = final.currentSrc;
        stage('upgrading');
        // Native transition completion owns the pre-hydration exit. There is no
        // timer delaying the final request, decode, or incoming opacity.
        frame = requestAnimationFrame(() => {
          if (!current()) return;
          const durations = getComputedStyle(final).transitionDuration.split(',')
            .map(value => parseFloat(value) * (value.trim().endsWith('ms') ? 1 : 1000));
          upgradeDeadline = setTimeout(() => finishUpgrade(upgradeSelected), Math.max(...durations, 0) + 50);
          const animations = final.getAnimations?.() || [];
          if (animations.length) Promise.allSettled(animations.map(animation => animation.finished))
            .then(() => finishUpgrade(upgradeSelected));
        });
      } else stage('ready');
    } else if (image === preview) {
      previewReady = true;
      if (!finalReady && !terminal.has(phase())) stage(same() ? 'single' : 'waiting');
      if (revealed && !finalReady) frame = requestAnimationFrame(() => {
        previewPainted = ownedSource() && visiblePreview() && !finalReady;
      });
    }
    if (image !== preview || (phase() !== 'single' && visiblePreview())) reveal();
    if (!progressive) cleanup();
  };
  const fail = image => {
    if (!ownedSource()) return;
    failed.add(image);
    if (image === final) stage('failed');
    if (failed.size === images.length) { reveal(); if (!progressive) cleanup(); }
  };
  const decode = image => {
    if (!ownedSource() || !root.contains(image)) return;
    if (!image.naturalWidth) { fail(image); return; }
    const selected = image.currentSrc;
    const decoded = () => {
      if (selected === image.currentSrc) ready(image);
    };
    if (image.decode) image.decode().then(decoded, () => {
      if (selected === image.currentSrc) fail(image);
    });
    else decoded();
  };
  const restored = event => {
    if (event.persisted && ownedSource() && final?.complete && final.naturalWidth) {
      finalReady = true;
      stage(same() ? 'single' : 'ready');
      decode(final);
    }
  };
  // The bounded entrance gate does not discard the final's still-pending image
  // lifecycle. A slow image must also upgrade after the page gate has opened.
  const deadline = setTimeout(reveal, 10000);
  const observer = progressive ? new MutationObserver(() => {
    if (!ownedSource()) { reveal(); cleanup(); }
    else if (hero.hasAttribute('data-image-managed')) {
      // Staging may finish before the sole same-source hero decodes. Its gate
      // keeps native load/decode listeners until the actual pixels are usable.
      releaseStage();
      if (revealed) cleanup();
    }
  }) : null;
  if (progressive) {
    root.__progressiveHero = hero;
    root.setAttribute('data-document-image-owner', 'native');
    root.setAttribute('data-document-image-src', src);
    observer.observe(root, {subtree: true, childList: true, attributes: true,
                            attributeFilter: ['src', 'data-image-managed']});
    window.addEventListener('pageshow', restored);
    final.addEventListener('transitionend', transitionDone);
    final.addEventListener('transitioncancel', transitionDone);
    if (same()) stage('single');
  }
  if (!images.length) { reveal(); cleanup(); return; }
  // Install all bindings before the no-decode fallback can finish synchronously.
  for (const image of images) {
    const load = () => decode(image);
    const error = () => fail(image);
    bindings.push([image, load, error]);
    image.addEventListener('load', load);
    image.addEventListener('error', error);
  }
  // Inspect the final first: already usable paired/cached pixels never fade again.
  for (const image of [...images].reverse()) {
    if (image.complete) decode(image);
  }
})();
