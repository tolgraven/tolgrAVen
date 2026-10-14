# Neovim in the browser

The lazy `:neovim` module exposes `:editor` and `:page`. `/neovim` serves the page
with COOP `same-origin` and COEP `require-corp`, required by the official WASM
build's SharedArrayBuffer transport. No Neovim bytes or isolation headers are
acquired by ordinary pages. Dedicated editor routes declare `:shell {:chrome? false}`
to omit the site header, both footers and back-to-top control. The editor keeps one
compact Menu disclosure for navigation, repository tools, imports, buffer export,
restart and help; GitHub diffs place revision/file controls and commit details there
too. Loading and failure messages remain visible outside the menu. The return worker shares the module-owned header
policy so a synthesized `/neovim` document remains isolated. Links crossing the
isolation boundary in either direction use full document navigation. Routes with
the same isolation requirements retain ordinary SPA navigation.
A component embedded in an ordinary document shows an explicit link to the
isolated page. Every top-level document containing a working editor must provide
these headers, including iframe ancestors; an iframe alone cannot supply isolation.
The capability guard also checks SharedArrayBuffer, WebAssembly and Worker availability.
A browser may report isolation while suppressing SharedArrayBuffer; that state shows
a shared-memory explanation instead of a link back to the same isolated page.
Fixture checks are disabled in unsupported contexts, and the transport rejects a
missing SharedArrayBuffer constructor before worker creation. Modern Safari supports
shared memory under COOP/COEP, but the actual browser context must expose it.

```clojure
(m/<> :neovim/editor
  {:initial-text "(println \"hello\")"
   :filetype "clojure"
   :rows 24
   :on-save #(r/dispatch [:my-editor/accept-buffer %])})
```

`:menu-content` accepts extra Hiccup content inside the editor menu, without
changing or restarting the native session. Escape closes the menu and returns focus
to its summary; moving focus out closes it too.

`:cols` defaults to 80 (20–240), `:rows` to 24 (8–80). Width adapts to the
component's container through an owned ResizeObserver. `:initial-text` is bounded
to 8192 characters; this is an initializer, not a controlled value. Changing
initialization options restarts the process and clears the imported-file selector.
Responses from a stopped session cannot update its replacement. “Use buffer” reads the current
buffer and calls `:on-save`; the caller owns any application state or persistence.
`:on-write` receives `{:path <native buffer path>, :text <complete written text>}`
after an ordinary `:w`. It uses a lifecycle-owned `BufWritePost` notification and
dispatches into the consumer's event flow. Unnamed initial-text buffers receive a
session-local filename so writing works. Recoverable command/input errors leave
the worker, grid and buffer export usable; restarting still discards the session.
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
bundled help. It does not implement terminal jobs, OS processes,
external-process plugins, durable file export or saving back to the host. Browser Cmd
shortcuts and Ctrl+Shift+V remain native. Sessions are temporary; copy/use the
buffer before leaving. Large pastes that exceed the ring capacity fail visibly.
The wrapper suppresses upstream's per-input-byte console trace, which otherwise
can exhaust the RPC timeout during bulk imports. Other diagnostics and upstream
filesystem behavior remain available. The worker retains only the first 50 input
bytes for exit diagnostics, with a separate total counter, so imports do not grow
an unbounded JavaScript diagnostic array.

## Pinned artifacts

- Neovim official nightly published 2026-10-13, commit
  `a054b698c0b7c83e934a38df5c0021ae2cb444cc`.
- Release `nvim-wasm-emscripten.zip`, GitHub asset ID `636003451`, SHA-256
  `cb37edbf2474788d1d221e65a28da30f639ebc5b3b7f40a6f5207b79b4db225c`.
- `resources/public/vendor/neovim/` contains unmodified `nvim.js`, `nvim.wasm`,
  `nvim.data`, and upstream LICENSE.txt. `nvim-worker.js` has one documented local
  adaptation: bound the input diagnostic sample to 50 bytes while preserving the
  total count. No CDN requests are needed.
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
/Users/tol/.local/bin/tolgraven-cpu run -- sh -c 'npm run build && lein with-profile +project/test,+provided run -m shadow.cljs.devtools.cli compile neovim-check && lein with-profile +project/test test website.neovim-http-test'
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

## Browser profile and repository workspace

