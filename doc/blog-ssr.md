# Page SSR and re-frame 1.4.7

SSR is enabled by default. Configure it under `:ssr` in your `config.edn`
(`dev-config.edn` in local development):

```clojure
:ssr {:enabled true
      :render-workers 2
      :worker "target/ssr/site.js"
      :node-binary "node"}
```

Production config points `:worker` to `/app/ssr/site.js`; Docker bundles that build.
Raw `SSR_ENABLED`/`BLOG_SSR_ENABLED`/`SSR_WORKER` environment switches are no longer
read. Set `:enabled false` explicitly to disable SSR. The normal development REPL
watches both the browser and Node targets. Successful Node builds invalidate old
render cache entries and restart each pooled worker on its next lease. The Node
protocol has no development REPL injected into stdout.

The currently enabled page declarations cover `/`, `/about`, `/services`, `/hire`,
`/cv`, `/docs`, `/docs/codox/:doc`, `/blog`, `/blog/page/:nr`, and numeric-ID
permalinks such as `/blog/post/title-42`. Archive and tag pages still use the SPA.
Run `make ssr` for a standalone release build when no SSR watcher is running.

Each module owns a `pages.cljc` namespace exposing `spec`, a native Reitit
route tree containing its pages, names, exported view keys, and opt-in `:ssr`
policy. It can declare multiple pages, nested routes, and inherited route data.
These lightweight namespaces do not require their module's views, events, or
subscriptions. Browser controllers use reader conditionals; the same declarations
are readable by the Clojure backend. Module specs expose their local `pages/spec`.

The browser router and server page router compose those route trees directly
using Reitit. There is no global page registry or second list of page definitions.
To opt a supported page out, remove its `:ssr` entry. Adding a new page requires
an ordinary view and a public data plan; toggling SSR does not create another
component definition.

## Rendering and hydration

Both environments render `views/page.cljs`, the ordinary application shell.
The blog uses `blog/views.cljs`; the landing page uses `views/auto.cljs` and its
existing home/media components. There are no SSR-only copies of the header,
footer, post markup, Markdown configuration, pagination or landing sections.

Ring fetches the requested post bodies (three per listing page), lightweight
summaries for pagination/tags/adjacent links, public
author profiles in one filtered `site_users` request, bounded comment windows, and fresh CMS content. Subsequent
SPA comment reads use the ordinary filtered subscriptions.
All four landing URLs load the complete landing content because their existing
controllers scroll within that page. Landing SSR does not read Supabase.

A pool of persistent Node workers renders with Reagent `render-to-string`.
The renderer source is ClojureScript compiled by Shadow; Node supplies React’s
JavaScript runtime. The JVM owns source acquisition and cache coordination.
Each request initializes an isolated public app-db, renders using ordinary
subscriptions, then clears the subscription cache, app-db and restoration context
in `finally`. Browser effects are disabled during server rendering; accidental
browser HTTP construction throws instead of starting an untracked request.
Only required SSR modules enter the Node compilation graph; browser-only modules
such as Leaflet remain in their existing lazy bundles.

The browser installs the same content and scoped post/summary/comment caches, preloads
the visible shell modules, then hydrates the ordinary page. Public author data is
held stable through hydration. The comments section, the newest ten root comments per post and their immediate
replies render on the server. Roots use a stable timestamp/ID order and one extra
row to detect another page. Immediate replies are fetched in bulk; deeper bodies
stay unloaded, with a bulk IDs-only query providing reply counts for folded
placeholders. Comment reads are bounded to the selected posts;
folded content stays unmounted. Supabase connections and browser controllers
begin after commit. Existing DOM nodes remain in place. Main-module
viewport loading, media interactions, editing, search, user/settings panels,
Markdown and link previews use their existing implementations.

