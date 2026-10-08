# Composable components with `defc`

Reusable rendered UI lives in `tolgraven.components.*`; feature views live under
`tolgraven.modules.<feature>.*`. `tolgraven.component.*` implements declaration,
lifecycle, data and state capabilities. Import the owning namespace directly;
there is no `tolgraven.views` compatibility entry point. See [source layout](source-layout.md).


Require `[tolgraven.macros :refer-macros [defc]]` and
`[tolgraven.component.registry]`, and use components in Hiccup.
The default compiles directly to Reagent 2's `reagent.core/defc`: a memoized
function component, with no error boundary, lifecycle class, DOM wrapper or data
loader. Registering its descriptor happens once at definition time. As with
Reagent 2 components, use `[<component> args]`, not `(<component> args)`.

```clojure
(defc <title> [{:keys [title]}] [:h2 title])

(defc <counter> [label]
  :let [*count (r/atom 0)]
  [:button {:on-click #(swap! *count inc)} label ": " @*count])
```

Put component configuration (`:features`, `:depends`, `:state`, etc.) in the
declaration map after the optional docstring, rather than on the component symbol.
Hiccup metadata such as `^{:key ...}` still identifies instances.
The macro accepts an optional docstring and declaration map, one
argument vector (including destructuring and variadic arguments), and optional
`:let [bindings]` for per-instance state. Existing form-2 bodies also work.
Bindings survive argument updates; rendering receives current arguments. Lean
components also accept ordinary multiple render arities. Composed features need
one argument vector. Empty prototype bodies render `nil`, so unfinished definitions
can retain their API during a migration.

The registry is deliberately independent of the component runtime. Leaf views
such as the error fallback can declare lean components without loading the runtime
that uses them. Only opt-in features require `tolgraven.component`.

A `defc` value is a React component descriptor, not an ordinary callable function.
Use `[<component> value]` even inside threading/helper code. At a native React
adapter boundary, use `(r/reactify-component (fn [props] [<component> props]))`
instead of passing the descriptor to `reactify-component`. Module exports may
still use Vars; resolve them with `component/resolve-view` before placing the
result in Hiccup. Mount roots with the component value rather than an unresolved
Var wrapping that value.

## Selecting features

Features are explicit, ordered and registered independently of the macro:

```clojure
(defc <article>
  {:features [:props :error-boundary :links]}
  [spec title]
  [:article [:h2 title] [:a {:href "https://example.com"} "Read more"]])

[<article> {:props {:id "article" :class "compact"}
            :classes ["interactive"]
            :links {:id :article :text "https://example.com"}}
 "An article"]
```

| Feature | Behavior | Adds a class? |
| --- | --- | --- |
| none | Native Reagent function component | No |
| `:container` | Optional layout root outside other features; merge caller attrs there | No |
| `:props` | Merge first-argument `:props` / `:classes` into a native DOM root; preserve both refs | No |
| `:data` | Enable dependencies supplied in the first argument's `:depends` | No |
| `:error-boundary` | Isolate render/initialization/descendant lifecycle failures; log and offer retry | Yes, a boundary around the function body |
| `:lifecycle` | First-argument `:init` / `:exit` through layout effects | No |
| `:links` | Existing link-preview discovery, update and cleanup | No |
| `:appear` | Enter animation merged onto the native root | No |
| `:seen` | Viewport visibility merged onto the native root | No |
| `:exit` | Exit animation when removed by a presence-enabled parent | No |
| `:presence` | Retain keyed direct children until exit completion | No |

Definition-level `:depends` automatically enables data handling. The macro recognizes a first argument named `spec`, `opts` or `options`, or a map
destructuring feature keys such as `props`, `classes`, `depends`, `appear`, `seen`
or `links`. Other arguments are domain data, even if their map happens to contain
keys named `:props` or `:depends`. No spec argument needs to be added when absent.
A feature may be `[id config]`; `[id false]` disables it.
Require the namespace that registers a feature (for links,
`tolgraven.modules.link-preview.views`) before using it.

