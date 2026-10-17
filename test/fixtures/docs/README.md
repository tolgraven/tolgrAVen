# Pinned native Docs visual inputs

These are the exact generated Codox HTML inputs used by the reviewed native Docs
index, topic and sidebar-hover cases. The fixture loads only these two documents
through the existing managed URL adapter, strips their head as the backend does,
and renders the real Docs owner with application-owned CSS and link rewriting.
No generated inline styles, HTML, source text or links were rewritten.

The frozen corpus was retained through build snapshot IDs `4a1096ae953f`,
`1a53efc220c9` and `927950247a4b`; all decoded bytes match. Original generation came
from the earlier `ui-parity-host-0535ca340f27` native snapshot. This provenance pins
measured input bytes; it does not claim that corpus documents current source.
These identifiers are build snapshots, not Git commits.
The generator configuration is the repository's Codox0.10.8 `lein docs` alias.

| File | Decoded bytes | Decoded SHA256 |
| --- | ---: | --- |
| index.html.gz | 379373 | 0274b19eccfd0cf20da50f54d4c3e9056a214f44a0ecafc7866373effbebf757 |
| docs.html.gz | 92834 | 619f164b2dde82aaf464af42e475177c91e36926cc08cece2431f20660865ce2 |

Gzip is storage encoding only (`mtime=0`). The dedicated test server sends decoded
UTF-8 HTML bytes with no Content-Encoding. Body subtrees require no Codox image,
stylesheet or JavaScript assets: those resources exist only in the discarded head.
Sidebar/source links remain real links, but this six-case corpus does not exercise
navigation to every generated namespace page. Preserve that scope honestly.

Changing this corpus is an explicit visual-fixture change requiring review and
native narrow/wide inspection, distinct from approving any baseline. `lein docs`
still tests current-source generation separately and must not silently replace
these inputs when application source changes.
