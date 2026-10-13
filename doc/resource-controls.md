# CPU controls for agent work

Use `bb cpu run -- <command>` from a checkout, or the installed
`~/.local/bin/tolgraven-cpu run -- <command>`. The default is a serialized,
isolated Docker build using the existing prefab with **1 CPU and 2 GiB RAM**.
JVMs see one logical processor, two JIT compiler threads, one parallel/concurrent
GC worker each, and a 1 GiB heap. For a measured larger build:

```sh
tolgraven-cpu run --memory 2500m --java-heap 1792m -- \
  lein with-profile prod,provided run -m shadow.cljs.devtools.cli release app ssr return-worker
```

Shadow release/check/compile targets run sequentially in the installed CLI;
`:parallel-build false` also disables parallel work within each target.

Each invocation copies tracked working files plus nonignored untracked source
into a new `.local-wip/cpu-builds/<id>` checkout. It excludes local private config,
symlinks, ignored output and dependencies. Unstaged source edits are included;
deleted files stay deleted. All generated files remain in that reported directory.
Copy wanted output back only when the owning watch is stopped or absent. The
launcher never writes a live checkout's `target` or public build files.

The prefab's Maven artifacts are reused. Matching npm manifests use its locked
`node_modules`; a changed lock installs within the capped container. This does
not refresh/publish a prefab or deploy anything. An explicit `--image` can select
a current dependency-hash builder. No credentials or local dev config are copied.

For host-only watches, nREPL and browser interoperability:

```sh
tolgraven-cpu runtime -- lein repl
```

The `runtime` mode starts a supervised host process without holding the compiler queue. Short host build commands use `run --mode host`, which serializes work and starts it with nice 10 and supplies bounded JVM defaults
when `JAVA_TOOL_OPTIONS` was unset. It does not itself impose a kernel CPU quota.
The installed native monitor owns enforcement. Shells inherit priority for child
Java/Lein processes. Existing interactive watches are inspected through their
worker; never launch a competing compiler into watched output.

## Native scope and budgets

`bb cpu monitor --project /path/to/tolgrAVen --enforce` discovers only that Git
repository's registered worktrees and refreshes the inventory. It admits:

- Same-user Java processes whose current working directory is inside one of those
  checkouts, including the existing interactive backend/Shadow JVMs.
- Exact ChatGPT/Codex app bundle renderer executables whose parent ancestry reaches
  the corresponding main app process.

It excludes the main app, Codex CLI/agent engines, service/GPU/network helpers,
computer-use and tool Node runtimes, system/other-user processes, unrelated Java,
its own helpers, and controller ancestors. Those exclusions keep the control path
and human input responsive; they are not an application-wide CPU quota.

The default **aggregate budget is 1.5 cores**, with Java limited to at most 1.5 and
renderers to at most 1.0 within that shared total. A verified active default Docker
build reserves 1.0 from the same budget, leaving 0.5 for the native families.
Explicit larger Docker overrides retain a native floor of 0.25; the status report
shows the resulting budgets rather than silently treating them as 1.5 overall.
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
PID and **microsecond process start time**. Renderer ancestry and project Java CWD
are rechecked each sampling window. Initially stopped processes are excluded and
stay stopped. STOP is always a positive individual PID, never a process group.

An independent watchdog acknowledges a lease before any STOP. It sends guarded
CONT on socket closure, controller failure/signal exit, or a 250 ms expired lease;
expired leases repeat CONT to cover a delayed controller. Normal/error cleanup
also resumes owned PIDs. PID reuse does not inherit a stale stop/resume operation.
A separate Docker guardian verifies the container's unique label and immutable ID
before stopping a container orphaned by its launcher. Normal completion leaves no
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
# Observe without sending any process signals:
bb cpu monitor --project /path/to/tolgrAVen --once
# Read current targets, actual cores, run duties and Docker reservation:
tolgraven-cpu status
# Disable installed enforcement and resume its paused processes:
tolgraven-cpu stop
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
