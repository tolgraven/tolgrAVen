// Only this dedicated test origin is reset. Application state is never seeded.
async function resetTestOrigin(frame, waitFor) {
  if (location.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname) ||
      !location.pathname.startsWith('/__tests/'))
    throw new Error('Reset requires the dedicated loopback test origin');
  frame.src = 'about:blank';
  await waitFor(() => frame.contentDocument?.URL === 'about:blank', 'previous document closes');
  // Pagehide queues the outgoing pair. Clear after the worker's pending writes.
  const worker = navigator.serviceWorker?.controller;
  if (worker) await new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    const timeout = setTimeout(() => {
      channel.port1.close(); reject(new Error('Return cache clear timed out'));
    }, 5000);
    channel.port1.onmessage = event => {
      clearTimeout(timeout); channel.port1.close();
      event.data.ok ? resolve() : reject(new Error('Return cache clear failed'));
    };
    worker.postMessage({op: 'clear'}, [channel.port2]);
  });
  // Heavy persisted component data lives separately from the small stores.
  // Delete only the application's known cache, never enumerate origin databases.
  if (typeof indexedDB === 'undefined') throw new Error('Component cache clear requires IndexedDB');
  await new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase('tolgraven-component-cache');
    const finish = error => {
      clearTimeout(timeout);
      request.onsuccess = request.onerror = request.onblocked = null;
      error ? reject(error) : resolve();
    };
    const timeout = setTimeout(() => finish(new Error('Component cache clear timed out')), 5000);
    request.onsuccess = () => finish();
    request.onerror = () => finish(new Error('Component cache clear failed: ' + (request.error?.message || 'unknown error')));
    request.onblocked = () => finish(new Error('Component cache clear blocked'));
  });
  localStorage.clear(); sessionStorage.clear();
  document.cookie = 'tolgraven-return=; Max-Age=0; Path=/';
}
