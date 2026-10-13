# Browser and renderer implementation

Keep this guide current with source changes. Detailed APIs/examples live in
`doc/components.md`, `doc/ssr.md`, and `doc/dev-console.md`.

- The Markdown editor configure hook generates a pinned selective public SDK
  entry under ignored `target/generated-sdk`; Shadow's declared package directory
  resolves it consistently for browser, test and Node builds. Never edit shared
  `node_modules` or copy private SDK implementations. Review the version/hash pin
  and public exports when upgrading. Adding/removing a native SDK export requires
  restarting that watch; the compile guard reports this explicitly. Ordinary
  edits using existing exports retain HMR. See `doc/markdown-authoring.md`.

## Implementation priority: Reagent and re-frame

- Apply the root guide's Reagent/re-frame-first principles to every frontend change,
  including lazy loading and performance optimizations. See
  `doc/reagent-re-frame.md` for the event flow, examples and justified exceptions.
- Render ordinary Hiccup with `defc`; handlers belong in Reagent attribute maps.
  Use the existing declarative dependency/loading/visibility/presence capabilities
  before adding hooks, custom scheduling or native listeners.
- Read scoped state with `<sub` and dispatch writes with `>reset`/`>update` or
  registered domain events. Compose layer-2/3 subscriptions from their actual
  input subscriptions; keep derivations pure and narrow. Application state must
  remain visible in app-db and application IO in registered effects/source adapters.
- Delayed application work uses event effects such as `:dispatch-later`. Native
  Promise code belongs at SDK/module-loader boundaries. A local pointer gesture
  can own a cancellable timer/ref when feeding every movement into app-db would
  add noise; explain that exception and dispatch the resulting application action.
- Reach for modern React features only for a concrete lifecycle/interoperability
  need or demonstrated performance benefit. Keep them behind small shared adapters,
  preserve normal re-frame event tracing, and document the reason. Do not create a
  second state machine/store merely to use a React API.
- Through `tolgraven.react`, hooks delegate to upstream `reagent.hooks`. Effects
  may return implicit nil; cleanup functions run on dependency changes/unmount.
  Hook dependencies use Clojure value equality without converting their contents;
  native handles/functions remain opaque. Memo/callback helpers require explicit
  dependencies. Equal state/reducer updates retain the previous value identity.
  Browser adapters must release their listeners/observers/timers and guard SSR.
  Use `:listener/register` / `:listener/stop` effects for application bindings.
  Committed browser/component lifecycles use `listener/listen!` binding maps and
  its cleanup through that same registry, including capture/passive/once options.
  Low-level feature code may retain native listeners when importing the registry
  creates a dependency cycle; document the concrete reason at the adapter.
  Link-container pointer/focus/click interactions use Hiccup event attributes;
  their event handlers resolve the current committed candidate registry. Preserve
  caller events and release observation/timers on reconfiguration or unmount.

## Ownership and feature contracts

- Spec-capable `defc` instances accept a map or feature-vector first argument.
  The runtime expands vectors to `{:features ...}` before validation and callbacks,
  then resolves declaration defaults and ordered overrides. Disabled entries must
  skip config functions and resource acquisition. Root capabilities reconcile owned setup/cleanup without
  conditional hooks or body remounts; initial structural wrappers stay stable unless
  the caller explicitly changes the Hiccup key. Full-page boundaries and declaration
  dependencies remain required. See `doc/components.md` for precedence and adapters.
- Normal visibility/prefetch UI uses `defc :on-seen` and event vectors. Core source
  namespaces that cannot depend on the full runtime use the lean shared visibility
  sentinel; do not duplicate its IntersectionObserver algorithm.

- Feature caches observe re-frame subscriptions through owned, disposable
  reactions. Persistence belongs in the storage lifecycle adapter; do not add
  feature watches to app-db or perform IO in subscription computations.
  Scoped state handles are native re-frame subscriptions; write through
  `>reset`/`>update` events. Accepted transactions enqueue persistence through
  the storage interceptor/effect; no mirrored app-db or writable cursor.
  Local-return capture observes its source subscription in the component lifecycle.
  Return-worker decisions use Clojure data in `ssr/return_policy.cljs`; Cache,
  Request/Response and event lifetime handling stay in `ssr/return_worker.cljs`.
  Decode JSON once at that boundary; process native entry arrays as sequences,
  retaining opaque Request/Response handles. Keep the native Promise write queue
  and synchronous `waitUntil`/`respondWith` event ownership.