Restoration skips loading placeholders for ready resources and entrance/typing
animations. Genuinely missing resources retain the normal loading/error states.
CMS image IDs are prefixed: a bare `cljs` ID can hijack the compiler's global
namespace through the browser's named-element properties. Bootstrap JSON escapes
script terminators; server credentials never enter the snapshot.

Initialization failures retain server-rendered articles and display a retry
message. A Supabase/CMS/renderer failure on the server returns a visible retryable
503; missing posts return 404. Runtime comment failures use the HUD.

## Cache correctness and limits

The backend caches the rendered fragment together with its exact public snapshot,
keyed by path and query parameters. Every request still reads fresh CMS content and, for blog routes,
the required Supabase rows before reusing HTML. This deliberately saves rendering, not database reads: the
schema does not provide a reliable revision covering edits, author names,
deletions, and listing membership. A timestamp-only or blind path TTL would serve
stale pages. Listing pages follow the same comparison and cannot reuse HTML if
their contents have changed. Errors never validate or replace cached entries.

The cache retains at most 64 paths and excludes entries larger than two million
characters (HTML plus snapshot), with an eight-million-character total budget.
`:ssr :render-workers` sets a bounded process pool (default 2, range 1–4; restart the server to resize); each Node
process has a 128 MiB V8 heap limit, plus runtime overhead. Rendering within one
worker is synchronous and isolated, while different workers render concurrently.
A lease waits at most ten seconds and a render has its own ten-second deadline;
only a failing worker is restarted. Cache locks cover bookkeeping, never reads
or rendering. Identical concurrent path/query requests share one in-flight task,
including source acquisition; different paths proceed independently.

Undertow uses its three-argument async handler. The outer adapter runs the existing
middleware chain on Java 21 virtual threads, preserving Clojure dynamic bindings,
so blocking Ring middleware and HTTP clients do not occupy Undertow workers.
Independent graph nodes execute in concurrent waves, and CMS/configuration reads
run alongside Supabase data acquisition. Upstream work is capped at eight active
calls; the request and distinct-page queues are bounded at 64. Whole HTTP responses
are `no-store`: CSRF and request-specific layout data are never cached with public HTML.

## Shared data declarations

`supabase/query.cljc` defines the public table/field mappings, projections, filters,
ordering, limits, batching keys and reply-count relation. The JVM reader and browser
SDK adapter consume those same plans and the existing shared row-to-app-db contract.
`blog/data.cljc` declares the graph of summaries, selected posts, bounded roots,
immediate children and public authors. The module’s page spec carries that plan;
`ssr.clj` has no blog SQL/REST predicates or blog snapshot branch.

The pure `supabase/plan.cljc` evaluator accepts an adapter. On the JVM it uses
batched concurrent reads; `:store/plan` in the browser acquires ordinary managed
subscriptions. Blog route readiness and the visible components share the same
query constructors. Exact query results are serialized with the public snapshot
and installed into app-db before hydration. Public profile subscriptions batch
by ID on both sides, including missing profiles as completed empty results.


## Individual blog reads

Normal browser blog navigation now loads an index without body text. Visible
posts fetch their own bodies, and each comment thread filters on `parent_post`
and `parent_comment` at the database (`is.null` for roots). Adjacent-post links
read summaries. Archive previews still load each displayed post individually.

“Load more comments” increases the visible root window by ten. It reads the new
prefix plus one lookahead row, retaining the previous visible window until the
response arrives. This avoids offset gaps when new comments are inserted.
Expanding a deeper comment acquires its ordinary thread subscription; collapsing
releases that reader while retaining cached data and the existing exit transition.

Scoped readers coalesce identical subscriptions and next-tick invalidations.
Compatible sibling-thread reads for a post are combined into one `parent_comment
IN (...)` request, then distributed to their individual app-db query caches by one
result event. Reply counts likewise use one filtered IDs query per batch. Public
profile readers share filtered ID batches. No component instance starts
its own profile request.
One lightweight Realtime invalidation channel per active table refreshes only
active filtered queries. Delete events can contain only a primary key, so they
invalidate all active queries for that table. Channels/retries are released
when readers unmount; app-db keeps the data. HTTP reads do not depend on a working
WebSocket. Reconnection refreshes snapshots, and responses from disposed readers
or replaced clients cannot overwrite current content.

