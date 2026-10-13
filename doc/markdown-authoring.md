# Markdown authoring

The saved document remains Markdown. `/blog/new-post` belongs to `:blog-editor`,
which owns the posting form, media-library UI and serialized attachment queue.
The reusable visual SDK, its toolbar and rich help live in the independently lazy
`:markdown-editor` bundle. `blog-editor` declares `:site/owner :blog`, and `markdown-editor` follows
`blog-editor`; disabled parents prune child code, routes and packaged assets.
Attachment rows show measured upload bytes, an indeterminate conversion phase,
and success/error surfaces with retry/remove. Byte callbacks retain the transport
auth owner and queue attempt guards; cancelled and replaced attempts cannot update
the row. Files and byte progress stay in transient module state. Server-ready rows
remain until the published preview acknowledges visibly decoded image content.
The existing keyed presence lifecycle owns their brief opacity exit. Preview
failures retain a notice; retry reloads the preview without reuploading the file.
Reading a public post uses the normal public Markdown
renderer; it does not acquire either authoring bundle.

| Feature | Visual editor | Published post |
| --- | --- | --- |
| Bold, italic, underline, strike, super/subscript, inline code | Toolbar; both Ctrl and Cmd B/I/U | Markdown and narrowly recognized semantic marks |
| Headings 1–6, quotes, bullet/number/task lists | Toolbar and Markdown typing shortcuts | Standard Markdown |
| Links, tables, rules, fenced code | Link dialog, toolbar and code editor | Shared Markdown and deferred highlight |
| Images | Search server assets, upload, paste/drop, URL insertion | Shared responsive picture with paired rendition metadata |
| Source mode | SDK source/diff and a plain Markdown attachment view | The same saved source |
| Site components | Search canonical names, docstrings, signatures and schemas | The actual public components and their normal data dependencies |
| Widget geometry | Argument inputs, corner drag and arrow-key resizing | Persisted sizing, alignment and floating text flow |
| Frames and embeds | HTTPS/local iframe; YouTube/SoundCloud oEmbed | Existing sandbox/embedding policy |

## Component identities and arguments

Fenced-code editor descriptors render Highlight's shared code-block component;
the SDK supplies code/language/metadata setters. Its optional editable fields
retain the same highlighted display, copy/wrap/fold controls and deferred formatter
as a public post. Both authoring owners declare their Highlight dependency.
Source-mode CodeMirror edits the document rather than providing another fenced
code renderer.

Fenced blocks retain their language tag and metadata in Markdown. The `clj`,
`cljs`, `cljc` and `clojurescript` tags use Clojure's highlighting grammar without
rewriting the authored tag. Untagged fences use automatic detection.

The tag is a data container. Its `component` field preserves the actual qualified
ClojureScript component identity, including the angle brackets in its Var name:

```markdown
<Component component="website.components.content.box/<highlight-box>" title="A useful detail" text="Something worth noticing" tone="tip" />

<Component component="website.components.content.markdown/<heading>" level="json:2" text="A section" />

<Component component="website.components.content.markdown/<list>" items='json:["One","Two"]' ordered="json:true" />

<Component component="website.components.content.box/<highlight-box>" text="db:[:content :common :title]" />

<Component component="website.modules.settings.views/<config-table>" title="Make yourself comfortable" />
```

Components with a map/spec argument expose its required and optional fields. Other
signatures take the `args` field as a quoted data vector, retaining the original
positional call contract:

```markdown
<Component component="website.modules.highlight.code-block/<code-block>" args='json:["(+ 1 2)",{"language":"clojure"}]' />
```

Plain strings are literal. `json:` supplies numbers, booleans, arrays, objects or
an explicitly escaped string. `edn:` supplies literal Clojure data, including
keywords, sets and typed map keys; it never evaluates code and rejects tags.
`db:` supplies an EDN **data path**, retaining keyword,
string-ID and numeric segments. Paths may read public `:content`, component-owned
`:content-components`, or `[:options :theme ...]`. They cannot read account,
credential, administration or arbitrary application state. No tagged code,
JavaScript expressions, spreads, imports or exports execute.