- Feature modules live under `modules/<module>/`; collocate schemas, query
  declarations and `pages.cljc` with the implementation. Browser/Node-only schemas
  use `.cljs`; portable routes/data contracts use `.cljc` and must not require
  browser implementation namespaces. Shared platform contracts remain in `src/cljc`.
- `navigation/` owns routing, preload, transition and scroll adapters and contracts.
  `validation/schema.cljs` assembles browser/Node app-db sections from their owners;
  small single-use shell-state contracts stay inline there.
- Image readiness callbacks use the shared picture lifecycle: notify only a decoded,
  visibly committed final candidate after its overlap completes. Source changes and
  unmount release native observation; hidden ancestors wait on scoped visibility/
  motion changes rather than frame polling. See `doc/progressive-images.md`.
- Hydrate through `tolgraven.react/hydrate-root`: it preserves the upstream root
  lifecycle and compiler while forwarding native recovery/prefix options that
  Reagent 2.0.1 otherwise drops. Initial nested SSR boundaries remove their markers
  in a transition and release hydration batching only after that commit.
- Authoring drafts/uploads/anchors belong to re-frame. Pending image chips use
  native editor nodes/decorations, never authored markers. SDK extension slots
  may require stable reverse React adapters; keep their native document/selection
  work scoped and render ordinary Reagent UI. See `doc/markdown-authoring.md`.
- Working/named blog drafts use account-owned projections in the existing storage
  queue/debounce and navigation/pagehide drain. Restore validates before applying;
  new input and account changes supersede pending reads. Published-post revision
  collections retain their edit target; selected document IDs own native undo lifetime.
- Source mode uses literal native text plus the same media nodes, without an
  independent CodeMirror view or Markdown markers. Source input commits its
  registered form event before the next key; controlled reconciliation reads
  the latest managed query. Keep the text/on-change/disposal boundary reusable.
- Rendered reusable UI lives in `components/`; feature-specific UI lives with its
  module. `component/` is the declaration/lifecycle runtime, not a view inventory.
  Import the owning namespace directly; there is no top-level `views.cljs` shim.
- The page shell owns one lazy contact popup outside both footer copies. Footer
  controls share its re-frame state; do not mount the popup inside each control.
  Keep the HUD above the contact layer so notices remain actionable.
- Render state through `tolgraven.react` subscriptions and change it through events.
  Effects/source adapters own I/O; view functions remain pure.
- Use `<component>` names, `defc` features and `defpage` boundaries. `m/<>` selects
  direct or lazy module exports; it does not add a permanent wrapper. Module
  exports default to proximity activation; `:load-on` and `loader/<trigger>`
  configure intent/earlier triggers. Hidden panels use explicit intent. Completed
  SSR boundaries retain native nodes until activation and inner hydration commit.
  User controls/avatars do not depend on Markdown or Link Preview; the comments
  view acquires its candidate-aware Markdown export through `m/<>` when rendered.
  The global Link Preview controller stays deferred until a candidate-bearing
  container activates it; plain routes do not acquire it just for an empty host.
  CSS/code failures retry silently once after three seconds before a local error.
  Bootstrap waits for route readiness, not a fixed set of optional modules; their
  mounted boundaries own acquisition and preserve static SSR until activation.
- Module `:styles` metadata is available before code acquisition. The shared loader
  starts CSS and JS together and waits for both before readiness; React owns
  stylesheet insertion. Parse the document style manifest once per element/text
  revision and retain readiness for installed React resources. Initial inlined
  route styles already satisfy readiness
  and must not trigger duplicate external requests. Popup-only `:ssr-styles :deferred`
  sheets stay out of the SSR head but still start before Shadow acquisition. Keep
  feature CSS in `resources/scss/modules` and preserve
  ordinary selector specificity. Literal `:icons ["brands/name" "solid/name"]`
  on a component/module exports font glyphs as SVG masks in the owner’s CSS.
  Feature ownership comes from its module folder or explicit `:module`; shared
  components use the shell. Regenerate assets with `bb fonts:icons`. See `doc/styles.md`.
