// Exercise the actual compiled return worker with a temporary local document.
const build = 'neovim-fixture';
const result = document.getElementById('result');
const proofHtml = `<!doctype html><html lang="en"><meta charset="utf-8"><title>Cached isolation proof</title><body><h1>Cached isolation proof</h1><p id="proof"></p><button id="cleanup">Clean up test worker</button><script src="/fixtures/neovim-return.js"></script></body></html>`;

async function cleanup() {
  for (const registration of await navigator.serviceWorker.getRegistrations()) {
    if (registration.active?.scriptURL.includes('build=' + build)) await registration.unregister();
  }
  await caches.delete('tolgraven-return-v1-' + build);
  document.getElementById('proof').textContent += '; test worker/cache removed';
}

if (document.getElementById('proof')) {
  document.getElementById('proof').textContent = crossOriginIsolated && typeof SharedArrayBuffer === 'function'
    ? 'PASS: cached document is cross-origin isolated and SharedArrayBuffer is available'
    : 'FAIL: cached document lost cross-origin isolation';
  document.getElementById('cleanup').onclick = cleanup;
} else {
  document.getElementById('run').onclick = async () => {
    try {
      result.textContent = 'Registering actual return worker…';
      await navigator.serviceWorker.register('/js/return/return-worker.js?build=' + build, {scope: '/'});
      const registration = await navigator.serviceWorker.ready;
      await new Promise((resolve, reject) => {
        const channel = new MessageChannel();
        const deadline = setTimeout(() => reject(new Error('Save timed out')), 10000);
        channel.port1.onmessage = ({data}) => {
          clearTimeout(deadline);
          channel.port1.close();
          if (data.ok) resolve(); else reject(new Error('Worker rejected the cached document'));
        };
        registration.active.postMessage({op: 'save', build, url: location.origin + '/neovim', html: proofHtml}, [channel.port2]);
      });
      location.href = '/neovim';
    } catch (error) { result.textContent = 'FAIL: ' + error.message; }
  };
}