Root props only apply to native DOM roots, never fragment, child-component or
React-interop roots. Link discovery needs a native DOM root. Feature composition
itself adds no DOM wrapper; a custom feature may deliberately add one.

Register a feature with `component/register-feature!`:

- `:transform (fn [hiccup instance-spec config] ...)` transforms the body's
  Hiccup; transforms run in declaration order.
- `:wrap (fn [hiccup definition config] ...)` wraps the composed body; the first
  declared wrapper is outermost.
- DOM features supply `:setup (fn [config] state)`, `:mount (fn [state element])`
  and `:unmount (fn [state])`. They run as layout effects in the function component, refresh when configuration
  or root changes, and clean up on unmount. The instance spec may override the
  feature's configuration using its registered ID.

No macro edit is needed to add another feature. Select the boundary outside
features whose failures should be contained.

## Root-merged appearance and actual removal

```clojure
(defc <card>
  {:features [[:appear "slide-in"] [:exit {:timeout-ms 1500}]]}
  [{:keys [title]}]
  [:article.card [:h3 title]])

(defc <cards> {:features [:presence]} [cards]
  (into [:section.cards]
        (map (fn [card] ^{:key (:id card)} [<card> card]) cards)))

(defc <viewport-card>
  {:features [[:seen {:class "slide-in" :threshold 0.5 :once? true}]]}
  [title]
  [:article [:h3 title]])
```

`:appear` and `:seen` merge the existing `appear-wrapper`, animation kind and
`appeared` classes onto the component's own root. There is no wrapper element or
React component. Original classes, styles and refs are preserved. `:appear` waits
for the initial frame before adding `appeared`. `:seen` uses IntersectionObserver
with a default 50% threshold; `:root-margin` and `:once?` are configurable. Without
IntersectionObserver it falls back to appearing immediately after the initial
frame. `:force true` skips waiting for visibility. Use either `:seen` or `:appear`;
when both are selected, visibility controls the reveal.

For removal, the parent selects `:presence` and its keyed direct children select
`:exit`. Remove a child from the parent's collection normally. The parent keeps
that same child instance only while exiting, removes `appeared`, adds `exiting`
and any configured exit `:class`, and finally lets React unmount it. The existing
appearance CSS reverses by default; custom exit classes can define another
transition or keyframe animation. Exiting roots are inert and aria-hidden.

Root animation/transition completion releases the child. A bounded timeout handles
paused/infinite animations or missing completion signals; `:timeout-ms` overrides
the computed CSS-duration fallback, with a maximum of 10 seconds. Reduced motion
skips animations and releases exits promptly. Reintroducing the same key during
exit cancels removal and preserves state. Changing the key creates a new instance.
Observer, frame and deadline work is cleaned up on unmount.

The parent must remain mounted during exit: React cannot defer removal from a
child's unmount callback. Removing the whole parent removes its subtree at once.
Place `:presence` on a higher surviving parent to animate that parent as a child.
Managed children must have stable, unique Hiccup metadata keys, and both features
need native DOM roots. They reject fragments/component roots rather than silently
adding wrappers. Nested lists should put `:presence` on their own direct parent.
Only removed children are retained, and only until completion; no hidden DOM is
kept after exit. Declare `:seen` and `:appear` on the owning component; the
root features require no wrapper.

## Data dependencies, before mount

Dependencies are ordinary resource descriptors. Declare a vector, or a function
of the component's arguments returning that vector:

```clojure
(defc <article-page>
  {:features [:error-boundary]
   :depends (fn [id]
              [{:source :strapi :keys [:blog]}
               {:source :url :url (str "/api/articles/" id)
                :into [:articles id]}])}
  [id]
  [:article @(rf/subscribe [:get :articles id :title])])

;; Starts every request now, without constructing the component body or DOM.
(component/preload! <article-page> "welcome") ; Promise, errors reject
(component/prefetch! <article-page> "welcome") ; speculative, errors reported

[<article-page> "welcome"]
```