- The root-merged `:font` feature keeps initial text visible and uses re-frame
  readiness/fade events for a late face. Own the font CSS in its consumer module;
  use an optional initial face plus a separately named ready face to prevent an
  uncontrolled browser swap. Font phases are transient, never persisted. Reduced
  motion and local returns bypass fades. See `doc/styles.md` and `doc/components.md`.
- A module `:install` hook may restore browser-local caches after code acquisition.
  The loader shares/awaits it before publishing code readiness; ordinary `:init`
  still owns data activation. SSR adapters must not install disk caches or watches.
- Declare managed dependencies at the narrowest owning component/module/page.
  Preloads acquire those same sources; keep pending, empty, failed and cached distinct.
- `:component-data/status` acquires the shared queue through its managed source
  factory; the derived reaction only reads readiness. Persistent scoped value
  subscriptions dispatch the idempotent `:component-state/load` event on acquisition;
  ordinary scoped reads stay pure. Declared defaults initialize after commit through
  `:component-state/initialize`, sharing the same effect with eager storage loads.
  Keep transport/initialization out of component bodies and derived computations.
- Feature layout commits capture their render's callbacks and shape. Never store
  pending render work in mutable slots that an abandoned render can overwrite.
  Popover/link observers belong to committed effects with current configuration
  and cleanup; closed popovers acquire no measurement observer/listener.
- Supabase startup retries a settings/SDK initialization failure silently once after
  three seconds; only a second failure publishes the retry HUD. Queued reads
  retain their boot dependency and resume when startup succeeds.
- Subscription-backed Supabase/Strapi results enter app-db through events. Never
  duplicate transport reads in pages or component render functions.
- Reuse appearance/presence/loading features. Hydration and restored data bypass
  loading and entrances; later SPA navigation must restore ordinary motion.
  Appearance classes merge onto native roots; `appear-wrapper` is only a CSS
  class. Network SSR retains the original site/hero entrance in CSS across
  hydration; the document-owned mount container gates it on hero decoding.
  Streamed skeletons remain until that gate opens. Local/history returns stay settled.
- Shared Markdown, full syntax highlighting and mapping code live in the
  `:markdown`, `:highlight` and `:maps` bundles. Keep the full highlighter out of
  Markdown so link previews do not acquire every language. Node renders highlighted
  code inside completed React Suspense boundaries. Copy/wrapping and the page
  hydrate first; the formatter loads/hydrates after the common after-page gate.
  Keep the formatter in its own memoized `defc` so control updates do not replace
  pending SSR nodes. Initial boundaries own transition batching in
  `component/hydration.cljs` until inner commit/unmount; navigation releases it.
  Cached child completion can precede parent setup: acquire no owner after that
  completion. Overlapping owners and StrictMode cleanup must restore batching
  only when the last current owner releases; stale document callbacks are inert.
  Capture their initial restoration decision without subscribing to completion.
  New SPA code uses the normal loader immediately. Paired local
  returns render completed boundaries too and skip the formatter during bootstrap.
  Local-return capture uses lazy `:page-capture` transport and renders in an
  isolated worker using cached browser chunks and the `:page-render` bundle;
  development and DOM-dependent Leaflet pages retain browser rendering.
  The installer remains eager to restore state before the first commit.