`npm run build` (also the Sass build/watch) runs `build.neovim-workspace`. It
synchronizes the editable `vendor/neovim/init.lua` and `browser.vim` into the
pinned profile (including `after/syntax/nerdtree.vim`) and creates the ignored `vendor/neovim/workspace.json` from the
current checkout's `src/` and `resources/scss/` text files. Symlinks, other roots,
config files and binary assets are excluded. The existing packaging policy
also prunes disabled modules and their style sources; disabling Neovim omits
its workspace bundle and packaging excludes all its public assets. This is a
build snapshot, not access to the server checkout at runtime.

The small `nvim-profile-worker.js` adapter loads the profile/workspace alongside
startup, then uses Emscripten `preRun` to populate the virtual filesystem before
the unchanged upstream worker starts Neovim. Plugin files do not pass through
RPC. One Lua initialization request enables the browser profile and sets cwd to
`/workspace/tolgrAVen`. `/neovim` opens the bundled editor source by default;
`:initial-file` selects a relative repository file instead of `:initial-text`.
The initial file/filetype are configured before attaching the UI at the container's
measured width. The editor shows an indeterminate animated progress bar until its
first complete redraw; the grid remains hidden during startup so intermediate
unnamed buffers cannot flash. Reduced motion uses a static indicator.

