/* Geometry-only fixture adapter: one real viewport frame at a time, no mocks. */
(() => {
  if (new URLSearchParams(window.location.search).has('geometry-parking')) {
    document.body.dataset.geometryParking = 'true';
  }
  function verifyParking(frame, item) {
    const heading = document.querySelector('body > h1');
    const parking = heading?.getBoundingClientRect();
    const bounds = frame.getBoundingClientRect();
    if (document.body.dataset.geometryParking !== 'true' || !parking ||
        parking.left < 0 || parking.right > window.innerWidth || parking.right <= parking.left ||
        parking.top < bounds.bottom || parking.bottom > window.innerHeight || parking.bottom <= parking.top) {
      throw new Error('Native geometry parking is not visible below the complete iframe bounds');
    }
    if (!heading.matches(':hover') || frame.matches(':hover')) {
      throw new Error('Native pointer left geometry parking or entered the fixture');
    }
    const target = frame.contentWindow;
    if (bounds.left !== 0 || bounds.top !== 0 || bounds.right > window.innerWidth ||
        bounds.right - bounds.left !== item.viewport[0] || bounds.bottom - bounds.top !== item.viewport[1] ||
        target.innerWidth !== item.viewport[0] || target.innerHeight !== item.viewport[1] || target.devicePixelRatio !== 1) {
      throw new Error('Native geometry child viewport differs from its declared dimensions/DPR');
    }
  }
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
          verifyParking(frame, item);
          await target.visualFixture.ready();
          verifyParking(frame, item);
          if (item.action) {
            // Actions are trusted checked-in test code, executed in that fixture's
            // own realm so native handlers/portals use its document and viewport.
            target.visualFixture.motion.phase('action');
            await target.eval('(async () => {' + item.action + '})()');
            await target.visualFixture.ready();
          }
          verifyParking(frame, item);
          if (item.capture_assert) await target.eval('(() => {' + item.capture_assert + '})()');
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

})();