- Markdown code, including untagged blocks and inline snippets, shares the deferred
  formatter boundary. Node SSR renders Bruvbox-highlighted tokens immediately;
  browser acquisition waits for the existing after-page gate. The blog supplies
  its Clojure fallback and detection set; untagged inline snippets use Bash unless
  their owner specifies `:inline-language`. Explicit tags take precedence.
  Copy gestures and optional folding belong to the outer component; selection and
  multi-click cancel copying, as do drag, pointer exit/cancellation during the press
  and interactive controls. Movement after release preserves the completed tap.
  Linked inline code suppresses its copy control instead of nesting a button in a
  link. Normal gestures use Reagent event props. Only while the SSR formatter is
  suspended, a ref/effect adapter bridges native intent to those same handlers
  through the shared listener registry. It prevents duplicate event replay and
  releases its bindings on the inner hydration commit.
  Copy the source, excluding line numbers/buttons. Markdown passes `:code-options` through
  the shared component contract; line numbers are opt-in and rendered by SSR too.
  Bruvbox palette/token ownership lives in `modules/highlight/theme.cljs`.
- Pretty data and expanded error details live in `:data-inspector`; keep pprint
  out of initial browser code. Rendered Markdown uses ReactMarkdown in its module; keep
  unused Markdown-clj imports out of the shell. Shared numeric display uses native fixed decimal
  formatting. Shared clocks/relative dates and calendar-month ranges use native Date
  and epoch milliseconds; do not reintroduce cljs-time/Closure locale tables for these
  small formatting cases. The optional Java-time Transit codec stays behind `:dev`;
  normal browser APIs use JSON or standard Transit, and backend codecs stay native.
  Carousel implementations, events, index subscription and CSS belong to `:carousel`;
  Home/CV/Strava declare that bundle dependency, and shared UI keeps lazy exports.
  Closed Settings contributes no DOM or CSS-variable reads; its view and styles
  belong to `:settings` and activate on open intent. Dynamic glyph consumers use
  `components.icon/<icon>`; unknown CMS names acquire the full `:icon-catalog`
  sheet, while known selectors and subset fonts remain in the small icon sheet.
  Include newly split SSR components in the eager Node worker spec map and verify
  their actual markup in fixtures before checking hydration identity.
  Contact email/toggle markup stays in the shell; the popup view/styles belong
  to `:contact` and mount with immediate activation only after the toggle opens.
  Landing story/float helpers belong to `:home`, with lazy compatibility
  exports in shared UI. Preserve prototype implementations when moving ownership.
- Saved local HTML/state pairs also restore reload and address-bar document
  navigation at the exact URL/build, once. With no usable pair, network SSR
  hydrates first; validated disk display choices restore afterwards, preserving
  interactions made since startup. Blog disk restoration and persistence tracking
  start together behind the shared after-page gate for network SSR; tracking
  server defaults earlier can overwrite saved display choices. Server content/query
  results retain priority.
  Paired shell markup suppresses default header/menu/footer entrances; explicit
  menu intent resumes ordinary motion. Temporary preview surfaces do not enter
  saved markup/state: owners declare retained return keys, such as visited URLs.
  Shared `:state` restoration is opt-in: shell/navigation/module schema owners
  declare retained keys. Header/footer visibility, scroll measurements, timers and
  boot flags are rebuilt; HTML and installed state use the same policy. Component,
  page and module scoped state retains its existing ownership contract.
  Preview departures use the common capture adapter, and BFCache closes transient
  surfaces; already visited URLs do not reopen automatic previews.
- The local return adapter renders an isolated state snapshot using the shared
  renderer. HTML and state are inseparable. Do not copy DOM or replay init before
  hydration; retain exact query caches and fold/window state.
- The `:font` feature owns a simultaneous text-only crossfade. Keep controls,
  inputs and stateful child components outside its duplicated text. Incoming
  text is decorative until the outgoing layer is declaratively removed; SSR,
  cached initial faces, reduced motion and local returns use one text layer.
- Code controls stay outside the highlighted/font text layers, with Markdown-owned
  icon CSS. Capped comment text uses non-scrolling clipping; an owned resize
  adapter measures code boundaries and React renders the corresponding fade.
