# Frontend architecture audit — 2026-10-04

Scope: production ClojureScript, shared page declarations, browser/SSR rendering,
resource loading, persistence and service adapters. Searches covered DOM writes,
JavaScript property writes, Promise construction/chaining, JSON conversion,
app-db access, event handlers and subscription ownership. Disabled experiments
and unfinished code were preserved.

## Corrected

- Debug controls derive main-element classes from subscriptions instead of
  toggling DOM classes from events and click handlers.
- Theme events unpack the coeffects map correctly instead of installing it as db.
- Popover measurements update local reactive state; React renders the scale style.
- Link containers render their discovery marker declaratively, including defc's
  merged feature. Cleanup no longer removes a React-owned attribute.
- Preview anchor selection is expressed by a React-owned stylesheet portal;
  generated Markdown links are measured/located without mutating their styles.
- Prefetch links are React-owned portal children, removed on completion, failure
  or controller unmount instead of manually appending/removing head nodes.
- Codox links are transformed before rendering. External/fragment URLs stay
  intact; no global document query or post-hydration href rewrite remains.
- Markdown code consumes the Clojure props supplied by reactify-component.
  The adapter has a stable component identity, and its props use ordinary maps
  and vectors instead of unnecessary JavaScript containers.
- CSS settings reads happen in a mount-triggered coeffect. The subscription reads
  app-db and the controls react to subsequent changes.
- Blog preloading no longer has an imperative preparation namespace, duplicated
  queries or nested Promise chains. A declared readiness subscription acquires
  the existing body/comment readers; pending and loaded-empty are distinct.
- The generic readiness adapter owns and releases its own reaction without
  disposing a subscription still used by a component. Startup route readiness
  uses that adapter too, rather than inspecting app-db directly.

- Supabase transport buffers publish snapshots through events. Public and private
  query subscriptions derive component values from app-db, with account/client
  generation checks rejecting stale private results.
- Strapi subscriptions enqueue the existing batched content-load event; network
  responses enter app-db through `:content/install` before readiness resolves.
- SSR seeds public profiles, posts, comments and display dates into the same
  app-db paths. Blog components use ordinary subscriptions, including authors;
  they no longer inspect server snapshots or own transport logic.
- Expired Instagram images no longer dispatch the removed per-post fetch event;
  the existing fallback remains. The module-owned server feed supplies post data.
- Manual Supabase reads and initialization execute in registered effects.
  Shared timer and measurement helpers keep those native interfaces out of blog
  rendering code. Missing permalinks seed completed empty reads before hydration.

## Necessary boundaries retained

Native Promise interop remains at Shadow lazy loading, Supabase's thenable SDK,
callback-to-resource adapters, load deduplication/timeouts, storage batching and
Web Animations completion. Introducing another async library or disguising these
with wrappers would not remove the interoperability requirement. Page-specific
or component render code should not build transport Promise chains.

JSON.parse with js->clj and JSON.stringify with clj->js are the standard boundary
conversions for embedded server JSON, the Node worker protocol and external SDK
payloads. Convert once at those boundaries; application code consumes Clojure
values. React refs, native events, FileList, URL, observers, media playback,
canvas drawing and scroll/focus APIs necessarily expose JavaScript properties.

The document shell (html, title and embedded bootstrap script) is outside the
React application root. Its theme/CSS-variable and bootstrap effects remain
explicit interop boundaries. Trusted generated Codox HTML is an opaque React
leaf, not markup whose individual descendants React manages.

## Remaining legacy surface

The unused `:darken/but-element`/`:darken/restore` effect path still invokes the
legacy generic DOM-class helpers in util.cljs. There are no current callers in
src/cljs. It is preserved as unfinished functionality, not a pattern for new UI.
The generic html attribute helper is currently used for the document-level theme.
Do not use these helpers on React-owned elements; migrate any reactivated
highlighting UI to component state before using that legacy path.

This pass does not claim every historical component has been ported to defc,
or that all third-party widgets are declarative internally. Leaflet and canvas
experiments remain isolated browser-library integrations.

## Validation for this pass

- Browser regression suite: 111 tests, 566 assertions, all passing. Includes real
  Node-rendered blog, landing, CV, docs and missing-permalink hydration; shared
  subscription disposal; streaming app-db updates; stale private-response rejection;
  restoration/explicit deletion; error recovery; and preview interactions.
- Backend suite: 66 tests, 389 assertions, all passing.
- Browser release, SSR release and the running development build: zero CLJS
  compiler warnings. The rrb-vector hook also applies to the production target.
- CSS build passes. Dependency prefab hash matches the published registry image.
- Live localhost checks cover blog content/avatars, permalink navigation and
  landing/blog SPA navigation. Runtime inspection confirms CMS sections and
  Supabase public/scoped/snapshot content in app-db.
- The browser's mail handler extension rewrites mailto links before hydration,
  producing an attribute-only React warning. The isolated hydration fixtures pass
  without recovery; this external DOM modification is not suppressed by the app.
