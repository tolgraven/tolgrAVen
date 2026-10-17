# Fast staging Docker builds

Run commands in a managed feature worktree. The default platform follows the
local ARM64/AMD64 host; `DOCKER_PLATFORM` selects an explicit platform.

```sh
make docker           # build locally, push changed layers to S3, deploy the normal staging app
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

Required locally: Docker/BuildKit, Babashka, make, a registry login, and working
`ssh bux` authentication. This Mac's Docker login is already configured through
Docker's credential store. Other machines need their own authorized credentials:

```sh
docker login registry.bux.tolgraven.se
```

No additional Coolify API token or SSH key is created.

## Prefab builder

`Dockerfile.builder` combines pinned Babashka 1.13.225, Node 22, Temurin Java 21, and Leiningen images,
then installs ImageMagick and `cwebp`, exactly `package-lock.json`, and build and
documentation Maven artifacts. Both the shared prefab and the self-contained
fallback encode and decode WebP/AVIF fixtures during construction; a missing codec
fails the build. ImageMagick defaults to one worker. Responsive catalog generation
and real backend codec tests use this ordinary builder, with no separate test
image or per-run tool installation.
The prefab includes the complete project `node_modules` directory, including
Sass, PostCSS, and Shadow CLI tools. npm scripts use these local executables;
no global CSS-tool installs or compatibility symlinks are needed.

`make docker-prefab` publishes the current native dependency-hash tag; Coolify advances compatibility aliases
`registry.bux.tolgraven.se/tolgraven/builder:java21-node22-v2` and `java21-node22-v1`.
The v2 image also contains `/opt/tolgraven-runtime`: the clean runtime filesystem
with Java, Node, Babashka and the tested upload codecs. Application images copy
only that root, then the application artifacts; compiler tools, npm packages and
Maven artifacts remain outside the final image. All OS package installs and base
image composition happen during prefab construction.
Refresh it when changing `project.clj`, either package manifest, the builder
Dockerfile, or the bundled `scripts/media/images.clj`. Local builds
check the dependency hash automatically and pull an existing hash before
considering a build. `make docker-prefab` skips rebuilding and pushing when the
published image and alias already match. Do not refresh it for ordinary source
edits. This dependency-update rule is also recorded in `AGENTS.md`.
A stale server prefab still supports
changed dependencies, but needs to fetch them again during that build.

The Dockerfile defaults to the exact published dependency hash in both production
and staging. The reference resolves native ARM64/AMD64 images through one index.
Every build host needs the existing private registry login (`docker login registry.bux.tolgraven.se`); Coolify mounts the host Docker credential store into
its helper. Its build-secret mode cannot pass a variable into Docker `FROM`, so
`BUILDER_IMAGE` environment records are no longer used. Ordinary Docker CLI
builds can still override that ARG; `--build-arg BUILDER_IMAGE=prefab` explicitly
builds the matching self-contained recipe when registry access is unavailable.

Maven caching belongs to the Dockerfile, independently of Coolify or the Mac
launcher. Both prefab construction and application dependency preparation mount
`id=m2,target=/root/m2,sharing=locked`, retaining the original cache identity.
An explicitly active user profile selects that repository. Both compiler and
final packaging graphs are resolved: uberjar removes provided dependencies and
can otherwise select additional runtime jar versions. Ephemeral prefab builders can
seed it from the previous published native prefab (`MAVEN_SEED_IMAGE`); a cold
builder uses an empty seed. Application preparation first
seeds missing artifacts from the prefab, resolves the current manifests, then
copies a repository snapshot into the compiler stages. A fresh host benefits
from baked artifacts; a changed dependency downloads once into persistent cache.
The mounted cache is an optimization, not a required build artifact. Private Maven
settings and credentials are never copied from the host.

Coolify provisioning reconciles new and existing Dockerfile apps: keep secret
mode enabled for the explicit CMS mounts, disable automatic ARG injection and
build-time source-commit metadata, remove the ineffective builder secret, and
make AWS/Firebase/OpenAI keys runtime-only. Runtime source-commit identity remains
available. Normal and preview environments use the same policy. Actual CMS
capability/CSS acquisition still runs in the source stage with its existing secrets.

BuildKit runs three independent branches concurrently: the frontend builds CSS,
releases the Shadow targets (`app`, `ssr`, `return-worker`, and `document`) in one
invocation, then prepares the Optimus inventory; the backend
resolves Clojure dependencies and AOT-compiles; a third stage generates Codox
documentation. The packaging stage waits for all three, copies the completed
browser assets and prepared inventory, and
creates the uberjar without repeating prep tasks or cleaning compiled classes.
Separate stage filesystems prevent target-directory and Maven download races.
The Docker compile and assembly tasks explicitly share `target/uberjar+provided/classes`;
changing `:target-path` alone does not update Lein's already-resolved compile path.
The ordinary `lein with-profile uberjar,provided uberjar` prep tasks also generate
production Shadow/CSS output and the inventory. The shared `docs` alias disables
nested prep tasks, so direct documentation commands and the uberjar prep step
cannot restart the build through Codox. Missing or stale prepared assets fail
startup clearly; no runtime transformation fallback runs.
Codox analyzes source without the custom `:browser` reader feature. The loader's
`.cljc` reader conditional therefore omits lazy-loadable declarations during
documentation analysis and SSR; browser builds explicitly enable that feature.
The npm imports use the supported `$default`/`:as` form, which Codox can parse,
and the namespace filter matches `website.*` names rather than file extensions.
The prefab still supplies most dependencies, so ordinary builds gain primarily
from overlapping compilation rather than downloading dependencies.

Agent prefab refreshes use the shared CPU launcher:

```sh
tolgraven-cpu run --mode buildkit -- make docker-prefab
```

This retains a shared job slot, 1.5-CPU quota, four-GiB memory budget and
BuildKit worker parallelism of one. Two slots allow up to three Docker CPUs
combined. The launcher stops its owned BuildKit daemon
when the command finishes; the reusable cache remains available.

The independent `media-tools` target exports the runtime root already in the
prefab. `docker build --target media-tools .` performs no OS installation or
application compilation. Codecs are smoke-tested when rebuilding the prefab;
verify the exported image's actual Java/Node/Babashka and WebP/AVIF conversions
when changing that recipe. Uploads keep `/app/scripts/media/images.clj` and their
existing Supabase Storage transport; see [upload storage](supabase-provisioning.md).

The Shadow JVM defaults to a 1536 MB heap (`BUILD_JAVA_OPTIONS`), while the
concurrent backend JVM defaults to 768 MB (`BUILD_CLJ_JAVA_OPTIONS`) and Codox to
512 MB (`BUILD_DOCS_JAVA_OPTIONS`). These are
build arguments; actual peak memory also includes Lein launchers, native memory
and CSS tooling. Parallel compilation trades additional peak memory for elapsed
time; adjust the build arguments to the builder's capacity. Parallel stages do
not change npm or Maven dependencies. The corrected Codox configuration changes
`project.clj`, which is part of the prefab hash; local build tooling will select
the new hash even though its dependency contents are unchanged.

The final image contains the JRE and application jar,
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
for a different preview. Default is the normal staging app (`0`), so merged previews are never resurrected. `COOLIFY_SSH_HOST` can select another
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
REGISTRY_HTPASSWD_FILE=/secure/registry.htpasswd bash scripts/ops/host/setup-build-registry.sh
sudo install -D -m 755 scripts/ops/host/deploy-image.py /usr/local/lib/tolgraven/deploy-image.py
sudo install -D -m 755 scripts/ops/host/staging_supabase.py /usr/local/lib/tolgraven/staging_supabase.py
sudo install -D -m 755 scripts/ops/host/coolify-site-runtime-policy.py /usr/local/lib/tolgraven/coolify-site-runtime-policy.py
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

## Build cache behavior

Coolify writes a release-specific `docker-compose.yaml` into the checkout before
building. Docker's context excludes that generated manifest (and the ignore file
itself), so deployment metadata and documentation/tooling-only changes do not
invalidate `COPY . .` and force a new application compilation. Application source
changes still compile normally.

## PR preview cleanup

The Coolify prefab gate handles signed `pull_request.closed` events through
Coolify's native preview lifecycle job. Cleanup has a separate control worker, so it
never waits for a dependency build. GitHub emits this event for both merges and
unmerged closes. Before deploying, the build worker rechecks the current PR head
and open state; stale queued/building events cannot recreate a closed preview.
The native handler removes the preview and cancels its deployments. Existing
staging Supabase suspension preserves its volumes; production stays running.
Cold resume reacquires missing configured images (for example after cache cleanup)
without recreating existing containers or volumes.

## Dependency cache verification

Run the real offline regression after publishing a changed prefab:

```sh
tolgraven-cpu run --mode buildkit -- python3 scripts/test/docker_cache.py \
  --image registry.bux.tolgraven.se/tolgraven/builder:java21-node22-v2 \
  --report .local-wip/docker-cache-check
