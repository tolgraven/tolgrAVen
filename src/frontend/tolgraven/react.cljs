(ns tolgraven.react 
  (:refer-clojure :exclude [atom])
  (:require-macros [tolgraven.react])
  (:require
    [react-dom :as react-dom]
    [react :as react]
    [reagent.core :as r]
    [reagent.ratom]
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
(def use-state                 react/useState)
(def use-ref                   react/useRef)
(def create-context            react/createContext)
(def use-context               react/useContext)
(defn context-provider [context] (.-Provider context))
(def use-reducer               react/useReducer)

(defn- hook-dependencies [dependencies]
  ;; Convert only the container: maps, refs and functions must keep their identity
  ;; so React's Object.is dependency comparison retains its native semantics.
  (if (sequential? dependencies) (to-array dependencies) dependencies))

(defn- effect-setup [setup!]
  (fn []
    (let [cleanup! (setup!)]
      ;; Clojure's implicit nil means no cleanup. Preserve other results so React
      ;; still diagnoses accidental Promise/non-function returns.
      (if (nil? cleanup!) js/undefined cleanup!))))

(defn use-effect
  "Owned React effect; accepts Clojure dependency sequences and implicit nil cleanup."
  ([setup!] (react/useEffect (effect-setup setup!)))
  ([setup! dependencies]
   (react/useEffect (effect-setup setup!) (hook-dependencies dependencies))))

(defn use-layout-effect
  "Layout effect with the same Clojure dependency/cleanup contract as use-effect."
  ([setup!] (react/useLayoutEffect (effect-setup setup!)))
  ([setup! dependencies]
   (react/useLayoutEffect (effect-setup setup!) (hook-dependencies dependencies))))

(defn use-memo
  "Native memoization with shallow conversion of Clojure dependency sequences."
  ([calculate] (react/useMemo calculate))
  ([calculate dependencies]
   (react/useMemo calculate (hook-dependencies dependencies))))

(defn use-callback
  "Native callback identity/return values with Clojure dependency sequences."
  ([callback] (react/useCallback callback))
  ([callback dependencies]
   (react/useCallback callback (hook-dependencies dependencies))))
(defn component-argv
  "Recover Reagent's original Hiccup and metadata for function or class views."
  []
  (when-let [instance (r/current-component)]
    (if-let [argv (.-argv ^clj instance)]
      (if (instance? cljs.core/Subvec argv) (.-v ^cljs.core/Subvec argv) argv)
      (r/argv instance))))
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
