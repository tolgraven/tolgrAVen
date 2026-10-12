// Only this dedicated test origin is reset. Application state is never seeded.
async function resetTestOrigin(frame, waitFor) {
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
  localStorage.clear(); sessionStorage.clear();
  document.cookie = 'tolgraven-return=; Max-Age=0; Path=/';
}
