# Page rendering, hydration and navigation

SSR is enabled by default. Configure it under `:ssr` in your `config.edn`
(`config/local.dev.edn` in local development):

```clojure
:ssr {:enabled true
      :render-workers 2
      :worker "target/ssr/site.js"
      :node-binary "node"}
```

Production config points `:worker` to `/app/ssr/site.js`; Docker bundles that build.
Set `:enabled false` explicitly to disable SSR. The normal development REPL
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

Both environments render `components/page.cljs`, the ordinary application shell.
The blog uses `modules/blog/views.cljs`; the landing page uses `modules/home/sections.cljs` and its
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
subscriptions, then disposes its isolated subscriptions in `finally`. It does not reset a live application store. Browser effects are disabled during server rendering; accidental
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
keyed by path and query parameters. A successfully acquired snapshot stays fresh
for `:ssr :cache-ttl-ms` (default 3600000, one hour). Fresh hits avoid provider
reads and Node rendering. The browser hydrates the exact cached HTML/snapshot
pair before switching its managed bindings to live data, so server-rendered
content can be older than the live page. Set this to zero to revalidate on every
request. Edits, deletions, author changes and listing membership can remain in
the initial server snapshot until its next revalidation; browser bindings
retain their own refresh behavior.

Presentation query variants, such as open/closed side panels, reuse a fresh
public snapshot for the same path and page selection. Each variant still renders
and caches its own HTML with its exact query parameters. Reuse retains the
original successful data-read timestamp; creating another variant cannot extend
the one-hour interval. The page declaration selects data from path parameters,
and a changed selection or renderer build prevents reuse.

Expired entries read the complete public data plan again while the initial shell
streams. Reuse HTML only if the exact snapshot and renderer build match. An
unchanged snapshot renews freshness after its successful read; errors never
validate or replace cached entries. A renderer change immediately invalidates
freshness, independently of the interval.

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
`modules/blog/data.cljc` declares the graph of summaries, selected posts, bounded roots,
immediate children and public authors. The module’s page spec carries that plan;
`ssr.clj` has no blog SQL/REST predicates or blog snapshot branch.

The pure `supabase/plan.cljc` evaluator accepts an adapter. On the JVM it uses
batched concurrent reads; `:store/plan` in the browser acquires ordinary managed
subscriptions. Blog route readiness and the visible components share the same
query constructors. Exact query results are serialized with the public snapshot
and installed into app-db before hydration. Public profile subscriptions batch
by ID on both sides, including missing profiles as completed empty results.


## Individual blog reads

Feed and tag subscriptions request bounded full posts using shared Supabase
filters; bulk results seed individual post readers. Permalinks fetch their own
body when absent. The lightweight index supplies tags and adjacent-post links.
Comment queries filter by `parent_post` and `parent_comment` on the server.

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

## Navigation and history

SPA navigation commits a destination immediately and acquires its module and
managed data dependencies independently. Related blog routes keep their shared
heading mounted. CSS View Transitions fade between sections; the component
fallback supplies the transition in browsers without native support. Missing
content uses the component loading view and animates when ready.

