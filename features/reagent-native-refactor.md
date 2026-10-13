# Native Reagent implementation and maintenance

Use this alongside the [Reagent 2 / React 19 guide](../doc/reagent-2-react-19-guide.md),
[authoring policy](../doc/reagent-re-frame.md), [component API](../doc/components.md),
[SSR contract](../doc/ssr.md) and scoped AGENTS instructions. The hook and ownership
migration described by the original brief is implemented. This file records the
current code owners, contracts and justified exceptions for future changes.

## Defaults

- Our full-featured `website.macros/defc` builds on upstream `reagent.core/defc`,
  including the composed feature path. Keep docstrings, typed arguments, lean
  arities, form-2 support, prototype bodies and stable component identity.
- Hooks in `website.react` delegate directly to installed `reagent.hooks`.
  Keep the shim's re-frame validation and development consumer tracing.
- Feature code uses Hiccup, Clojure maps/vectors, small functions, subscriptions
  and registered events/effects. Use local ratoms for transient view state and
  scoped native re-frame subscriptions for state that survives remounts.
- React owns markup, attributes and transitions. Existing appearance, visibility,
  presence, loading and readiness declarations own their lifecycles.
- Preserve unfinished views, disabled experiments, examples and stubs. This
  refactor is not authorization to remove them.

## Hook boundary and caller inventory

`src/frontend/website/react.cljs` delegates `use-state`, `use-ref`, `use-context`,
`use-reducer`, `use-sync-external-store`, `use-effect`, `use-layout-effect`,
`use-memo` and `use-callback` to Reagent 2.0.1. Inspect installed upstream definitions
when extending the hook surface; hooks do not all have identical adaptation rules.

The facade's hydration root preserves Reagent 2.0.1's compiler, two root wrapper
slots, batching setup and initial after-render flush. Its narrow native adapter
forwards recovery callbacks and identifier prefixes that the installed upstream
function accepts but omits from React's `hydrateRoot` call. Keep mismatch checks
on that public facade so production recovery and tests exercise the same path.

Use ordinary dependency vectors. Upstream compares Clojure values, retaining
memo/callback/effect ownership for equal collections. Functions and native handles
inside those vectors remain identity-sensitive. Equal state/reducer updates retain
the previous value identity. Do not serialize dependency maps with `pr-str` or
stabilize them with primitive-field memoization just to approximate value equality.

Effects return cleanup or nil. Upstream suppresses other non-function results,
but an accidental returned function is still interpreted as cleanup. Memo/callback
helpers require explicit dependencies. Retain actual API-specific undefined returns
and native containers at other boundaries.

Review these source owners when changing the shim; paths are relative to
`src/frontend/website/` unless another root is given:

| Owners | Contract to preserve |
| --- | --- |
| `component.cljs`, `component/motion.cljs`, `component/restore.cljs`, `component/font.cljs` | Stable hook order, current committed feature configuration, root identity and readiness |
| `component/hydration.cljs`, `loader.cljs`, `core.cljs`, `boot.cljs` | Selective hydration, module activation, cancellation and document disposal |
| `components/page.cljc`, `components/shell.cljs` | Native context values, restored shell state and simultaneous 250ms linear fades |
| `components/timer.cljs`, `components/oembed.cljs`, `components/popover.cljs` | Mount deadlines, latest committed callbacks, local hover state and open-surface measurement |
| `components/image.cljs`, `components/video.cljs`, `modules/user/views.cljc` | React 19 ref cleanup, native observers and pre-hydration image recovery |
| `components/ui/*`, `modules/markdown/views.cljs`, `modules/highlight/{code_block,views}.cljs` | Declared Markdown mapping, deferred formatting, stable native adapters, copy/fold/wrap controls and pure markup |
| `browser_resources.cljs`, `navigation/preload.cljs`, `navigation/transition.cljs`, `ssr/client.cljs`, `ssr/local.cljs` | Lifecycle-owned scheduling, restoration and cleanup |
| `modules/blog/views.cljs`, `modules/home/sections.cljs`, `modules/link_preview/views.cljs`, `modules/search/views.cljs`, `modules/settings/views.cljs` | Current inputs, one focus owner, deferred acquisition and declarative interactions |
| `env/dev/cljs/website/component/dev_instrumentation.cljs` | Commit-owned resolver publication, Clojure dependency/context values and bounded diagnostics |
| `env/dev/cljs/website/dev_console/{capture,views}.cljs`, `env/dev/cljs/website/dev/{source,stack}.cljs` | Cached external-store snapshot identity, cancellation, measurement and source acquisition |