```

It gives the real Dockerfile a fresh cache identity, builds with RUN networking
unavailable, then removes baked Clojure from the second build. Both must succeed
without repository downloads or OS installs; the second proves persistent cache
reuse rather than reuse of an image layer. Reports retain the exact test recipes
and logs. BuildKit may evict caches under its existing storage policy; fresh-cache
seeding remains supported. The final `media-tools` image must contain functioning
Java/Node/Babashka and codecs, with no JDK compiler, Lein, npm dependencies or Maven
repository. Verify actual staging and production builds select the published image.

## Automatic PR dependency gate

The tracked pre-commit hook computes the prefab hash from staged dependency bytes
and stages only Dockerfile's generated image default. It preserves unstaged
manifest and Dockerfile changes. `bb docker pin` updates the working pin explicitly;
`bb docker check-pin` rejects stale pins and mismatched fallback recipes.

Coolify owns the build queue and deployment sequencing. Its existing GitHub App
sends signed events to `https://prefab.bux.tolgraven.se/github`. The managed
`tolgraven-prefab-gate` service persists deliveries in SQLite and deduplicates
redelivery across restarts. Only same-repository PRs from owners, members and
collaborators targeting master may build; fork events cannot acquire credentials
or start a privileged builder. Other repositories' App events retain their native
Coolify handler. PR closes have a separate control worker so cleanup is immediate.

