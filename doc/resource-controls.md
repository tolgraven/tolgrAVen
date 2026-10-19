# CPU controls for agent work

Use `bb cpu run -- <command>` from a checkout, or the installed
`~/.local/bin/tolgraven-cpu run -- <command>`. The default admits **two builds at once**, each in an isolated Docker snapshot
using the existing prefab with **1.5 CPUs and 4 GiB RAM**. The combined Docker
ceiling is **3 CPUs**. A third build waits for either inherited-lock slot.
A smaller `--cpus` value is allowed; values above 1.5 are rejected so concurrent
builds cannot exceed that ceiling.
JVMs see one logical processor, two JIT compiler threads, one parallel/concurrent
GC worker each, and a 2 GiB heap. Run targets sequentially:

```sh
tolgraven-cpu run -- \
  lein with-profile prod,provided run -m shadow.cljs.devtools.cli release app ssr return-worker
```

For a build that needs more memory, raise its container and JVM budgets together:

```sh
tolgraven-cpu run --memory 6g --java-heap 3g -- <build command>
```

This is a per-invocation override; the default remains 4 GiB/2 GiB. It retains the
same CPU ceiling, shared slots and isolated output. Check the Docker VM's total
memory limit before running multiple larger builds concurrently. OrbStack's
`memory_mib` setting applies to all containers and machines together, while its
allocated memory grows on demand. Do not restart the VM while unrelated builds
or services are running.

Shadow release/check/compile targets run sequentially in the installed CLI;
`:parallel-build false` also disables parallel work within each target.

Each invocation copies tracked working files plus nonignored untracked source
into a new `.local-wip/cpu-builds/<id>` checkout. It excludes local private config,
symlinks, ignored output and dependencies. Unstaged source edits are included;
deleted files stay deleted. All generated files remain in that reported directory.
Missing isolated dev/test runtime files are seeded only from the tracked public
`config/dev.example.edn` and `config/test.example.edn` at their known canonical paths
`env/dev/resources/config.edn` and `env/test/resources/config.edn`. Private checkout
files at those paths are excluded even if accidentally tracked. Existing destination
configuration is preserved, and production defaults are copied normally without seeding.
This lets plain agent Docker test commands use the public configuration layout.

Shadow compiler data survives those fresh snapshots. The launcher binds only
`.shadow-cljs/builds` from the primary checkout's ignored
`.local-wip/shadow-caches` directory, shared by its linked worktrees; server/nREPL markers and
application output remain snapshot-local. Cache identities separate canonical
worktrees, immutable prefab image IDs, Maven/npm dependency declarations, public
site selection and declared Lein profiles. Helper reporting text, source revisions
and target lists do not create new identities. Shadow independently separates
build IDs and development/release modes and validates compiler options and changed
source/macro dependencies. This never reads or mounts a checkout's watched cache.

An identity has one inherited writer lock, held by both launcher and container
guardian. Simultaneous commands using that identity wait; unrelated worktrees or
profiles may use the existing two build slots. A container orphan keeps its cache
lock until the supported recovery path confirms it has stopped. No JVM remains
running between commands. Disk reuse saves analysis/compilation, but each fresh
JVM still starts Lein, reads the classpath and initializes Shadow; a hot watched
REPL's 2–5 second increment is a different runtime environment.

Use `run --shadow-cache off -- <command>` for a cold comparison, or
`run --shadow-cache refresh -- <command>` to discard and rebuild that invocation's
cache identity under its lock. A custom Shadow `:cache-root` or unavailable local
image identity bypasses cache mounting. Cache cleanup removes only unlocked,
managed identities whose owner checkout disappeared, after seven idle days, or
oldest-first above a 2 GiB repository budget; active writers may temporarily exceed that budget. `tolgraven-cpu cache-prune`
runs the same cleanup explicitly. It preserves lock files to prevent inode races.
Cache metadata records its originating checkout. Runtime state and the small
original writer-lock files remain in Application Support: keeping those same
lock inodes preserves coordination with older launchers and active guardians.
Compiler cache cleanup is separate from finished output snapshot cleanup.

### Finish a task and retire its resources