On first rendering, requests start before mounting. The body is created after
all required resources are ready. A `role=status` loading placeholder is shown
until then; set definition `:loading` to custom Hiccup to replace it. Failures
produce a visible alert with **Retry data**, plus the existing service
notification/log. Identical in-flight descriptors share requests, including
preloads and later mounts. One failing dependency does not prevent the others
from starting. Retry invalidates those dependencies and starts a new attempt.

Built-in sources:

```clojure
{:source :url :url "/api/example" :into [:example]}
;; JSON with keyword keys by default; :format :text for text responses.

{:source :app-db :path [:content :blog]}
;; Existing nil/false values count as loaded; an absent path is awaited.

{:source :app-db :path [:content :blog]
 :load {:source :strapi :keys [:blog]}}
;; Start a producer when absent, then wait for the app-db value.

{:source :strapi :keys [:blog]}
;; Existing backend-mediated content client installs into [:content ...].

{:source :supabase :query {:path-collection [:blog-posts]}
 :into [:loaded :posts]}
;; Uses existing authenticated read-once query/filter rules; no new stream.
```

`:into` installs the result into app-db. Use distinct paths for independently
rendered resources (for example `[:articles id]`); cache hits do not repeatedly
write over app-db. If different in-flight resources target the same path, the
newer request owns the destination and an older completion cannot overwrite it.

Remote snapshots default to a 60-second TTL and 15-second request deadline;
positive `:ttl-ms` / `:timeout-ms` override these. Completed cache entries are
bounded to 128 (in-flight requests are retained). App-db and Strapi readiness
comes from current app-db values. Account/client changes invalidate URL and
Supabase snapshots, reject pending waiters, and clear values installed by those
resources if another writer has not replaced them. A late old response cannot
install. Failures remain explicit until retry, avoiding automatic retry loops.

Custom sources register via `data/register-source!` with `:load!` returning a
value or Promise. Optional `:read` returns `{:ready? boolean :value value}`;
optional `:scope` partitions the request cache (for example by session).

## Loading views and skeletons

Missing dependencies use the inferred native root and classes by default.
Choose `:loading-prefab` for a skeleton, or `loading/<spinner>` explicitly. A static Hiccup `:loading` value or
a function of current component arguments can build a skeleton:

```clojure
(require '[tolgraven.component.loading :as loading])

(defc <profile>
  {:depends [{:source :url :url "/api/public-profile" :into [:profile]
              :persist {:scope :public :ttl-ms 300000}}]
   :loading [:section {:role "status" :aria-label "Loading profile"}
             [loading/<avatar>]
             [loading/<h1> {:style {:width "18ch"}}]
             [loading/<lines> {:count 3}]
             [loading/<box> {:style {:min-height "12rem"}}]]}
  []
  [:article @(rf/subscribe [:get :profile :name])])
```

Helpers include `<span>`, `<h1>`, `<h2>`, `<box>`, `<avatar>` and `<lines>`. They
accept ordinary DOM attributes/styles and are aria-hidden so the enclosing status
label describes the loading state. Skeleton pulse and spinner rotation respect
reduced motion. Errors still show the existing retry UI instead of a skeleton.

## State that survives unmounts

```clojure
(defc <filters>
  {:state {:scope :page :initial {:search "" :expanded? false}
           :persist {:scope :public :ttl-ms 86400000}}}
  []
  (let [*state (component/state)]
    [:input {:value (:search @*state)
             :on-change #(swap! *state assoc :search (.. % -target -value))}]))
```

`component/state` returns a writable Reagent cursor whose reads use a registered
re-frame subscription and whose writes update app-db. `@`, `reset!` and `swap!`
work like a normal ratom, but unmounting does not delete the value. Call it in the
render body; no `:let` is needed. Options on that call override definition defaults.