For the current commit, the gate validates the staged/generated image pin, reuses
published native hashes or builds missing ARM64/AMD64 images sequentially, then
executes the clean runtime's Java/Node/Babashka/codec smoke checks. BuildKit's
bundled emulator handles a foreign architecture; no privileged binfmt installer
is required. The persistent worker has 1.5 CPUs, 4 GiB RAM, one build step at a time,
a 6 GB cache target and a 20 GB free-space target. Maven caches are seeded from the
previous native prefab and remain locked during updates.

After both runtimes pass, it publishes the multi-platform reference, reports
`prefab-ready` through the Coolify GitHub App and invokes
the existing native Coolify deployment handler. PRs go to staging; master pushes
go to production and advance compatibility aliases. It rechecks the GitHub head
before publication of aliases and dispatch, so obsolete events cannot release an
old application revision. GitHub branch protection requires `prefab-ready` from
this App. GitHub Actions performs source validation only (the preserved legacy
CapRover job remains explicitly opt-in); it has no registry credentials or
Coolify relay. Coolify's ordinary Deploy button remains a manual recovery path,
and therefore requires the pinned image to exist already.

`python3 scripts/ops/provision-prefab-gate.py prepare` builds the small trusted
control image and creates/updates its Coolify service. `start` launches it; `wire`
checks public health and changes the existing App webhook URL, retaining a private
rollback copy in Coolify. Run these through the configured persistent SSH alias.
The one-time control image's Alpine tools install is independent of application
builds; application Docker builds perform no OS installations. Updating control
source requires prepare/start again; it never executes updated control code from
a PR checkout. The worker holds the Docker socket as trusted deployment
infrastructure; recipe RUNs receive neither that socket nor the App identity.

The existing App needs Repository permissions → Commit statuses: Read and write.
Its private key and installation tokens remain inside Coolify; its encrypted
webhook secret is copied only to the managed service's environment. Registry
credentials remain in the server's existing Docker configuration, mounted read-only.
The gate does not receive a personal GitHub token. Runtime control logs are in
Coolify, and detailed build logs plus the queue live under
`/data/coolify/prefab-gate/state` on the host. Failed jobs retry twice automatically;
a GitHub webhook redelivery retries a final failure. To recover the event path,
restore the saved App webhook URL and leave native Coolify deploys enabled.
