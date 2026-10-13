# Markdown editor SDK entry comparison

Both measurements use source checkpoint `9387ca3deba475d46f6a9de8eba3b79bdf16ddb6`,
the same dependency lock and MDXEditor 4.3.3. The second adds the selective public
SDK entry adapter. Builds use the same isolated `editor-final` production audit
target through the shared runner: 1.5 CPUs, 4 GiB container memory, 2 GiB heap.
No watched output or user REPL is involved.

| Bundle | Stock entry gzip bytes | Selective entry gzip bytes |
| --- | ---: | ---: |
| Visual/source editor | 1,159,568 | 497,471 |
| Posting form, queue and drafts | 21,672 | 21,672 |
| Public blog reading | 27,849 | 27,849 |

The editor drops **662,097 gzip bytes (57.1%)**. Its raw JavaScript decreases from
3,805,679 to 1,815,713 bytes; Brotli decreases from 915,942 to 399,980 bytes.
The stock entry resolves CodeMirror/Lezer/diff-source/theme modules; the selective
entry resolves **zero** of those sources, so their optimized contribution is zero.
Authoring SDK/Lexical/feature implementations are absent from both the public main
and blog module graphs in each measurement.

The normal audit emits Shadow's resolved module inventory and source-map byte
attribution plus compressed actual chunk sizes. Gzip uses level 9; Brotli uses
quality 11. The compact machine-readable records are in
[markdown-editor-entry.json](markdown-editor-entry.json), including snapshot IDs
and resolved/optimized exclusion counts. The current audit rejects these unwanted
modules anywhere in the resolved production graph.

These are bundle-transfer and ownership measurements, not editor interaction or
load-time timings. Compiler durations are not compared because the disk-cache
state and number of compiled files differ. The normal package lock still includes
the stock SDK's declared transitive dependencies; they remain installed but are
absent from the site's resolved editor code graph. Browser behavior after this
adapter change remains a separate verification gate.
