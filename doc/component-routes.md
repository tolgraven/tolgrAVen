# Component destinations

Page declarations remain native Reitit trees on the server and in the browser.
`website.navigation.component-routes/mount` adds module-owned child routes under
a named page. It retains the page's exact URL as a terminal child and puts its
name in inherited `:page-route` data. The child keeps the host's page, module,
SSR, content and loading contracts while gaining its own Reitit route name and
typed path parameters. Named components with descendants remain addressable
through their own terminal child too.
Mounted routes retain the host's selection keys. Changing a component tail on
the same typed page preserves scroll and header state, including numeric and
slug aliases of the same blog post.

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
It does not prepare the target view without its parent arguments; the mounted
host owns that view's managed dependencies and data.
The result event checks the current path before dispatching the module's action.
A late completion or error for an abandoned destination cannot activate it.

GitHub's action waits for its managed repository configuration through normal
component initialization. It puts the SHA in the existing filter and reads that
commit directly rather than paging from the latest thirty. Canonical and short
SHA detail caches share the result. Paginated and direct reads merge one list row
per SHA; the list does not retain file patches. Repository generations and pending
request sets reject obsolete responses and allow failed requests to retry.
Archive searches acquire cursor pages from the server, including older matching
commits. SHA input can acquire a commit directly even before the archive reaches
it; cached details remain immediately available across query changes. Only the
matching SHA appears for a SHA filter; list rows do not duplicate cached patches.

After the component commits its requested data, it dispatches
`[:scroll/component-target element-id route-path]`. This guarded effect uses the
page transition's existing layout restoration owner, replacing the earlier page
anchor and retaining its settlement and user-cancellation behavior. Component
anchors resolve the element's position again as ancestor rows, images and fonts
settle, then use a native smooth scroll, rather than holding the pixel position
measured before expansion. Reduced motion retains immediate positioning. Component
views read subscriptions and dispatch events; they do not read app-db directly
or implement a separate scrolling timer.
Accepted navigation owns the path-scoped focus policy: history returns keep their
saved scroll and fold state even if the component mounts after restoration begins.
Clicking the same permalink again records a new explicit target intent.

Blog post destinations mount `/comment/:comment-id` beneath the post route.
Their shared public plan reads the selected comment, its ancestors and the
needed reply branches, and adds a focused root even if it is outside the current
ten-comment window. Initial server state opens the ancestor paths; an accepted
browser intent does so once, then ordinary explicit folds remain authoritative.
An absent comment or a comment from a different post does not acquire that other
post's thread. The view offers a standalone link when it cannot show the target.

`/blog/comment/:comment-id` is a separate complete route. Its plan reads only the
selected subtree and a parent-post summary for the back link. It does not acquire
neighboring root comments or the full post body. The existing comment component
renders the selected comment as its visible hierarchy's root; its children use
the same fold and intrinsic-height lifecycle. Permalinks target the comment in
its post; “Open thread” opens the standalone route. Quoted comments do not claim
the real comment's DOM ID, so they cannot intercept target scrolling.

Cold network SSR routes can declare `:document-target`: either a constant ID or
`{:prefix "blog-comment-", :parameter :comment-id}` resolved from typed page
selection. The native document entrance waits for that mounted target and fonts,
positions against untransformed layout offsets while hidden, then starts the
existing entrance animation. The streamed skeleton is outside layout during
this measurement. Missing targets release after five seconds; a later mounted
target scrolls smoothly. Paired returns and native history keep their saved
position; hydration preserves the document's original animation clock.

Comment UI is isolated in the feature-conditional `blog/comment_views.cljs`.
Posts, comments and authoring share `blog/presentation.cljc`, while the page owns
the blog shell. Permalink and thread links share one compact action row with
authenticated Reply/Edit buttons. Owned hover/focus labels dismiss on Escape;
native URLs remain copyable. Text collapse controls require measured truncation.