## Using the re-frame upgrade

re-frame is now 1.4.7. Application code uses the existing `tolgraven.react`
shim for re-frame operations. Its macros forward the original call site to
`re-frame.core-instrumented`, preserving file/line metadata when `goog.DEBUG`
is true. Release builds compile to the regular API without metadata allocation.
First-class function values remain available through the same shim. See
`doc/re-frame-pair.md` for live inspection with the installed skill.

Tests use `re-frame.tooling/live-query-vs` to check subscription disposal instead
of depending on the internal cache representation. `dispatch-and-settle` verifies
event completion without a guessed sleep, and `dispatch-sync-with` exercises the
real blog edit handler with dispatch-local effect overrides. The latter supports
safe REPL experiments without globally replacing effects:

```clojure
(require '[re-frame.core-instrumented :as rf]
         '[re-frame.tooling :as tooling])
(tooling/live-query-vs)
(rf/dispatch-sync-with [:blog/edit-post {:id 42 :title "Preview"}]
                       {:dispatch-n prn})
```

`dispatch-and-settle` covers synchronous event cascades, not completion of HTTP
requests, lazy module imports, React commits, or localStorage queues. Those keep
their own promises and lifecycle checks. Do not enable alpha `:forever`
subscriptions for mounted components: disposal is part of this architecture.

The dependency change requires `make docker-prefab`; the published manifest-hash
prefab includes 1.4.7, and both the prefab and fallback stages use `project.clj`.

## Verification

```sh
make ssr
lein test tolgraven.blog-ssr-test tolgraven.supabase-shape-test
lein with-profile +test run -m shadow.cljs.devtools.cli compile app-test
python3 scripts/test-blog-ssr.py
python3 scripts/serve-browser-tests.py --port 4002
lein with-profile prod run -m shadow.cljs.devtools.cli release app
```

Open the browser suite at port 4002 after generating the SSR fixture. It checks
real Node-rendered HTML against client hydration, DOM identity, absence of
loading/mount transitions, and comment node preservation through hydration. Landing tests preserve the hero, image,
story, gallery and main nodes while enhancing the shell, and exercise the contact
action. Regression tests cover exported Reagent 2 defc Vars, error-boundary reset
on route changes, and asynchronous per-post updates. The fixture generator also
checks alternating landing/blog request isolation and Markdown escaping.