Use **Search repository** (`:Meta!`), **Browse source files** (`:Meta! #file`), or
**File tree** (`:NERDTreeToggle /workspace/tolgrAVen`). Meta falls back to Neovim's
filesystem globbing because there is no ripgrep executable. The tree and Meta
prompt/results remain native Neovim windows in the shared grid; buttons restore
keyboard focus to the editor. Normal mode uses a solid cursor with reversed
text/native cursor highlight; insert mode uses the native vertical bar. The
renderer consumes `mode_info_set`, `mode_change`, cursor highlight IDs and cell
percentages, following [the Neovim UI protocol](https://neovim.io/doc/user/api-ui-events/).

The pinned profile contains these runtime plugins, with revisions, local-change
flags, file hashes and license documents in `profile.json`:

- The locally modified **Bruvbox** and **Metabuffer** (Meta revision
  `2f2acdaea483fe5738991b700bf71a27efac9d19`). Metabuffer's embedded Fennel is aliased
  to `fennel` for its cljlib dependency. Animations use the native backend and are
  disabled for responsive startup; mini.nvim is not required.
- **NERDTree**, **vim-devicons**, **vim-airline** (Bruvbox theme/tabline only),
  **vim-clojure-static**, **vim-sexp**, and the user's **rainbow_parentheses.vim**,
  **vim-cursorword**, **vim-numbertoggle**, and **vim-sayonara** forks.
- Small editing helpers: **vim-diminactive**, **vim-repeat**, **vim-commentary**, **vim-textobj-user**,
  **vim-textobj-indent** and **vim-easy-align**.

The browser profile carries the active local `init.vim` settings from its LOOK/general
block (lines 416–497), plus the nearby mouse, incremental-command and popup settings.
The Homebrew fzf runtime path and executable-dependent ripgrep command are omitted.
Undo files use this session's virtual filesystem and disappear with it; title options
remain native Neovim settings rather than changing the site's document title.

It also carries Space/comma leaders, indentation/search/split
preferences, Bruvbox column/selection options, NERDTree settings, airline's empty
separators/tabline, Clojure alignment, rainbow settings, and the complete active
sexp mappings plus `SexpInit` from the local init. `#` opens Meta; Space+n toggles
NERDTree; Space+x invokes Sayonara; comma+o raises a sexp then indents it. Window
navigation uses Neovim splits directly. `vim-diminactive` uses Bruvbox's native
ColorColumn highlight for inactive splits; active splits restore the local 80–240
color-column range. The local dimming blacklists and focus setup are retained.
The RGB UI explicitly enables the colorcolumn method without depending on a terminal
`t_Co` value. Tree devicon delimiters are concealed after NERDTree syntax loading,
without concealing brackets in filenames. Rainbow activation excludes NERDTree,
so it cannot override tree conceal. Icons have no leading padding and retain one
space before the filename.

Reagent pointer handlers send click/drag/release events to `nvim_input_mouse`; a
lifecycle-owned non-passive wheel listener prevents simultaneous page scrolling.
The grid follows Neovim's `mouse_on`/`mouse_off` events. Window focus/visibility
changes send `nvim_ui_set_focus` so the profile's focus autocommands work. Browser
Ctrl/Cmd wheel zoom remains native. The source config was compared with
[tolgraven/nvim-setup](https://github.com/tolgraven/nvim-setup/tree/d3377432fe5b2c5397ada2dd8953b465468387be);
the local init/plugin declarations differ, so the local settings are authoritative.

An icon-only WOFF2 subset of the locally installed Fira Code Nerd Font Mono
contains the profile's private-use glyphs, uses a separate `Neovim Icons` family,
and keeps regular text/cell measurement in Menlo. Its copyright/OFL notice is
retained beside it. It avoids shipping a whole Nerd Font.

These are compatibility limits, not claims that every plugin in the original
config was exercised:

| Original config area | Browser suitability |
| --- | --- |
| Ordinary options, pure keymaps, syntax/indent, text objects, comments, alignment | Portable; the subset above is installed and the important flows are checked. |
| Buffer/cmdline completion (nvim-cmp with cmp-buffer/cmdline), surround/autopairs, other small mini modules | Plausible next additions; not installed or live-verified by this change. |
| NERDTree, Meta project mode, sessions and file-based operations | Work on bundled/imported virtual files. Session files and writes do not alter the host repo. Meta git metadata and external transforms remain unavailable. |
| Tree-sitter, render-markdown, navic, lazydev, Trouble | Need usable WASM parsers or a language/diagnostic source; existing native `.so` parsers cannot simply be copied. |
| Fugitive, gitgutter, neogit/diffview, fzf/fzf-lua/Telescope native sorter, Mason | Need external executables/installers; no working browser bridge here. |
| clojure-lsp, Conjure/fireplace, DAP, Python providers, Copilot/Chat, tmux/shell/terminal tools | Need server/process/network adapters; excluded from this profile. |
| vim-plug/lazy bootstrap and full init.vim | The download/build/OS paths prevent direct sourcing. Install pinned runtime files and port setup explicitly instead. |

## Imported files

**Import files** accepts multiple files; **Import folder** preserves relative
paths. Files are copied in bounded binary-safe RPC packets into `/workspace/`,
the imported directory becomes cwd, and the first file opens. The picker can
switch among imports; **Search imported project** uses Meta on that cwd. A batch
is limited to 512 files, 4 MiB per file and 32 MiB total. Relative traversal,
absolute paths and duplicate names are rejected. Empty files, Unicode text and
multi-packet binary bytes are supported. File import state is only the mounted
component's UI projection; the worker owns the actual files.

`:w` and Meta writeback affect this session's virtual filesystem. Reload,
restart and unmount discard it. Durable export/persistence and browser directory
write access are separate work. “Use buffer” remains available to a component
consumer that supplies `:on-save`.

The fixture also has **Run plugin checks** for real colors, Meta filtering/edit
writeback, NERDTree, devicons, airline and sexp mappings, and **Run workspace
checks** for Meta source discovery and real File-object imports. **Run settings
checks** verifies local option values, inactive split dimming/restoration, and tree
conceal rules in the actual WASM process. The displayed
worker startup measurement is local fixture evidence, not a production-network
performance guarantee. Additional checks:

```sh
bb -cp scripts:src/cljc:resources test/scripts/neovim_workspace_test.clj
node test/scripts/neovim_profile_test.cjs
```

## GitHub commit diffs

Commit rows open `/neovim/github/:user/:repo/:sha` as a new isolated document. Crossing into or out of isolation uses document navigation, so normal pages recover their normal media policy.
The GitHub module owns this route, data and controls; it is omitted when Neovim
is disabled, preserving the existing basic commit viewer. Before/after uses one
native Neovim process with vertical diff windows, absolute line numbers and
nonmodifiable, readonly buffers. This layout attaches its hidden UI at the measured size before loading source files and creating equal-width windows; it publishes only the completed redraw. The patch mode requests GitHub's complete patch.

Revision metadata and sources use the shared browser HTTP effects, with cached
results keyed by repository and immutable SHA. Comparisons use the first parent
for merges, the previous filename for renames, and empty sides for additions,
deletions and root commits. Changed-file metadata follows GitHub's pages up to
its 3000-file limit. UTF-8 source files and patches are limited to 1 MiB each;
binary, larger and unavailable files show an error and a GitHub link.