- Browser-only APIs belong in lifecycle/effect adapters with cleanup and SSR guards.
  Ordinary Hiccup callback refs receive node/nil; a plain atom can retain a node
  without rerendering. `react/compose-refs` forwards internal/caller refs only where
  needed, including explicit React 19 returned cleanup. React owns attachment and
  detachment; do not add a second ref lifecycle manager.
  Keep `component/compat.cljs` as the sole version-specific Hiccup-key metadata
  adapter; React keys are not props and public Reagent class argv is insufficient
  for function-component metadata. Run keyed persistence tests on Reagent upgrades.
  `boot.cljs` installs document persistence/history listeners after app-db setup,
  then site listeners/preferences from the committed root lifecycle. Deferred
  resources, route prefetch and local-return capture share that lifecycle host.
  `listener.cljs` replaces named registrations and removes them by owner on stop;
  do not install browser listeners in `defonce` or other import-time forms.
  See `doc/boot.md` for the stage boundaries.
  Deferred Search owns focus while open and releases it on close/unmount; hidden
  input focus must not drive scrolling during later navigation.
  Its owned painted-frame callback replaces guessed loading/focus delays.
  Services owns background focus once per mount/section intent; child/media ref
  replacement must not repeat it. `<timeout>` retains its mount deadline while
  invoking the latest committed callback, and cancels on unmount.
- `browser_resources.cljs` acquires analytics
  after hydration, window load, two paint frames and idle with no pending page
  bindings. Its deferred host renders hidden markup until a cancellable timeout
  publishes local Reagent readiness, then renders its child Hiccup. Analytics
  requests `/api/browser-resources` through the existing managed URL dependency
  before rendering an async script. The SDK queue initializes at layout commit;
  keep its ID, bootstrap, remote scripts and preconnects out of SSR HTML. Browsers
  use native smooth scrolling without a compatibility script (Safari 15.4+).
  Older browsers retain instant scrolling as progressive enhancement.
  Lazy videos omit sources until visible; their observer owns cleanup, React owns markup.
  Use Shadow reader features for build-specific dependencies, not runtime imports.
- Development console and React profiling implementations live in `env/dev/cljs`.
  Inspector styles compile into `dev.min.css`, linked only by development documents.
  Only builds with the `:dev` reader feature import them; production and SSR use
  small diagnostics/instrumentation boundaries. Timing capture is bounded and inactive without a console, picker, or explicit page-capture owner. Keep diagnostics out
  of persistence snapshots and do not let diagnostic events trigger page renders.
- Page navigation uses a brief simultaneous opacity crossfade. Native View Transitions
  and the page-root fallback share duration/easing; neither delays the incoming fade.
  Native page snapshots must not cover the sticky footer: retain its own opaque,
  unanimated snapshot layer above the page. DOM z-index cannot outrank that overlay.
  Both request the highest frame rate only when the native Animation API exposes it;
  do not drive page opacity with a JavaScript loop or change browser settings.
- `defpage` enables the optional `:container` layout capability outside its error
  boundary. Merge caller and transition attrs there; loading/content/error share
  the same root; the site shell opts out with `:container false`. No swapper wrapper.
  Fallback roots overlap in one grid cell and both contribute height until cleanup.
- SPA scroll restoration waits for committed page code/data, relevant image/font
  layout and a reachable saved offset on consecutive measured frames. Track pending
  work through the page readiness context and wait for finite layout animations;
  controlled navigation/restoration scrolls must not trigger header hide/show feedback; do not use a guessed completion delay.
- Idle navigation prefetch is limited to adjacent (`rel=prev/next`) and explicit
  `data-preload=true` links. Other internal links acquire on pointer/keyboard
  intent, through the same loader and bindings; never prefetch the whole navbar.
  Start speculation after hydration, page readiness, load and painted idle frames.
  Only visible hints participate; skip hidden tabs, Data Saver and 2G connections.
  The `:home` bundle owns landing views and CSS; the router imports its portable
  pages only. `:styled-input` owns the optional custom field; Search depends on it,
  blog editing acquires it when opened. Markdown and styled-input share one
  independent monospace sheet; font bytes load only for used glyphs.
- After edits check watched Shadow errors and the actual routes in the browser.

