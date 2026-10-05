# Shared schemas and runtime validation

Malli schemas live in `src/cljc`, alongside the declarations/data they describe.
The browser, Node renderer, Ring handlers and tests use the same schema values.
Maps are open by default: a partially migrated section does not reject unrelated
application data. Make a map closed explicitly when unknown keys are an error.

## Runtime policy

Internal checks default on in development and off in production. Configure:

```clojure
;; dev-config.edn or the deployed config.edn
{:validation {:enabled true}}
```

`VALIDATION_ENABLED=true` or `false` overrides the file. The server emits only the
public boolean in a meta element and passes it to Node renderer workers. Reload
the browser/restart the server after changing deployment configuration.

Disabled internal checks do not install the app-db interceptor or validate
component/module declarations. The schema code remains available in optimized
builds so a deployment can enable it without rebuilding. This is runtime gating,
not complete removal of Malli from the bundle.

Request coercion is always active for declared HTTP and page parameters, including
production. Malformed external input must not reach handlers. Response checking
and internal declaration/state checking follow the runtime setting.

## Declaration contracts

`tolgraven.schema.declarations` exports `component`, `module`, `dependency`,
`dependencies`, `route-data`, `routes`, `data-plan`, `path`, and `event`.

- `component` describes evaluated `defc` options: features, dependencies, loading
  shape and module identity. Feature extension names remain open to registered
  capabilities. `defpage` still injects its mandatory error boundary.
- `module` requires a keyword `:id`; it accepts exported views, native page trees,
  dependencies, an initializer, preloaded modules and app-db sections.
- `routes` accepts the native nested Reitit tree, with optional data and multiple
  child pages. Router construction validates flattened, inherited route data.
- `data-plan` describes ordered query stages with `:id`, `:queries` and optional
  predecessor IDs. It validates shape, not the dependency graph's semantics.
- Dependency adapters still enforce their operational requirements and own I/O;
  schema checking never fetches data.

For example, an owner can expose its state schema with its module:

```clojure
;; my_site/catalog/schema.cljc
(def filters [:map [:search {:optional true} :string]
                   [:page {:optional true} [:int {:min 0}]]])
(def sections {[:state :catalog] filters})

;; my_site/catalog/module.cljs
(def spec
  {:id :catalog
   :pages pages/spec
   :view {:page #'<page>}
   :db-schema schema/sections
   :depends [{:source :strapi :keys [:catalog]}]})
```

The module loader checks the spec when code becomes available, then registers its
`:db-schema` sections. This uses the ordinary loader/subscription lifecycle.

## Component inputs and schema composition

Declaration options and values passed to an instance have separate contracts:

- `:schema` extends the component declaration, module spec or page route data.
- `:spec-schema` validates the first instance argument as a component spec map,
  automatically composed with the common `component-spec` contract (`:props`,
  `:classes`, managed `:depends`).
- `:args-schema` validates the entire supplied argument vector before destructuring
  or running the component body. Use `:tuple` for fixed arity and `:cat`, `:*`, `:?`
  or `:alt` for optional, variadic and multiple arities.

These checks cover props passed by parents as well as data originating in subs.
They work for lean function components and composed components. They add no DOM
or React wrapper. An invalid input is caught by the existing nearest error boundary;
components with `:error-boundary` display their own shared fallback.

```clojure
;; schema.cljc
(def label-spec [:map [:label :string]])
(def count-args [:tuple [:map [:title :string]] [:int {:min 0}]])
(def sum-args [:cat :string [:* :int]])

;; views.cljs
(defc <label> {:features [:props :error-boundary]
              :spec-schema schema/label-spec}
  [{:keys [label] :as spec}]
  [:span label])

(defc <count> {:args-schema schema/count-args}
  [{:keys [title]} amount]
  [:p title ": " amount])

(defc <sum> {:args-schema schema/sum-args}
  [label & amounts]
  [:p label ": " (reduce + 0 amounts)])
```

Schemas describe arguments as supplied. For optional inputs, declare `:?` or
`:maybe` as appropriate; a destructuring default does not make an invalid supplied
value valid. Skeleton sample arguments must satisfy the same contract.

