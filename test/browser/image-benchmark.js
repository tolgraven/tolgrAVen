// Probe instrumentation starts before preload discovery. It observes native DOM,
// decoding and paint readiness without replacing images or responsive selection.
if (location.pathname.startsWith('/probe/')) {
  const decoding = new WeakSet(), decoded = new WeakSet(), loads = new WeakMap();
  let firstVisible = null, previewVisible = null, gradientVisible = null;
  let finalReady = null, finalPaint = null, finalOpaque = null;
  let sent = false;
  const decode = image => {
    if (image.complete && image.naturalWidth && !decoding.has(image)) {
      decoding.add(image);
      Promise.resolve(image.decode?.()).then(() => {
        decoded.add(image);
        if ((image.hasAttribute('data-image-final') || image.id === 'top-banner') && finalReady == null) finalReady = performance.now();
      });
    }
  };
  document.addEventListener('load', event => {
    const image = event.target;
    if (image.matches?.('#top-banner, #top-banner img')) {
      loads.set(image, performance.now());
      decode(image);
    }
  }, true);
  const observe = () => {
    const hero = document.querySelector('#top-banner');
    const images = hero?.matches('img') ? [hero] : [...(hero?.querySelectorAll('img') || [])];
    const final = images.find(image => image.hasAttribute('data-image-final')) || images[0];
    images.forEach(decode);
    const main = document.querySelector('main');
    const painted = image => {
      const rect = image.getBoundingClientRect();
      let layer = image;
      while (layer && layer !== document.body) {
        const style = getComputedStyle(layer);
        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) <= 0) return false;
        layer = layer.parentElement;
      }
      return decoded.has(image) &&
        rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 &&
        rect.top < innerHeight && rect.left < innerWidth && Number(getComputedStyle(main).opacity) > 0;
    };
    if (main && !document.querySelector('#app').hasAttribute('data-document-pending')) {
      if (gradientVisible == null && hero.hasAttribute('data-image-placeholder') &&
          getComputedStyle(hero).backgroundImage !== 'none' && Number(getComputedStyle(main).opacity) > 0) {
        const rect = hero.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top < innerHeight) gradientVisible = performance.now();
      }
      if (previewVisible == null && images.some(image => image.hasAttribute('data-image-preview') && painted(image))) previewVisible = performance.now();
      if (firstVisible == null && images.some(painted)) firstVisible = performance.now();
      if (finalPaint == null && final && painted(final)) finalPaint = performance.now();
      if (finalOpaque == null && final && painted(final) && Number(getComputedStyle(final).opacity) >= 0.999) finalOpaque = performance.now();
    }
    if (!sent && finalReady != null && firstVisible != null && finalPaint != null && finalOpaque != null) {
      sent = true;
      const record = {width: innerWidth, height: innerHeight, dpr: devicePixelRatio,
        heroVisibleMs: firstVisible, previewVisibleMs: previewVisible, gradientVisibleMs: gradientVisible,
        finalLoadMs: loads.get(final) ?? null, finalReadyMs: finalReady, finalPaintMs: finalPaint, finalOpaqueMs: finalOpaque,
        visibleImageLayers: images.filter(painted).length,
        finalSource: final.currentSrc.split('/').pop(),
        previewSource: images.find(image => image.hasAttribute('data-image-preview'))?.currentSrc.split('/').pop() || null,
        resources: performance.getEntriesByType('resource').filter(entry => entry.name.includes('/image/')).map(entry =>
          ({name: entry.name.split('/').pop(), encodedBytes: entry.encodedBodySize, transferBytes: entry.transferSize,
            startMs: entry.startTime, requestMs: entry.requestStart, headersMs: entry.responseStart, endMs: entry.responseEnd}))};
      parent.postMessage({type: 'image-benchmark', record}, location.origin);
    } else if (!sent) requestAnimationFrame(observe);
  };
  requestAnimationFrame(observe);
}
// CONTROLLER
if (location.pathname === '/') {
  const frame = document.querySelector('iframe'), results = document.querySelector('#results');
  const runs = [];
  let metadata;
  const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
  const render = () => {
    const groups = new Map();
    for (const run of runs) {
      const key = `${run.width} / ${run.dpr} / ${run.version}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(run);
    }
    results.replaceChildren();
    for (const [key, values] of groups) {
      const row = document.createElement('tr');
      for (const value of [key, values.length, median(values.map(v => v.heroVisibleMs)).toFixed(1),
        median(values.map(v => v.finalReadyMs)).toFixed(1), median(values.map(v => v.imageBytes)), values[0].finalSource]) {
        const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
      }
      results.append(row);
    }
    document.querySelector('#raw').textContent = JSON.stringify({metadata, runs}, null, 2);
  };
  fetch('/saved-results').then(response => response.json()).then(saved => {
    metadata = saved.metadata; runs.push(...saved.runs); render();
  });
  const drain = async () => {
    await new Promise(resolve => {frame.onload = () => {frame.onload = null; resolve();}; frame.src = 'about:blank';});
    return (await fetch('/idle')).json();
  };
  document.querySelector('#run').onclick = async () => {
    const button = document.querySelector('#run'), status = document.querySelector('#status');
    button.disabled = true; document.body.classList.add('running');
    try {
      metadata = {...await (await fetch('/metadata')).json(), browserUserAgent: navigator.userAgent};
      const [width, dpr] = document.querySelector('#case').value.split(',').map(Number);
      for (let iteration = 0; iteration < 5; iteration++) {
        const versions = iteration % 2 ? ['progressive', 'baseline'] : ['baseline', 'progressive'];
        for (const version of versions) {
          const isolation = await drain();
          const key = crypto.randomUUID();
          status.textContent = `Running ${width}/DPR${dpr}: ${version} ${iteration + 1}/5`;
          const record = await new Promise((resolve, reject) => {
            const timer = setTimeout(() => {removeEventListener('message', receive); reject(new Error('Probe timed out'));}, 30000);
            const receive = event => {
              if (event.origin === location.origin && event.source === frame.contentWindow && event.data?.type === 'image-benchmark') {
                clearTimeout(timer); removeEventListener('message', receive); resolve(event.data.record);
              }
            };
            addEventListener('message', receive);
            frame.src = `/probe/${version}/${key}`;
          });
          if (record.width !== width || record.dpr !== dpr) throw new Error(`Expected ${width}/DPR${dpr}; actual ${record.width}/DPR${record.dpr}. Set browser viewport/DPR first.`);
          const requests = await (await fetch(`/report/${key}`)).json();
          runs.push({...record, version, isolation, iteration: iteration + 1, requestCount: requests.length,
                     imageBytes: requests.reduce((total, request) => total + request.sentBytes, 0), requests});
          document.querySelector(`#${version}-probe`).href = `/probe/${version}/${key}`;
          render();
          await fetch('/results', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({metadata, runs})});
        }
      }
      await drain();
      status.textContent = `Completed ${width}/DPR${dpr}: five cold-cache runs per version. ${runs.length} runs saved.`;
    } catch (error) {status.textContent = `FAILED: ${error.message}`;}
    finally {await drain(); document.body.classList.remove('running'); button.disabled = false;}
  };
}