- `:coercion` owns the Malli engine, portable `schema/registry.cljc` and
  `schema/malli_coercion.cljs`. Eager schemas are data, including `[:merge ...]`.
  `schema/page_coercion.cljc` compiles stable Reitit dispatchers; no router rebuild
  is needed when the adapter arrives. Only the matching initial SSR pair supplies
  typed parameters without Malli. Other navigation waits before controllers/data,
  and ignores stale acquisition completions. Boot schedules acquisition through
  the common after-page gate. Client-only/local returns and explicit production
  validation acquire it before restoring disk/rendering. Disk and CMS transport
  checks remain active; do not replace them with handwritten validators.
  Browser tests use the release registry; development/Node retain eager engines.
- `validation/runtime.cljs` owns declaration checks, module section registration
  and the app-db interceptor. Invalid transactions retain previous state and do
  not run associated effects. Declare event/subscription contracts through the
  existing shim's registration macros; `validation/bindings.cljs` owns argument
  coercion and result checking. Keep handwritten validation and diagnostic effects
  out of domain subscription computations.

- Use inline `defc`/`defpage` `[value :- schema]` for meaningful public inputs;
  a rest annotation validates each remaining value. Keep schemas beside their
  owning component or module; browser-only inputs do not require CLJC. Define
  schemas used only by one component near the top of that component file. Keep
  contracts consumed by both API and browser in CLJC and use them on both sides.
  Give persistent component state a `:state {:schema ...}` when it has
  a domain shape, and test normal updates plus rejected transactions.

- Bundled shell images reserve catalog dimensions. Footer icons declare sizes
  for their relative height and use small responsive candidates with lazy loading.
  Markup normalization preserves native image/iframe loading attributes while
  removing component loading specifications. Optional logo backgrounds stay unset
  for nil/blank sources; a relative empty URL can request the site document as an
  image and follow its module preload hints.
- Avatar fallbacks must also recover originals that failed before hydration attached
  handlers. Preserve modern-format retries and constrain broken-image alt text to
  the avatar box; a missing upload must not widen post/comment layout.

- Thin build adapters may use `.cljc` solely for Shadow reader features (`:browser`,
  `:ssr`, `:dev`); `.cljs` cannot contain reader conditionals. Keep these separate
  from shared JVM contracts and do not infer a server renderer from the extension.

- Icon faces use small application subsets with disjoint Unicode ranges and the
  original full fonts as on-demand fallbacks. Keep CMS-driven names in
  `resources/icon-fonts.json`; regenerate after adding literal icons. The deferred
  icon bundle and font URLs are fingerprinted by Optimus. See `doc/styles.md`.

- Link Preview acquires readable text/image data only when opened through the
  managed URL source. Render remote content as text in site styles; an optional
  sandboxed miniature starts after the readable card paints and only when frame
  policy permits the parent origin. Following a preview expands it before departure;
  visited URLs and transient-surface restoration keep their existing owner policy.

- Markdown marks generated code with a private pass-through HAST node type before
  parsing trusted raw HTML; HTML attributes cannot forge that provenance. Only
  generated pre wrappers defer to the code component; raw pre retains its own
  attributes and whitespace, without nested block wrappers.

- oEmbed provider HTML belongs only in an opaque-origin `srcDoc` iframe. Known
  HTTPS player endpoints may use a direct cross-origin iframe with provider-origin
  access; derive/check that URL through the shared oEmbed contract on both sides.
  Never give arbitrary markup same-origin access or insert it into application DOM.
  Acquisition uses the shared managed URL source.

- Development diagnostics observe existing component/query ownership through the
  shim and native trace/Profiler APIs. Keep render-body and inclusive React times
  separate, and label observed reasons rather than claiming complete React causes.
  UI selection, capture options and bounded records use re-frame; native window,
  measurement and WeakMap ownership adapters must release their lifecycle resources.
  Source/stack and actual-value details stay behind `:dev` reader boundaries.
  See `doc/dev-console.md` for capture, source links and diagnostic limitations.

- Missing scoped-state and installed-data subscriptions return `:present? false`
  with `:value nil`; lookup sentinels stay private. Preserve equality across
  unrelated db writes while distinguishing stored nil/false from absence.

