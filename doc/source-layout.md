# Source layout

Namespaces follow their source paths. Ownership determines the location; runtime
portability determines the extension.

| Location | Responsibility |
| --- | --- |
| `src/frontend/tolgraven/modules/<feature>/` | Feature module specs, views, events, subscriptions, schemas and portable page/data declarations |
| `src/frontend/tolgraven/components/` | Reusable rendered UI, page roots and the site shell |
| `src/frontend/tolgraven/component/` | `defc` declaration, lifecycle, managed data, motion and persistence runtime |
| `src/frontend/tolgraven/navigation/` | Router, page preload, transitions, scroll restoration and their contracts |
| `src/frontend/tolgraven/validation/` | Browser/Node state assembly and runtime validation adapters |
| `src/frontend/tolgraven/dev_console/` | Development instrumentation and inspector tooling |
| `src/cljc/tolgraven/` | Contracts and algorithms consumed by both JVM and CLJS |
| `src/backend/tolgraven/` | HTTP, renderer, provisioning, transport and other server adapters |
| `experiments/clj/` | Preserved JVM prototypes, enabled only by the experiments profile |

Module IDs, route names, event IDs and state keys are independent of source paths.
Keep them stable when moving code. Update Shadow module entries and loadable
namespaces together. Route declarations remain independent of module views so
both JVM and browser routers can compose them.

Keep an input schema inline when one component consumes it. Module schemas may be
separate when several components/events use them or initial validation must load
before the lazy implementation. Small single-use shell-state schemas are inline
in `validation/schema.cljs`; avoid empty feature directories containing only a
schema. Provider response contracts belong to their provider module.

Browser and Node-only contracts use `.cljs`. Portable `.cljc` declarations stay
beside their module when feature-owned; public platform contracts and generic
composition helpers stay in `src/cljc`. Run browser-only schema assertions in the
browser suite instead of making production contracts portable for test discovery.

Platform adapters such as `content/`, `supabase/`, and `ssr/` span source roots and
are not lazy feature modules. They retain platform ownership rather than moving
into a feature module simply because several views use them.