Close task-created Chrome tabs/windows as soon as verification finishes; reuse
one task tab during sequential checks. Additional private windows are temporary
isolation fixtures, not permanent per-check resources. Preserve user-owned pages
and another session's still-needed tests, and never quit Chrome. Stop only owned
fixture servers before removing their output snapshots.

`bb cleanup` reports cache candidates without deleting data. `bb cleanup --apply`
prunes only unlocked managed caches. For a completed worktree, run from the primary
checkout or another retained checkout:

```sh
bb cleanup --worktree /path/to/completed/tolgrAVen --remove-worktree
bb cleanup --worktree /path/to/completed/tolgrAVen --remove-worktree --apply
```

Retirement requires a registered feature branch with no tracked/untracked source
changes, a worktree HEAD matching either the head or exact merge commit of a
merged PR in `origin/master`, no open follow-up on its branch,
no process with a working directory inside the target, and no owned Docker
reservation/container mounting its output (including cache-disabled/legacy builds). It rechecks these
conditions before removal. Ignored private configuration and task/review records
are archived with restricted permissions under `.local-wip/task-archives` before
ordinary `git worktree remove`; reproducible compiler/dependency/output trees are
omitted. Branch refs are retained. Main, detached and ambiguous worktrees are
preserved for manual review. The script cannot infer another session's future
intent; only retire a task whose owner has finished. A Git hook cannot establish
that lifecycle, so cleanup is an explicit completion step rather than a destructive
checkout/merge hook.

The former Application Support caches have no owner metadata. Once a plan is
reviewed, discard their unlocked managed entries using:

```sh
bb cleanup --legacy-caches --all-unused-caches
bb cleanup --legacy-caches --all-unused-caches --apply
```

Active old writers and malformed/unmanaged directories are preserved. No cache
migration is needed; Shadow rebuilds the disposable data at the new location.
Update future launcher invocations without restarting the monitor or existing
workers with `bb cpu install --project /path/to/tolgrAVen --launcher-only`.

Reproduce compiler-cache measurements with
`python3 scripts/test/shadow_cache.py --report /tmp/shadow-document.json` or
`--target app-test --report /tmp/shadow-app-test.json`. These invoke the installed
launcher with the normal budgets and write logs plus JSON outside the checkout.
The document matrix verifies unchanged reuse, reporting-prefix reuse, a changed
source and its restoration; fixture edits exist only in isolated snapshots.
The full browser-test graph measures cold/warm/prefix variants. Reports separate
queue wait, dependency setup, compiler time and total wall time, and capture load
and the active budget. Coordinate a quiet build window for timing comparisons.

Copy wanted output back only when the owning watch is stopped or absent. The
launcher never writes a live checkout's `target` or public build files.

Ordinary Docker builds bind the host `~/.m2/repository` at `/root/.m2/repository`
read/write, sharing downloaded artifacts across worktrees and subsequent builds.
Maven settings and credentials outside that directory are not mounted. This mount
hides the prefab's repository; dependencies absent on the host download once into
the shared cache. BuildKit image creation also bakes dependencies into the prefab
for remote/fresh hosts. Dockerfile deployment builds separately retain the
portable `m2` BuildKit cache and snapshot it before parallel compilation; they
do not depend on this Mac-specific bind mount. Matching npm manifests use its locked
`node_modules`; a changed lock installs within the capped container. This does
not refresh/publish a prefab or deploy anything. An explicit `--image` can select
a current dependency-hash builder. No credentials or local dev config are copied.

Custom media images that have codecs but no Node tools can explicitly skip
Node dependency setup for non-Node tasks:

```sh
tolgraven-cpu run --image tolgraven-media-tools-check --node-deps skip -- bb images:responsive
```

This retains the isolated source snapshot, shared build queue, container guardian
and CPU/memory limits. Ordinary builds retain the default `--node-deps auto`,
which reuses matching prefab dependencies or runs the locked npm installation.

Dependency prefab refreshes and Dockerfile builds use the same shared slot with
an explicitly capped BuildKit container:

```sh
tolgraven-cpu run --mode buildkit -- make docker-prefab
```

