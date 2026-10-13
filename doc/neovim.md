# Neovim in the browser

The lazy `:neovim` module exposes `:editor` and `:page`. `/neovim` serves the page
with COOP `same-origin` and COEP `require-corp`, required by the official WASM
build's SharedArrayBuffer transport. No Neovim bytes or isolation headers are
acquired by ordinary pages. The return worker shares the module-owned header
policy so a synthesized `/neovim` document remains isolated. A link into `/neovim`
from a non-isolated document
uses a full document navigation; subsequent navigation can use the SPA as usual.
A component embedded in an ordinary document shows an explicit link to the
isolated page. Every top-level document containing a working editor must provide
these headers, including iframe ancestors; an iframe alone cannot supply isolation.

```clojure
(m/<> :neovim/editor
  {:initial-text "(println \"hello\")"
   :filetype "clojure"
   :rows 24
   :on-save #(rf/dispatch [:my-editor/accept-buffer %])})
```

`:cols` defaults to 80 (20–240), `:rows` to 24 (8–80). Width adapts to the
component's container through an owned ResizeObserver. `:initial-text` is bounded
to 8192 characters; this is an initializer, not a controlled value. Changing
initialization options restarts the process. “Use buffer” reads the current
buffer and calls `:on-save`; the caller owns any application state or persistence.
Restart and unmount terminate the worker and cancel pending RPC requests.

The process owns the editable buffer. The component holds only transient redraw
cells, highlight attributes, cursor, mode and connection status in local Reagent
state. Initialization options are domain data (`:spec false`), rather than component
feature specs; the full page retains the normal `defpage` boundary. Adjacent cells
share text runs to preserve readable text and avoid one DOM node
per cell. Pure transformations interpret `ext_linegrid`; React owns all rendered
DOM, including loading/error status and keyboard/paste handlers. Hooks exist only
for worker and resize-observer acquisition/cleanup. The JavaScript adapter is the
native Worker/SharedArrayBuffer/MessagePack boundary, following upstream's ring
layout and startup wake protocol. There is no transport initialization during SSR.

The current port is experimental. This integration provides ordinary keys,
modifier keys, paste and composition input, buffer reads, Lua, syntax colors and
bundled help. It does not implement mouse editing, terminal jobs, OS processes,
network plugins, filesystem import/export or durable file saving. Browser Cmd
shortcuts and Ctrl+Shift+V remain native. Sessions are temporary; copy/use the
buffer before leaving. Large pastes that exceed the ring capacity fail visibly.
Upstream's worker still contains its diagnostic logging and filesystem behavior.

## Pinned artifacts

- Neovim official nightly published 2026-10-13, commit
  `a054b698c0b7c83e934a38df5c0021ae2cb444cc`.
- Release `nvim-wasm-emscripten.zip`, GitHub asset ID `636003451`, SHA-256
  `cb37edbf2474788d1d221e65a28da30f639ebc5b3b7f40a6f5207b79b4db225c`.
- `resources/public/vendor/neovim/` contains unmodified `nvim.js`, `nvim-worker.js`,
  `nvim.wasm`, `nvim.data`, and upstream LICENSE.txt. No CDN requests are needed.
- MessagePack uses the vendored no-eval CommonJS distribution of `msgpackr` 2.1.0
  (named `msgpackr.js` for Shadow resource discovery) beside the adapter, with its
  MIT license. npm tarball integrity:
  `sha512-p/pBCVO63CsvvpkomUnNNag6+n38rULuDA6HHe70o2gtC8ODI52foF/4ko2qQcp6OiErJXTmrZeXmsGGHsIQNQ==`.

To update, download the official release zip, verify its GitHub digest before
extracting these four files, update provenance, and repeat the focused checks.
The module is discovered normally; disable with
`{:modules {:neovim {:enabled false}}}`. Packaging then excludes its namespaces,
styles and runtime assets; routes and scoped isolation adapters are absent too.
No dependency-manifest change or prefab refresh is required for these vendored
artifacts.

## Verification

The native RPC/codec checks run with `node test/scripts/neovim_transport_test.cjs`.
`node test/scripts/neovim_return_worker_test.cjs <compiled-worker-path>` exercises
the actual release worker with bounded in-memory Cache/Fetch adapters; this does
not replace a browser isolation check.

```sh
/Users/tol/.local/bin/tolgraven-cpu run -- sh -c 'npm run build && lein with-profile +project/test,+provided run -m shadow.cljs.devtools.cli compile neovim-check && lein with-profile +project/test test tolgraven.neovim-http-test'
```

Serve the resulting isolated snapshot with COOP/COEP headers and open
`/fixtures/neovim.html` in existing Chrome. The fixture runs pure redraw/key
regressions and mounts the real component. “Run live checks” verifies the real
WASM buffer, Lua and bundled help. Worker counts verify restart/unmount cleanup;
“Use buffer” verifies the edited text reached the consumer. Serve the same fixture
without isolation to exercise the unsupported state. The fixture is separate from
the site: also compile the production browser and Node SSR targets, and check
`/neovim` through the real Ring stack when a local instance is available.

Upstream: https://github.com/neovim/neovim/pull/41342 and
https://github.com/neovim/neovim/issues/39987.

The additional `/fixtures/neovim-return.html` fixture registers the real compiled
return worker, saves a temporary `/neovim` document, and navigates to it. The
proof page must report isolation and SharedArrayBuffer availability. Its cleanup
button unregisters only this test worker and deletes its test cache. The fixture
server must send `Service-Worker-Allowed: /` for the worker script.
