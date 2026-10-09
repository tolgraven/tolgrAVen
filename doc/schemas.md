# Shared schemas and runtime validation

Keep Malli contracts beside their consumers. Feature contracts live under
`src/frontend/tolgraven/modules/<module>/`; reusable component inputs can be inline
beside the component. Browser and Node-renderer state/input contracts use `.cljs`.
Use `.cljc` when the JVM actually consumes the same definition: module-local
`pages.cljc`, blog data/query declarations, public CMS/Supabase data, HTTP parameters,
SSR snapshots and shared schema infrastructure. A JVM test alone is not a reason
to give browser state a portable namespace; those tests run in the browser suite.
See [source layout](source-layout.md) for ownership boundaries.
Maps are open by default: a partially migrated section does not reject unrelated
application data. Make a map closed explicitly when unknown keys are an error.

## Runtime policy

Internal checks default on in development and off in production. Configure:

```clojure
;; config/local.dev.edn or the deployed config.edn
{:validation {:enabled true}}
```

`VALIDATION_ENABLED=true` or `false` overrides the file. The server emits only the
public boolean in a meta element and passes it to Node renderer workers. Reload
the browser/restart the server after changing deployment configuration.

Disabled internal checks do not install the app-db interceptor or validate
component/module declarations. The schema code remains available in optimized
builds so a deployment can enable it without rebuilding. The interpreter lives in
the deferred `:coercion` browser bundle, with the same runtime policy after
installation.

Browser releases set `malli.registry/type` to `"custom"` and install the types in
`schema/registry.cljc` when the interpreter loads. The registry uses Malli's
extension constructors; unused function instrumentation and named branching
constructors can be eliminated. Predicate, scalar and sequence types remain
available for the application's declaration/input contracts. Browser tests use
this same registry; ordinary development and the Node/JVM renderer retain the
full one. Add a constructor when introducing an additional schema type.

CLJS page routers use the eager `schema/page_coercion.cljc` dispatcher and the
lazy `schema/malli_coercion.cljs` adapter, implementing Reitit's coercion protocol
with Malli validators, explainers and string decoders/encoders. It retains
open query maps, boolean false and redacted humanized errors. The JVM uses the
standard Reitit Malli adapter for Ring/API documentation and response coercion.
Swagger, JSON Schema, EDN schema serialization and lite-schema conversion are not
needed by the page adapter and stay out of the production browser graph.

Schema composition returns Malli's native `[:merge base extension ...]` data.
The interpreter resolves recursive map refinements when a contract is used;
namespace loading does not construct Malli schemas. JVM, Node SSR and development
retain eager interpretation.

The server supplies typed `:route-parameters` beside raw query parameters in each
network SSR pair. Initial hydration consumes them only for the matching path and
query. Production boot acquires `:coercion` after hydration, page readiness, window
load and painted idle frames. An earlier SPA navigation shares that acquisition,
then coerces before committing controllers/data; a newer URL supersedes older
pending requests. The router stays the same object. Client-only loads, local return
pairs and explicitly enabled internal validation acquire the engine during startup.
Disk envelopes wait for that engine and are always validated before restoration;
network SSR uses the already validated paired state rather than waiting on disk.
Later CMS responses also wait for the engine before validation/installation.

Request coercion is always active for declared HTTP and page parameters, including
production. Malformed external input must not reach handlers. Response checking
and internal declaration/state checking follow the runtime setting.
CMS bundle and persisted-envelope checks also remain active at their input
boundaries, replacing the previous handwritten validity checks. They prevent
corrupt content or saved state from entering the application.

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
;; src/frontend/my_site/catalog/schema.cljs
(def filters [:map [:search {:optional true} :string]
                   [:page {:optional true} [:int {:min 0}]]])
(def sections {[:state :catalog] filters})

;; src/frontend/my_site/catalog/module.cljs
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
;; schema.cljs
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

Inline annotations are also supported on `defc` and `defpage`, including
destructuring and multiple arities:

```clojure
(def label-spec (declarations/extend-spec [:map [:label :string]]))

(defc <label> {:features [:props :error-boundary]}
  [{:keys [label] :as spec} :- label-spec]
  [:span label])

(defc <count> [{:keys [title]} :- [:map [:title :string]] amount :- :int]
  [:p title ": " amount])

(defc <sum> [label :- :string & amounts :- :int]
  [:p label ": " (reduce + 0 amounts)])
```

Annotations are removed from the actual argument vector. They build the existing
`:args-schema`, with unannotated arguments accepting any value. A rest annotation
checks **each remaining argument**, not the rest collection; it also works with
`& [optional-callback] :- [:maybe fn?]`. For constraints involving several arguments,
provide `:args-schema`; if inline annotations are also present both must pass.
There is no return annotation or global function instrumentation. Inputs are
validated before destructuring, without adding component/DOM wrappers.

Persistent state can declare its own contract:

```clojure
(def counter-state [:map [:count [:int {:min 0}]]])
(defc <counter>
  {:state {:initial {:count 0} :schema counter-state
           :persist {:scope :public :version 1}}}
  []
  :let [*count (<sub :comp [:count])]
  [:button {:on-click #(>update *count inc)} @*count])
```