SSR and the first client render use matching public content and view defaults.
For external returns, a service worker can serve local HTML paired with the exact
app-db content and view state used to render it. Install the pair before hydration;
expanded comment windows must not hydrate over a shorter default server tree.
See [local return documents](components.md#local-return-documents) for lifecycle,
limits and build requirements. BFCache resumes the live document directly.

Public localStorage restoration remains a fallback when the pair is unavailable.
Consumed snapshots are removed from disk and current state is saved on departure.
SPA Back restores cached content/view state together and uses the shared
layout-aware scroll adapter. Ordinary SSR and BFCache leave scroll restoration
to the browser.

Service failures use the shared HUD and component retry fallback. Cached content
remains visible during refresh failures. Initial WebSocket negotiation is quiet;
a failed connection or lost established connection is reported.

## Verification

See [testing](testing.md) for build commands and the real-browser checklist.
The Node fixture runner checks request isolation, escaping and ordinary page
rendering; mounted browser tests verify DOM identity across hydration. Neither
fixture tests nor offline content seeds establish live CMS/database integration.

References: [React hydration](https://react.dev/reference/react-dom/client/hydrateRoot),
[Reagent server rendering](https://reagent-project.github.io/docs/master/reagent.dom.server.html),
[Shadow Node targets](https://shadow-cljs.github.io/docs/UsersGuide.html#target-node-library).

## Progressive first response and startup content

Page specs can declare CMS sections which must be resident before HTTP startup:

```clojure
{:depends [{:source :strapi :keys [:blog] :availability :startup}]
 :shell {:heading [:blog :heading] :lines 8 :avatar? true}}
```

The server collects these declarations from the composed Reitit routes. Shared
`:document`, `:header`, `:common`, `:footer`, and `:post-footer` sections are also
startup dependencies. `content.service/startup-content` is a Mount prerequisite
of the HTTP server: its initial read must succeed, then it refreshes atomically
in the background every 30 seconds (`:ssr :shell-refresh-seconds`). A failed
refresh logs a warning and retains the last successful snapshot. Startup-loaded
sections are read from memory by both page snapshots and the CMS HTTP endpoint;
requests never revalidate those sections against Strapi synchronously.

Streaming is enabled by default (`:ssr {:streaming false}` disables it). On a
cold render-cache miss the page-data future starts immediately. A short isolated
React render produces the shell, using the ordinary header, footer, heading and
skeleton components. Its HTML is flushed before waiting for page data. The same
HTTP response then carries the complete ordinary page root and its public
hydration snapshot. CSS displays that root only after both have arrived; React unmounts the temporary loading root after its exit animation. Elements
paint fully visible with the same root-merged `defc` appearance classes on the
server and browser. The completed page does not replay page/child entrances;
the temporary shell owns its dissolve, respecting reduced motion. Hydration
neither replaces the selected page with a code-loading shell nor starts another
animation. Cached SSR responses paint immediately without a skeleton step.

This first stage streams two complete React renders, not suspended component
renders. It preserves request isolation in the existing renderer pool: no Node
worker is leased during the upstream wait. It does not yet progressively reveal
multiple independent component boundaries. There is no HTML fetch or DOM
replacement during SPA navigation. Fresh render-cache entries and browser
history restoration bypass the shell. Expired entries are validated against
a fresh snapshot (with startup CMS sections supplied from memory).

The flush-aware response body implements the installed Undertow adapter's
`RespondBody` protocol as well as Ring's streaming protocol. Plain InputStream
responses are buffered by this adapter until its output buffer fills. The custom
body flushes the shell explicitly. When the client accepts gzip, it compresses
directly into Undertow with sync flush, preserving progressive decompression;
it never passes through the generic middleware's buffered InputStream.
`X-Accel-Buffering: no` requests unbuffered delivery from compatible proxies.
Verify first-byte/chunk delivery through the deployed proxy before claiming
production streaming timings.

As with any early HTTP response, a failure discovered after the shell is sent
cannot change the already-sent HTTP status. It renders the retryable error page
instead. Set `:streaming false` on a route when final status must be determined
before sending headers; documentation routes do this to preserve missing-file
404 responses. The initial HTML title is the startup CMS document title; the
completed snapshot installs the page-specific title through the document effect
when the client initializes.


## Shared loading views and background link preloading

`defc` loading views apply to both server shells and ordinary SPA data waits.
The default is an empty root retaining the literal DOM tag and classes inferred
from the component's terminal Hiccup form. Inference never calls the render body
with missing data. For conditional/dynamic roots, declare `:loading-tag` and
`:loading-props` explicitly.

```clojure
(defc <name> {:depends name-data
              :loading-prefab :text}
  [spec]
  [:span.profile-name (:name spec)])

(defc <post> {:loading-tag :section.blog-post
              :loading-prefab :lines}
  [spec]
  (if (:ready? spec)
    [<post-body> spec]
    [<loading>]))
```

Available prefabs: `:text`/`:span`, `:heading`/`:h1`/`:h2`, `:avatar`, `:box`,
`:lines`. `:loading` accepts a custom Hiccup form or component, including Reagent
2 function descriptors. A local `<loading>` helper is injected when referenced
in a `defc` body; it accepts optional loading-option overrides. The existing
`component.loading/<span>`, `<h1>`, `<h2>`, `<avatar>`, `<box>` and `<lines>`
helpers remain available to compose full skeletons. Spinners remain explicit.

After the first client commit, `page-preload/<background>` schedules work during
idle time, discovers same-origin links in the current DOM, and loads their
modules and page dependencies with two concurrent destinations at most. It
observes new links after SPA navigation/content changes, deduplicates pending
work, remembers successful destinations for five minutes, and retries failed
speculation on a later scan. It does not recursively crawl unloaded pages.
The main module also declares `:preload-modules [:user :link-preview :search]`;
these common modules share the same bounded queue.
Buttons can advertise destinations with `data-preload-href`; an element can
opt out with `data-preload="false"`, or a page spec with `:preload false`.

Additional managed dependencies can be declared as `:preload-depends` in a page
spec (a vector or a function of the Reitit match). Blog pages acquire the existing
`:blog/page-ready?` subscription, which evaluates the shared plan and caches
posts, visible comments, and authors in app-db. Tag pages use the same scoped
query as their normal subscription. Temporary subscription ownership is released
on completion; app-db content stays cached. No SSR HTML request is made by this
background queue or by SPA navigation.

SPA route commits never wait for page data or image decoding.
A cold code load commits a destination shell immediately; an available
module renders its normal view and managed loading/error states immediately.
Module initialization continues independently. CSS page transitions finish their
capture after React/controller updates and scroll positioning, so slow images
cannot freeze the outgoing page. Link preloading prioritizes `rel="prev"` and
`rel="next"` links, then other links inside `main`, before shared navigation and
common modules. Blog post and pagination links use these relations; their shared
page-ready dependency acquires posts, bounded comment threads, and authors.

History restoration is distinct from ordinary navigation: popstate selects the
settled component state before committing the route, skips the native page
transition, and restores the saved scroll position before paint. External Back
uses the same restoration context (including BFCache). Leaving that restored
route enables normal SPA entry motion again; a previously visited content ID
alone no longer permanently disables its animation.

The SSR Shadow target is an eager `:node-script` program, with no browser module
loader. Browser hydration remains split, but the response head and HTTP `Link`
header preload the route's transitive modules from Shadow's generated manifest
(including shared user/link-preview dependencies) alongside the main bundle.
This downloads code in parallel without overriding Shadow's execution order.
For SSR responses, both preload forms and the deferred main script use
`fetchpriority="low"`, keeping first-paint CSS and fonts ahead of hydration code.
Responses without server-rendered content keep ordinary script priority.
Lazy chunks use `as="fetch"` and `crossorigin="anonymous"` to match Shadow's
XHR acquisition. The main bundle uses `as="script"` to match its deferred tag.

Related blog routes share a `:transition-key`: their healthy page boundary and
heading remain mounted while the destination post's managed subscription loads.
Only incoming content runs its appearance transition. Error boundary reset keys
clear failures on navigation without using the URL as a React remount key.

The initial streamed shell has its own page fade. It is flushed before awaiting
the snapshot; fresh cache hits never emit a shell root. Image refs also check for a
modern-format decode failure that occurred before hydration attached `on-error`,
so Safari can recover using the original JPEG/PNG. Search defaults to closed
until explicitly opened, even when its module is eagerly loaded.

Document titles belong to module-local page specifications. A route may supply
`:document-title`, a pure function of its public snapshot; the shared page helper
falls back to `[:content :document :title]`. The blog spec selects a single post's
title, while the renderer and layout have no blog-specific title rules. Layout
can also use `[:site :title]` configuration when no snapshot title is available.

The HTTP layout remains Hiccup data through response preparation. Streaming emits
the head and Hiccup shell, flushes, then renders the completed Hiccup body. Only
the transport adapter keeps the enclosing document tags open between chunks;
there is no HTML marker or serialized-page splitting.

## Runtime and source ownership

There is one generic SSR pipeline. Ring matches the module's native route and
acquires public content through JVM adapters, coordinates the cache/in-flight
work, and leases a persistent Node worker. Node renders the ordinary page
components and returns markup; it does not acquire database or CMS content.
The shell and complete page use the same renderer. Blog query plans are module
capabilities, not a second blog server or alternate set of page markup.

The browser's optional local-return renderer also calls the shared React renderer.
A saved return cookie alone never bypasses network SSR: reloads, direct visits
and audits still receive server content when the worker is unavailable or bypassed.
Only the worker's explicit `X-Page-Render: state` header selects browser state
restoration after a failed local return.

Its service worker serves an exact cached document/state pair; it does not create
another server rendering pipeline. Ordinary SPA navigation never requests SSR HTML.

Source extensions reflect actual consumers:

- Module `pages.cljc`, public query plans, data normalization and
  `ssr/contract_schema.cljc` are consumed by JVM and CLJS code.
- `ssr/contract.cljs`, `return_contract.cljs`, and `schema.cljs` belong to Node and
  browser execution. Their tests run in the browser suite. The JVM does not need
  a copy of hydration or browser persistence logic.
- `modules/blog/ssr.cljs` owns legacy display-row snapshot conversion and initial
  blog state. Node rendering and browser hydration use this once through the
  same snapshot adapter; exact `:app-db-edn` state takes precedence.
- `modules/main/layout.cljs` owns home section ordering and its module SSR metadata.
- Small `.cljc` build adapters may use Shadow's `:browser`, `:ssr` and `:dev`
  reader features even without JVM consumers. `.cljs` does not support reader
  conditionals; these adapters do not imply a second JVM renderer.

Run `bb ssr:fixtures` against the current Node build before browser tests. The
blog-named fixture remains a blog test input, while the command checks all page
kinds, request isolation, unsafe Markdown, missing permalinks and initial shells.
