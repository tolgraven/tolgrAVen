# Component destinations

Page declarations remain native Reitit trees on the server and in the browser.
`website.navigation.component-routes/mount` adds module-owned child routes under
a named page. It retains the page's exact URL as a terminal child and puts its
name in inherited `:page-route` data. The child keeps the host's page, module,
SSR, content and loading contracts while gaining its own Reitit route name and
typed path parameters. Named components with descendants remain addressable
through their own terminal child too.

The Home page mounts GitHub's portable `routes.cljc` fragment. Consequently,
`/github/commits/<sha>` renders Home and names a GitHub component destination.
Unknown trailing segments do not match. A commit SHA must be 7–40 hexadecimal
characters; normal browser/server route coercion validates it before activation.
Page identity and landing content use `:page-route`, so menu state and acquisition
still belong to Home. Page declaration namespaces do not import module views,
subscriptions or events.

A component route declares `:component-route` with its loader `:module`, exported
`:view`, and a pure `:action` function accepting the match's coerced parameters.
Its native browser controller dispatches `[:navigation/activate-component]`.
That event reads the accepted route after commit, activates the ordinary loader,
and awaits code/style readiness through the loader's normal deduplicated adapter.
The result event checks the current path before dispatching the module's action.
A late completion or error for an abandoned destination cannot activate it.

GitHub's action waits for its managed CMS repository settings through normal
component initialization. It puts the SHA in the existing filter and reads that
commit directly rather than paging from the latest thirty. Canonical and short
SHA detail caches share the result. Paginated and direct reads merge one list row
per SHA; the list does not retain file patches. Repository generations and pending
request sets reject obsolete responses and allow failed requests to retry.
The filter treats input literally, includes messages and SHAs, and can acquire
a typed SHA itself. Automatic pagination runs only while browsing without a filter.

After the component commits its requested data, it dispatches
`[:scroll/component-target element-id route-path]`. This guarded effect uses the
page transition's existing layout restoration owner, replacing the earlier page
anchor and retaining its settlement and user-cancellation behavior. Component
views read subscriptions and dispatch events; they do not read app-db directly
or implement a separate scrolling timer.
