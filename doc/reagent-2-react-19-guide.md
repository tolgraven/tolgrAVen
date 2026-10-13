# Classic Reagent and Reagent 2 with React 19

Reagent 2 can still feel like Reagent: Hiccup, Clojure data, reactive atoms,
subscriptions and event handlers remain the normal application vocabulary.
Function components make React hooks available; they do not oblige us to express
every view as a JavaScript React component translated into ClojureScript.

Some of the unfamiliar code in this project comes from browser integration and
SSR. Some comes from choices in our component runtime. The latter is open to
improvement. Hooks now delegate to upstream `reagent.hooks` through
`tolgraven.react`, retaining Clojure equality instead of a custom approximation.

Our full-featured `tolgraven.macros/defc` should continue to build on upstream
`reagent.core/defc`. Its purpose is to add declarative capabilities while retaining
Reagent's rendering and reactivity, not to implement another component system.

This guide explains the library differences and evaluates the current design.
The [refactor brief](../features/reagent-native-refactor.md) gives future agents
the concrete ownership boundaries and verification requirements.

## Reading map and scope

Start with the comparison and component examples. Then read the hook/effect
sections to decode the unfamiliar syntax. The project assessment explains why
our abstractions exist and where their costs are avoidable.

The inspected PR base declares Reagent **2.0.1**, React/React DOM **19.2.8** and
re-frame **1.4.7** in `project.clj` and `package.json`. Reagent details below are
checked against the 2.0.1 source, including the locally installed JAR. React's
live documentation can describe later releases; release-specific claims also use
the React 19 release and upgrade guides.

Here, “classic Reagent” means the familiar `defn`, form-2 and `create-class` style
with Reagent managing React class components. Function component support already
existed before Reagent 2. The version change and the programming style change
are related, but are not the same thing.

## What changes and what stays familiar

| Concern | Classic style | Reagent 2 with React 19 | Our intended default |
| --- | --- | --- | --- |
| Markup | Hiccup vectors and Clojure attribute maps | Same | Same |
| Domain data | Clojure maps, vectors, keywords | Same | Same |
| Reactive local state | `r/atom`, `@`, `swap!`, `reset!` | Still supported in Reagent components | Ratom for transient view state when sufficient |
| Application state | re-frame events and subscriptions | Still applicable | Existing shim, scoped subscriptions and events |
| Component definition | Usually `defn`; Reagent creates a React class | `defn` remains supported; `r/defc` creates a function component | Our `defc`, built on `r/defc` |
| Per-instance initialization | Form-2 or `r/with-let` | Both remain useful | `:let` or `r/with-let` for local allocation |
| Lifecycle integration | Class callbacks | Hooks available in function components; classes remain available | Shared features or Reagent hook wrappers |
| DOM access | Some old code uses `dom-node` | `dom-node` removed; use refs | Small ref/effect adapters |
| Root rendering | Older `reagent.dom/render` | `reagent.dom.client` roots for React 19 | Existing boot/hydration owner |
| JavaScript interop | Required at browser/library boundaries | Still required at those boundaries | Keep it at those boundaries |

