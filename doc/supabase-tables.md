# Supabase table ownership

Module specs expose `:storage storage/tables` from a portable namespace beside the
owning module. Discovery bakes selected definitions into JVM, browser and Node
artifacts; packaged servers do not scan source files. See
[Site capabilities](site-capabilities.md) for file/CMS merging and build selection.

A table declares `:table`, `:key`, `:order`, optional `:jsonb`, `:references`, owned
`:sql`, optional `:feature`, and query/realtime projections. References determine
parent-first imports and child-first deletions. `:seed-key` participates in legacy
seed read/import/diff operations. Votes and private user documents have no legacy
seed key but participate in explicit reset ordering. `:cache-key` retains existing
realtime cache ownership when it differs from the table name.

Conflict targets, delete filters, JDBC ordering, JSON conversion, read projections,
query allowlists and realtime identities derive from those declarations. A custom
owner adds its declarations and module-spec reference; it requires no backend table
list edit. Legacy Firebase conversion remains a format adapter.

Build selection is site-wide. Runtime backend `{:supabase {:modules {:blog
{:comments false}}}}` can further narrow seed tools but cannot add compiled-out
owners. Unknown owners/options, non-booleans, duplicate tables/seed keys, missing
parents and dependency cycles fail explicitly. Empty catalogs perform no seed I/O.

Canonical SQL lives in `resources/supabase/modules/`. Selected tables contribute
DDL/RLS/RPCs; `:server` contributes extra resources such as search and avatar setup.
Reapplying the plan restores enabled access and revokes disabled table/column grants,
RPC execution and realtime publication while retaining data. Reference-only legacy
`schema.sql`/`operations.sql` snapshots are excluded from production jars.

JDBC reset truncates the explicit selection together without unrestricted `CASCADE`.
It refuses a reset when an excluded table references a selected one. This prevents
silently clearing retained disabled-feature data. REST deletions run in reverse
dependency order and retain database `ON DELETE` behavior; use JDBC's refusal boundary
when resetting databases with retained disabled tables.

The real PostgreSQL check creates a unique rollback-only fixture schema:

```sh
SUPABASE_CATALOG_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/test_db' \
  lein with-profile +project/test run -m clojure.main test/sql/supabase_catalog_check.clj
```

It verifies JDBC imports/JSONB/reads, FK-safe resets, actually absent blog/comment
tables and a custom table. It does not test Supabase REST/Auth. The capability matrix
and retention test exercise fresh DDL installs and disable/re-enable grants separately.

Main's `:media` feature declares `site_media` and its private-source/public-rendition
buckets. Its ready-only public columns and server-only job RPCs are module-owned;
post `image_assets` stores the server-derived manifest paired with Markdown.
Apply these resources before enabling media processing. See [Managed media](media-pipeline.md).