Paths are populated automatically under `[:component component-ns component-name instance-key ...]`, using the
component namespace/name. `:scope :page` adds the current pathname and query;
the default scope shares state across navigation. `:key` (a value or function of
component arguments) distinguishes instances such as editors for different posts.
`:id` overrides the component identity. Key precedence is the React/Hiccup metadata `^{:key ...}`, then the manual
`:state :key`, then no key segment. Without either key, all instances of the same
component share state. React keys are carried through data/error-boundary wrappers.
Existing app-db values win over disk
snapshots, and snapshots win over `:initial`; nil/false are valid stored values.
When calling outside `defc`, provide an explicit `:id`.

## Local snapshots and returning from another site

Persistence is opt-in: state uses `:state {:persist ...}`, and data descriptors use
`:persist ...`. Only these declared values and the public CMS bundle are saved,
not the whole app-db. `component/dump-state!` and `component/dump-content!` enqueue
the respective tracked snapshots. State updates are debounced, and
pagehide/visibilitychange flush pending snapshots before leaving the document.

Snapshots use versioned EDN, a default 30-minute expiry and a 2 MiB per-owner envelope size
limit. Set `:version` to invalidate a changed data shape and `:ttl-ms` to choose its
lifetime. Expired, malformed or incompatible snapshots are cache misses. Explicit
data retry discards its disk snapshot before fetching. Storage failures do not
break the page and failed writes produce a visible service notification.

UI state, app-db dependencies and Strapi default to public scope. URL and Supabase
resources default to account scope; they cannot restore until an authenticated
owner is known. Use `:persist {:scope :public}` only for public endpoint data.
Use `:persist {:scope :user}` for private state. Account-specific state paths and
resource snapshots are isolated by account; old account callbacks cannot install
new values. Persistence stores data, never the client's credentials/session.

At component creation, a valid resource snapshot is installed synchronously
before loading is considered, so it needs no spinner and starts no network
request. On external history return, the public CMS bundle is also restored
before bootstrap fetches missing sections. Embedded server content takes priority.
A browser back/forward-cache restore already retains its live app-db and DOM.

Hydration and external-back restoration suppress mount/visibility entrance
animations for the initial page. A later route change clears that context. Ready
server-injected `:into` values are adopted as resource snapshots during restoration.
Missing data still loads and gets a spinner; bypass never means rendering fake
empty content. The core's explicit `data-hydrate="true"` root contract selects
`hydrate-root`, waits for route/module readiness, and leaves existing server DOM
in place while preparing it. Only mark a root when its markup and initial app-db
represent the same component tree; ordinary server placeholders use create-root.
The server renderer must supply matching state/content before hydration. See [page rendering](blog-ssr.md) for the shared server implementation.

## Modules, viewport prefetch and SSR

`content.contract/module-dependencies` is a shared CLJC manifest available before
module JavaScript loads. The loader starts known data requests alongside the
chunk download, then joins module and selected-view dependencies before init.
Module specs may declare `:depends`; retain the shared manifest for requests that
must start before that module's code is available. Existing `:content` remains
supported during migration.

`[component/<prefetch> resources]` starts requests when its one-pixel sentinel
comes within 800px of the viewport, with immediate fallback without
IntersectionObserver. Auto sections combine their declared `:depends` with
legacy content requirements. Explicit `component/preload!` also works from route,
hover or navigation preparation, without needing a sentinel or mounted view.

Page declarations and public read plans live in CLJC and are consumed by JVM
source adapters and the CLJS renderer. Credentials stay in server/client adapter
configuration, never component descriptors.

## Error recovery

Render boundaries and failed required dependencies share the component fallback
with retry and expandable diagnostics. Event/transport failures use registered
error effects. Keep cached content visible during refresh failures and report
those through the HUD; do not add duplicate banners above usable content.