The resolved component path registers its schema for subsequent event updates.
Initial, restored and existing values are checked too. Invalid storage envelopes
are discarded at the storage boundary; retained data keeps its EDN types.
Dynamic contracts survive unmount alongside cached state and are released when
that state is explicitly deleted. Disabled validation does not register them.
Compiled validators have a bounded cache; assembled app-db schemas retain only
the current section set, so repeated instance creation does not retain every
historical schema assembly.

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

## Re-frame event and subscription contracts

The `tolgraven.react` registration macros accept an optional contract map immediately
after the event/subscription ID. Existing registrations retain their ordinary
re-frame signatures, including input subscriptions and event interceptors.
Keep reusable schemas in the owning module's `schema.cljs`; use `.cljc` when JVM consumers need them too.

```clojure
;; catalog/schema.cljs
(def item-id-args [:tuple [:int {:min 1}]])
(def item [:map [:id :int] [:title :string]])

;; catalog/events.cljs
(rf/reg-event-db :catalog/select
  {:args schema/item-id-args, :coerce :string}
  (fn [db [_ id]] (assoc-in db [:state :catalog :selected] id)))

;; catalog/subs.cljs
(rf/reg-sub :catalog/item
  {:args schema/item-id-args
   :coerce :string
   :result [:maybe schema/item]}
  (fn [db [_ id]] (get-in db [:catalog :items id])))
```

`:args` describes the vector **after the ID**. Use `[:tuple]` for no arguments,
`:tuple` for fixed arity, or `:cat` with `:?`/`:*` for optional/variadic inputs.
`:result` describes the value yielded when a mounted consumer dereferences a
subscription; `nil` during an outstanding read must be declared when appropriate.
These options also work with `reg-event-fx` and `reg-sub-raw`.

Validation follows the existing deployment flag. Coercion is explicit (`:string`
or `:json`) and remains active with validation disabled, so an event's intended
normalization does not change between development and production. Subscription
queries normalize before re-frame's cache lookup: equivalent normalized queries
share the usual subscription and managed source lifecycle. Optional
`:result-coerce` applies a transformer to the subscription result.

Invalid event arguments stop the transaction before user interceptors, handlers
or effects run. A redacted report enters the existing diagnostics/HUD flow.
Invalid subscription arguments or results throw to the component's nearest error
boundary by default. `:on-error :warn` delivers a bounded, deduplicated warning
and allows the value through for an intentional migration; invalid events remain
blocked regardless of notification severity. Reports contain schema paths and
messages, never the rejected payload. Domain subscription functions retain their
normal pure computations; the shared adapter owns checking and report delivery.

## App-db by section

`tolgraven.validation.schema/sections` contains the initial browser/Node sections; blog owns
its definitions in `tolgraven.modules.blog.schema`. The composition covers CMS sections, normalized public records and managed query caches,
blog pagination/thread state, navigation/forms/options, provider caches, loader
readiness, validation reports and inspector records. Persistent roots gain their
domain contracts from module sections or component `:state {:schema ...}`.

| Owner | Shared contracts |
| --- | --- |
| `content/schema.cljc` | All CMS sections; headings, media, menus, CV timelines, footer items and versioned bundles |
| `supabase/schema.cljc` | Queries, projected SQL rows, normalized profiles/posts/comments/chat, caches and closed write requests |
| Module-local `*/schema.cljs` | The owner's component inputs, forms, state, options and event/subscription contracts |
| `validation/schema.cljs` | Assembly of those module-owned state/form/option schemas plus shared navigation and diagnostics |
| Provider module `schema.cljs` | Consumed GitHub, Strava, Instagram and search response fields |
| `modules/blog/schema.cljs` | Blog state and parent-supplied post/comment component specs |
| `ssr/contract_schema.cljc` | JVM/Node public render snapshot and renderer settings |
| `ssr/schema.cljs` | Browser/Node return snapshots, storage envelopes and hydration state |
| `dev_console/schema.cljs` | Mounted instances, profiler/trace/epoch/layout metadata and bounded records |

Provider extension fields remain open. Function values, DOM/SDK objects, arbitrary
inspected EDN and generic component-owned payloads are not recursively prescribed.
A schema describes data that a consumer uses; it is not a duplicate of an entire
third-party API. Small private view signatures need no annotation when the owner
already validates their input. Add contracts for new meaningful CLJ/CLJS data and
public boundaries as part of the feature, rather than a later cleanup.

```clojure
(require '[tolgraven.schema.app-db :as app-db]
         '[tolgraven.validation :as validation]
         '[tolgraven.validation.schema :as state])

(def total-schema (app-db/schema state/sections))
(validation/check! :app-db total-schema
  {:state {:blog {:page 0 :comment-limit {28 20}
                 :comment-thread-expanded {[28 "comment-id"] true}}}})
```

`schema` recursively merges the sections into one optional-root schema. Missing
sections are allowed; a present field must match its schema. Keep deep schemas
near their owning module. SSR/local rendering also composes sections from the
available module specs. Add a section to the initial browser/Node composition if state
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
contact, numeric examples and uploads. Authenticated chat, comment, vote, post, profile and private-document bodies use
closed shared domain schemas. Direct operation callers use the same schemas;
authorization/RLS checks remain separate. Integration queries coerce search page
and page size and validate the shared service-path allowlists.

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