References: [Reagent server rendering](https://reagent-project.github.io/docs/master/reagent.dom.server.html),
[Reagent client hydration](https://reagent-project.github.io/docs/master/reagent.dom.client.html),
[React hydration requirements](https://react.dev/reference/react-dom/client/hydrateRoot),
[Shadow Node targets](https://shadow-cljs.github.io/docs/UsersGuide.html#target-node-library),
[re-frame 2026 releases](https://day8.github.io/re-frame/releases/2026/).

Cached comments and display state restore through the shared component-storage
envelope before mounting. Only public scoped post/comment/profile queries are
eligible; fresh SSR entries take precedence. The requested comment-window size, root expansion, thread folding and
motion identities survive return navigation. SSR comments are present in the initial markup and matching client query caches;
hydration does not gate the comments section on an interactive flag. Previously
shown posts/comment frames use stable motion keys; explicitly folded threads
remain unmounted.

Reading a valid snapshot consumes its disk entry on the shared write tick. Its
in-memory copy remains available to other consumers without further disk reads.
Unchanged debounce flushes do not recreate consumed entries; navigation/pagehide
publishes current tracked values again. Explicit app-db deletions still update
or remove the corresponding snapshots. Expired envelopes are compacted on read.
Legacy blog display settings migrate once into the same queue, and legacy
settings reads remove the consumed path and empty parents.

The user panel uses `defc` presence and exit handling. Closing sets app-db to
closed immediately. The component retains its last inputs during its CSS exit,
then removes the original wrapper; reopening cancels removal. Event handlers
contain no delayed close/open transitions.

Service failures use sticky, accessible HUD alerts with retry controls, without
duplicate banners above page content. They remain until dismissed or recovered.
When required content is unavailable, the affected component uses the same
accessible fallback as render boundaries, module loading and page initialization.
Retry belongs to the failed loader; usable cached content stays visible during
background failures.
Initial WebSocket negotiation is silent, including transient socket replacement
during authentication. An explicit timeout or failure to join within ten seconds
reports an error; losing an established connection reports immediately. Successful
reconnection clears the notice, and intentionally disposing a reader never raises
a disconnection error. HTTP content remains usable while live updates reconnect.

Client navigation does not request SSR output. The loader accepts a module's
declarative `:route-depends`; blog readiness acquires the same re-frame
subscriptions as the views for summaries, visible post bodies and root comment
threads before committing navigation. A generic subscription adapter waits for
readiness and releases its own reaction, retaining app-db content. Main-page CMS
requirements, including the hero and landing sections, likewise resolve as one
batched dependency before navigation. CSS View Transitions capture the outgoing
page while React mounts only the incoming page. The lifecycle adapter waits for
queued controller events and the React commit, restores the scroll position
instantly, and decodes visible images before releasing the new capture. No
outgoing component can start reading the new route during its fade. First loads,
query changes, and reduced motion skip the transition; unsupported browsers
commit normally. Obsolete navigation callbacks cannot scroll a newer page. Supabase settings initialization runs in the
background without the global loading spinner.

The shell uses one Open Sans stylesheet, with the existing v29 Latin font served
locally and preloaded. The fallback font uses matching vertical metrics so the
landing title keeps its line-box height before the font arrives. A restored hero
retains its settled decoration while its page fades out.

## Saved returns without personalized server renders

On pagehide/hidden visibility, the shared storage adapter writes one envelope per
owner. After a successful public-content save (including ordinary batched saves, before
a reload can begin) it sets a 30-minute `tolgraven-return`
cookie containing up to 16 recently saved pathnames (bounded below 3 KB). A matching document request receives
a client-rendered shell marked `data-restore`, without Supabase/CMS snapshot reads
or an SSR worker call. The client restores content and display state before its
first render, so an expanded 20-comment window does not hydrate over a default
10-comment server window. No view state is replicated to the server.

BFCache history returns retain the existing DOM directly. Ordinary SPA navigation
never requests SSR. Fresh visits and paths not matching the saved-return hint
still use public SSR. A non-BFCache return waits for the cached JavaScript and local
restore before displaying content; it deliberately does not show an incorrect
shorter SSR page. The cookie is only a hint: expired, missing, corrupt or unavailable
storage falls back to the normal subscription/module loading and HUD error paths.
Existing cached content remains visible during Supabase background refresh. Failed
storage writes clear the hint. The cookie retains recent paths across document returns and merges paths from
other tabs when saving; older paths can eventually be evicted and receive normal SSR. In that case the fresh public snapshot owns
the initial fold/window defaults, ensuring cached display settings cannot cause a
hydration mismatch. No schema or deployment configuration changes
are required for this behavior.

Initial route resolution retains the browser's scroll position through hydration.
The navigation handler reads the actual injected `:id` counter; an empty initial
scroll snapshot does not trigger scroll-to-main. Development scroll save/restore
runs only when a root is already mounted. Frame-by-frame first-load verification
on `/blog/post/A-new-era-28` retained a 79.195px header at y=35.195px and main content
at y=193.820px through hydration, with scrollY=0. The prior startup scroll-to-main
moved both by 1.5px. Normal SPA navigation and saved browser-back positions retain
their existing scroll behavior.