This uses a dedicated Docker `docker-container` builder per build slot with a 1.5-CPU CFS quota,
4 GiB memory/no additional swap, one concurrent BuildKit step, and automatic image
loading so existing publishing commands work. The standard BuildKit daemon uses
privileged mode inside the existing Docker VM; it does not install a privileged
macOS service. The low-priority Docker client runs
on the host for local daemon/registry interoperability. The launcher pins its
local Docker endpoint and checks the worker's ownership and actual quotas before
running the command. The exact container guardian reserves that CPU budget and
stops the worker on completion or parent death; its cache volume and builder
metadata remain for reuse. Each worker enables BuildKit garbage collection with
a 2 GB cache target, 256 MB reservation and 20 GB free-space target. These are
periodic reclamation thresholds, not a hard cap on active build layers. Finished
isolated snapshots must be removed after preserving their useful logs/reports and
checking that browser servers and later verification no longer use them.
The user's default builder is not changed. BuildKit
mode executes in the selected checkout, so Dockerfile/context exclusions still
apply; ordinary compiler commands retain isolated prefab snapshots.

For host-only watches, nREPL and browser interoperability:

```sh
tolgraven-cpu runtime -- lein repl
```

The `runtime` mode starts a supervised host process without holding the compiler queue. Short host build commands use `run --mode host`, which acquires one of the two slots and starts it with nice 10 and supplies bounded JVM defaults
while preserving unrelated `JAVA_TOOL_OPTIONS` and explicit heap sizing. Worker
bounds are always appended, so preexisting options cannot silently bypass them. It does not itself impose a kernel CPU quota.
The installed native monitor owns enforcement. Shells inherit priority for child
Java/Lein processes. Existing interactive watches are inspected through their
worker; never launch a competing compiler into watched output.

## Native scope and budgets

`bb cpu monitor --project /path/to/tolgrAVen --enforce` discovers only that Git
repository's registered worktrees and refreshes the inventory. It admits:

- Same-user Java descendants of a live, explicitly registered `run --mode host`
  or `runtime` launcher, within that launcher’s dedicated process group and a
  registered checkout. A working directory alone never admits Java.
- Exact ChatGPT/Codex app bundle renderer executables whose parent ancestry reaches
  the corresponding main app process.

User-launched `lein` clients, their backend/Shadow descendants, and other
unregistered Java remain unthrottled. Reply’s SIGCONT handler therefore receives
no repeated resume signals from this monitor. Docker agent quotas are unchanged.

It excludes the main app, Codex CLI/agent engines, service/GPU/network helpers,
computer-use and tool Node runtimes, system/other-user processes, unrelated Java,
its own helpers, and controller ancestors. Those exclusions keep the control path
and human input responsive; they are not an application-wide CPU quota.

The default **aggregate budget is 1.5 cores**, with Java limited to at most 1.5 and
renderers to at most 1.0 within that shared total. Verified active Docker reservations are summed, including an older one-slot
build that was already running during an upgrade. Native enforcement retains a
0.25-core floor while Docker is active; the status report shows the resulting
budgets rather than treating the Docker and native totals as 1.5 overall.
Other Docker containers are outside this project's budget.

Native enforcement samples cumulative CPU time through libproc, converting Mach
clock ticks with the machine's timebase. It adapts run duty once per second and
uses overlapping 100 ms STOP/CONT cycles only when a cap is needed. Quiet monitors
sleep until the next one-second sample; an unarmed watchdog blocks on its socket. It is an approximate moving CPU ceiling,
with a one-sample response to changing demand, not a hard cgroup quota. Short
bursts and scheduling variance can exceed the target. Active renderers may show
brief frame/input delays under pressure, and native JVM timers/network operations
can be delayed by suspension. Prefer Docker quotas for compilation.

Status separates live `pti_numrunning` (XNU `TH_RUN`) thread counts from total parked/active threads and historical 1/5/15-minute load averages. Scan duration is reported too; native CWD lookup avoids shell-command stalls. Worktree inventory refresh runs asynchronously once per minute.

