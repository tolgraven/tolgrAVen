# Fast staging Docker builds

Run commands in the migration worktree (or any checkout containing this setup).
Bux and the local Mac use ARM64; the default platform is `linux/arm64`.

```sh
make docker           # build locally, push changed layers to S3, deploy PR 45
make docker-build     # build only
make docker-push      # build and publish, leave the live app alone
make docker-prefab    # build/publish the reusable tools and dependencies
make docker-registry  # check the HTTPS registry on bux
```

`make docker` is an explicit manual deployment. It includes uncommitted application
changes in this checkout. Tags include the Git revision, a dirty marker when
applicable, and a timestamp. The last image reference is stored in ignored
`.local-wip/docker/last-image`. It does not commit or push Git changes.

## Registry and S3

One Distribution registry runs on **bux only**, backed by the private storage
prefix `s3://tolgraven/docker-registry/` at
`https://hel1.your-objectstorage.com`. The Mac pushes and pulls through
`registry.bux.tolgraven.se` over HTTPS with Docker authentication. Coolify uses
`127.0.0.1:5005` on bux for the same repository. Only missing layers upload.
There is no registry container or S3 credential requirement on the Mac.

Traefik provides TLS and bcrypt HTTP basic authentication for public access;
anonymous requests receive HTTP 401. The raw registry port is bound to loopback.
The registry also joins the private Coolify proxy network. S3 objects remain
private, separate from the public Maven repository under `m2/releases/`.

The server setup script reuses the existing `hetzner` S3 record inside Coolify.
Credentials are supplied to the registry at runtime, never Docker build arguments.
Removing the registry container does not delete its S3 image data.

Required locally: Docker/BuildKit, Python 3, make, a registry login, and working
`ssh bux` authentication. This Mac's Docker login is already configured through
Docker's credential store. Other machines need their own authorized credentials:

```sh
docker login registry.bux.tolgraven.se
```

No additional Coolify API token or SSH key is created.

## Prefab builder

`Dockerfile.builder` combines pinned Node 22, Temurin Java 21, and Leiningen images,
then installs exactly `package-lock.json` and downloads production Maven artifacts.
The prefab includes the complete project `node_modules` directory, including
Sass, PostCSS, and Shadow CLI tools. Sass/PostCSS
symlinks support the existing login-shell Sass command without global npm installs.

`make docker-prefab` publishes a dependency-hash tag and the compatibility alias
`registry.bux.tolgraven.se/tolgraven/builder:java21-node22-v1` (the server uses
the same alias through its loopback endpoint). Refresh it when changing
`project.clj`, either package manifest, or the builder Dockerfile. Local builds
check the dependency hash automatically and pull an existing hash before
considering a build. `make docker-prefab` skips rebuilding and pushing when the
published image and alias already match. Do not refresh it for ordinary source
edits. This dependency-update rule is also recorded in `AGENTS.md`.
A stale server prefab still supports
changed dependencies, but needs to fetch them again during that build.

Coolify staging has `BUILDER_IMAGE` set to the compatibility alias as a build-only
variable in both normal and preview scopes. Existing AWS, Firebase, and OpenAI
credential variables are runtime-only in staging; builds need no application
credentials. The Dockerfile retains a self-contained
fallback for other servers without this registry. Keep its `prefab` stage in sync
with `Dockerfile.builder` when changing toolchain setup.

Application builds reuse prefab npm/Maven dependencies and a BuildKit cache for
Shadow release analysis. Application source changes no longer reinstall Node,
Leiningen, or npm packages. The final image contains the JRE and application jar,
not source checkout, node_modules, Maven cache, or compiler tools. Runtime JVM
memory bounds are preserved.

## Manual deployment lifecycle

`make docker` calls the installed `/usr/local/lib/tolgraven/deploy-image.py` on bux
through SSH. The helper is scoped to staging UUID
`o84wgo08wcs048ss8sokgkgw`; it never stops production or Supabase containers.

1. Take a lock and refuse an unfinished manual deployment or an active Coolify
   staging deployment. Pull the finished image before stopping anything.
2. Save build configuration and rollback container metadata (no runtime secrets)
   in `/var/lib/tolgraven/manual-deploy.json`.
3. Temporarily disable automatic deployment and switch this application to
   Coolify's Docker Image build pack, retaining preview/runtime settings.
4. Disable restart policies on existing staging runtimes and gracefully stop all
   of them with a 30-second grace period before queuing the replacement.
5. Let Coolify deploy the image to the existing preview. Wait for its result,
   check the actual running image and single-container count, and check homepage
   and public settings responses with the HTTPS proxy header.
