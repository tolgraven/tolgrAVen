(ns tolgraven.react.runtime
  "Low-level framework boundary for the public facade and its contract/observer
   adapters. It has no application dependencies, so those adapters cannot cycle
   back into the instrumented facade. Ordinary components use tolgraven.react."
  (:refer-clojure :exclude [atom flush])
  (:require [react-dom :as react-dom]
            [react :as react]
            [reagent.core]
            [reagent.hooks :as hooks]
            [reagent.ratom]
            [reagent.dom.client]
            [reagent.impl.batching]
            [re-frame.router]
            [re-frame.interop]
            [re-frame.subs]
            [tolgraven.react.targets :as targets]
            [re-frame.core]
            [re-frame.db]
            [re-frame.registrar]
            [re-frame.std-interceptors]))

(def adapt-react-class         reagent.core/adapt-react-class)
(def argv                      reagent.core/argv)
(def as-element                reagent.core/as-element)
(def atom                      reagent.core/atom)
(def children                  reagent.core/children)
(def create-class              reagent.core/create-class)
(def create-element            reagent.core/create-element)
(def lazy                      react/lazy)
(def start-transition          react/startTransition)
(def reactify-component        reagent.core/reactify-component)
(def make-reaction             reagent.ratom/make-reaction)
(def track                     reagent.core/track)
(def track!                    reagent.core/track!)
(def create-portal             react-dom/createPortal)
(def suspense                  (adapt-react-class react/Suspense))
(def strict-mode               react/StrictMode)
(def profiler                  react/Profiler)
(def use-state                 hooks/use-state)
(def use-ref                   hooks/use-ref)
(def create-context            react/createContext)
(def use-context               hooks/use-context)
(defn context-provider [context] (.-Provider context))
(def use-reducer               hooks/use-reducer)
(def use-sync-external-store   hooks/use-sync-external-store)
;; Upstream owns Clojure equality, state identity and cleanup adaptation.
;; Dependency collections are Clojure values; native handles remain opaque.
(def use-effect                hooks/use-effect)
(def use-layout-effect         hooks/use-layout-effect)
(def use-memo                  hooks/use-memo)
(def use-callback              hooks/use-callback)
(defn- set-ref! [reference element]
  (cond
    (fn? reference) (reference element)
    reference (do (set! (.-current ^js reference) element) nil)))

(defn compose-refs
  "Forward React's node/nil callback to each ref. Ordinary refs need no cleanup
   manager; forward an explicit React 19 cleanup only when a caller returns one."
  [& references]
  (let [references' (vec (distinct (remove nil? references)))]
    (fn [element]
      (let [results (mapv #(set-ref! % element) references')]
        (when (and element (some fn? results))
          (fn []
            (doseq [[reference result] (map vector references' results)]
              (if (fn? result) (result) (set-ref! reference nil)))))))))

(defn component-argv []
  (when-let [instance (reagent.core/current-component)]
    (if-let [arguments (.-argv ^clj instance)]
      (if (instance? cljs.core/Subvec arguments)
        (.-v ^cljs.core/Subvec arguments)
        arguments)
      (reagent.core/argv instance))))
(def flush-sync                react-dom/flushSync)



(def after-render reagent.core/after-render)
(def current-component reagent.core/current-component)
(def dispose! reagent.core/dispose!)
(def flush reagent.core/flush)
(def merge-props reagent.core/merge-props)
(def rswap! reagent.core/rswap!)
(def set-state reagent.core/set-state)
(def state reagent.core/state)
(def unsafe-html reagent.core/unsafe-html)
(def flush! reagent.ratom/flush!)
(def run reagent.ratom/run)
(def path re-frame.std-interceptors/path)
(def clear-event re-frame.core/clear-event)
(def make-restore-fn re-frame.core/make-restore-fn)
(def get-handler re-frame.registrar/get-handler)
(def register-handler re-frame.registrar/register-handler)
;; This is the framework's existing atom, for boundary adapters/test fixtures.
;; Views still read subscriptions and write registered events.
(def *app-db re-frame.db/app-db)
(def preinit react-dom/preinit)
(def create-root reagent.dom.client/create-root)
(def hydrate-root reagent.dom.client/hydrate-root)
(def render reagent.dom.client/render)
(def unmount reagent.dom.client/unmount)
(def native-suspense react/Suspense)

(defn without-tracking [read-value]
  (binding [reagent.ratom/*ratom-context* nil] (read-value)))

(defn reaction? [value] (instance? reagent.ratom/Reaction value))
(defn reaction-value [reaction] (.-state ^reagent.ratom/Reaction reaction))
(def dispatch re-frame.core/dispatch)
(def dispatch-sync re-frame.core/dispatch-sync)
(def dispatch-sync-with re-frame.core/dispatch-sync-with)
(def subscribe re-frame.core/subscribe)
(def reg-event-db re-frame.core/reg-event-db)
(def reg-event-fx re-frame.core/reg-event-fx)
(def reg-sub re-frame.core/reg-sub)
(def reg-sub-raw re-frame.core/reg-sub-raw)
(def reg-fx re-frame.core/reg-fx)
(def reg-cofx re-frame.core/reg-cofx)
(def ->interceptor re-frame.core/->interceptor)
(def clear-subscription-cache! re-frame.core/clear-subscription-cache!)
(def debug re-frame.core/debug)
(def get-coeffect re-frame.core/get-coeffect)
(def get-effect re-frame.core/get-effect)
(def inject-cofx re-frame.core/inject-cofx)
(def set-loggers! re-frame.core/set-loggers!)
(def reg-global-interceptor re-frame.core/reg-global-interceptor)
(def clear-global-interceptor re-frame.core/clear-global-interceptor)

(defn dispatch-function [] re-frame.core/dispatch)
(defn subscribe-function [] re-frame.core/subscribe)
(defn set-dispatch-function! [callback] (set! re-frame.core/dispatch callback))
(defn batching-flush [] reagent.impl.batching/react-flush)
(defn set-batching-flush! [callback] (set! reagent.impl.batching/react-flush callback))
(def queue-dispatch re-frame.router/dispatch)
(def set-timeout! re-frame.interop/set-timeout!)
(def cache-lookup re-frame.subs/cache-lookup)
(def *subscription-cache re-frame.subs/query->reaction)





(def render-to-string targets/render-to-string)
(def render-to-static-markup targets/render-to-static-markup)
(def kind->id->handler targets/kind->id->handler)
(def live-query-vs targets/live-query-vs)
(def query->reaction targets/query->reaction)
(def query-v-for-reaction targets/query-v-for-reaction)
(def register-epoch-cb targets/register-epoch-cb)
(def register-trace-cb targets/register-trace-cb)
(def remove-epoch-cb targets/remove-epoch-cb)
(def remove-trace-cb targets/remove-trace-cb)
(def dispatch-and-settle re-frame.core/dispatch-and-settle)
(def dispatch-with re-frame.core/dispatch-with)
