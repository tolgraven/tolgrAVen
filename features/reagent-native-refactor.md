# Refactor toward native Reagent and idiomatic React

## Objective and authorization

Make frontend implementation as ClojureScript/Reagent-native as practical while
respecting React's lifecycle, purity and ownership rules. This is an implementation
brief for future agents, not a claim that the listed changes have been made.

The user explicitly selected these defaults:

- Our fully-featured `tolgraven.macros/defc` uses upstream `reagent.core/defc` as
  its foundation, including its composed feature path.
- Hooks exposed by `tolgraven.react` should delegate to `reagent.hooks` rather
  than raw React hooks or independently maintained approximations.
- Ordinary feature code should remain familiar ClojureScript: Hiccup, maps,
  vectors, small functions, reactive state, events and subscriptions.

Read the [explanatory guide](../doc/reagent-2-react-19-guide.md),
[authoring policy](../doc/reagent-re-frame.md), [component API](../doc/components.md),
[SSR contract](../doc/ssr.md) and the scoped AGENTS files before implementation.
Keep this brief current as tasks complete. Do not retain a completed audit diary
or old test totals in it; documentation describes behavior and remaining work.

## Preserve these contracts

1. Retain upstream `r/defc` underneath lean and composed definitions. Do not
   replace it with raw JS components, a global compiler switch or class wrappers
   for all components. Preserve docstrings, typed arguments, multiple lean
   arities, form-2 compatibility, prototype bodies and stable component identity.
2. Keep application calls through `tolgraven.react`, including subscription/event
   validation and development consumer tracing. Changing hook delegates must not
   bypass the shim's re-frame macros or add another React/Reagent runtime.
3. Preserve scoped state ownership and event-driven writes. Current `<sub` handles
   are native re-frame subscriptions; use `>reset`/`>update`. Do not bring back
   writable cursors, mirror app-db in hooks or add a second application store.
4. Keep React in charge of markup, attributes, loading/error states and motion.
   Preserve appearance on native roots, keyed exit retention, retry boundaries,
   font layers, lazy media and page readiness.
5. Preserve exact initial SSR/hydration state and DOM, local return snapshots,
   code/highlighter deferral, and parallel CSS/JS module loading. No loading flash,
   replayed entrance, extra eager dependency or fabricated empty data.
6. Preserve disabled experiments, unfinished views and API stubs. Their apparent
   non-use does not authorize deletion or a speculative rewrite.
7. Native data containers and handles remain appropriate where a specific browser,
   SDK or React API requires them. This is not a blanket ban on `#js`, `set!`,
   `.-current`, Promises, classes or native event listeners.

## Upstream hook contract

[src/frontend/tolgraven/react.cljs](../src/frontend/tolgraven/react.cljs) delegates
the public hook names directly to installed Reagent 2.0.1 functions:

| Public helper | Upstream delegate |
| --- | --- |
| `use-state` | `reagent.hooks/use-state` |
| `use-ref` | `reagent.hooks/use-ref` |
| `use-context` | `reagent.hooks/use-context` |
| `use-reducer` | `reagent.hooks/use-reducer` |
| `use-sync-external-store` | `reagent.hooks/use-sync-external-store` |
| `use-effect` | `reagent.hooks/use-effect` |
| `use-layout-effect` | `reagent.hooks/use-layout-effect` |
| `use-memo` | `reagent.hooks/use-memo` |
| `use-callback` | `reagent.hooks/use-callback` |

`use-memo`/`use-callback` require an explicit dependency collection, following
upstream's two-argument contract. Do not emulate an undocumented upstream arity.
Inspect installed definitions when extending the hook surface. `use-imperative-handle`
has different dependency adaptation details; do not claim every hook has the
same Clojure-aware behavior.

Do not reintroduce custom `hook-dependencies` or `effect-setup` implementations.
Retain React operations that are not wrapped hooks: context creation/provider,
lazy, Suspense, Profiler, StrictMode, transitions, portal/root operations and the
small class boundary. Upstream does not replace those with hook functions.

### Equality and cleanup semantics

- Dependency equality uses upstream Clojure equality over the dependency
  collection. Equal maps/vectors should
  retain memo/callback/effect ownership; changed data should restart it.
- State/reducer updates producing an equal Clojure value should retain the old
  identity. Do not depend on setting an equal fresh map to force rendering.
- Upstream effects suppress every non-function result.
  Ensure setup functions return cleanup or no meaningful value. Do not return
  Promises or accidentally return a callable as cleanup.