Reagent 2 does **not** globally make ordinary `defn` components function
components. In 2.0.1, the default Hiccup compiler is still the class compiler.
`defc` selects the function implementation directly; `:f>` and an explicitly
configured function compiler are other routes. The project does not need to
change the global compiler to use `defc`.
[Reagent compiler guide](https://github.com/reagent-project/reagent/blob/v2.0.1/doc/ReagentCompiler.md),
[2.0.1 template source](https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/impl/template.cljs).

React 19 also does not force every class to be rewritten. It removes old APIs,
including `findDOMNode`, `ReactDOM.render` and the old hydration entry point.
Changing the root adapter and DOM lookup strategy is necessary; changing every
stateful view into raw hooks is a separate decision.
[React 19 upgrade guide](https://react.dev/blog/2024/04/25/react-19-upgrade-guide),
[Reagent upgrade guide](https://github.com/reagent-project/reagent/blob/v2.0.1/doc/2.0-upgrade.md).

## Four layers to keep separate

**React** owns component identity, reconciliation, commits, hooks, context,
hydration and the rendered DOM.

**Reagent** supplies Hiccup conversion, Clojure arguments, ratom tracking,
component adapters and Clojure-aware hooks.

**re-frame** supplies application state, event processing, effects and derived
subscriptions. A React effect hook and a re-frame effect are different mechanisms:
the former synchronizes a mounted component with an external resource; the latter
performs application work declared by an event.

**Our project** supplies feature declarations, scoped state, module loading,
managed content, page readiness, restoration and animations. `:features`,
`:depends`, `:state`, `defpage`, `:let` in our macro and `<sub`/`>update` are project
APIs, not requirements introduced by Reagent 2.

The [component API](components.md) documents those project extensions; the
[Reagent and re-frame policy](reagent-re-frame.md) documents application ownership.

## Components before and after

### Ordinary views remain ordinary views

An upstream comparison, independent of the project's declaration features:

```clojure
(ns example.views
  (:require [reagent.core :as r]))

;; Classic Reagent component.
(defn <greeting-classic> [{:keys [name]}]
  [:p "Hello " name])

;; Reagent 2 function component. Same application arguments and Hiccup.
(r/defc <greeting> [{:keys [name]}]
  [:p "Hello " name])

[<greeting> {:name "Ada"}]
```

In this repository, import `defc` from `tolgraven.macros` instead. The extra
declaration machinery should be invisible for a simple component.

Upstream `defc` produces a memoized React component value. The declared argument
list is still the Clojure argument list used by Hiccup, but the public value is
not an ordinary domain function. Render it with `[<greeting> data]`; do not call
`(<greeting> data)` to get markup. Keep computations that need direct invocation
in separate `defn` helpers.
[Reagent defc source](https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/core.clj).

This also explains the stable adapter in `components/ui/code.cljs`: a JavaScript
package expecting a component gets `reactify-component` around an ordinary
function that returns `[<our-component> props]`. Passing our `defc` descriptor
as though it were an ordinary Clojure render function is the wrong contract.

### Form-2 still has a place

```clojure
(defn <counter-classic> [initial-count]
  (let [*count (r/atom initial-count)]
    (fn [initial-count]
      [:button {:on-click #(swap! *count inc)} @*count])))
```

The outer call allocates instance state; the inner function renders with current
arguments. Reagent 2's function implementation also supports returning an inner
render function. This is a compatibility feature, not evidence that hooks can
be called anywhere inside the outer initializer.
[Reagent component implementation](https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/impl/component.cljs).

Our equivalent keeps the familiar atom and makes allocation explicit:

```clojure
(defc <counter> [initial-count]
  :let [*count (r/atom initial-count)]
  [:button {:on-click #(swap! *count inc)} @*count])
```

Changing `initial-count` on the same mounted instance does not reset this atom.
That is appropriate for an initial value. If a different record should have a
fresh editor, give it a different stable React key. If a value should always
reflect its input, derive it from that input instead of maintaining a copy.

### `with-let` is still useful, with a lifecycle qualification

`r/with-let` keeps bindings across renders and supports a `finally` clause tied
to disposal. It is a good fit for allocating local ratoms and ordinary instance
helpers. Our lean `:let` path uses it.
[Reagent core API](https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/core.cljs).

Allocation is not a DOM commit. Starting listeners, observers, network calls or
timers in an initializer can do work for a render React never commits, and can
run in a server renderer. For external resources, use an owned effect or shared
feature with cleanup. Keep hooks in the render path that executes in the same
order every time, not in `:let` bindings that execute only on initialization.

## Choosing state without turning everything into hooks

| State or value | Owner and preferred representation |
| --- | --- |
| Loaded posts, authentication, search results, route, persistence | Existing re-frame section and subscriptions |
| A setting that must survive remount/navigation | Existing scoped `<sub` handle, with `>reset`/`>update` |
| Temporary component-only expansion or draft | Local `r/atom` when sufficient |
| A DOM node, observer handle, timer identifier | Ref or nonreactive instance atom inside an adapter |
| Local state participating in a React hook adapter | `reagent.hooks/use-state` through the shim |
| A value computable from other state | Plain expression or derived subscription |

A plain ClojureScript `atom` is useful for nonreactive handles. Dereferencing it
does not by itself make a component rerender. Use a Reagent atom when local
changes should drive a Reagent view.

Current scoped handles are **native re-frame subscriptions**, not writable
ratom-like cursors. `<sub` reads the scoped value; `>update` and `>reset` dispatch
writes. Older implementations of this project had writable cursor adapters;
those are not the authoring contract on this PR base.

```clojure
(defc <setting>
  {:state {:initial {:enabled? false}}}
  []
  :let [*enabled? (<sub :comp [:enabled?])]
  [:button {:aria-pressed @*enabled?
            :on-click #(>update *enabled? not)}
   (if @*enabled? "Enabled" "Disabled")])
```

Declared defaults initialize after commit through an idempotent event/effect.
An ordinary `<sub` fallback does not write app-db. Persistent storage is the
managed-source exception: acquiring the real value subscription dispatches a
load event, while its derived reaction only reads app-db. Eager initialization
uses the same effect, so later mounting does not repeat the snapshot read. This
matches the useful acquisition pattern of our Strapi/Supabase bindings; a dummy
subscription returning `true` solely to trigger work adds no useful data flow.

Do not mirror a subscription in hook state with an effect that copies values.
That creates two owners and an extra update cycle. The existing blog vote/edit
views demonstrate that subscriptions, events and Hiccup are still sufficient
for most application interactions.
[re-frame subscriptions](https://day8.github.io/re-frame/subscriptions/),
[React guidance on unnecessary effects](https://react.dev/learn/you-might-not-need-an-effect).

## Hooks in ClojureScript

A hook is a function whose state or lifecycle React associates with the current
function component and its call position. It is not inherently a JavaScript
data model. Reagent can adapt the arguments and equality rules while React
continues to own its lifecycle.

Use hooks at the top level of a function component or another hook, with the
same order on every render. Put conditions inside an effect callback. Do not
call hooks from event handlers, loops, conditional render branches or one-time
initializers. A helper calling hooks is itself a custom hook and should be named
`use-...` to expose that fact.
[React rules of hooks](https://react.dev/reference/rules/rules-of-hooks).

### The shim delegates to upstream Reagent

`tolgraven.react` exposes upstream Reagent effect, layout-effect, memo, callback,
state, reducer, ref, context and external-store hooks. Application code continues
using that shim, preserving tracing and validation around re-frame entry points.
There is no independent hook dependency/cleanup implementation. Memo and callback
hooks require an explicit Clojure dependency collection.

An upstream illustration of the style we want at that boundary:

```clojure
;; Standalone upstream example; project application code uses tolgraven.react.
(ns example.clock
  (:require [reagent.core :as r]
            [reagent.hooks :as hooks]))

(r/defc <clock> [period-ms]
  (let [[ticks set-ticks!] (hooks/use-state 0)]
    (hooks/use-effect
      (fn []
        (let [timer (js/setInterval #(set-ticks! inc) period-ms)]
          #(js/clearInterval timer)))
      [period-ms])
    [:output ticks]))
```

The JS calls above implement an actual browser timer. The component's state,
dependencies, arguments and markup remain Clojure values. This does not mean
application retry scheduling should move from re-frame effects into clocks.

### What the upstream wrappers actually improve

Reagent's effect, layout-effect, memo and callback wrappers compare Clojure
dependency collections by value. State/reducer wrappers retain the previous
value when an update returns an equal Clojure value. Effect wrappers treat a
non-function return as no cleanup; returning a function declares cleanup.
`use-ref` still returns React's mutable ref object, and hook tuples can still
be native arrays even though Clojure destructuring works on them.
[Reagent hooks source](https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/hooks.cljs).

For example, rebuilding `{:id 42}` produces an equal Clojure map even if it has a
new JavaScript identity. The upstream wrapper retains the prior equal dependency
collection. This avoids
unnecessary disconnect/reconnect work for value-oriented configuration.

`test/cljs/tolgraven/react_hooks_test.cljs` exercises equal and changed Clojure
values, state/reducer identity, resource cleanup and native handle/function identity.
Functions, refs and DOM nodes remain identity-sensitive values; do not recursively
convert them or treat them as interchangeable configuration data.

One more tradeoff: Reagent's non-function-return handling can hide an accidental
Promise returned by an effect. An effect setup must not be an async function;
start asynchronous work inside it and return cleanup or no value. Keep that
discipline explicit in code review and tests.

## Effects, commits and stale values

React can calculate a render without committing it. A commit is when React
applies the chosen result to the DOM and connects its lifecycle. This distinction
explains why render functions should compute Hiccup rather than perform external
work. It is a React principle that predates React 19, made especially relevant
by modern scheduling and SSR.
[React component purity rules](https://react.dev/reference/rules/components-and-hooks-must-be-pure).

Use an ordinary effect for listeners, observers or external synchronization that
does not need to block painting. Dependencies describe the values the setup uses;
when they change, React cleans up the old setup before installing the new one.
Omitting dependencies means setup after every commit; `[]` expresses no changing
dependencies, not an unconditional promise that setup happens only once in all
development scenarios. Effects do not run in SSR.
[React useEffect reference](https://react.dev/reference/react/useEffect).

A layout effect runs during the commit before repaint and can block painting.
Use it for a concrete timing requirement, such as page-root positioning or
registering readiness after a DOM commit. An HTTP request or generic initialization
callback does not automatically need layout timing.
[React useLayoutEffect reference](https://react.dev/reference/react/useLayoutEffect).

| Older lifecycle concept | Modern expression | Qualification |
| --- | --- | --- |
| Allocate local state | `:let`, `with-let`, state/ref hook | Keep initialization free of external work |
| `component-did-mount` | Effect with stable dependencies | Layout effect only for pre-paint work |
| `component-did-update` | Effect depending on relevant values | Avoid copying derived data to another store |
| `component-will-unmount` | Cleanup returned by setup | Cleanup also runs when dependencies change |
| Find a rendered DOM node | Ref attached to its Hiccup root | No `findDOMNode` |
| Catch descendant render errors | Small class error boundary | Hooks do not provide a direct boundary equivalent |

Each render's closures capture that render's values. An effect with `[]` that
uses the first `record-id` continues using it when arguments change unless it is
intentionally mount-scoped. A `:let` callback can have the same stale-input
problem. Adding dependencies is usually clearer than maintaining a “latest value”
ref everywhere.
[React state snapshots](https://react.dev/learn/state-as-a-snapshot).

Hook setters schedule a new render; the local binding remains the current
render's snapshot. An updater such as `(set-count! inc)` computes from the pending
state. Ratom mutation updates the dereferenced value immediately, with DOM work
still batched. Neither mechanism makes reading the DOM immediately after a state
change a reliable measurement strategy.
[React useState reference](https://react.dev/reference/react/useState).

React Strict Mode can repeat rendering, effect setup/cleanup and ref attachment
in development to reveal ownership bugs. Our core contains a disabled diagnostic
Strict Mode branch; that is not evidence that the whole app is currently checked.
Mounted adapter tests use targeted Strict Mode checks for resource ownership.
[React StrictMode reference](https://react.dev/reference/react/StrictMode).

## Decoding the interop vocabulary

| Syntax | Meaning | Appropriate place |
| --- | --- | --- |
| `#js {...}` / `#js [...]` | Native JS object/array literals; nested values are not recursively converted | APIs expecting a native container |
| `(.-current ref)` | Read React's ref field | Ref/effect adapter |
| `(set! (.-current ref) value)` | Store a nonreactive handle; does not rerender | Ref owner, with lifecycle care |
| `(.. event -target -value)` | Read a native event's target value | UI event boundary |
| `(js->clj payload :keywordize-keys true)` | Normalize a JSON/SDK payload into Clojure data | Transport adapter, once |
| `(clj->js options)` | Convert Clojure data for a JS API | SDK/API boundary |
| `js/undefined` | A JS return/argument sentinel | Native API contract when actually needed |
| `js/Promise`, `.then`, `.catch` | Native asynchronous API integration | Shared loader/source/browser adapter |

Upstream hook wrappers remove the need for native dependency arrays and explicit
undefined effect returns. They cannot remove the DOM's native event objects,
IntersectionObserver's configuration or a Promise required by `React.lazy`.
Likewise, `use-ref` is not a ratom: changing its field does not notify Reagent.
[React useRef reference](https://react.dev/reference/react/useRef).

Prefer a callback ref or a small owned adapter over spreading `.-current` reads
through domain code. React discourages arbitrary ref reads/writes during rendering;
predictable one-time initialization is a narrow exception. Our “latest args” and
diagnostic resolver publication and feature callbacks now belong to commits, so
discarded renders cannot overwrite the work belonging to a selected render.

React 19 supports cleanup returned by a ref callback. Do not accidentally return
a function-valued assignment or helper result unless it is intended as cleanup.
Effect return normalization does not normalize ref callback returns.
The familiar `[:div {:ref #(reset! *node %)} ...]` works with a plain atom allocated
in `:let` or an outer function: attachment stores the node, detachment stores nil,
and neither change rerenders. Reagent/React handles those callbacks. Our small
`react/compose-refs` helper exists only where internal and caller refs must both
receive the node; it forwards explicit cleanup when a caller returns it. Ordinary
callbacks do not require manual release, leases or a second lifecycle registry.
[React 19 release notes](https://react.dev/blog/2024/12/05/react-19).

### Browser APIs around Clojure data

Native APIs do not require native-shaped application logic. In the return worker,
JSON arm records become maps once, native cache arrays become sequences, and
request eligibility, expiry and eviction use pure functions in
`ssr/return_policy.cljs`. The worker adapter retains actual Request/Response and
Cache objects, clocks, URL parsing and the Promises needed by browser event
lifetimes. `array-seq` retains opaque handles while enabling ordinary destructuring,
`map`, `filter` and `drop-last`; recursively converting browser objects would be
the wrong boundary. Response options are Clojure maps converted at construction.
[ClojureScript js->clj](https://cljs.github.io/api/cljs.core/js-GTclj),
[array-seq](https://cljs.github.io/api/cljs.core/array-seq),
[service-worker event ownership](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API/Using_Service_Workers).

Application listeners likewise use existing re-frame effects. Events return
`:listener/register` / `:listener/stop`; committed component/browser lifecycles
use `listener/listen!` with declarative binding maps through the same registry.
Its cleanup releases exactly that acquisition. Capture/passive/once are data,
converted only by the native adapter. Rendered link-preview pointer/focus/click
interactions use Hiccup attributes. Native resource readiness and worker events
remain small explicit adapters where their lifecycle requires it.

### Hiccup integration forms

Optional scripts can be rendered as Hiccup too. The boot lifecycle mounts
`[browser-resources/<deferred> [browser-resources/<analytics>]]` only in production.
The deferred component starts as `[:div {:hidden true}]`; after the shared page
gate and a cancellable timeout, resetting its local ratom changes its render to
the supplied Hiccup. Analytics then acquires public settings through the existing
managed URL source and renders `[:script {:async true :src ...}]`. Its ID lives in
server configuration, and its native adapter only configures the SDK queue.
This keeps the page update in ordinary Reagent state/rendering instead of hiding
script acquisition inside `preinit`. React 19 deduplicates async scripts by `src`.
[React script reference](https://react.dev/reference/react-dom/components/script).

| Form | Contract |
| --- | --- |
| `[<our-component> data]` | Reagent component with Clojure arguments and tracking |
| `[:f> ordinary-render-fn data]` | Explicitly use a Reagent function component; ratoms and hooks work |
| `[:> NativeComponent {:option value}]` | Native React component with Reagent prop conversion |
| `[:r> NativeComponent #js {:option value}]` | Raw native React props; Hiccup children still converted |
| `[:<> child-a child-b]` | Fragment without a DOM wrapper |

`adapt-react-class` is the historical name of a native component adapter; it can
adapt function components too. `as-element` converts Hiccup when a native API
expects a React element, as with a portal or Suspense fallback. These are explicit
boundary tools, not a reason to convert ordinary application arguments to JS.
[Reagent React feature guide](https://github.com/reagent-project/reagent/blob/v2.0.1/doc/ReactFeatures.md).

Context values may be Clojure maps. Our raw provider envelope can keep its `value`
opaque. Replacing `:r>` with `:>` blindly risks changing data representation;
inspect conversion behavior first. Both sides must agree on whether the value
is a Clojure map or a native object.

## Rendering and hydration explain some real complexity

The root adapter uses `create-root` for client rendering and `hydrate-root` for
matching server HTML. Hydration requires the initial browser component tree,
state and attributes to agree with the server output. Browser-only measurements
should happen after attachment instead of changing the first render. React can
recover mismatches, but recovery is a bug signal, not the normal loading strategy.
[React hydrateRoot reference](https://react.dev/reference/react-dom/client/hydrateRoot).

That makes restored state, deferred font/highlighter work, visibility effects
and entrance suppression relevant. Their complexity comes from this site's
behavioral requirements. It is not inherent to a plain Reagent 2 counter.

Reagent batches ratom updates and, on modern DOM roots, uses `flushSync` when
flushing its batch to avoid adding another React scheduling delay. This does not
turn all React hook state into synchronously readable state, nor justify manual
flush calls throughout application code.
[Reagent batching notes](https://github.com/reagent-project/reagent/blob/v2.0.1/doc/React19.md).

Our `component/hydration.cljs` temporarily changes `reagent.impl.batching/react-flush`
to `startTransition` while deferred SSR boundaries finish hydration. That is a
project-specific integration with an implementation namespace, with shared/global
ownership implications. Its tests cover cached child completion before parent
setup, overlapping owners, discarded renders, StrictMode and stale document
callbacks. Installed Reagent's root API offers no public per-boundary flush
option, so this remains a narrow compatibility adapter with an explicit release
contract. Calling it “how Reagent 2 works” would obscure the actual decision.

SSR here uses shared components and Node rendering. React Server Components,
Server Actions and React 19 form actions are not prerequisites for this design.
Adding them would be another architecture choice, not a migration obligation.
See [our SSR guide](ssr.md) and the
[Reagent SSR guide](https://github.com/reagent-project/reagent/blob/v2.0.1/doc/ServerSideRendering.md).

## How well the project's choices fit

| Choice | Assessment | Reason and next step |
| --- | --- | --- |
| Full-featured macro based on upstream `r/defc` | Keep | Reuses Reagent tracking and React function identity; project capabilities remain declarative |
| Hiccup, domain helpers, subscriptions and events | Strong fit | Keeps data transformations ordinary ClojureScript and application actions traceable |
| Shared appearance/presence/font features | Useful project extensions | Avoid repeated lifecycle code; preserve declarative markup and one resource owner |
| Upstream hook delegates through the shim | Keep | Reagent owns Clojure equality, state identity and cleanup adaptation |
| Small error boundary using `create-class` and native state | Justified interop | React's boundary API is class-based; keep the class narrow and preserve retry behavior |
| Native refs/observers inside motion/video adapters | Justified at the boundary | DOM lifecycle requires native handles; use Clojure configuration around them |
| Hiccup-key recovery in `component/compat.cljs` | Retain as a tested compatibility boundary | React keys are not props; Reagent's class accessor does not expose function metadata. Backing representation access stays in one version-specific adapter |
| Managed `:component-data/status` acquisition | Keep | Source factory owns the shared queue; the derived reaction and view only read status, preserving parallel preloads |
| Global batching override during selective hydration | Specialized, needs scrutiny | Preserves SSR content but couples us to Reagent internals and overlapping boundary ownership |
| Stable ReactMarkdown/highlighter adapters | Appropriate | Packages require native components; keep Clojure values until the specific boundary |
| Ref/state machinery for every simple UI choice | Usually unnecessary | Prefer a local ratom or subscription when that fully expresses the behavior |

The error boundary does not replace error handling for event callbacks, transports
or arbitrary asynchronous tasks. Those failures belong to their event/effect or
source adapters. Keeping a class boundary is idiomatic for that specific React
API, even in an otherwise functional codebase.
[React Component reference](https://react.dev/reference/react/Component).

Memoization is an optimization, not a lifecycle guarantee. Upstream `defc` already
provides memoization with Reagent argument comparison. Adding stable callbacks
or memo hooks is useful when a measured boundary depends on identity; wrapping
every expression makes the component harder to understand without necessarily
improving behavior.
[React memo reference](https://react.dev/reference/react/memo).

## Practical authoring standard

1. Write domain computation as small Clojure functions over maps and vectors.
2. Define project components with our `defc` and pages with `defpage`; retain
   upstream `reagent.core/defc` underneath both lean and composed paths.
3. Read application data through existing subscriptions and express actions
   through events. Use declarations to acquire the same managed sources.
4. Use local ratoms for genuinely transient view state; scoped subscriptions
   for state that survives remounts. Avoid duplicated state.
5. Use shared component features before introducing another lifecycle adapter.
6. When a hook is needed, use the shim delegating to `reagent.hooks`. Include
   dependencies, cleanup and the SSR contract.
7. Keep native containers, refs, observers and Promises at APIs that require them.
8. Verify actual lifecycle and route behavior when changing adapters, including
   hydration identity, restoration, empty/error states and resource cleanup.

This standard preserves the functional component foundation while making normal
feature code read like ClojureScript. The [refactor brief](../features/reagent-native-refactor.md)
records the implemented ownership changes and the remaining compatibility
boundaries that future changes must preserve or replace with evidence.
