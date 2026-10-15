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
  function geometry() {
    const selected = [...document.body.querySelectorAll('*')].filter(element =>
      !['SCRIPT', 'STYLE', 'LINK'].includes(element.tagName));
    return selected.map(element => {
      const box = element.getBoundingClientRect();
      const parent = element.parentElement.getBoundingClientRect();
      const style = getComputedStyle(element);
      const pseudo = getComputedStyle(element, '::before');
      return {
        tag: element.tagName, id: element.id, class: element.className,
        selector: element.id ? '#' + element.id : element.tagName.toLowerCase() +
          (typeof element.className === 'string' && element.className.trim() ? '.' + element.className.trim().split(/\s+/).join('.') : ''),
        role: element.getAttribute('role'),
        text: element.children.length ? null : element.textContent,
        box: [box.x, box.y, box.width, box.height],
        relative: [box.x - parent.x, box.y - parent.y],
        scroll: [element.scrollWidth, element.scrollHeight, element.clientWidth, element.clientHeight],
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
        icon: element.matches('i,.icon') ? {
          content: pseudo.content, width: pseudo.width, height: pseudo.height,
          mask: pseudo.maskImage, display: pseudo.display
        } : null
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
    await timeout(Promise.all([...document.images].map(async image => {
      if (!image.complete) await new Promise((resolve, reject) => {
        image.addEventListener('load', resolve, {once: true});
        image.addEventListener('error', () => reject(new Error('Fixture image failed: ' + image.getAttribute('src'))), {once: true});
      });
      if (!image.naturalWidth) throw new Error('Fixture image failed: ' + image.getAttribute('src'));
      await image.decode();
    })), 'Fixture images did not decode');
    const backgrounds = new Set([...document.body.querySelectorAll('*')].flatMap(element =>
      [...getComputedStyle(element).backgroundImage.matchAll(/url\(["']?(.*?)["']?\)/g)].map(match => match[1])));
    await timeout(Promise.all([...backgrounds].map(src => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => image.decode().then(resolve, reject);
      image.onerror = () => reject(new Error('Fixture background failed: ' + src));
      image.src = src;
    }))), 'Background images did not decode');
    // Native finite entrances/expansions run to completion. Infinite shimmer,
    // spinner and caret clocks are paused only after the component has settled.
    let stable = 0, previous = '';
    while (performance.now() < deadline) {
      const finite = document.getAnimations().filter(animation =>
        animation.playState === 'running' && animation.effect.getTiming().iterations !== Infinity);
      const current = JSON.stringify(geometry());
      stable = current === previous && !finite.length ? stable + 1 : 0;
      if (stable >= 4) break;
      previous = current;
      await pause(100);
    }
    if (stable < 4) throw new Error('Layout or native motion did not settle');
    for (const animation of document.getAnimations()) {
      if (animation.effect.getTiming().iterations === Infinity) {
        animation.pause(); animation.currentTime = 0;
      }
    }
    await frame(); await frame();
    if (document.querySelector('[role="alert"], .component-error')) throw new Error('Native fixture contains an error fallback');
    if (errors.length) throw new Error(errors.join('\n'));
  }
  window.visualFixture = {
    ready,
    snapshot: () => ({
      viewport: [innerWidth, innerHeight, devicePixelRatio],
      environment: {userAgent: navigator.userAgent, platform: navigator.platform,
        language: navigator.language, viewport: [innerWidth, innerHeight, devicePixelRatio],
        fonts: [...document.fonts].map(font => [font.family, font.style, font.weight, font.status]).sort()},
      geometry: geometry(), errors
    })
  };
})();
