# Deployment and public activity panels

The GitHub module shows the running container's deployment identity above its
commit browser, then the configured user's public GitHub contribution calendar.
The separate `:codex-profile` module follows it in the landing layout.

Commit category and subcategory labels share a right-hand column beside the
message. List previews show the parsed subject (up to three lines); hovering
shows the complete original subject and body. Squash bodies and trailers remain
in the expanded message rather than spilling into the feed preview.

Pressing a commit or its View diff button opens its inline unified patch. Each
hunk resets independent old/new source numbers; context advances both, additions
advance only new, deletions only old, and newline notes advance neither. Source
syntax uses the existing lazy formatter, with stable gutters during acquisition.
Changed files acquire sequential metadata pages through GitHub's bounded API;
partial acquisition remains explicit until complete. Files without a text patch
expose a GitHub link rather than a fabricated diff.
The full message body renders safe Markdown with author, timestamps, SHA, parents,
file status and change counts beside the patches. Permalinks select this same
surface without changing the feed's query; component navigation lands at GitHub.
Every detail intent captures the current list offset before selecting content;
Back to commits restores it. GitHub's immediate component-target positioning
tracks layout without waiting for unrelated lazy images elsewhere on Home. When enabled, the full
before/after Neovim workspace remains an explicit link.

A narrow contribution calendar starts at its right edge to show the latest week.
The native scroll container owns overflow; days remain chronological left to right,
with the newest day as the initial tab stop. Home/End still select first/last day. Label clicks keep their category search.

## CMS ownership

Explicit server `:github-user` and `:github-repo` settings select the public
repository (or `GITHUB_USER` / `GITHUB_REPO` through cprop). Both are required
together. The module declares a managed URL dependency on `/api/github/settings`,
installing its checked `{:settings ...}` response at `[:module :github :configuration]`.
The `:github/get-from` subscription and archive use the same server selection;
no token or unrelated configuration is exposed. Missing settings return
`{:settings nil}` and show the configuration prompt. Invalid settings fail only
this module, without blocking shell startup or other content.

The retained CMS `github-settings` contract also defines `user` and `repo`. The
currently deployed CMS does not publish this section, so the module declares no
CMS content dependency. After publication a build may restore that declaration;
the settings adapter then reads it when neither explicit key is supplied. A CMS
failure never silently falls back to seed content. GitHub provider responses remain in
`:content :github`, separate from editor content. Repository changes reset that
provider cache and advance a request generation, rejecting late responses even
after an A/B/A switch. Pending reads are deduplicated; failed initial reads can
retry on remount without resetting successfully loaded pages. Calendars are
installed and subscribed to by username, so a cached A/B/A switch retains the
correct user's counts.

The retained `codex-profile` contract defines `displayName`, `profileUrl`, `bio`
and optional `days`. This section is also unpublished by the current CMS, so its
module declares no content dependency and absent activity remains unavailable. The public contract uses `:display-name`, `:profile-url`, `:bio`
and `:days`. A day has ISO `date`, nonnegative integer `count`, and intensity
`level` from 0 through 4. Supply chronological, consecutive days, at most 371.
Absent days means unavailable activity; an explicit empty vector means a supplied
empty calendar. The unauthenticated profile at `https://chatgpt.com/u/tolgraven`
currently requires login to view its full content. The site does not infer usage
from GitHub commits or collect private ChatGPT conversations.

These single types and their public adapters require the companion
`tolgrAVen-strapi` change before their module content dependencies can be enabled.
Until then, bootstrap and module acquisition omit both keys. Published sections
still report CMS failures through the usual retry flow; configured CMS failures
do not fall back to seed values. See [Content dependencies](strapi-content.md).

## Deployment identity

`GET /api/deployment` returns only `commit`, `deployed-at` and `commit-url`, with
`Cache-Control: no-store`. The Docker runtime captures the commit and a UTC
first-start timestamp in `/app/deployment.json`. It retains that file across
restarts of the same container. Replacing the container records a new timestamp.
This timestamp represents the container's first application start, not the build
start or the hosting platform's later health-check completion.

CapRover supplies `CAPROVER_GIT_COMMIT_SHA` during Docker build; the final stage
bakes it in `/app/revision`. Coolify exposes `SOURCE_COMMIT` at runtime by default,
and optionally as a build argument. Local `bb docker build` supplies `VCS_REF`.
The baked image revision takes priority over runtime checkout metadata, so a
prebuilt image cannot be mislabeled with Coolify's current repository head.
The baked input defaults to `/app/revision`, independently of `DEPLOY_FILE`.
`DEPLOY_REVISION_FILE` may select an input path for a nonstandard image layout.
Set `DEPLOY_REPOSITORY` when building an image for another repository.

Operators can supply `DEPLOY_COMMIT`, `DEPLOYED_AT`, `DEPLOY_REPOSITORY` and
`DEPLOY_FILE` through existing cprop runtime settings. `DEPLOYED_AT` is an ISO UTC
instant supplied by a deployment hook when the platform's exact timestamp is
needed. Unknown hashes/timestamps remain unavailable rather than displaying the
current GitHub head or server time. A development process without deployment
metadata displays unavailable values.

## GitHub calendar

The module-owned `GET /api/github/activity?user=…` reads only
`https://github.com/users/<validated-user>/contributions`. It parses date/count/
level fields from GitHub's public calendar, not the commit browser's paginated
subset. Redirects are disabled, response bodies and timeouts are bounded, four
requests may run concurrently, and shared in-flight results use the existing
bounded cache. Successful reads last fifteen minutes; failures last one minute.
Provider errors use the managed component dependency retry UI.

