(ns website.react
  (:refer-clojure :exclude [atom flush])
  (:require-macros [website.react])
  (:require [website.react.runtime :as runtime]
            [website.validation.bindings :as contracts]
            [website.diagnostics.consumer :as consumer]
            [re-frame.subs.alpha :as subs-alpha]))

(def adapt-react-class runtime/adapt-react-class)
(def argv runtime/argv)
(def as-element runtime/as-element)
(def atom runtime/atom)
(def children runtime/children)
(def create-class runtime/create-class)
(def create-element runtime/create-element)
(def lazy runtime/lazy)
(def start-transition runtime/start-transition)
(def reactify-component runtime/reactify-component)
(def make-reaction runtime/make-reaction)
(def track runtime/track)
(def track! runtime/track!)
(def create-portal runtime/create-portal)
(def suspense runtime/suspense)
(def strict-mode runtime/strict-mode)
(def profiler runtime/profiler)
(def use-state runtime/use-state)
(def use-ref runtime/use-ref)
(def create-context runtime/create-context)
(def use-context runtime/use-context)
(def context-provider runtime/context-provider)
(def use-reducer runtime/use-reducer)
(def use-sync-external-store runtime/use-sync-external-store)
(def use-effect runtime/use-effect)
(def use-layout-effect runtime/use-layout-effect)
(def use-memo runtime/use-memo)
(def use-callback runtime/use-callback)
(def use-deferred-value runtime/use-deferred-value)
(def compose-refs runtime/compose-refs)
(def component-argv runtime/component-argv)
(def flush-sync runtime/flush-sync)
(def after-render runtime/after-render)
(def current-component runtime/current-component)
(def dispose! runtime/dispose!)
(def flush runtime/flush)
(def merge-props runtime/merge-props)
(def rswap! runtime/rswap!)
(def set-state runtime/set-state)
(def state runtime/state)
(def unsafe-html runtime/unsafe-html)
(def flush! runtime/flush!)
(def run runtime/run)
(def path runtime/path)
(def clear-event runtime/clear-event)
(def make-restore-fn runtime/make-restore-fn)
(def get-handler runtime/get-handler)
(def register-handler runtime/register-handler)
(def *app-db runtime/*app-db)
(def preinit runtime/preinit)
(def create-root runtime/create-root)
(def hydrate-root runtime/hydrate-root)
(def render runtime/render)
(def unmount runtime/unmount)
(def native-suspense runtime/native-suspense)
(def without-tracking runtime/without-tracking)
(def reaction? runtime/reaction?)
(def reaction-value runtime/reaction-value)

;; First-class API values remain available (e.g. passing dispatch to a helper).
;; The macro emits a symbol in this required namespace, avoiding an implicit
;; transitive dependency on the development-only consumer during hot reload.
(def observe-subscription! consumer/subscribe!)
;; Calls use the macros above and retain source metadata in debug builds.
(defn dispatch [& args] (apply (runtime/dispatch-function) args))
(defn dispatch-sync [& args] (apply runtime/dispatch-sync args))
(defn subscribe [query & args]
  (let [query' (contracts/query query)]
    (consumer/subscribe! query' (apply (runtime/subscribe-function) query' args))))
;; Use the lifecycle implementation directly: re-frame.alpha also registers
;; the core default event error handler a second time when both APIs load.
;; Safe reads outside render contexts retain re-frame's managed alpha lifecycle.
(defn sub [query]
  (let [query (contracts/query query)]
    (consumer/subscribe! query (subs-alpha/sub query))))
(defn reg-event-db [& args] (apply runtime/reg-event-db args))
(defn reg-event-fx [& args] (apply runtime/reg-event-fx args))
(defn reg-sub [& args] (apply runtime/reg-sub args))
(defn reg-sub-raw [& args] (apply runtime/reg-sub-raw args))
(defn reg-fx [& args] (apply runtime/reg-fx args))
(defn reg-cofx [& args] (apply runtime/reg-cofx args))
(def ->interceptor runtime/->interceptor)
(def clear-subscription-cache! runtime/clear-subscription-cache!)
(def debug runtime/debug)
(def get-coeffect runtime/get-coeffect)
(def get-effect runtime/get-effect)
(def inject-cofx runtime/inject-cofx)
(def set-loggers! runtime/set-loggers!)

(def reg-global-interceptor runtime/reg-global-interceptor)
(def clear-global-interceptor runtime/clear-global-interceptor)

(def dispatch-function runtime/dispatch-function)
(def subscribe-function runtime/subscribe-function)
(def set-dispatch-function! runtime/set-dispatch-function!)
(def batching-flush runtime/batching-flush)
(def set-batching-flush! runtime/set-batching-flush!)
(def queue-dispatch runtime/queue-dispatch)
(def set-timeout! runtime/set-timeout!)
(def cache-lookup runtime/cache-lookup)
(def *subscription-cache runtime/*subscription-cache)





(def dispatch-sync-with runtime/dispatch-sync-with)

(def render-to-string runtime/render-to-string)
(def render-to-static-markup runtime/render-to-static-markup)
(def dispatch-and-settle runtime/dispatch-and-settle)
(def dispatch-with runtime/dispatch-with)
(def kind->id->handler runtime/kind->id->handler)
(def live-query-vs runtime/live-query-vs)
(def query->reaction runtime/query->reaction)
(def query-v-for-reaction runtime/query-v-for-reaction)
(def register-epoch-cb runtime/register-epoch-cb)
(def register-trace-cb runtime/register-trace-cb)
(def remove-epoch-cb runtime/remove-epoch-cb)
(def remove-trace-cb runtime/remove-trace-cb)

(def use-id runtime/use-id)