Relevant direct test callers include `react_hooks_test.cljs`, `blog_ssr_test.cljs`,
`page_return_test.cljs`, `suspense_deferred_test.cljs` and `viewport_module_test.cljs`
under `test/cljs/website/`. Do not make tests use raw dependency arrays when
application callers use Reagent's value-oriented contract.

## Ownership changes to retain

| Code | Current implementation |
| --- | --- |
| `component.cljs/use-lifecycle!`, `render-function` | Each committed effect captures its render's callbacks and shape; speculative renders cannot overwrite pending shared slots. Capability owners reconcile without changing hook order or remounting the body. |
| `component.cljs/<data-body>`, `component/data.cljs` | `:component-data/status` acquires the existing queue through a managed source factory. Its reaction computes readiness without IO. Preloads use that same queue, preserving CSS/code/data parallelism. |
| `component/persistent_state.cljs` | `<sub` returns the native scoped value subscription. Persistent acquisition dispatches an idempotent load event, sharing the effect used by eager initialization. Derived reads stay pure; declared defaults initialize after commit. No dummy owner reaction, writable cursor or duplicate store. |
| `components/popover.cljs` | Closed or abandoned renders allocate no observer/listener. Open committed surfaces acquire fresh measurement owners; changing configuration, close and unmount release them. |
| `modules/link_preview/views.cljs` | Committed candidate/config changes replace observer ownership. Pointer/focus/click interactions use Hiccup attributes and resolve current candidates at event time, preserving caller handlers. Window events use the shared listener registry. |
| `react.cljs/compose-refs`, root/media consumers | Ordinary Hiccup refs use node/nil callbacks, including plain atoms. Composition only forwards internal/caller refs and explicit React 19 cleanup; React owns attachment/detachment. |
| `env/dev/cljs/website/component/dev_instrumentation.cljs` | Latest diagnostic resolver publication happens in a layout commit, with Clojure values rather than serialized dependency workarounds. |
| `component/hydration.cljs` | Cached child completion before parent setup acquires no batching owner. Overlapping boundaries retain ownership until the last current release; stale document callbacks cannot release newer ownership. |
| `components/oembed.cljs`, `components/page.cljc` | Transient hover uses a local ratom; internal transition tokens stay vectors, with serialization only for actual React keys. |
| `components/timer.cljs` | `<timeout>` keeps its mount deadline and invokes the latest committed callback. Canceled/completed hide timers release their handles. |
| `modules/home/sections.cljs`, `modules/search/views.cljs` | Services has one focus effect. Search uses the existing painted-frame adapter, cancels pending focus and blurs the hidden input on close. |
| `components/ui/diagnostics.cljs`, `modules/github/views.cljs` | Render bodies and refs no longer trigger the legacy DOM highlighter. The deferred formatter owns highlighting; preserved prototypes/helpers remain available. Missing HUD content renders nil without dispatching from render. |
| `browser_resources.cljs`, `boot.cljs` | A timeout changes local Reagent readiness from a hidden placeholder to supplied Hiccup. Analytics settings acquire through the existing URL source after that delay; scripts render declaratively, with only SDK queue setup in the native adapter. Server config owns the ID. |

Managed acquisition before commit is intentional: re-frame owns source subscriptions
and their disposal; derived computations remain pure. Do not push every dependency
into a post-mount effect and introduce loading waterfalls. Keep abandoned-render,
shared-source adoption, restoration and disposal tests when altering this boundary.

## Browser listeners and worker data

Application events use `:listener/register` and `:listener/stop` effects from
`listener.cljs`. Browser/component commit lifecycles use `listener/listen!` with
Clojure binding maps and retain its cleanup. This extends the same registry rather
than introducing another listener store. Each acquisition has independent ids;
replacement-safe teardown and capture/passive/once options preserve ownership and
timing. Partial acquisition failure releases already-installed bindings.

Owners include popovers, preview measurement/window controls, module activation,
idle preload intent, navigation restoration, browser readiness, local-return
capture and development picker/selection/popout/keyboard controls. Ordinary UI
interactions still belong in Hiccup event attributes. Do not dispatch an asynchronous
registration event when early document capture or pre-paint setup is required.