- Native arrays are unsuitable as a way to express Clojure value dependencies
  through these wrappers: fresh arrays compare by identity as values. Convert
  hook dependency containers to ordinary Clojure vectors. Functions and native
  handles inside those vectors retain their intended identity semantics.

`test/cljs/tolgraven/react_hooks_test.cljs` exercises this contract. Separate
probes for dependency equality from
enabled/phase changes: changing `enabled?` at the same time as an equal map would
otherwise hide the semantic distinction. Cover changed maps, equal maps/vectors,
native handle/function identity changes, state/reducer equal updates, effect
cleanup and omitted-versus-empty dependencies. Verify the wrapper's supported
arity and cleanup behavior with mounted components.

## Caller inventory

These are all files with hook calls/declarations found under `src`, `env` and
`test` on this brief's inspected base. Every row requires migration review;
already-Clojure dependency vectors may require no textual edit. Rows with only
ref/context/state calls are still affected by changing the central delegates.
Use the symbols listed here as anchors; line numbers drift during implementation.

For each file, replace hook dependency `#js` arrays with vectors, remove redundant
effect `js/undefined`/`or ... js/undefined` plumbing, and review equality, cleanup
and captured inputs. **Do not remove native containers or undefined returns used
by other APIs.** In particular, callback refs and native callback contracts are
not effect setups.

### Application runtime

All paths in this table are relative to `src/frontend/tolgraven/`.

| File | Code to inspect and required focus |
| --- | --- |
| `react.cljs` | All hook exports, `hook-dependencies`, `effect-setup`; implement upstream delegation and document changed equality/arity |
| `component.cljs` | `use-lifecycle!`, `render-function`, `<container>`, `<data-body>`; migrate effect deps; review feature hook order and latest-args refs |
| `component/motion.cljs` | `use-motion`, `use-presence`; callback/effect arrays, reducer and ref handles; preserve visibility, reduced motion, root replacement and exit cancellation |
| `component/restore.cljs` | `use-readiness!`; dependency vector and no-cleanup branches; retain commit-only readiness ownership |
| `component/hydration.cljs` | `use-deferred!`; empty deps and completion callback; keep release ownership and review global batching separately |
| `component/font.cljs` | `use-font`; existing vector deps and primitive-field stabilization; reassess redundant map-stabilization only after equality migration |
| `components/page.cljs` | `use-page-crossfade`, `<page>`; token/vector state, effects and context provider envelopes; preserve positioning and simultaneous fades |
| `components/timer.cljs` | `<timeout>`, `use-delayed-visible?`, `use-delayed-hide`; empty/changed deps, stale callbacks, cancellable timer cleanup |
| `components/image.cljs` | `<picture>` capture callback; native ref return handling, equal attrs and src updates; preserve pre-hydration image failure recovery |
| `components/video.cljs` | `use-source-attrs`; callback/effect arrays, observer cleanup and source gating; keep native observer options |
| `components/popover.cljs` | `<popover>` outer initializer/inner effect; arrays, ResizeObserver allocation, changing open/configuration and portal teardown |
| `components/shell.cljs` | `<header>`, `<footer-full>`; initial menu/restoration snapshots, refs/state and effect arrays; do not synchronize duplicate menu state |
| `components/ui.cljs` | `<log>` layout effect; preserve intentional every-commit timing only if needed; inspect legacy ref-triggered highlighter work |
| `components/ui/code.cljs` | `<markdown-code-component>`, `<parse-markdown-components>`; context reads and `use-memo` deps for adapters; preserve Markdown/raw HTML trust and representation |
| `components/code_block.cljs` | `<formatted>`, `<formatter>`, `use-copy-feedback`, `use-copy-gesture`, `<code-block>`; lazy gate, commit callbacks, memo/state and gestures; preserve controls outside deferred formatting |
| `components/oembed.cljs` | `<oembed-view>` hover state; assess whether local ratom is sufficient without changing loading/cache ownership |
| `loader.cljs` | `<trigger>`, `<module-content>` and `<module-boundary>`; activation effects, arrays, memoized boundary and hydration completion; preserve current options/args and shared loading |
| `core.cljs` | `<disposed-shell>` layout effect; arrays and implicit cleanup; preserve root/overlay disposal sequencing |
| `boot.cljs` | `<lifecycle>` effects; startup and page-preload ownership, setup/cleanup and empty deps |
| `browser_resources.cljs` | `<deferred>` effect; eliminate no-cleanup ceremony while keeping any actual cleanup; preserve readiness/load/paint/idle ordering |
| `navigation/preload.cljs` | `<background>` effect; `start!` cleanup and empty deps; preserve intent/adjacent-only prefetch |
| `navigation/transition.cljs` | `use-ready!`; compare commit as Clojure data, preserve handled token and commit-only route completion |
| `ssr/client.cljs` | `<hydrate>` layout effect; microtask cancellation and empty deps; no synchronous Reagent flush from a React lifecycle |
| `ssr/local.cljs` | `<capture>` effect; subscription-owned capture and listeners, empty deps; preserve worker/state-document pairing |
| `modules/blog/views.cljs` | `<comment-post>` and shared timer hook consumers; ResizeObserver deps, current comment text, truncation/expansion and delayed visibility |
| `modules/home/sections.cljs` | `<service-categories>` effect and focus ref; arrays and focus ownership; avoid duplicate focus paths |
| `modules/link_preview/views.cljs` | `<observed-link-container>` and `<link-preview>`; form-2 captures, mount-only observation and every-commit preview actions; preserve trust, external return and cleanup |
| `modules/search/views.cljs` | `<box>` focus effect; open dependency and timer/ref ownership; closed input must release focus |
| `modules/settings/views.cljs` | `<settings>` CSS-variable read effect; empty deps and explicit event ownership |
| `modules/user/views.cljs` | `<user-avatar>` effect/ref; avatar/src dependency arrays, cancelled animation frame and pre-hydration failure recovery |
| `modules/highlight/views.cljs` | `<code-block>` tree/generator memoization; existing vector deps, equal language-option collections, stable formatter identities |

