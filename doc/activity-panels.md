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
