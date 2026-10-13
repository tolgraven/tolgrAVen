# Isolated Shadow cache measurements

Measured on this Mac on 2026-10-13 from application base
`a38d012983e2e6a03655cc666323c1ec448d8f66`, using Shadow 3.5.7 in the existing
Docker prefab. Each build had 1.5 CPUs, 4 GiB memory, a 2 GiB JVM heap and the
existing bounded JVM pools. No hot JVM remained between commands. These samples
used fresh output snapshots; both compared graphs reused one cache identity each.

| Browser-test graph | Files compiled / total | Compiler time | Total wall time | Queue / dependency setup |
| --- | ---: | ---: | ---: | ---: |
| Cold cache | 1,234 / 1,235 | 41.69 s | 60.884 s | 0 / 0 s |
| Unchanged source | 2 / 1,235 | 5.90 s | 24.212 s | 0 / 0 s |
| Changed reporting prefix | 2 / 1,235 | 5.77 s | 22.099 s | 0 / 0 s |

Warm elapsed time was about 60% lower in this sample. The two intentional
recompiles are the application's `tolgraven.loader.code` and Shadow's
`shadow.test.browser`; both declare `:dev/always`. Their analyzer cache files
remain absent, preserving their inventory/runner behavior. All builds completed
with zero ClojureScript compiler warnings; the dependency parser emitted its two
existing Phoenix JSDoc notices. These are compilation measurements, not browser
suite or deployment results.

The document correctness matrix also verified missing fresh JavaScript output,
unchanged source, a reporting prefix change, one mutated source file, and the
original source restored on the next invocation. It compiled respectively
48, 0, 0, 1 and 1 files out of 50, with zero warnings; all five used the same
cache identity. The temporary definition appeared only in the mutated snapshot's
output and disappeared after restoration.

The browser-test samples had one active Docker build; the recorded historical
one-minute load averages were 3.65, 5.61 and 5.87. The document samples were taken
while earlier jobs were settling, with historical one-minute loads from 14.43
through 6.86. Those small-graph timings are correctness evidence rather than a
controlled performance comparison. Earlier queued samples are excluded from the
comparison above.

A watched master REPL's 2–5 second increment retains its JVM, compiler state and
resolved classpath. Disk-cache reuse does not remove fresh Lein/JVM startup,
resolution and output flushing: about 18 seconds remained outside the reported
compiler phase in the unchanged browser-test sample. Dependency setup timing has
one-second resolution. These are individual observations, not a multi-run average.

Reproduce through the installed launcher:

```sh
python3 scripts/test/shadow_cache.py --report /tmp/shadow-document.json
python3 scripts/test/shadow_cache.py --target app-test --report /tmp/shadow-app-test.json
```

Coordinate a quiet compiler window and retain the generated logs/JSON. The scripts
never alter checkout source or watched output; mutation fixtures run only inside
isolated Docker snapshots. See [resource controls](../resource-controls.md) for
cache identities, invalidation, inherited locks and cleanup controls.