Use `defpage` for page components; its error boundary resets on route changes.
See [testing](testing.md) for mounted lifecycle and browser checks.

## Shared work queues

Storage reads are queued once per owner per document. Application startup awaits
`storage/ready!` before rendering, then components read the in-memory index. A
150 ms state/content debounce coalesces snapshot requests; the next write tick
serializes one envelope per dirty owner. Equal, unexpired values do not produce
another write. Components never invoke localStorage directly. `pagehide` and
hidden visibility consolidate outstanding writes immediately because browsers
can freeze timers during navigation. A cold, unfinished read cannot overwrite
its existing disk envelope.

Dependencies register pending promises synchronously and adapters start together
on the next tick. Repeated resources share a promise. Strapi unions all section
keys queued in that tick into one `/api/content?keys=...` request. Modules and
components use the same queue, including `tolgraven.modules.main.module/spec` and its
explicit shell dependencies in the shared backend/frontend manifest.

```clojure
;; Subscribing requests the owning section; all values still come from app-db.
@(rf/subscribe [:content [:blog :heading]])

;; Explicit preloading, independent of mounting/subscribing:
(rf/dispatch [:content/load [:blog :common]])
(rf/dispatch [:component-data/load
              [{:source :supabase :query {:path-collection [:blog-posts]}}
               {:source :strapi :keys [:blog :common]}]])
```

Re-frame disposes content/state reactions when their last consumer unmounts;
app-db values remain. Supabase live readers are shared per table, queued, and
closed when their last query is disposed. Loaded snapshots remain available
immediately on remount while the reconnected stream refreshes missed changes.
Explicit Supabase preloads are one-shot reads, deduplicated by normalized query,
and populate the cache used by subsequent subscriptions without opening a channel.
Strapi batches disjoint sections; Supabase shares identical queries and live table
readers, rather than combining unrelated SQL reads into an invented batch API.

## Scoped subscription and write shortcuts

`defc` makes `<sub`, `>reset`, and `>update` available locally. No `:state`
declaration is needed unless configuring component keys, defaults, or persistence.

```clojure
(defc <setting> []
  :let [*setting (<sub :comp [:opts :setting] {:initial false})]
  [:button {:on-click #(>update *setting not)} (str @*setting)])
```

`<sub` returns a reactive writable handle backed by a re-frame subscription.
`@*setting` always reads the value. The handle carries its resolved path in metadata,
so write helpers accept it directly without dereferencing it or requiring a tuple.
`(component/path-of *setting)` returns the resolved vector when needed.

| Scope | Expanded path |
| --- | --- |
| `:comp` (or `:component`) | `[:component "namespace" "component-name" optional-key ...path]` |
| `:module` | `[:module module-id ...path]` |
| `:page` | `[:page page-key ...path]` |
| `:global` (or `:shared`) | `[:state ...path]` |

Module identity comes from `:module` in the call options or component declaration,
then the component namespace's registered module, falling back to `:main`.
Outside a component, module subscriptions require `{:module :blog}` (for example).
Page identity defaults to the current pathname and query; `{:page page-key}` overrides it.

Component instance identity comes from Hiccup `^{:key ...}`, then `:state :key`;
without either, instances share state. Bindings in `:let` initialize once per
instance: use a React key to remount when logical identity changes. Calls in the
render body resolve current arguments instead. Captured handles and resolved paths
retain their routing context in callbacks after render has finished.

- `(>reset handle-or-path value)` queues replacement, like `reset!`.
- `(>update handle-or-path f & args)` queues an atomic update, like `swap!`.
- `(<sub :comp path {:initial value})` initializes only a missing leaf.
- Native `reset!` and `swap!` on a handle perform synchronous re-frame writes.
- Component `:state {:initial {...} :persist true}` restores/persists the whole
  component root; nested subscriptions share that registration.