Hovering or focusing a calendar day mounts a compact viewport-contained popup
with the original daily count/date. Nonempty GitHub days acquire their typed
breakdown only then, through the shared managed URL dependency. Commits, issues,
pull requests, reviews, new repositories and restricted contribution counts stay
separate. Zero days and the CMS-owned Codex calendar do not request GitHub data.
Escape, blur, pointer exit, scrolling and resize close and unmount the popup.

`GET /api/github/activity/day?user=…&date=YYYY-MM-DD` reads GitHub's public
single-day profile timeline without authentication. It counts only category
headings, including featured contributions and their disjoint “other” rollups,
and verifies the date range and sum against that response's calendar count.
Unknown headings, truncated/missing data or a count mismatch report unavailable
instead of inventing zero counts. This depends on GitHub's public HTML structure;
a provider markup change can require an adapter update. No repository names,
issue bodies or private content are returned; only the public anonymous count
for private contributions is retained. Date and user validation precede
acquisition; requests, body size, concurrency and caches are bounded.

## Complete repository archive

The selected GitHub module declares `github_repositories` and `github_commits`
through its ordinary storage catalog. Apply the module's SQL using the existing
Supabase provisioner before enabling its service. `:server :start` returns a
cleanup owned by the mounted module-services lifecycle. With server Supabase
settings, it starts asynchronously; `:github {:sync-enabled false}` disables it.
Without explicit or published CMS repository settings the automatic worker has no target and
performs no archive/content acquisition. Optional module-service startup failures
are logged locally and do not stop the shared HTTP server or healthy siblings.
When configured, it uses the shared selection, traversing every current public branch and tag's
reachable history, not only commits attributed to the configured owner. Removed
branch commits already archived remain searchable. Unreachable/deleted GitHub
objects cannot be discovered through the branches/tags APIs.

Each branch head is pinned to an immutable SHA while paging 100 commits at a
time. Unchanged completed heads skip traversal. Later heads use fully paginated
`BASE..HEAD` comparisons against an already archived immutable head, preserving
all new ancestry without re-reading shared history. Unrelated/unavailable bases
or oversized comparison diffs restart a complete pinned-head walk. Provider
visibility is checked both before acquisition and before publishing its rows. One
repository lease works across app instances, and one transaction writes the
commit page and its next checkpoint. A crash or failed page is retried without
skipping data or creating duplicates. The worker performs at most eight steps
per minute, checks again after fifteen minutes when caught up, and respects
GitHub rate-limit reset times. No token is required for public history. GitHub's
REST limit is 60 requests/hour per IP without authentication; an optional
server-only `GITHUB_TOKEN` (`:github-token`, or nested `:github :token`) normally
raises that to 5,000/hour. Token-free backfills pause and resume at the reset
time, so a large initial archive takes longer. Startup never waits for the backfill.

`GET /api/github/commits?user=…&repo=…&q=…&cursor=…` reads only the selected
repository from Supabase, with indexed full-text matching over the complete
stored message/body, author name/login and hash. Each response includes 30 rows,
matching total, next cursor and whether a complete scan has finished. The cursor
uses immutable commit time/SHA ordering, so inserts between requests do not repeat
earlier pages or hide the existing tail. The widget debounces
search with re-frame events, rejects late responses after repository/query
changes, and stops pagination at the explicit end cursor. SHA deduplication also prevents
duplicate rows/React keys. While initially backfilling, it
states that coverage is partial and provides an explicit refresh action.

Commit details use `GET /api/github/commits/:sha?user=…&repo=…`. The endpoint
accepts hexadecimal prefixes of at least seven characters and returns the
canonical SHA, public author metadata, first-parent information and consumed file
patch fields. The Neovim viewer passes `page=1…30`; each page requests 100 files,
preserving GitHub's changed-file pagination. Straight patches use the sibling
`/:sha/patch` endpoint; before/after files use `/api/github/contents` with a
validated repository-relative path and immutable `ref` SHA. Browser requests
remain on the site's origin; the existing optional GitHub token stays on the server.

All three detail reads must match the current configured repository. They check
public visibility before every response, including server-cache hits, and again
after fresh acquisition; a token's access to private content never authorizes its
publication. The shared promise cache retains at most 16 responses for fifteen
minutes, shortens failures to one minute, and bounds concurrent requests to four.
HTTP responses use `no-store` so privatization cannot leave a shared HTTP cache
serving the old detail. Provider JSON remains capped at 4 MiB; patches and decoded
source files retain the viewer's 1 MiB limits. Real provider failures and exhausted
rate limits remain explicit retryable errors with bounded `Retry-After` timing.

Public roles may read only published archive columns for public repositories;
writes, checkpoints, leases and job RPCs remain service-role only. Author email,
provider tokens and raw provider payloads are not stored. The stable
`(owner, repo, sha)` identity, complete message, category/subcategory and indexed
UTC timestamp provide the source for the later Typesense export. This change
does not install a second search service or change the top-of-page search.

For an explicit initial import or recovery, the server-side command uses the
same leased checkpoints (it never falls back from a failing CMS):

```sh
lein run -m website.modules.github.sync owner repository
```

Omit the two arguments to use the shared configured selection. The command stops when the
scan completes, a retry delay is reached, another worker holds the lease, or its
bounded step budget is used. Re-running resumes rather than starting over.
Calendar days use one tab stop; arrow keys navigate by day/week, with Home/End
for the first/last day.
