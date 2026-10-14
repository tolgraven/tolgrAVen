/* Reuse the site's content-hashed chunks in an isolated rendering runtime.
 * This worker has no window: the main entry registers code but never boots UI. */
const loaded = new Set();
self.onmessage = function (event) {
  const {id, assets, request} = event.data;
  try {
    for (const asset of assets) {
      if (loaded.has(asset.id)) continue;
      const url = new URL(asset.url, self.location.origin);
      if (url.origin !== self.location.origin) throw new Error("Invalid render asset");
      if (asset.id === "main") importScripts(url.href);
      else self.website.app.worker_import_BANG_(asset.id, url.href);
      loaded.add(asset.id);
    }
    const pair = self.website.ssr.local_render.worker_pair_BANG_(request);
    self.postMessage({id, pair});
  } catch (_) {
    // Do not send values from page state through error messages.
    self.postMessage({id, error: "Local page rendering unavailable"});
  }
};
