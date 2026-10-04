# Page SSR and re-frame 1.4.7

Set `SSR_ENABLED=true` to enable server rendering for `/`, `/about`, `/services`,
`/hire`, `/blog`, `/blog/page/:nr`, and numeric-ID permalinks such as
`/blog/post/title-42`. `BLOG_SSR_ENABLED` remains a compatibility fallback.
`SSR_WORKER` defaults to `target/ssr/site.js` locally and `/app/ssr/site.js` in
Docker (`BLOG_SSR_WORKER` is also accepted). Run `make ssr` for the local worker.
Other routes, including CV, docs, archive, and tags, currently render in the
browser. No Coolify flag is changed by this branch.

The generic `ssr` Shadow target renders a shared page shell with route-specific
bodies. `ssr/contract.cljc` declares the landing layout and its CMS dependencies;
the ordinary client layout and main module reference the same declaration.
All four landing URLs use the complete landing bundle because their client
routes scroll within that page. They do not read Supabase during server rendering.

## Rendering and hydration

Ring fetches only the requested public posts (three per listing page, plus one
lookahead), relevant author names, and fresh CMS shell content. It sends that
allowlisted snapshot to a persistent Node renderer compiled with Shadow. The
renderer calls Reagent `render-to-string`. The browser uses the same pure
`ssr/views.cljs` shell and blog/landing body views and exact snapshot with `hydrate-root`.

No server request uses the browser's global re-frame app-db, subscription cache,
effects, or Supabase auth session. Dates are formatted on the backend in UTC.
The snapshot contains no clock-dependent output or random component IDs.
CMS image IDs are prefixed: a bare server-rendered ID such as `cljs` creates a
named window property and can hijack Closure namespace initialization.
Markdown uses the same ReactMarkdown/GFM configuration on both sides, with raw
HTML disabled and the default safe URL transform. Bootstrap JSON escapes script
terminators. Server credentials never enter that JSON.

Comments are enabled by a client effect after hydration and use the existing
blog comment components. Post bodies remain in their original DOM nodes.
The server snapshot also seeds the exact scoped post cache. A partial snapshot
never marks a complete table loaded. Navigation to a different blog page discards the initial SSR
root and resumes the normal application; it cannot reuse that snapshot for a
different post or a later navigation back.

The first browser render exactly matches the public server shell. After commit,
the existing interactive header, footer, settings, user, search and notification
components replace shell slots while main content retains its DOM nodes.
The landing hero, services, story, gallery and media are in server HTML. Hero
entrances and typing animations are skipped. Services and contact actions attach
during hydration. Video backgrounds use native controls and defer downloading.
External modules (Strava, SoundCloud, Instagram, GitHub, GPT and chat) mount as
viewport islands within 800px of the viewport; observers disconnect on entry or
unmount. Their reserved space prevents an empty placeholder collapsing to zero.
Navigating between landing aliases retains the hydrated content; leaving it
resumes the normal application. Post editing and link previews remain in normal
blog views; archive/tag SSR is still future work.

Initialization failures retain server-rendered articles and display a retry
message. A Supabase/CMS/renderer failure on the server returns a visible retryable
503; missing posts return 404. Runtime comment failures use the webpage notices.

## Cache correctness and limits

The backend caches the rendered fragment together with its exact public snapshot,
keyed by path. Every request still reads fresh CMS content and, for blog routes,
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

re-frame is now 1.4.7. Blog views/events/subscriptions and the component state
helpers use `re-frame.core-instrumented`, preserving call-site file/line data in
development without production metadata overhead. Function-valued APIs elsewhere
continue to use `re-frame.core`.

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
loading/mount transitions, and post-hydration comment insertion. Landing tests preserve the hero, image,
story, gallery and main nodes while enhancing the shell, and exercise the contact
action. Regression tests cover exported Reagent 2 defc Vars, error-boundary reset
on route changes, and asynchronous per-post updates. The fixture generator also
checks alternating landing/blog request isolation and Markdown escaping.

References: [Reagent server rendering](https://reagent-project.github.io/docs/master/reagent.dom.server.html),
[Reagent client hydration](https://reagent-project.github.io/docs/master/reagent.dom.client.html),
[React hydration requirements](https://react.dev/reference/react-dom/client/hydrateRoot),
[Shadow Node targets](https://shadow-cljs.github.io/docs/UsersGuide.html#target-node-library),
[re-frame 2026 releases](https://day8.github.io/re-frame/releases/2026/).
