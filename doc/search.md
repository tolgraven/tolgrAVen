# Site search and Typesense

Search uses one server-owned Typesense collection and a public, bounded hit
contract. The browser sends a debounced query to `/api/integrations/search`;
generation checks reject obsolete requests and responses, including closing the
panel while acquisition is pending. Completions are plain titles, selectable with
Arrow Up/Down, Enter or Tab. Hit fragments render literal text and `<mark>` nodes;
author HTML never becomes DOM. Only the current response is retained.

Each selected module declares `:server :search` sources with a `:kind`, qualified
`:preview` export, and either projection `:sql` or public CMS `:content` keys.
An optional server `:filter` Var narrows results; GitHub checks the configured
repository's current public status on every search. Credentials and filter
implementation details stay server-side. Result documents contain only the shared
allowlist and a bounded text excerpt, rather than each complete article.

Small `:blog-preview`, `:github-preview` and `:home-preview` owners export their
presentations through the normal module loader. They acquire native styles and
share presentation helpers without importing the full post, comment tree,
calendar or commit-diff views. Their `:site/owner` and literal `:site/requires`
capabilities prune the preview when either its content owner or Search is disabled.
The shared blog author/timestamp presentation retains the original public entry.

## Automatic indexing

Module-owned SQL creates private canonical documents and delivery queues. Post,
comment and commit triggers project explicit public fields in the same transaction
as their writes. Deletion produces a tombstone. Repository visibility changes
revoke or restore its projections without rewriting archived commits. Consumers
are separate for each Typesense destination and collection; an index delivery
cannot acknowledge another site's work.

The module worker claims bounded leased batches, validates and normalizes documents,
checks each Typesense import row, acknowledges its exact revision, and retries
failures. An expired worker delivering an older revision schedules the current
projection again, even when another worker already acknowledged it. Public CMS
text refreshes independently; failed refreshes retain the previous projection.
Missing collections are recreated and replayed. The private queue functions and
tables are unavailable to anonymous and ordinary authenticated clients.

Server configuration uses `TYPESENSE_URL`, `TYPESENSE_API_KEY` and
`TYPESENSE_COLLECTION` (default `website_v1`), or equivalent nested `:search`
settings. Keys belong only to runtime configuration and never enter frontend
bundles or public settings. Apply the selected module SQL through the existing
Supabase schema plan before starting a configured worker.

## Coolify and migration

`scripts/ops/provision-typesense.py` accepts a private manifest containing the
server, environment, destination and application UUIDs, managed service name and
collection. `prepare --dry-run` checks installed Coolify models and rolls back;
`prepare`, `start`, `verify` and `wire` are explicit separate operations. The service
uses pinned Typesense 30.2, persistent data, bounded resources and the application's
private Docker network, with no public port or domain. `verify` checks health,
authenticated access and rejection of unauthenticated collection reads. `wire`
stores server-only runtime settings for the selected application's normal
deployment without restarting it. It does not copy production credentials into
preview variables. Configure preview search against a separate service/collection
so preview workers cannot acknowledge or remove production deliveries.
If an earlier run copied this exact managed production sink into previews, `wire`
removes that matching triple; unrelated preview settings are retained.

For CapRover migration, run `scripts/ops/typesense/export.py` on the source host
with a new private output directory. It reads that service's key in memory and
exports schemas, JSONL documents, aliases, overrides and synonyms without printing
credentials or documents. Keep the recovery archive and source service until
cutover is verified. Copy the directory into Coolify with private ownership for
its CLI user. The `migrate` operation additionally requires its absolute
`archive_directory` and `manifest_sha256`, validates target ownership, checks every
import result and exports the target documents for independent checksum comparison.
Migrated legacy collections remain separate from the new canonical collection;
the latter reflects current published data rather than resurrecting stale hits.

## Component destinations

Module-local native Reitit fragments mount beneath their host page. Complete
matching and typed parameters remain available; no unmatched-prefix parser is
needed. `/blog/post/:post/comment/:comment-id` reveals the target's ancestors,
including older roots outside the first page. `/blog/comment/:comment-id` renders
the selected subtree with a link back to its complete post. `/github/commits/:sha`
searches the complete archive by hash independently of the latest loaded page.
`/section/intro`, `/section/services`, `/section/moneyshot`, `/section/story`,
`/section/interlude`, `/section/soundcloud` and `/section/gallery` retain the Home
page and position their native section after content commits. Cold destinations
declare their document target for positioning before the original entrance;
history returns retain the user's saved position.

Browser checks: `fixtures/search-tests.html`, `fixtures/search-routing-tests.html`
and `fixtures/comment-upload-tests.html`. Backend search tests cover safe fragments,
destination isolation, delivery retry, response bounds and GitHub visibility. SQL
queue fixtures must run only in a disposable database. Real deployment checks use
the actual services and router, with no injected application state.
