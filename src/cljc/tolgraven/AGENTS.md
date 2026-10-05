# Shared declarations and contracts

Keep this guide current. Shared data shapes, schemas, query constructors, page
specs and normalization belong here so CLJ and CLJS use the same definition.

- Module `pages.cljc` exports a native Reitit route tree and may contain nested or
  multiple pages. Do not require module implementation namespaces from it.
- Public data plans are adapter-independent. Server reads and managed browser
  subscriptions consume the same filters, projections, batching and row shape.
- Preserve EDN keys/types in persistence contracts. JSON is converted and normalized
  only at transport boundaries. Never include credentials in public snapshots.
- Keep schemas composable by module/app-db section; avoid a second global inventory
  of declarations already owned by modules. Tests should consume the same schemas.
- Reader conditionals must work for CLJ tooling/Codox as well as browser and Node
  Shadow builds. Do not make JVM documentation scan browser-only namespaces.
