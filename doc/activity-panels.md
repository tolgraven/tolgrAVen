# Deployment and public activity panels

The GitHub module shows the running container's deployment identity above its
commit browser, then the configured user's public GitHub contribution calendar.
The separate `:codex-profile` module follows it in the landing layout.

Commit category and subcategory labels share a right-hand column beside the
message. Commit text is limited to three lines; hovering the message shows its
complete original subject and body. Label clicks keep their category search.

## CMS ownership

The retained `github-settings` contract defines `user` and `repo`; the
`:github/get-from` subscription reads them when supplied. The currently deployed
CMS does not publish this section, so the module declares no content dependency
and shows its configuration prompt when settings are absent. GitHub provider responses remain in
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

`GET /api/github/activity/day?user=…&date=YYYY-MM-DD` uses GitHub GraphQL's
single-day contributions collection. Configure a server-only `GITHUB_TOKEN`
(`:github-token`, or nested `:github :token`) with public-read access for this
endpoint; without it the total remains usable and the managed detail dependency
reports unavailable. No repository names, issue bodies or private content are
returned by the day endpoint. Date and user validation precede acquisition;
requests, body size, concurrency and caches are bounded.

## Complete repository archive

The selected GitHub module declares `github_repositories` and `github_commits`
through its ordinary storage catalog. Apply the module's SQL using the existing
Supabase provisioner before enabling its service. `:server :start` returns a
cleanup owned by the mounted module-services lifecycle. With server Supabase
settings, it starts asynchronously; `:github {:sync-enabled false}` disables it.
Without published CMS repository settings the automatic worker has no target and
performs no archive/content acquisition. Optional module-service startup failures
are logged locally and do not stop the shared HTTP server or healthy siblings.
When configured, it uses the CMS-selected repository, traversing every current public branch and tag's
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
GitHub rate-limit reset times. A public-read GitHub token avoids the low
unauthenticated request limit. Startup never waits for the backfill.

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

Omit the two arguments to use the CMS selection. The command stops when the
scan completes, a retry delay is reached, another worker holds the lease, or its
bounded step budget is used. Re-running resumes rather than starting over.
Calendar days use one tab stop; arrow keys navigate by day/week, with Home/End
for the first/last day.