The hover/focus popup lists required/optional fields and schemas beside the real
docstring. Argument validation protects preview and published rendering; invalid
data yields a local repair message. Binding acquisition uses the existing
`:app-db` dependency adapter. The component's own declared dependencies still
acquire its normal service data and its normal actions retain their permissions.
Composing a component does not grant the reader new backend privileges. Recursive
composition is bounded so a post quoting itself cannot exhaust rendering.

The picker is generated from selected public `defc`, `defpage` and conventional
public `<…>` render functions. No authoring eligibility flag or alternative blog
name is needed. Private definitions, quoted experiments and disabled owners are
excluded. Full source help belongs to the editor; the public inventory contains
only canonical identities and lazy owners. The existing registry retains real
function identities. Legacy functions register through
`(m/register-render-functions!)` after their definitions, preserving their plain
function calls as well as their Reagent use.

## Layout and media

`layout-width`, `layout-height`, `layout-align` and `layout-flow` describe the
wrapper independently of a component's own arguments. Width/height accept `auto`,
`fit`, positive `rem`, `em`, `%` or authored `px`; align accepts `left`, `center`
or `right`; flow accepts `block`, `left` or `right`. Floating affects the actual
SDK node wrapper and the published content wrapper, allowing surrounding prose
to flow around images and widgets. A drag previews local geometry and commits
Markdown only when the gesture finishes. Arrow keys provide keyboard resizing.

Images inserted through the Image picker use the canonical image component and
are immediately available to these controls. Existing ordinary Markdown images
remain portable; their image toolbar's **Layout & resize** action converts them
to the same component data before applying geometry. The content owner supplies
its URL-keyed public rendition manifest through a React context, including in the
editor preview. No widget copies the asset store or fetches image metadata itself.

```markdown
<Component component="website.components.content.frame/<frame>" src="https://example.com" title="Reference" interactive="json:true" layout-width="24rem" layout-height="fit" layout-flow="right" />
```

Frames reuse the existing iframe sandbox. Safe local documents without scripts
can be measured directly. Cooperating embedded pages can respond to
`website:measure-request` with `{type: "website:iframe-size", height: number}`;
only the exact iframe window and expected origin are accepted, with bounded
finite heights. Measurement listeners and observers belong to the frame and
release on source change/unmount. Other pages use an aspect-fit or explicit-size
fallback. Browser CSP/X-Frame-Options restrictions remain effective.

## Styling and verification

The build scopes SDK styles to `.site-md-editor`, converts vendor fixed units to
relative units and isolates vendor palette variables. The owner styles use the
site's palette, spacing, lines and rounded geometry. Popup portals stay inside the
editor's overlay owner. Authored pixel dimensions are the explicit user-selected
exception; application-owned CSS remains relative.

Dependency changes require the verified Docker prefab refresh, using the shared
capped BuildKit queue. Browser checks cover source round trips, both keyboard
modifier families, task/list/table editing, media insertion, literal/path arguments,
actual component interactions, iframe measurement cleanup, image/widget resize
and float geometry, public SSR/hydration, and navigation ownership. Source tests
alone do not establish visual parity or live Strapi/Supabase behavior.

The focused workflow runs through the shared resource launcher:
`tolgraven-cpu run -- bash scripts/test/markdown-editor.sh --hydration` produces
current Node fixtures and the ordinary mounted suite. Use a separate isolated
`--production-hydration` run to verify delayed owner/coercion installation with
release readers. The release fixture checks native SSR node identity before each
gate opens and then operates the actual theme control. Use the current shared
budgets from `doc/resource-controls.md`; independent outputs preserve watched
builds. First-paint CSS follows the renderer's actual visible export
inventory, including embedded owners whose popup stylesheet is normally deferred.