### Development tooling

All paths in this table are relative to `env/dev/cljs/tolgraven/`.

| File | Code to inspect and required focus |
| --- | --- |
| `component/dev_instrumentation.cljs` | `<probe>`, `instrument`; state/ref/context/effects, latest resolver, serialized deps and native identity envelope; preserve bounded inactive diagnostics |
| `dev_console/capture.cljs` | `use-host-state`; refs/callbacks and `use-sync-external-store`; preserve subscription-owned snapshot caching and disposal |
| `dev_console/views.cljs` | Every hook-bearing inspector/picker/console component; rect state, layout measurement, popup ownership, capture connect/start and keyboard cleanup |
| `dev/source.cljs` | `<listing>` reveal ref; keep scroll/reveal work commit-owned and avoid JS-heavy domain source data |
| `dev/stack.cljs` | `<stack>` effect; source-map acquisition through declared managed resources, cleanup and changed stack input |

### Direct hook test callers

All paths in this table are relative to `test/cljs/tolgraven/`.

| File | Code to update or verify |
| --- | --- |
| `react_hooks_test.cljs` | Replace native-map-identity expectations with deliberate upstream equality/cleanup coverage described above |
| `blog_ssr_test.cljs` | Commit probe effect: empty array and undefined ceremony; keep real hydration identity assertions |
| `page_return_test.cljs` | Commit probe effect: empty array and undefined ceremony; preserve restored state/content checks |
| `suspense_deferred_test.cljs` | Hook-bearing hydration/deferred probes; preserve boundary completion/cancellation and scheduling assertions |
| `viewport_module_test.cljs` | Hook-bearing activation/commit probes; preserve trigger ownership and no premature acquisition |

Re-run this bounded search before implementation and after each slice. Inspect
every match, including direct React imports and tests; do not use a textual
replacement across all `#js` literals:

```sh
rg -n 'use(State|Ref|Context|Effect|LayoutEffect|Callback|Reducer|Memo|SyncExternalStore)|use-(state|ref|context|effect|layout-effect|callback|reducer|memo|sync-external-store)' src env test
rg -n '#js|js/undefined|component-argv|reagent\.impl|cljs\.core/Subvec' src/frontend src/cljc env/dev/cljs test/cljs
```

## Separate design work after wrapper conversion

These are concrete review targets, not permission to declare unverified bugs or
rewrite the runtime wholesale. Establish the behavior, make the smallest coherent
change, and explain any retained exception next to its owner.

### 1 Stabilize hook order and instance initialization

