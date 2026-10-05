# Live re-frame inspection

The [day8/re-frame-pair skill](https://github.com/day8/re-frame-pair) is installed
locally in `~/.codex/skills/re-frame-pair`. New Codex sessions discover it there.
It requires Babashka (`bb`), Shadow's nREPL, and an attached browser runtime.
The project's `:app-dev` build already enables re-frame tracing and loads 10x;
no production dependency or preload is needed.

Start the app with `lein repl` and the normal `:app-dev` watch, then open
`http://localhost:4000`. From the repository root:

```sh
bash scripts/re-frame-pair.sh discover-app
bash scripts/re-frame-pair.sh app-summary
bash scripts/re-frame-pair.sh eval-cljs '(re-frame-pair.runtime/app-db-at [:state :blog])'
```

Always run discovery at the start of a debugging session. The wrapper selects
`:app-dev` and prefers Lein's `.nrepl-port`, avoiding stale standalone compiler
ports. Override `SHADOW_CLJS_NREPL_PORT`, `SHADOW_CLJS_BUILD_ID`, or
`REFRAME_PAIR_SKILL_DIR` when deliberately targeting another process/build.

Read the installed `SKILL.md` before using its operations. Start with scoped
state reads and compact epoch diffs; avoid dumping credentials or private user
data. Dispatch, hot swapping, and time travel change the live browser state.
REPL changes are temporary and do not replace verified source changes.

If discovery reports `:browser-runtime-not-attached`, ensure the app is served
from the watched development build and reload the page. A standalone `compile`
can overwrite watch output without the browser REPL connection; run
`(shadow.cljs.devtools.api/watch-compile! :app-dev)` from the dev nREPL before
reloading. Use the watch process for ordinary development compilation.

### Shadow dependency warnings

`lein deps :tree` currently selects Fipp 0.6.29's `core.rrb-vector` 0.1.2
before Reitit's 0.2.0. Both it and upstream 0.2.1 omit `Vector`/`->Vector`
from `rrbt.cljs`'s core exclusions. With our ClojureScript compiler, this
produces both `:redef` and `:fn-arity` warnings, and the generated positional
constructor incorrectly calls `cljs.core.Vector`.

`tolgraven.build.compat/rrb-vector` applies just those namespace exclusions
at Shadow's `:compile-prepare` stage. It preserves the jar and library body,
changes the compiler cache key, and runs in every build (including SSR/tests).
The browser regression constructs an RRB vector through `->Vector`, then
checks its type, contents, slicing and concatenation. Remove this workaround
when an upstream release includes the exclusions; upgrading to 0.2.1 alone
is insufficient. Do not suppress these warnings globally or exclude the
library entirely: Fipp and the dev tooling actually use it.
