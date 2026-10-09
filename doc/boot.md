# Browser boot ownership

`core/init` installs app-db and dispatches `:boot/document` before restoring disk
or SSR state. This registers named document listeners through `listener.cljs`:
cross-tab legacy storage, visibility, pagehide, BFCache pageshow and window load.
Already-loaded documents publish `:booted :load` immediately. Namespace imports
only declare adapters/events; they do not install these listeners.

Pagehide captures scroll/history, flushes component snapshots, queues and drains
the legacy scroll write, then releases native history ownership. Visibility uses
the same save operation when the document becomes hidden. This ordering preserves
the exact return state even when browsers freeze timers.

The committed root mounts `boot/<lifecycle>`. Its effect dispatches
`:boot/interactive`: initialize breakpoint subscriptions and an owned resize
listener, restore preferences/forms, register scroll/popstate listeners and
publish site readiness. Scroll callback registration precedes the scroll listener.
The initial site setup no longer relies on a guessed post-render timer.

The same host owns route prefetch, local-return capture and optional script
acquisition. Their shared `after-page!` boundary waits for hydration, pending page
bindings, window load, two paint frames and idle time. Local worker registration
also uses this boundary; an explicit external return may still request a save.

Listener registrations have an ID and owner. Re-registering an ID removes the old
native listener before adding its replacement. `:boot/stop` removes document/site
listeners and the scroll callback registry; unmount also cancels the background
hosts' observers, timers and pending acquisitions. Component-owned focus,
measurement and animation listeners remain in their own ref/effect adapters.

The `:coercion` module shares the after-page gate for network SSR. Its engine and
Reitit adapter are installed once per document. Initial typed parameters come from
the server pair; pending disk reads cannot overwrite that paired state. Client-only
startup/local returns acquire the engine before using persisted state, and early
SPA navigation acquires it before route controllers. See [schemas.md](schemas.md).
