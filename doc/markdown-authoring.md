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
| Source mode | Literal Markdown in the existing native document with inline upload chips | The same saved source |
| Site components | Search canonical names, docstrings, signatures and schemas | The actual public components and their normal data dependencies |
| Widget geometry | Argument inputs, edge/corner drag and arrow-key resizing | Persisted sizing, alignment and floating text flow |
| Frames and embeds | HTTPS/local iframe; YouTube/SoundCloud oEmbed | Existing sandbox/embedding policy |

## Editor and application ownership

The outer React editor is adapted once with `r/adapt-react-class` and receives
Clojure props from ordinary Reagent views. SDK extension slots run in the opposite
direction: descriptor `Editor`, `EditImageToolbar` and `addEditorWrapper$` require
React component types. Their stable `r/reactify-component` adapters render normal
`defc` components; they are not a second UI implementation. Opaque native editor
handles stay inside these small extension adapters.

Lexical owns its editable document, selection and undo history. Re-frame owns the
canonical draft, upload queue, progress, pending insertion points and completion
guards. Native Lexical updates are document transactions; an event claims the
media node's canonical URL update and acknowledges it after a successful commit.
One stable media node renders pending/error/ready declaratively; completion never
inserts a neighboring image. Effect replay cannot claim the same update twice.
The SDK's own content
signal supplies Markdown changes, preserving normalization/mute guards and
avoiding another full serialization for cursor movement.

The posting owner supplies query descriptors, rather than a mirrored upload
vector, through SDK ancestry. Each native media node subscribes to its exact
managed job; the controller subscribes to the owner-specific derived queue.
Only native insertion/update capabilities and per-editor callbacks belong in
that context. Standalone editor hosts can supply static values instead. A
document mutation listener handles deleting an otherwise zero-width pending
node, because such deletion need not change the SDK's distinct Markdown cell;
selection changes do not trigger additional exports. Account changes end the
previous transport/editor owner without requiring route unmount.

Formatting, new directives and components use the SDK's normal publishers;
argument edits use its node updater. Upload cancellation uses one guarded native
document transaction: the SDK's removal hook assumes the node exists inside a
later transaction, which breaks under effect replay. The queue explicitly owns
cancellation and removes its record after the document owner acknowledges it;
temporary absence from a context render never implies cancellation. The one
anchored image-completion transaction updates its existing media node, rather
than the user's newer cursor position. `insertImage$` targets the
active selection, so it cannot express that asynchronous attachment operation.

Pending uploads are native inline nodes in both visual and source modes.
Their small highlighted widgets use ordinary Reagent views and the existing
presence/exit lifecycle. Pending IDs, status and
positions never appear in authored Markdown. Completed media exports ordinary
Markdown image data, or the canonical image component when saved layout is
needed, retaining its canonical source after queue disposal. The same media node
owns all four drag edges and its alternative text, dimensions and text flow.
Pure tree transformations preserve
surrounding formatting; native source changes map anchors through precise edits.
Source changes from upload completion use a normal editor transaction, retaining
selection and undo. Callback changes reconfigure the owned content listener
without recreating the editor. Source mode reads literal native text rather than
serializing it as rich Markdown, so unfinished syntax is editable too. Its
controlled text/on-change/disposal contract is the future Monaco adapter boundary;
it adds no second editor dependency or application store. Managed query values
are read again at reconciliation so a passive effect cannot apply an earlier
render's accepted text over newer typing. There is no whole-props ref.

Fenced-code editor descriptors render the ordinary shared code-block component;
the SDK supplies only code/language/metadata setters. Its optional editable fields
retain the same highlighted display, copy/wrap/fold controls and formatter gate
as a public post. Both authoring owners declare their highlight dependency.
Source mode does not implement another fenced-code rendering system.

## Working and named drafts

Accepted title, tags, Markdown and referenced ready image assets use the shared
150 ms storage debounce. Navigation/pagehide drains that same queue, including
the latest accepted edit; neither the editor nor the draft view owns another
timer or writes localStorage. Restoration validates private data before applying
it. Input typed while disk is loading wins over the earlier snapshot, and account
changes release the previous transport/native editor lifetime.

The working document keeps a stable ID across reloads. **Save draft** gives that
document its current title in the in-progress chooser; subsequent edits continue
to autosave. Named working documents store a pointer to their collection entry,
rather than a second copy of their content. Choosing a draft or starting another
ends the previous native document/undo lifetime. Published-post revisions have
their own collection, so selecting a new-post draft cannot inherit another
published post's edit target. Pending Files, node IDs and upload chips are never
persisted; the view explains that unfinished images remain in the current tab.

An explicit save acknowledges the storage adapter's actual durable write.
Unavailable/full storage reports an error and preserves the dirty write for
retry, including retry without changing the text.
Failed private reads preserve the original envelope and current input until a
successful retry. Reload restores surrounding canonical prose; unfinished uploads
have no saved directive or orphan progress widget because their native nodes
export no image before a canonical source exists.

## Code language choices

New fences default to **Auto detect**, serialized with an empty language tag.
Existing tags, including `cljs`, survive editing even when the formatter uses an
alias to choose its rendering grammar. The shared code-block's searchable picker
uses optional public CMS `:common :code-languages` choices, or an explicit
`:language-options` component option. Values are unique ASCII language names
of 1–80 characters (`a-z`, `A-Z`, digits, `_`, `+`, `-`); labels are nonblank and
at most 120 characters, with at most 200 choices. An absent field supplies a few
initial suggestions; an explicit empty list supplies only Auto detect. Authors
can still enter an existing/custom fence tag without changing the source code.

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

## SDK entry ownership

The pinned SDK exposes a single public JavaScript entry. That entry imports its
unused CodeMirror and diff-source modules, which Shadow's normal npm provider
retains even when our visual/source adapters never select those plugins.
The editor's configure hook derives the used public exports from its four SDK
adapters and creates a selective entry in ignored `target/generated-sdk`.
The SDK version and public-entry SHA-256 must match before generation; a changed
entry fails with a request to review this boundary instead of silently accepting
private or missing exports.

Shadow's file override cannot resolve relative SDK imports without npm package
metadata. The generated package supplies that metadata and a read-only reference
to the original SDK's `dist` directory. It copies no implementation and never
changes shared `node_modules`. Browser, development, mounted tests and Node SSR
use the same configure hook and normal npm resolver. React, Lexical and Gurx keep
their existing singleton dependencies. The adapter is build output, not a script
served to visitors or a source snippet embedded in application markup.

The focused workflow runs through the shared resource launcher:
`tolgraven-cpu run -- bash scripts/test/markdown-editor.sh --hydration` produces
current Node fixtures and the ordinary mounted suite. Use a separate isolated
`--production-hydration` run to verify delayed owner/coercion installation with
release readers. The release fixture checks native SSR node identity before each
gate opens and then operates the actual theme control. Use the current shared
budgets from `doc/resource-controls.md`; independent outputs preserve watched
builds. First-paint CSS follows the renderer's actual visible export
inventory, including embedded owners whose popup stylesheet is normally deferred.
