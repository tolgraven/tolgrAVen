(ns tolgraven.react 
  (:refer-clojure :exclude [atom])
  (:require-macros [tolgraven.react])
  (:require
    [react-dom :as react-dom]
    [react :as react]
    [reagent.core :as r]
    [reagent.ratom]
    [tolgraven.validation.bindings :as contracts]
    [re-frame.core :as rf]
    [re-frame.alpha :as alpha]))

(def adapt-react-class         reagent.core/adapt-react-class)
(def argv                      reagent.core/argv)
(def as-element                reagent.core/as-element)
(def atom                      reagent.core/atom)
(def children                  reagent.core/children)
(def create-class              reagent.core/create-class)
(def cursor                    reagent.core/cursor)
(def lazy                      react/lazy)
(def reactify-component        reagent.core/reactify-component)
(def make-reaction             reagent.ratom/make-reaction)
(def track                     reagent.core/track)
(def track!                    reagent.core/track!)
(def create-portal             react-dom/createPortal)
(def suspense                  (adapt-react-class react/Suspense))
(def strict-mode               react/StrictMode)
(def profiler                  react/Profiler)
(def use-state                 react/useState)
(def use-ref                   react/useRef)
(def create-context            react/createContext)
(def use-context               react/useContext)
(defn context-provider [context] (.-Provider context))
(def use-effect                react/useEffect)
(def use-layout-effect         react/useLayoutEffect)
(def use-callback              react/useCallback)
(def use-reducer               react/useReducer)
(defn component-argv
  "Recover Reagent's original Hiccup and metadata for function or class views."
  []
  (when-let [instance (r/current-component)]
    (if-let [argv (.-argv ^clj instance)]
      (if (instance? cljs.core/Subvec argv) (.-v ^cljs.core/Subvec argv) argv)
      (r/argv instance))))
(def flush-sync                react-dom/flushSync)


;; First-class API values remain available (e.g. passing dispatch to a helper).
;; Calls use the macros above and retain source metadata in debug builds.
(defn dispatch [& args] (apply rf/dispatch args))
(defn dispatch-sync [& args] (apply rf/dispatch-sync args))
(defn subscribe [query & args] (apply rf/subscribe (contracts/query query) args))
;; Safe reads outside render contexts use re-frame's managed alpha lifecycle.
(defn sub [query] (alpha/sub (contracts/query query)))
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