- Startup diagnostic capture buffers observations until its console host mounts.
  Its passive connection effect publishes the buffer; a layout-effect dispatch
  can queue a Reagent update before React finishes mounting the consumer. The
  concurrent console host uses React external-store subscription ownership over
  the existing re-frame queries: acquire in commit, dispose on unmount. Ordinary
  application components keep their Reagent subscriptions.
  Do not publish debug records into subscriptions owned by an uncommitted host.

- Module specs expose `:storage storage/tables` from portable owner declarations.
  Keep table keys, projections and persistence metadata there, independent of
  browser implementation code. See `doc/supabase-tables.md`.

- Site capabilities are resolved from file defaults plus optional CMS overrides before build/provisioning.
  Module specs own storage/content/pages/server declarations; disabled owners stay out of artifacts.
  Read `doc/site-capabilities.md` for build snapshots, dependency validation and retained-data tests.

- Supabase transport and npm SDK belong to the ordinary `:supabase` module.
  Shared bindings/account actions acquire it through `supabase/runtime.cljs`;
  do not initialize it globally or add an SDK script to server HTML. Keep restored
  public state renderable while the module is loading.

- Automatically eligible large catalog images default to a decorative smallest
  preview and responsive final; the shared dimensions/bytes predicate excludes cheap originals; `:progressive? false` opts out. Native initial-image owners are
  keyed by exact SSR wrappers and stable React IDs, stored only on the document
  mount. They write mount CSS variables, never React children, and retain phase
  through terminal handoff. Later upgrades use keyed `defc :exit`/`:presence`.
  Preview blur clears from 4rem over 100ms while opacity overlaps for 150ms; cached,
  reduced-motion and unnoticeable (opening scale <=2/3) finals settle directly.
  External departure/history returns use final-only policy from shared restore
  context; early head classification suppresses lazy preview discovery before JS.
  Paired SSR emits final-only markup. SPA navigation, including query/history
  destinations, clears that image policy while retaining the existing document clock.
  Native decode/source completion and ref cleanup remain exact-node/currentSrc guarded.
  Shared `restore/document-form` preserves three React document slots in Node/local
  SSR and hydration so native image useId tokens match. `src/bootstrap/bootstrap/`
  owns generated native document scripts; no authored snippets live in public JS.

- Blog Markdown attachments use owner-scoped registered events and one serialized
  authenticated upload queue. Browser File values remain transient; a stable native
  media node retains the original selection while its presentation derives from
  the managed queue. Only a complete validated manifest adds its canonical source.
  Retry/cancel/disposal invalidate stale attempts; pending nodes block publication.
  SDK reads and realtime events restore URL-keyed metadata before row validation.

- `/blog/new-post` and its upload queue/UI belong to `:blog-editor`; the
  independent lazy `:markdown-editor` owns the visual Markdown SDK. Canonical source and
  media jobs remain in the existing form/event owner. Published authored tags use
  the same public `:markdown-components` views and data-only argument contract;
  never compile/evaluate MDX or expose private state paths. See `doc/markdown-authoring.md`.

- Public component composition uses canonical qualified Var identities and the
  existing registry/module loader. Legacy `<…>` render functions register through
  `register-render-functions!`; never wrap their ordinary call contracts. Rich
  docs remain editor-owned; layout geometry uses separate `layout-*` arguments.
- Shared `component/public_catalog.cljc` retains only canonical identities and
  lazy owners. `markdown/authoring.cljs` handles data encoding; optional rendering
  stays in `:markdown-components`. Canonical boundaries use the existing selective
  hydration loader and await coercion installation before committing. SSR records
  actually rendered exports with full identities preserved in JSON; their styles
  are available before hydration, including visibly embedded deferred owners.

- Blog authoring upload progress comes from the authenticated transport's measured
  byte callbacks and remains attempt/owner guarded in transient module state.
  Server-ready media stays visible in the status list until the published preview
  acknowledges visible decoded content; keyed presence owns row exit and cleanup.
- GitHub settings and Codex profile content use declared Strapi dependencies. Shared activity markup renders provider counts; absent profile days remain unavailable. See `doc/activity-panels.md`.
