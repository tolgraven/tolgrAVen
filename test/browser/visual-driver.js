/* Geometry-only fixture adapter: one real viewport frame at a time, no mocks. */
window.visualDriver = {
  async capture(cases) {
    const output = [];
    for (const item of cases) {
      const frame = document.createElement('iframe');
      frame.width = item.viewport[0];
      frame.height = item.viewport[1];
      Object.assign(frame.style, {position: 'fixed', left: '0', top: '0', border: '0'});
      try {
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('Fixture document did not load')), 15000);
          frame.onload = () => { clearTimeout(timer); resolve(); };
          frame.onerror = () => { clearTimeout(timer); reject(new Error('Fixture document failed')); };
          frame.src = item.path;
          document.body.appendChild(frame);
        });
        const target = frame.contentWindow;
        await target.visualFixture.ready();
        if (item.action) {
          // Actions are trusted checked-in test code, executed in that fixture's
          // own realm so native handlers/portals use its document and viewport.
          target.visualFixture.motion.phase('action');
          await target.eval('(async () => {' + item.action + '})()');
          await target.visualFixture.ready();
        }
        output.push({id: item.id, state: target.visualFixture.snapshot()});
      } catch (error) {
        output.push({id: item.id, error: String(error)});
      } finally {
        frame.contentWindow.visualFixture?.motion?.stop();
        frame.remove();
      }
    }
    return output;
  }
};
