# Live re-frame inspection

The [day8/re-frame-pair skill](https://github.com/day8/re-frame-pair) is installed
locally in `~/.codex/skills/re-frame-pair`. New Codex sessions discover it there.
It requires Babashka (`bb`), Shadow's nREPL, and an attached browser runtime.
The project's `:app-dev` build enables re-frame tracing. The normal development
build uses the application's dev console and does not load 10x or re-frisk;
re-frame-pair does not require either inspector.

Start the app with `lein repl` and the normal `:app-dev` watch, then open
`http://localhost:4000`. From the repository root:

```sh
bb pair discover-app
bb pair app-summary
bb pair eval-cljs '(re-frame-pair.runtime/app-db-at [:state :blog])'
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

### Optional legacy inspectors

To use 10x and re-frisk, start a development JVM with the `:legacy-debug`
dependency profile and explicitly select the legacy preload:

```sh
lein with-profile +legacy-debug run -m shadow.cljs.devtools.cli watch app-dev --config-merge '{:devtools {:preloads [devtools.preload tolgraven.legacy-debug]}}'
```

Stop the existing `:app-dev` watch before starting this command; both write the
same development assets. This command watches the browser build; the HTTP
server and SSR/return workers still need their usual development startup.
The preload installs both inspectors with their panels initially hidden.
Return to the normal development watch without the merge to omit them again.

### Shadow dependency warnings

`project.clj` aligns `core.rrb-vector` at 0.2.1, which still omits
`Vector`/`->Vector` from `rrbt.cljs`'s core exclusions. With our compiler, this
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