`extend-component`, `extend-spec`, `extend-module`, and `extend-page` start with
their respective common bases. `compose` can combine other map contracts. They
recursively merge Malli maps, with later fields refining/replacing earlier fields.
Use `[:and base extra]` when both constraints must independently hold.

```clojure
(require '[tolgraven.schema.declarations :as declarations])
(def cacheable-module
  (declarations/extend-module [:map [:cache-limit [:int {:min 1}]]]))
(def catalog-page
  (declarations/extend-page [:map [:permission :keyword]]))

(def module-spec
  {:id :catalog :schema cacheable-module :cache-limit 20})
(def pages
  [["/catalog" {:name :catalog :module :catalog :page :page
                 :schema catalog-page :permission :signed-in}]])
```

For nested Reitit data, put a common schema on the parent. To replace/refine it on
a child, explicitly compose the schema and mark that value `^:replace` so Reitit's
metadata merge does not concatenate schema vectors. `extend-page` describes route
data; `defpage` component options use `extend-component`, like other `defc` views.

## App-db by section

`tolgraven.schema.app-db/sections` contains the initial shared sections; blog owns
its definitions in `tolgraven.blog.schema`. Initial coverage includes selected UI
options/state, blog pagination and thread state, loader readiness, and the map
shape of persistent component/page/module/global/content roots. It does **not**
yet describe every content field, Supabase row, event or app-db entry.

```clojure
(require '[tolgraven.schema.app-db :as app-db]
         '[tolgraven.validation :as validation])

(def total-schema (app-db/schema app-db/sections))
(validation/check! :app-db total-schema
  {:state {:blog {:page 0 :comment-limit {28 20}
                 :comment-thread-expanded {[28 "comment-id"] true}}}})
```

`schema` recursively merges the sections into one optional-root schema. Missing
sections are allowed; a present field must match its schema. Keep deep schemas
near their owning module. SSR/local rendering also composes sections from the
available module specs. Add a section to the initial CLJC composition if state
can arrive before its owning module is available.

In the browser a global re-frame interceptor compares section references after an
event. It validates only sections that changed. An invalid transaction is rejected
as a whole: its app-db update and accompanying effects do not run. The existing
HUD reports the failure. Dev console → Errors shows grouped, bounded reports with
contract, event ID, exact path and readable constraint. Consecutive identical
reports increment a count. Raw values and event arguments are not recorded.

SSR/local rendering checks its assembled state before rendering. It uses the same
schemas without installing browser event interception in the Node worker.

## Reitit and Ring parameters

Use `tolgraven.schema.http/coercion` and shared Malli parameter schemas. Query maps
remain open so unrelated query parameters survive navigation.

```clojure
;; pages.cljc
(def parameters [:map [:page [:int {:min 1}]]])
(def spec
  [["/catalog/:page" {:name :catalog-page :module :catalog :page :page
                     :parameters {:path parameters}}]])
```

Read coerced values from `[:parameters :path]` / `[:parameters :query]`. Reitit
controllers receive those typed values too. Raw `:path-params`/`:query-params` are
still transport strings and should not be used by new handlers.

Current page schemas cover blog page number, post permalink, tag and documentation
name, plus boolean `userBox`/`settingsBox` queries. For example `/blog/page/2`
produces `{:nr 2}` and `?userBox=false` produces `{:userBox false}`. An invalid page
address gets a readable fallback; invalid direct requests receive HTTP 400.

Declared API schemas live in the same namespace: documentation, oEmbed, messages,
contact, numeric examples and uploads. Existing authenticated operation bodies
retain their broad map contract and their domain adapter validation; this change
does not claim full schemas for those operations.

API failures are negotiated responses, for example:

```json
{"error":"Invalid request parameters",
 "issues":[{"path":["y"],"message":"should be an integer"}]}
```

Response-contract failures return a generic HTTP 500 message. Coercion logging
omits the exception payload because it can contain credentials/request bodies.

## Tests and extending coverage

Use `validation/check!`, `validation/explain` or Malli directly with the production
schema; do not duplicate fixture contracts in tests. Add both accepted and rejected
examples and verify coercion through the actual router/handler. Browser tests
should dispatch real events and mount subscriptions, including the diagnostic
view. Check that invalid transactions retain previous data and suppress effects.

When adding a schema, update its owner, this guide's coverage description, and the
appropriate `AGENTS.md`. Keep those guides current with the implementation.
