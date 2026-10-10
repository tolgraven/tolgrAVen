# Reagent and re-frame first

The default frontend flow is ordinary Reagent Hiccup backed by re-frame. This
applies to performance changes, lazy components and third-party React packages as
well as everyday UI. An explicit event, app-db change and called effect are easier
to inspect and debug than an equivalent flow spread across callbacks and stores.
Use the `tolgraven.react` shim so application calls retain development tracing.

## Follow the event flow

Prefer this sequence:

1. A Reagent event attribute expresses user intent.
2. A registered event updates the relevant app-db section and declares effects.
3. An effect or managed source performs IO and dispatches its result.
4. Scoped or derived subscriptions expose the result.
5. Hiccup derives the displayed state, including loading, failure and retry.

Keep subscription derivations and event-db handlers pure. Network, clipboard,
persistence and application timers belong in registered effects or their existing
owned adapters. The event log should reveal what happened and why the view changed.
Do not mirror app-db in atoms, writable Reagent cursors, hook state or custom watches.

Use the scoped shortcuts already supplied by `defc`:

```clojure
(defc <setting> []
  :let [*enabled? (<sub :comp [:opts :enabled?] {:initial false})]
  [:button {:type "button"
            :aria-pressed @*enabled?
            :on-click #(>update *enabled? not)}
   (if @*enabled? "Enabled" "Disabled")])
```

These handles are native re-frame subscriptions. `>reset` and `>update` dispatch
writes through the normal event path. Declare schemas and persistence on the owning
state specification when appropriate; see [scoped state](components.md#scoped-subscription-and-write-shortcuts).

Compose layer-2/3 subscriptions using their actual input subscriptions instead of
reading the entire database and filtering after every update. For example, a
higher-layer subscription can combine a domain result and a filter choice:

```clojure
(rf/reg-sub :example/visible-items
  :<- [:example/items]
  :<- [:example/filter]
  (fn [[items selected-kind] _]
    (filterv #(= selected-kind (:kind %)) items)))
```

Use the owning declarations and source bindings for real content. Preloading
acquires those same bindings instead of adding an imperative fetch path.

## Render and defer declaratively

Put handlers in Hiccup (`:on-click`, `:on-pointer-down`, `:on-change`, etc.). Use
`defc`/`defpage` declarations and the shared loading, visibility, appearance and
presence features. Closing, retries and delayed application actions are events.
For delayed events, prefer an event effect:

```clojure
(rf/reg-event-fx :example/retry-later
  (fn [_ _]
    {:dispatch-later [{:ms 3000, :dispatch [:example/retry]}]}))
```

A rendered component should declare its dependencies and observe their state.
Avoid component-level Promise chains that fetch data or coordinate application
boot. Native Promises remain appropriate at an SDK, browser or module-loader API
boundary. Do not add an async abstraction simply to hide Promise syntax.

Keep React in charge of rendered markup. State-driven Hiccup handles fallbacks,
errors and transitions. Refs for measurements, scrolling or observer ownership do
not permit replacing nodes or imperatively changing their classes/styles/text.

## When React features help

Hooks, Suspense, transitions, memoization and portals are useful where the existing
abstractions cannot express the needed lifecycle or interoperability, or where
measurement establishes a performance benefit. Before adding one, identify:

- The specific lifecycle limitation or measured bottleneck.
- The existing Reagent/re-frame capability considered first.
- The owner of state, IO, cleanup and SSR behavior.
- The verification that demonstrates the intended behavior.

Document the reason near the adapter or in the PR. Prefer a small reusable adapter
that leaves application events traceable. Keep third-party component adapters
stable and pass Clojure props through Reagent; use native containers only at APIs
that require them. Do not inspect React element internals to recover values.

A local high-frequency gesture can hold pointer coordinates and cancellable timers
in refs. This avoids flooding application state with transient pointer movement.
It must clean up on cancellation, dependency changes and unmount, and dispatch
its final application action through re-frame. This exception does not extend to
content caches, persistence, network state or business logic.

Browser interop needs SSR guards and an owning lifecycle. Native listeners are an
exception when ordinary Reagent event attributes cannot handle a specific browser
boundary. For example, React holds events aimed at an unhydrated Suspense child:
the code component temporarily bridges native intent to its same Reagent handlers,
then removes that bridge after the inner commit. Copy controls are siblings of
SSR-highlighted code, so they hydrate before the deferred highlighter. The bridge
must not mutate markup or become a second permanent interaction implementation.

## Clojure-friendly hook boundary

When hooks are justified, use the shim's wrappers. Dependency collections accept
Clojure vectors/sequences; only the outer container converts to a native array.
Maps, functions, refs and other dependencies retain their identities. An existing
JavaScript array also remains unchanged. React's dependency comparison still uses
its native identity semantics.

Effects accept implicit nil for no cleanup, so normal `when` forms work:

```clojure
(rf/use-effect
  (fn []
    (when enabled?
      (start-owned-adapter!))) ; returns its cleanup function
  [enabled?])
```

The effect wrapper maps nil/undefined to no cleanup and preserves a returned
cleanup function. Other accidental results remain visible to React diagnostics.
`use-layout-effect` has the same contract. Omitting dependencies reruns after each
commit; `[]` limits setup to mounting. Follow React's hook ordering rules.
`use-memo` and `use-callback` accept the same dependency collections and preserve
calculated/callback return values, including Clojure values and nil.

## Review before merging

Read the entire PR, including tests and failure paths. Check that intent, events,
effects and subscriptions are traceable; that UI state is declarative; and that
any React/browser adapter has a specific justification and releases its resources.
Verify actual cold SSR, hydration, SPA and restored navigation behavior when those
paths change. Successful compilation alone does not establish correct lifecycle
or performance behavior. Keep the root and scoped AGENTS guides current with any
new contract instead of leaving this policy only in conversation history.
