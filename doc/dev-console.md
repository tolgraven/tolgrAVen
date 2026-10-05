# Development console

In `:app-dev`, click **Dev** or press **Alt+Shift+D**. The console mounts after
hydration, so it does not change the first client tree. Release builds do not
mount it or run capture adapters. Application views use the existing Reagent,
re-frame shim, and scoped state helpers throughout.

## Inspecting the app

- **page**: active Reitit match, its composed page spec, and persistent page state.
- **components**: mounted `defc` instances, namespace-qualified identities, React
  keys, and their resolved app-db paths. “Inspect state path” opens the state editor.
- **modules**: composed routes, loaded module declarations and module state.
- **state**: expandable, typed maps/vectors/lists/sets and scalar values. Enter an
  EDN path to subscribe to it, or set its value through the normal state event.
- **subs**: enter a real subscription query. Its consumer mounts and dereferences
  the normal subscription, and releases it when this tab closes. Managed data
  subscriptions can therefore acquire their normal sources. Live queries are also listed.
- **loads**: shared dependency resource owners/status, loading state, and content stores.
- **events**: dispatch an EDN event and inspect its settled synchronous cascade,
  effects, timing and bounded before/after diffs. The optional override switch
  stubs the named common effects only; it is not a sandbox for arbitrary events.
- **errors**: existing diagnostics, preserving the application's HUD/fallback flow.
- **timings**: select an event window, inspect nested re-frame traces and React
  commit bars, and click a bar for its details. Durations include child work;
  this is an instrumented event/commit timeline, not a CPU sampling profiler.
- **layout**: component commit records and browser layout-shift sources/rectangles.
  Layout-shift observation depends on browser support; user-input shifts are
  retained too, because they matter when diagnosing comment expansion jumps.
- **code**: component declarations, feature/dependency options, source locations
  and instrumented handler metadata. Unloaded module code appears after loading.

The tree opens its first two levels, then expands on demand. Collapsed collections
show inline keys and short values, including shallow previews of child collections.
Map keys share a column sized to the longest key (capped at 36 characters), wrap
when needed, and expose the full key on hover. Vector indexes stay on one line.
Maps use curved right braces; vectors use full-width square brackets, lists use
parentheses, and sets use dashed braces. Delimiters and scalar syntax use the
accent palette from `resources/scss/vars.scss`; surfaces stay cool blue-purple.
Collections show 40 items at a time. **Clear** clears history without losing mounted-instance tracking.
**Record** pauses trace, epoch and layout observers and commit recording, while
mounted-instance tracking stays current. Console events and their trace descendants
are excluded, and the console's root host is not profiled, preventing capture loops. Dependency readiness reads use scoped subscription
results rather than making the rendering page depend on the entire app-db;
storing debug history therefore does not trigger more page renders.

Records drain in batches every 200 ms. History defaults to 500 entries, capped at
2000; queued records and retained payloads are also bounded. Whole app-db snapshots,
reactions and SDK objects are not retained in trace/settled-event records. Diffs are
previews, so use the state inspector for complete current content. Debug history
is not automatically persisted or uploaded.

## Component identity and registry

Components are registered by their actual function identity in a WeakMap. The
inspection catalog keys declarations by `[namespace component-name]`, replacing
that entry on hot reload. Identical names in different namespaces are supported;
component state already includes namespace, name and optional instance key.

Registration lets preloading acquire a component's declared dependencies before
it mounts, and lets the composable runtime resolve features/state/loading without
rendering it. It does not eagerly mount or fetch every registered component.
The `<loading>` macro helper only supplies a missing local helper: an existing
namespace definition, referred/renamed var or argument binding takes precedence.

Use `{:profile false}` in a `defc` declaration to omit its development probe. This
is used for the root that hosts the console itself. Probes use React Profiler and
add no DOM wrappers; native roots get `data-dev-component` for layout attribution.
Attribution attributes are added only after hydration; server and first-client
markup stay unchanged. Server rendering never runs observers.

## Hydration indicator

`[:options :dev-console :hydration-highlight?]` defaults to true under
`goog.DEBUG`, false in release. After actual hydration, a 150 ms brightness overlay
appears over `#main`; it changes no dimensions and ignores pointer input. It is
suppressed for reduced motion. Ordinary navigation does not retrigger it.

```clojure
[:dev-console/option :hydration-highlight? false]
[:dev-console/option :recording? false]
[:dev-console/option :limit 1000]
```

## Using it through re-frame-pair

Follow [re-frame-pair setup](re-frame-pair.md), starting each session with:

```sh
bash scripts/re-frame-pair.sh discover-app
bash scripts/re-frame-pair.sh dispatch '[:dev-console/clear]'
```

The console continuously mounts `[:dev-console/snapshot]`, even with its panel
closed. Read that existing reaction instead of creating a dangling subscription:

```sh
bash scripts/re-frame-pair.sh eval-cljs \
  '(when-let [reaction (some (fn [[_ r]] (when (= [:dev-console/snapshot] (re-frame.tooling/query-v-for-reaction r)) r)) @re-frame.tooling/query->reaction)] @reaction)'
```

The snapshot includes the route, options, mounted instances/resolved paths and
bounded records. **Copy snapshot** copies the same EDN to the local clipboard.
For exact handler sources, cascade tracing and post-mortems, use the installed
skill's `handler-source`, `trace-recent` and `watch-epochs` commands alongside it.
The console uses native `re-frame.tooling` trace/epoch callbacks and
`dispatch-and-settle`; it does not install another event-error handler.

`re-frame-pair` itself installs an error-capture bridge once per browser runtime.
Re-frame may log “overwriting :error handler for :event-handler” when it replaces
its default handler. That warning is not an application handler exception.