- Other scopes opt in with `{:persist true}` on `<sub`, persisting only that path.

Updates compute from current app-db inside the event handler, so queued updates
compose correctly. Account guards prevent old private handles from mutating another
session. Preserve metadata when passing resolved paths to write helpers. Raw
absolute vectors beginning with `:component`, `:module`, `:page`, or `:state` are
also accepted, as are `[:global ...]` / `[:shared ...]`. Relative component vectors
and `[:comp ...]` require active component context; use a handle in callbacks.
Outside the macro, use `component/<sub`, `component/>reset`, and `component/>update`.

Explicit app-db deletions invalidate affected local snapshots immediately in
memory and queue the disk update. Nested state deletions keep the surviving
siblings; deleting a whole state subtree removes its snapshot. This also covers
`>update` with `dissoc` and deletions from other re-frame events. A deletion is
distinct from storing `nil` or `false`, both of which remain valid values. Pending
cold reads and later autosaves cannot resurrect the deleted snapshot. Unmounting
alone still preserves app-db and persisted data.

Page declarations use `defpage`, with the same arguments and options as `defc`.
It enables a layout container and always supplies an error boundary, including when an incoming feature list
tries to disable one. Page boundaries reset on a route path or query change;
ordinary component boundaries keep their existing explicit recovery behavior.

Use `(m/<> :user/avatar user)` for a lazy exported view, or
`(m/<> <avatar> user)` for a direct reference. A qualified keyword is equivalent
to `{:module :user :view :avatar}`; computed keywords work too. Keep the map form
when specifying options such as `:defer?`, `:<before>` or a custom loading view.
This macro yields a Hiccup vector,
not a component wrapping the target. A shared module subscription acquires the
code and initialization through events/effects. Once code is available, the
containing render uses the actual component descriptor directly; pending data
belongs to that component's `:depends` and loading/error views. SSR resolves the
same vector from its bundled module specs without browser acquisition. External
module assets belong to the page's `loader/<loaded-assets>` component.

Declare module data in `:depends`, rather than the legacy `:content` loading path.
Page specs declare the first-paint content; section/component declarations own
more specific content. They all use the same managed resource queue and caches.

Rendered skeletons can reuse an ordinary component instead of a second loading
layout. `(m/<> {:module :blog :view :post-content :skeleton true} sample-spec)`
renders its usual tree in an inert skeleton region. Direct references also accept
`:skeleton` in their spec argument. CSS masks text and adds a breathing gradient;
the original classes, typography and line wrapping determine the geometry.
Sample headings and paragraphs in `sample-spec` provide useful lengths when the
real content is unavailable. Components must still tolerate missing optional data.
For dependency-gated `defc` components, `:loading-prefab :rendered` uses the normal
render body as the fallback. `:loading-args` supplies a sample spec, argument
vector, or function of the current arguments. Declared dependency acquisition is
skipped in this skeleton subtree; the live component's managed binding owns it.

A streamed page's `:shell` may declare `:loading-view` as a module/view reference
and `:loading-args` as its sample spec. Blog demonstrates this using the normal
post-content component. Cold streamed SSR dissolves the shell over the completed
page; cache hits and history restoration do not introduce a skeleton. React
waits for the shell exit animation, then disposes the temporary shell before
hydration, so hydration itself never replays the effect. Data/bootstrap work runs
in parallel with that exit. In the SPA, rendered dependency fallbacks keep the
live tree and skeleton as siblings in a shared grid slot. The existing presence
feature retains the outgoing skeleton and unmounts it after its animation. Reduced motion switches directly to the completed page.

Native Hiccup attrs flatten nested `:props`, merging classes and styles, and
remove component feature options. Component vectors retain their original specs;
only native-element maps are normalized.