`ssr/return_policy.cljs` holds pure request eligibility, freshness/arm expiry,
document retention and obsolete-build decisions over maps/sequences.
`ssr/return_worker.cljs` holds Cache/Fetch, native Request/Response, message-port and
worker-event adapters. Incoming JSON becomes Clojure data once; outgoing options
convert once. Cache entry arrays become sequences of opaque native handles.
Preserve the native serialized Promise write queue and synchronous
`waitUntil`/`respondWith` calls: those determine the browser event's lifetime.

## Narrow exceptions requiring deliberate review

| Boundary | Why it remains | Review when |
| --- | --- | --- |
| `component/compat.cljs` | Reagent 2.0.1 function-component Hiccup-key metadata has no supported accessor preserving the public `[component ...]` contract. Backing `Subvec` representation access is isolated here. React keys are not props. | Reagent changes its representation or adds a public accessor; keep keyed scoped-state tests. |
| `component/hydration.cljs` batching integration | Installed root APIs expose no public per-boundary flush option. The temporary global adapter preserves deferred SSR DOM against synchronous Reagent updates. | Upstream offers a supported alternative; test overlapping owners, cached completion, StrictMode, cancellation and stale callbacks. |
| Raw context provider envelopes | `:r>` preserves opaque Clojure values; ordinary Reagent native-prop conversion may recursively change them. | Changing provider/adapters; verify the receiving API's representation rather than blindly replacing `:r>` with `:>`. |
| Native lazy/Markdown/highlighter components | These packages require stable React component identities and actual React elements/module export objects. | Adapter identity, chunk ownership or formatter behavior changes. |
| Small class error boundary | React still exposes that boundary lifecycle through class components. | Retry/error handling changes; event/transport failures still belong to effects. |
| Motion media-query listener | This low-level feature participates in validation schemas; importing the validated application listener registry creates a dependency cycle. | Dependency layering changes; keep lifecycle-owned native cleanup. |
| Stylesheet load/error and worker activation listeners | Native resource handles have an asynchronous readiness lifetime, separate from rendered application interaction. | Resource ownership/retry changes; cleanup must run on success and failure. |
| Service-worker listeners | Worker events run outside the application's re-frame runtime and require native lifetime methods. | Worker protocol/cache policy changes; verify actual service-worker navigation as well as policy tests. |

Browser clocks, URL parsing, measurement, focus, scrolling, observers, SDK handles,
React refs and Promises are legitimate host boundaries. Around them, prefer ordinary
Clojure transformations and existing event/effect mechanisms. An external-store
snapshot must retain cached identity; a fresh equal map on every read violates the
React snapshot contract. Memoization remains appropriate for an actual identity
consumer or expensive calculation, not as the sole owner of a disposable resource.

## Verification for future changes

- Inspect active Shadow workers and use their existing runtime or isolated output.
  Never overwrite watched browser, SSR or return-worker assets.
- Regenerate current Node hydration fixtures with `bb ssr:fixtures`; run the
  affected mounted browser tests using `bb test:browser` and inspect final results.
- Include hook equality/cleanup, feature/motion/font/scoped-state, ref ownership,
  component lifecycle, deferred hydration, viewport/module and return-policy tests.
  Exercise discarded renders, StrictMode replay, changed configuration and errors.
- Compile affected SSR/browser/worker targets. Use `bb audit <label>` for isolated
  production bundles and dependency ownership; reuse the ordinary Docker prefab.
- Exercise actual cold and cached direct loads, home/blog/permalink/CV SPA navigation
  both ways, SPA Back and external/local return. Check Search focus, previews,
  expanded comments, avatar recovery, lazy media, deferred copy/fold/wrap controls,
  fonts and inactive/open diagnostics according to scope.
- Check DOM continuity, content, spinners, entrance replay, header/footer movement,
  layout jumps, duplicate loads/listeners and recovery. Fixture tests and successful
  compilation do not prove live route or visual behavior.
- Keep owning docs and AGENTS instructions current. Preserve module CSS ownership,
  relative styling units, no eager optional dependencies and original visuals.

Bounded audit searches (inspect each match; never replace all native literals):

```sh
rg -n 'use(State|Ref|Context|Effect|LayoutEffect|Callback|Reducer|Memo|SyncExternalStore)|use-(state|ref|context|effect|layout-effect|callback|reducer|memo|sync-external-store)' src env test
rg -n '#js|js/undefined|component-argv|reagent\.impl|cljs\.core/Subvec|addEventListener|on-window|on-event' src/frontend src/cljc env/dev/cljs
```
