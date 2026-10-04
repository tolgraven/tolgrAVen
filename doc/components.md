# Composable components with `defc`

Require `[tolgraven.macros :refer-macros [defc]]` and use components in Hiccup.
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

The macro accepts name metadata, an optional docstring and attribute map, one
argument vector (including destructuring and variadic arguments), and optional
`:let [bindings]` for per-instance state. Existing form-2 bodies also work.
Bindings survive argument updates; rendering receives current arguments.

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
| `:props` | Merge first-argument `:props` / `:classes` into a native DOM root; preserve both refs | No |
| `:data` | Enable dependencies supplied in the first argument's `:depends` | No |
| `:error-boundary` | Isolate render/initialization/descendant lifecycle failures; log and offer retry | Yes, a boundary around the function body |
| `:lifecycle` | First-argument `:init` / `:exit` through layout effects | No |
| `:links` | Existing link-preview discovery, update and cleanup | No |
| `:appear` | Enter animation merged onto the native root | No |
| `:seen` | Viewport visibility merged onto the native root | No |
| `:exit` | Exit animation when removed by a presence-enabled parent | No |
| `:presence` | Retain keyed direct children until exit completion | No |

Definition-level `:depends` automatically enables data handling. The old `:spec false`
is retained as a compatibility override, but is unnecessary in normal definitions.
The macro recognizes a first argument named `spec`, `opts` or `options`, or a map
destructuring feature keys such as `props`, `classes`, `depends`, `appear`, `seen`
or `links`. Other arguments are domain data, even if their map happens to contain
keys named `:props` or `:depends`. No spec argument needs to be added when absent.
A feature may be `[id config]`; `[id false]` disables it.
Require the namespace that registers a feature (for links,
`tolgraven.link-preview.views`) before using it.

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
kept after exit. Existing `ui/seen`/`ui/appear` helpers remain available.

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
  [:article (get-in @rfdb/app-db [:articles id :title])])

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

Missing dependencies show `loading/<spinner>` by default. It has an accessible
status label but only the spinner is visible. A static Hiccup `:loading` value or
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
  [:article (get-in @rfdb/app-db [:profile :name])])
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
The server renderer must supply matching state/content before hydration. This
change does not implement the future full server page renderer.

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

Resource descriptors and the CLJC manifest are intended to be shared with SSR.
The adapters in this change are browser adapters; server-side resource execution
and generation of matching server markup/state remain the server renderer's job. A server renderer can resolve the
same manifest and hydrate app-db before rendering, letting the app-db/Strapi
readiness checks consume that data without an extra load. Keep credentials in
backend/client configuration, never in component descriptors.

## Error recovery and the blog migration

Only components selecting `:error-boundary` get a React boundary. It catches
initialization, rendering and descendant lifecycle failures, logs via `util/log`,
and shows **Attempt reload** with expandable diagnostics. Retry remounts the
failed body with current arguments; siblings retain state. The existing
`ui/safe` signature uses this same boundary.

React boundaries do not catch event-handler errors, rejected promises/timers or
server-rendering errors; handle those in their action/data layer. Data loading
has its own failure UI regardless of whether a React boundary is selected.

Blog labels, buttons and layout helpers use lean function components. Boundaries
surround recoverable posts, comments, forms and pages; pages declare their CMS
requirements. Existing public component names and argument signatures remain.
The unfinished `delete-comment` and `blog-intros-view` stubs remain untouched.

## Verification

```sh
lein with-profile +test run -m shadow.cljs.devtools.cli compile app-test
npm run build
python3 scripts/serve-browser-tests.py --port 4002
```

Open `http://127.0.0.1:4002/` for the browser suite. The interactive fixture at
`http://127.0.0.1:4002/fixtures/defc.html` uses real blog views and local data.
Increment the sibling counter, break the post, repair its data, then use Attempt
reload: the counter retains its value. Click **Preload URL content** before
**Show data component**: the resource becomes ready with zero bodies created,
then the component consumes it when shown. This fixture makes no remote writes.

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
components use the same queue, including `tolgraven.main.module/spec` and its
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

`defc` recognizes `<csub`, `>creset`, and `>cupdate`, makes these helper names
available locally, and enables only the instance-state support they need. No
`:state` declaration is required unless configuring keys, defaults, or persistence.

```clojure
(defc <setting> []
  :let [[*setting path] (<csub [:opts :setting] {:initial false})]
  [:button {:on-click #(>cupdate path not)} (str @*setting)])
```

`<csub` returns `[re-frame-subscription absolute-path]`. Use `[*setting]` when only
reading; ordinary destructuring `[*setting & [path]]` works too. Bindings in
`:let` are initialized once per component instance, so use a React key to remount
when changing that instance's logical identity. Helper calls in the render body
resolve against current arguments instead.

The relative path expands to
`[:component "your.namespace" "<setting>" instance-key :opts :setting]`.
The key comes from Hiccup `^{:key ...}`, then `:state :key`; it is omitted when
neither is present, so unkeyed instances share their component state.
Page/account scopes add their isolation segments before the relative path. The
returned path captures this context and is safe to use later from event callbacks.
Keep the returned vector (including its metadata) when passing it to write helpers.

- `(>creset path value)` queues replacement, like `reset!`.
- `(>cupdate path f & args)` queues an atomic update, like `swap!`.
- `(<csub relative-path {:initial value})` initializes only a missing leaf.
- `:state {:initial {...} :persist true}` provides/restores the whole instance;
  nested subscriptions share its single persistence registration.

Updates compute from the current app-db value inside the event handler, so several
updates queued in one tick compose correctly. Read/write account guards prevent
old account callbacks from mutating another session. Outside the macro the same
helpers are available as `component/<csub`, `component/>creset`, and
`component/>cupdate`; relative paths require an active component context, while
returned absolute paths work in callbacks without it.

Explicit app-db deletions invalidate affected local snapshots immediately in
memory and queue the disk update. Nested state deletions keep the surviving
siblings; deleting a whole state subtree removes its snapshot. This also covers
`>cupdate` with `dissoc` and deletions from other re-frame events. A deletion is
distinct from storing `nil` or `false`, both of which remain valid values. Pending
cold reads and later autosaves cannot resurrect the deleted snapshot. Unmounting
alone still preserves app-db and persisted data.