Browser-only loader declarations use `#?(:browser ... :default {})` in
`loader/code.cljc`. Browser builds enable `:reader-features #{:browser}`; SSR enables
`#{:ssr :node}`. Use custom branches before `:cljs`, since both browser and Node
are ClojureScript. Conditional reading requires `.cljc`, including code shared
only between browser and Node builds. Standalone Codox reads the default branch.
The main page marks its shared document/header/footer dependencies for startup
availability, while individual sections use `defc` dependency lifecycle handling.

Appearance and visibility belong to actual components, for example
`{:features [[:seen "zoom"]]}` on an image component. For a visibility-driven event, use `[:on-seen {:event [:load-more] :once? false}]`; it observes the existing
native root and owns cleanup. Feature configurations may also be functions of the
component's arguments, such as `[:on-seen (fn [id] {:event [:load id]})]`.
`on-seen` supports `:threshold`, `:root-margin`, `:delay-ms`, and defaults to firing
once. A native root is required for root-owned lifecycle features.

Documentation has two independent dependencies: `[:docs]` CMS content supplies
its heading/framing, while `docs.pages/document-dependency` describes Codox HTML
served by `/api/doc`. Both `:docs/get` and the document component acquire that
same backend resource; SSR/restored HTML in app-db avoids another request.

Module activation (`:scope/inited?`) can precede code arrival. Shadow's
`lazy/ready?` remains the authority for code availability, but is not a reactive
value. `:loader/module` acquires the shared code/init adapter and observes its
completion event. `load-code!` returns as soon as code arrives; `load!` also waits
for declared data and optional initialization. Both share one Shadow acquisition.

First-document motion distinguishes a streamed shell from direct SSR. The shell
owns the page-container entrance; completing it reveals article/comment motion
without replaying that container. Cached or already-completed SSR skips the
shell and animates the rendered container. Hydration releases entrance suppression
for subsequently loaded SPA components, while existing components retain their
first-render decision. Browser history restoration continues to skip entrances.
Comment expansion uses a shared two-level query plan before child mounting,
respecting explicit folds. Short branches use 140ms motion; wider branches extend
up to 280ms with bounded recursive stagger. Restored query caches and expansion
state are installed together, so the visible tree renders in its first commit.

### Local return documents

After hydration, the application debounces changes to renderable state and
generates a return snapshot from the ordinary components. The HTML and its EDN
app-db state form one versioned document, including query caches, component/page
state and comment windows/folds. Rendering has its own app-db and subscription
cache; it neither replaces live state nor acquires network data.

On an external link click, the worker saves the latest pair and arms its exact
URL in one operation. A matching document navigation can then receive that local
HTML immediately. Bootstrap installs its paired state and acquires the same
module code before hydration. Module initialization is not replayed for this
preparation. Live bindings resume after the hydration commit. Browser BFCache
continues to resume the existing document directly when available.

This worker handles document navigation only; SPA routing, API calls and asset
requests keep their existing paths. Ordinary document requests use network SSR;
only an external departure armed for that exact URL can select a local pair.
This distinction also overrides old restoration cookies on regular reloads.
Cached pairs
expire after 30 minutes, are limited to eight documents and 2 MiB per document,
and are consumed when served. Worker activation removes other build caches;
account changes clear cached documents. Authentication tokens, password fields,
transport state and diagnostics are excluded. The paired state preserves EDN
types, including vector keys used by component and comment state.
Matching older public localStorage envelopes are consumed on installation
without overwriting the paired state, and are persisted again on departure.

The worker is built by the Docker frontend stage and the ordinary uberjar
frontend task. `lein repl` watches `:return-worker` alongside the application;
`make return-worker` builds it separately. Service workers require a secure
browser context (HTTPS or localhost). If registration, storage or rendering is
unavailable, existing network navigation and state restoration remain in use.
`:page-return/status` exposes readiness/failure for inspection, and
`[:page-return/clear]` clears local documents.


## Shared input schemas

