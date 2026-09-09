# Module and route integration

The integration starts from `origin/master` at `38a1c97`, with the local
`0d38af7` commit cherry-picked as `ba9256a`. Upstream already supplied the
comment-key and asynchronous-navigation fixes in that local commit; those
upstream implementations were retained when resolving the cherry-pick.

## What is integrated

- One composed Reitit router, with home, blog, CV, and docs route data split
  into their respective namespaces. Feature view imports remain inside lazy
  modules. `/blog/new-post` is the canonical URL for `:new-post`.
- The local home/media component extraction, SoundCloud oEmbed implementation,
  relocated showcase images, logo update, and pallet model.
- Consistent development/production module graphs, the production `init!`
  entry point, stable React roots across reloads, React 19/Reagent 2, and a
  compatible React Leaflet dependency.
- Promise normalization, shared module loads, one-time initialization, stale
  navigation protection, error cleanup, docs initialization, eager auth event
  registration, and literal autocomplete matching.
- A Firebase submodule fix for null redirect results. The submodule is pinned
  to `d68547b`; publish/retain that commit before using the parent branch.
- Correct server error response bodies and direct route requests covered by
  backend tests. API coercion tests call the route handler directly; frontend
  route tests use the application middleware with asset transformation omitted.
- Local npm tools, lockfile-based installation, Docker exclusions for private
  files, and deployment restricted to pushes to `master`.

## Recovery checkpoints — local only

`master` remains at the original local commit, `0d38af7`.

- `recovery/local-state-20260909` (`58e16f4`) records the local commit plus
  the original tracked work and selected untracked source/assets.
- `wip/checkpoint-recovered-20260909` (`4133c05`) records an intermediate
  application of that work on top of current upstream and the cherry-pick.
  This checkpoint is for recovery, not a passing build.
- `.local-wip/recovery-20260909/` contains the original workspace archive,
  index, staged/unstaged binary patches, HEAD and refs, repository bundles,
  and a Git metadata archive that includes nested repository metadata.
- The initial archive is also at
  `/private/tmp/tolgraven-recovery-20260909-015639/`.

The archives and both snapshot branches contain private scratch material.
Keep them local. Push only the integration branch and the reviewed Firebase
fix branch; do not use `git push --all` or publish the recovery archives.

The main workspace archive omits reproducible dependency/build caches, logs,
and `pg/`; those original directories were left in place. The original index
is saved separately, preserving which changes were staged and which were not.

### Restore the original working tree in a separate directory

Choose a new empty directory. Extract `git-metadata.tar.gz` there, then extract
`workspace.tar.gz` with its leading `workspace/` component removed. Set HEAD
back to the original master reference using `git symbolic-ref HEAD refs/heads/master`
and copy the saved `index` over `.git/index`. Do not run checkout/reset in this
restored directory: the archive already provides the exact original tracked
and untracked file contents, including original deletions.

The metadata archive retains `master` at `0d38af7`, and includes the nested
Git stores. `status.txt`, `staged.patch`, and `unstaged.patch` can be used to
verify the restored staged/unstaged split. Dependency caches can be rebuilt.
Leave the live integration checkout untouched while restoring or comparing.

## Work preserved for later

`.local-wip/experiments/` and the local snapshot preserve the unfinished
Firebase rules/emulators/Functions setup, raw showcase movies, Polytope and
webpack experiments, WARP notes, additional Strava image, and Graal/SSR code.
The Codox fork remains in the ignored `checkouts/codox` directory; its original
submodule addition and tooling changes are in the recovery snapshot.

The always-enabled SSR path was removed from the integration because it used
incorrect hydration arguments and an undeclared Graal dependency. The upstream
Portal removal was retained. The Instagram scratch API changes and embedded
credentials were not carried into the PR. The submodule's original lockfile
edit is saved as `firebase-lock.patch` as well as in the workspace archive.

Unused warning-image format deletions were not carried forward, so the server's
existing warning image retains its optimized variants.

## Validation

See README for the exact commands. Backend route checks, browser regression
tests, development compilation, production compilation, CSS build, and lint
were exercised during integration. Two compiler warnings remain in the existing
`clojure.core.rrb-vector` dependency (Vector redefinition/constructor arity).
This work does not deploy the website or apply the parked Firebase rules.
