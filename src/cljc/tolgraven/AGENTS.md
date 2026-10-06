# Shared declarations and contracts

Keep this guide current. Shared platform data shapes, schema infrastructure,
query constructors and normalization belong here so CLJ and CLJS use the same
definition. Module-owned `.cljc` schemas and page specs live beside their frontend
implementation in `src/frontend/tolgraven/<module>/`, also on the JVM source path.

- Module `pages.cljc` exports a native Reitit route tree and may contain nested or
  multiple pages. Do not require module implementation namespaces from it.
- Public data plans are adapter-independent. Server reads and managed browser
  subscriptions consume the same filters, projections, batching and row shape.
- Preserve EDN keys/types in persistence contracts. JSON is converted and normalized
  only at transport boundaries. Never include credentials in public snapshots.
- Keep schemas composable by module/app-db section; avoid a second global inventory
  of declarations already owned by modules. Tests should consume the same schemas.
- Keep component argument, form, state, event and subscription schemas in the
  owning module's `src/frontend/tolgraven/<module>/schema.cljc`.
  `schema/state.cljc` assembles these owners;
  it must not become a central inventory of module component arguments.
- Reader conditionals must work for CLJ tooling/Codox as well as browser and Node
  Shadow builds. Do not make JVM documentation scan browser-only namespaces.

- `schema/declarations.cljc` defines declaration shapes; module-local schemas own
  domain fields. `schema/app_db.cljc` composes initial sections. Keep coverage
  explicit and retain open maps where migration is incomplete.
- `schema/http.cljc` is shared by page and API routers; use those typed parameters
  in handlers/controllers and use the same schemas for accepted/rejected fixtures.

- Define meaningful new contracts alongside their owner and use them across both
  runtimes. CMS bundles and normalized app-db/provider content have different
  shapes. SQL projections include selected NULL columns. Keep raw transport,
  normalized record and component presentation contracts distinct where needed.