Review `src/cljc/tolgraven/macros.cljc` (`defc`, generated `:let`/form-2 paths) and
`component.cljs` (`render-function`, `render-component`, `use-lifecycle!`). Conditional
hook groups depend on feature selection, registered implementations and inline
versus wrapped paths. Prove the groups stay stable for a mounted identity, including
hot reload, caller options, font capability changes and feature registration.

If capability changes alter hook order, keep the owning hook sequence stable or
explicitly remount the appropriate boundary. Preserve the macro's lean path;
do not add all hooks to every leaf merely to hide uncertainty. Never put hooks in
one-time `:let` bindings or a form-2 outer initializer.

Review observer/resource allocation in `components/popover.cljs` and
`modules/link_preview/views.cljs`. Move external resource acquisition into owned
commit effects where needed. Form-2 closures must either intentionally capture
mount-only values or receive changing inputs with correct teardown/reconfiguration.

### 2 Make data acquisition and state initialization ownership explicit

Review `component.cljs/<data-body>` render-time `data/prefetch!`,
`component/data.cljs`, `component/sources.cljs`, `component/persistent_state.cljs`
initialization, and `loader.cljs`/`loader/activation.cljs` request ownership.
Deduplication limits duplication but does not itself establish render purity.

Prefer existing declarative dependencies, preload owners, event/effect paths and
managed subscription acquisition. Separate a subscription's resource lifecycle
from its pure derived computation. Preserve acquisition before/alongside code
loading and adoption of SSR/cache values; moving everything to a post-mount
effect would regress readiness and introduce waterfalls. Specify abandoned-render
behavior and test it before choosing a replacement.

Review diagnostics in `component/instrumentation.cljc`,
`env/dev/cljs/tolgraven/component/dev_instrumentation.cljs` and
`src/cljc/tolgraven/diagnostics/consumer.cljc`: render attempts may be observed,
but must not publish committed ownership or perform application IO prematurely.

### 3 Reduce dependence on Reagent implementation representation

Review `react.cljs/component-argv`, `component.cljs/current-argv`, generated calls
in `macros.cljc`, `component/instrumentation.cljc` and development `instrument`.
They recover keys/arguments through current-component fields and the backing
`cljs.core/Subvec.-v`. Investigate explicit argument/key propagation or supported
Reagent APIs without changing the public `[component ...]` syntax.

If no supported equivalent preserves automatic Hiccup-key identity, retain one
small compatibility adapter with a version-specific contract and tests. Do not
inspect React element internals or distribute the workaround across views.
Preserve state-key precedence, metadata and feature wrapper propagation.

### 4 Review selective hydration scheduling as its own integration

Review `component/hydration.cljs` (`hold!`, `release-all!`, `use-deferred!`),
`loader.cljs`, `components/code_block.cljs`, `ssr/client.cljs` and
`navigation/transition.cljs`. The temporary global `reagent.impl.batching/react-flush`
override is a compatibility boundary, not a public Reagent hook pattern.

Test multiple overlapping deferred boundaries, failure/retry, rapid navigation,
unmount, Strict Mode, abandoned work and restoration of the previous flush adapter.
Determine whether supported React/Reagent APIs can preserve selective hydration
without that global override. Do not remove it before reproducing and preserving
the behavior it protects. Retain cold SSR DOM and prevent diagnostic ancestor
updates from replacing unhydrated code content.

### 5 Keep refs narrow and remove redundant state and memoization

Review `component.cljs` latest-args/instance/capture refs, motion presence refs,
`components/page.cljs` route tokens, `component/font.cljs` options stabilization,
shell restoration snapshots, oEmbed hover state and code-block gesture refs.
Move domain data to existing subscriptions and derive values when practical.
Keep local gesture/observer/timer handles local; a ref can be the simplest owner.

After upstream equality is established, replace serialized hook dependency
workarounds such as the probe's `pr-str` collection with actual Clojure dependency
data where equivalent. Reassess manual primitive-field stabilization rather than
keeping two equality mechanisms. Preserve cached native snapshot identity for
`use-sync-external-store`: returning a newly allocated equal map on every snapshot
read is not a valid substitute.

Audit memo/callback hooks for a concrete identity consumer or measured expensive
computation. Upstream `defc` already memoizes component arguments. Retain stable
React.lazy/ReactMarkdown/native adapter identities and lazy module ownership.
Do not make memo caches the sole owner of resources that require cleanup.

### 6 Clarify native data and context boundaries