Lower CPU priority is a soft scheduling preference; Docker `--cpus` is a kernel
quota. A CPU quota does not measure or guarantee whole-machine watts, GPU/ANE
power, display power, battery charging or thermal response. GPU helpers remain
unmodified. iStat's battery charging/discharging rate is not automatically a
measurement of whole-machine power.

## Ownership, recovery and lifecycle

Before admission and before STOP, the adapter checks same user, executable path,
PID and **microsecond process start time**. Renderer ancestry and explicit host
owner/root birth identities, group, ancestry and project CWD are rechecked each
sampling window. Registrations live in `host-active/` and are removed after owned
family cleanup; missing, stale or reused identities never admit native Java.
Initially stopped processes are excluded and stay stopped. STOP is always a positive individual PID, never a process group.

An independent watchdog acknowledges a lease before any STOP. It sends guarded
CONT on socket closure, controller failure/signal exit, or a 250 ms expired lease;
expired leases repeat CONT to cover a delayed controller. Normal/error cleanup
also resumes owned PIDs. PID reuse does not inherit a stale stop/resume operation.
A separate Docker guardian acknowledges its stable post-exec identity before
registration, then verifies the container's unique label and immutable ID
before stopping a container orphaned by its launcher. Dispatch captures the original
local Unix Docker endpoint; guardian and recovery keep using it after context changes.
Only exact container absence is accepted, never a missing socket/configuration error. Normal completion leaves no
container or helper behind. Docker cleanup always runs even if its CLI exited; uncertain
cleanup retains its guardian, inherited build lock and quota reservation until confirmed.
Host cancellation targets only the new owned process family, with guarded graceful
termination; existing REPLs are never restarted or terminated.

These safeguards cover an individual controller crash or stall. Killing both
controller and watchdog, OS failure, or simultaneous external signal manipulation
cannot offer an unconditional recovery guarantee. The native PID check and signal
are separate macOS calls, with a small unavoidable race. Do not layer another
STOP/CONT tool over these same processes.

```sh
# Observe without signals when no monitor/service owns the singleton lock:
bb cpu monitor --project /path/to/tolgrAVen --once
# During active enforcement use this read-only status command instead:
# It reports current targets, actual cores, run duties and Docker reservation.
tolgraven-cpu status
# Disable installed enforcement and resume its paused processes:
tolgraven-cpu stop
# Recover a confirmed recorded orphan without deleting/bypassing the build lock:
tolgraven-cpu recover-build
# Older reservations need the original explicit --endpoint; no new daemon is guessed.
# Re-enable the installed service:
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/se.tolgraven.cpu-controls.plist
```

Installation is explicit: `bb cpu install --project /path/to/tolgrAVen`. It copies
the adapter into `~/Library/Application Support/tolgraven/cpu-controls`, writes
`~/.local/bin/tolgraven-cpu`, and installs the user LaunchAgent. It changes neither
shell startup nor system-wide Java/Git settings. Logs/status live in that directory.
`stop` unloads only that exact service; manual monitors use the recorded PID/start
identity. Changing rules means stopping, then reinstalling with the desired options.

Run `python3 -m unittest discover -s test/scripts -p cpu_controls_test.py` for
policy, real process identity, pre-stopped preservation and controller/watchdog
recovery tests. `python3 scripts/test/cpu_probe.py` measures four disposable native
burners; `bb cpu run -- java scripts/test/cpu_probe.java` measures a capped four-thread
JVM. Run CPU probes separately from browser/image timing benchmarks. Record quotas
and host load with any performance numbers; do not compare throttled and unthrottled
runs as if they were equivalent environments.

Primary references: [Docker CPU constraints](https://docs.docker.com/engine/containers/resource_constraints/),
[Apple process priorities](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/setpriority.2.html),
and [Apple signals](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/kill.2.html).
The native layouts are checked against the installed macOS SDK `sys/proc_info.h`.
Python is the small native lifecycle/ctypes adapter here because microsecond libproc
identity, independent signal recovery and inherited sockets belong at this OS boundary;
Babashka remains the project's task entry point. No dependencies were added.

Each build has its own reservation. If more than one orphan is recorded, use
`recover-build --name <exact-recorded-container-name>` to select it; recovery
still checks the original endpoint, guardian identity and container label.
