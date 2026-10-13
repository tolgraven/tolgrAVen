(ns tolgraven.react 
  (:refer-clojure :exclude [atom])
  (:require-macros [tolgraven.react])
  (:require
    [react-dom :as react-dom]
    [react :as react]
    [reagent.core :as r]
    [reagent.hooks :as hooks]
    [reagent.ratom]
    [tolgraven.component.compat :as compat]
    [tolgraven.validation.bindings :as contracts]
    [tolgraven.diagnostics.consumer :as consumer]
    [re-frame.core :as rf]
    [re-frame.subs.alpha :as subs-alpha]))

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
(def use-id                    react/useId)
(def use-state                 hooks/use-state)
(def use-ref                   hooks/use-ref)
(def use-id                    react/useId)
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

(def component-argv compat/argv)
(def flush-sync                react-dom/flushSync)


;; First-class API values remain available (e.g. passing dispatch to a helper).
;; The macro emits a symbol in this required namespace, avoiding an implicit
;; transitive dependency on the development-only consumer during hot reload.
(def observe-subscription! consumer/subscribe!)
;; Calls use the macros above and retain source metadata in debug builds.
(defn dispatch [& args] (apply rf/dispatch args))
(defn dispatch-sync [& args] (apply rf/dispatch-sync args))
(defn subscribe [query & args]
  (let [query (contracts/query query)]
    (consumer/subscribe! query (apply rf/subscribe query args))))
;; Use the lifecycle implementation directly: re-frame.alpha also registers
;; the core default event error handler a second time when both APIs load.
;; Safe reads outside render contexts retain re-frame's managed alpha lifecycle.
(defn sub [query]
  (let [query (contracts/query query)]
    (consumer/subscribe! query (subs-alpha/sub query))))
(defn reg-event-db [& args] (apply rf/reg-event-db args))
(defn reg-event-fx [& args] (apply rf/reg-event-fx args))
(defn reg-sub [& args] (apply rf/reg-sub args))
(defn reg-sub-raw [& args] (apply rf/reg-sub-raw args))
(defn reg-fx [& args] (apply rf/reg-fx args))
(defn reg-cofx [& args] (apply rf/reg-cofx args))
(def ->interceptor rf/->interceptor)
(def clear-subscription-cache! rf/clear-subscription-cache!)
(def debug rf/debug)
(def get-coeffect rf/get-coeffect)
(def get-effect rf/get-effect)
(def inject-cofx rf/inject-cofx)
(def set-loggers! rf/set-loggers!)

(def reg-global-interceptor rf/reg-global-interceptor)
(def clear-global-interceptor rf/clear-global-interceptor)