Review raw provider envelopes in `component.cljs/<container>` and
`components/page.cljs`, the provider in `component/loading.cljs`, native diagnostic
identity in development `<probe>`, and adapters in `components/ui/code.cljs`,
`components/code_block.cljs`, `modules/highlight/views.cljs`,
`components/portal.cljs` and `components/highlight.cljs`.

Use Clojure maps for configuration and domain values. Keep raw provider envelopes
when needed to preserve an opaque Clojure `value`; do not blindly exchange `:r>`
and `:>`. Keep native lazy-module `default` objects and React element conversion at
the receiving API. Do not recursively `js->clj` Reagent props, SDK instances,
React children/elements or DOM nodes.

Transport conversions in `content/client.cljs`, `supabase/client.cljs`,
`ssr/client.cljs`, `ssr/local.cljs`, `ssr/local_render.cljs`, `ssr/worker.cljs` and
`ssr/return_worker.cljs` remain appropriate adapter work. Review consistency only
where payloads escape unnormalized into views; do not churn correct conversions.

### 7 Enforce DOM ownership and deliberate timing

Review ref callbacks in image/video/container/motion composition for React 19 ref
cleanup and accidental function returns. Preserve forwarded ref teardown.
Review `components/ui.cljs/<log>` highlighter ref dispatch, home service focus,
search focus timing, `util.cljs` class/attribute/theme helpers and their callers.
Read actual call sites before deciding a mutation touches React-owned markup.

State-driven classes/styles/text belong in Hiccup. Measurements, focus, scrolling,
browser document settings and SDK-owned canvases remain adapter operations.
Do not rewrite preserved experiments or third-party SDK DOM simply because their
internals are imperative. Remove duplicate focus/scheduling paths only with a
reproduced reason and behavioral verification.

Review layout effects across the inventory: keep pre-paint readiness/positioning
where required, use ordinary effects for nonvisual external synchronization, and
avoid a new arbitrary timeout as a substitute for a commit or readiness signal.

## Verification and delivery order

Work in coherent slices. First migrate the hook boundary and every affected
caller/test contract in one consistent change. Commit it after focused checks.
Then take the design items separately, committing verified changes and recording
remaining exceptions in owning docs. Do not mix speculative architecture work
with a mechanical dependency-container cleanup.

For runtime changes:

- Inspect active Shadow workers before compiling. Use existing workers or isolated
  output; never overwrite watched `:app-dev`/`:ssr`/`:return-worker` assets.
- Run the relevant browser tests using `bb test:browser`; consult
  `doc/testing.md` for selection and mounted async workflows. Regenerate current
  Node-rendered hydration fixtures with `bb ssr:fixtures` before hydration tests.
- Include `react_hooks_test`, component/motion/font/persistence tests,
  loader/viewport/deferred-Suspense/code-block tests and the affected feature tests.
  Test names/files already exist; inspect their current assertions and fixtures.
- Add focused tests for meaningful uncovered behavior: equal versus changed
  dependency values, cleanup/reconfiguration, repeated Strict Mode setup, stable
  hook order, abandoned rendering and nested hydration ownership.
- Run affected browser, SSR and worker targets as appropriate; inspect compiler
  warnings and browser console errors. Run backend tests only for changed shared
  contracts/macros/backend behavior that needs them.
- Exercise actual cold direct loads, hydration, home/blog/permalink/CV navigation
  both ways, SPA Back and external return. Check comment expansion, Search focus,
  preview close/return, lazy video, avatar recovery, deferred code copy/fold/wrap,
  font changes, and inactive/open diagnostics according to the changed slice.
- Check DOM continuity, missing content, spinners, entrance replay, header/footer
  movement, layout jumps, duplicate listeners/loads and error/retry states. Preserve
  simultaneous 250ms linear page fades. Fixture tests do not prove live behavior.

Update `doc/reagent-re-frame.md`, `doc/components.md`, this brief and the relevant
root/frontend/shared/test AGENTS guidance in the implementation that changes
their contracts. Explicitly document upstream value equality, supported hook
arities, effect returns and remaining native/implementation exceptions. Scan
style changes for prohibited pixel units and preserve feature CSS ownership.

No dependency upgrade is needed just to use `reagent.hooks` in installed 2.0.1.
Reuse the prefab for ordinary source changes. If dependencies/toolchain change
for a separately justified reason, follow the documented Docker prefab workflow.

Completion requires the upstream delegates, migrated callers, revised behavioral
tests, real affected flows and updated contracts. A clean compiler or a search
with fewer `#js` literals is not sufficient evidence.