6. Restore the original Git-build configuration and automatic-deployment setting.
   A terminal failure restores the newest previous staging runtime instead.

Set `COOLIFY_PR=0` for the normal staging app or another existing preview number
for a different preview. Default is PR 45. `COOLIFY_SSH_HOST` can select another
SSH alias for bux; it must still reach the same server. The helper deliberately
hard-codes the staging UUID and image repository to prevent accidental production
operations. This replacement causes a brief staging interruption.

The existing `tolgraven-runtime-policy` service remains active for ordinary Git
builds. Manual deployments stop all old staging instances before starting the new
one; ordinary cross-PR Git deployments still use that service's reconciliation.

## Installation and recovery

```sh
# On bux, after copying the scripts from this checkout:
# Use an existing bcrypt htpasswd file; keep it out of Git.
REGISTRY_HTPASSWD_FILE=/secure/registry.htpasswd bash scripts/setup-build-registry.sh
sudo install -D -m 755 scripts/deploy-image.py /usr/local/lib/tolgraven/deploy-image.py
sudo install -D -m 755 scripts/staging_supabase.py /usr/local/lib/tolgraven/staging_supabase.py
sudo install -D -m 755 scripts/coolify-site-runtime-policy.py /usr/local/lib/tolgraven/coolify-site-runtime-policy.py
sudo systemctl restart tolgraven-runtime-policy
```

The public route template is `deploy/coolify/registry-proxy.yaml`. The setup script
installs its authentication file under `/data/coolify/proxy/auth/` and its route
under `/data/coolify/proxy/dynamic/`. The existing `letsencrypt` resolver issues
its certificate. The route contains no credentials; the htpasswd file stays on
bux. Without `REGISTRY_HTPASSWD_FILE`, setup preserves any existing public route.

The helper uses the installed Coolify 4.x PHP application/queue API through its
container; verify compatibility after a Coolify upgrade. It does not edit Coolify
source or create an unmanaged replacement web container.

If a deployment times out or SSH disconnects, inspect its Coolify deployment log
and `/var/lib/tolgraven/manual-deploy.json`. The helper keeps recovery metadata
and does not start a second runtime while a deployment may still be active.
Do not remove this file or restore build settings until the queue job has stopped.
The file identifies the deployment, prior application fields, prior automatic
setting, and old container ID/restart policy. After resolving the job, restore
those fields through Coolify, and restart the old container only if the new image
failed. Leave exactly one staging runtime running.

Diagnostics:

```sh
docker logs tolgraven-build-registry
ssh bux 'systemctl status tolgraven-runtime-policy --no-pager'
ssh bux 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}"'
```

Do not run registry garbage collection during pushes; image retention/garbage
collection is intentionally a separate operation. Old versions remain available
for rollback in S3.

## Verification on 2026-10-03

The prefab was published successfully. An unauthenticated request for its S3
manifest link returned HTTP 403; authenticated S3 access succeeded. The final
ARM64 runtime image was 419 MiB versus the preceding staging image's 1.32 GB.
Homepage, CSS and compiled JavaScript served successfully from the local image.
The end-to-end `make docker` run with a warm local build cache completed in
42 seconds, including the server's first runtime-image pull and Coolify rollout.
The deployed homepage and public settings endpoint both returned HTTP 200, and
production remained available. These timings are measurements of this run,
not a guarantee for source changes or the initial prefab build/upload.

Coolify writes a release-specific `docker-compose.yaml` into the checkout before
building. Docker's context excludes that generated manifest (and the ignore file
itself), so deployment metadata and documentation/tooling-only changes do not
invalidate `COPY . .` and force a new application compilation. Application source
changes still compile normally.

Cache validation on the Mac (2026-10-03): the first local image build after the
context change took 54.82 seconds. Two subsequent builds with different generated
Compose image references took 2.17 and 0.85 seconds; the `lein uberjar` layer was
cached in both. These are local image-build times, excluding push and deployment.

## PR preview cleanup

The GitHub deployment workflow forwards `pull_request.closed` to Coolify using
the same signed webhook as preview creation. GitHub emits this event both when a
PR is merged and when it is closed without merging. Cleanup bypasses CSS
validation and shares the PR-specific concurrency group with deployment, so a
close event supersedes an in-flight workflow for that PR. Coolify's native
cleanup cancels that preview's deployments and removes its runtime and preview
record. Once no staging web runtime or deployment remains for 60 seconds,
the runtime policy also stops staging Supabase, preserving its volumes and data.
Production remains running. Reopening the PR wakes Supabase and recreates its
preview; the web app waits for Auth and REST readiness before serving requests.