`defc` accepts `:spec-schema` for its instance spec and `:args-schema` for the full
positional/destructured/variadic argument vector. `:schema` extends declaration
options. Define these in CLJC and compose the common base contracts; see
[schemas and validation](schemas.md#component-inputs-and-schema-composition) for
examples, runtime configuration, and error behavior. Input checks add no wrappers.

## Page crossfade frame rate

Page navigation crossfades opacity for 250 ms with linear timing and no delayed
incoming fade. Native View Transitions and the swapper fallback share the CSS
navigation duration/easing variables. Their lifecycle adapters request
`Animation.frameRate = "highest"` only if the browser exposes that experimental
property, honoring reduced motion. This is a sampling-rate hint, not a change to
playback speed or a guarantee of 120 Hz. Unsupported or rejected requests leave
the ordinary CSS animation and completion cleanup intact. The request targets
only the two page layers; it does not increase the rate of unrelated animations.

### Page roots and crossfades

`defpage` enables an optional layout container outside its error boundary. Its
`div.page-root` stays mounted across loading, content and failure output; ordinary
component boundaries keep their fragment output. The outer site shell uses
`:container false`. A `defc` can opt in with `:container {:tag :section :props {...}}`
without enabling an error boundary. Enable `:container` in `:features` to accept
caller-supplied containers with a default `div`. A caller's `:container` may be a
settings map, a DOM Hiccup template or `[<layout> spec & forms]`. Custom layouts
accept `[spec & forms]`, forward `:props`/ref onto their DOM root, and place forms
inside their own layout. `:container/content` marks an inner slot in a template;
without a slot, content is appended. Declared default root attrs are preserved
when the caller changes the container. `false` disables the container.
Page-root context supplies navigation classes, refs, `inert` and `aria-hidden`
without changing page argument signatures. It is consumed at the owning page
root and cleared for descendants. Native React provider props use `:r>` with a
JavaScript props container so the Clojure attrs map remains a Clojure value.

The site page calls `use-page-crossfade` unconditionally. It returns keyed forms
in a fragment, with no swapper component/div. `#main` provides a single grid cell
for overlapping roots; both stay in flow while visible, and the outgoing root
retains its pre-navigation viewport offset through destination scrolling. After completion the outgoing root is
removed and the main/footer adopt the destination's natural height. Native View
Transitions retain pixels rather than outgoing DOM and reserve the measured
outgoing main height until their completion event, matching fallback layout.
The sticky footer has its own opaque, unanimated native snapshot layer above the
page; ordinary DOM stacking cannot place it above the native transition overlay.
Only the new footer snapshot is shown, avoiding the browser default crossfade.
The crossfade timing remains
250 ms linear in both paths. Do not introduce permanent absolute page layout.

The main page also provides a readiness context. Mounted data/code lifecycles
register pending layout work and release it after their ready or terminal-error
DOM commit; intentionally deferred sections do not block. SPA Back retains its
saved scroll target until that work completes, layout-affecting images before the
saved viewport and fonts are ready, and consecutive animation-frame measurements
confirm both layout and the reachable offset. Finite animations of layout
properties also block completion; their finished promises wake measurement.
Controlled navigation/restoration scrolls do not toggle the directional header/footer UI. Reserved image dimensions/aspect
ratios permit restoration without waiting for lazy downloads. Resize, mutation,
asset and readiness signals wake retries. User input/new navigation cancels;
the 15-second bound releases failed/unreachable restoration, never marks it ready.

Module `:install` is a no-argument lifecycle hook for code-owned setup, such as
restoring a browser feature cache. The shared loader runs it once per successful
code acquisition, shares its Promise between callers, and publishes code readiness
only after completion. Failure remains retryable. A module with an installation
hook is not renderable until installation finishes. `:init` retains its existing
data/dependency activation and arguments. Node-specific adapters can leave
browser cache installation inert. Shared public/motion snapshots belong to the
storage runtime; blog query/display snapshots install with the blog module.
