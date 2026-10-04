# Page SSR and re-frame 1.4.7

Set `SSR_ENABLED=true` to enable server rendering for `/`, `/about`, `/services`,
`/hire`, `/cv`, `/docs`, `/docs/codox/:doc`, `/blog`, `/blog/page/:nr`, and numeric-ID permalinks such as
`/blog/post/title-42`. `BLOG_SSR_ENABLED` remains a compatibility fallback.
`SSR_WORKER` defaults to `target/ssr/site.js` locally and `/app/ssr/site.js` in
Docker (`BLOG_SSR_WORKER` is also accepted). Run `make ssr` for the local worker.
Other routes, including blog archive and tags, currently render in the browser. No Coolify flag is changed by this branch.

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

Ring fetches the requested post bodies (three per listing page, plus one
lookahead), lightweight summaries for pagination/tags/adjacent links, public
author profiles, selected posts' comment threads, and fresh CMS content. Subsequent
SPA comment reads use the ordinary filtered subscriptions.
All four landing URLs load the complete landing content because their existing
controllers scroll within that page. Landing SSR does not read Supabase.

A persistent Node worker renders synchronously with Reagent `render-to-string`.
Each request initializes an isolated public app-db, renders using ordinary
subscriptions, then clears the subscription cache, app-db and restoration context
in `finally`. Browser effects are disabled during server rendering; accidental
browser HTTP construction throws instead of starting an untracked request.
Only required SSR modules enter the Node compilation graph; browser-only modules
such as Leaflet remain in their existing lazy bundles.

The browser installs the same content and scoped post/summary/comment caches, preloads
the visible shell modules, then hydrates the ordinary page. Public author data is
held stable through hydration. The comments section, the first four root comments and their initially expanded
replies render on the server. Comment reads are bounded to the selected posts;
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
characters (HTML plus snapshot), with an eight-million-character total budget. Rendering is serialized in a single Node worker,
with a ten-second deadline and 128 MiB V8 heap cap. The worker restarts after
failure. A cache miss is single-flight under the cache lock. A slow cache miss
can delay other misses; add a bounded worker pool only if measured traffic needs
it. Whole HTTP responses are `no-store`: CSRF and request-specific layout data
are never cached with public HTML.

## Individual blog reads

Normal browser blog navigation now loads an index without body text. Visible
posts fetch their own bodies, and each comment thread filters on `parent_post`
and `parent_comment` at the database (`is.null` for roots). Adjacent-post links
read summaries. Archive previews still load each displayed post individually.

Scoped readers coalesce identical subscriptions and next-tick invalidations.
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
eligible; fresh SSR entries take precedence. Root expansion, thread folding and
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

Service failures use the HUD, without duplicate banners above page content.
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
requirements likewise resolve before the page swap. The keyed swapper retains
the outgoing page instance for its fade, and stale completion events cannot
finish a different transition. Supabase settings initialization runs in the
background without the global loading spinner.

The shell uses one Open Sans stylesheet, with the existing v29 Latin font served
locally and preloaded. The fallback font uses matching vertical metrics so the
landing title keeps its line-box height before the font arrives. A restored hero
retains its settled decoration while its page fades out.
