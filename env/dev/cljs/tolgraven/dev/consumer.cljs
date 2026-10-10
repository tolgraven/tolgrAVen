(ns tolgraven.dev.consumer
  "Observation only: query ownership on Reagent instances, without extra watches."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [tolgraven.dev.values :as values]))
(defonce *owners (js/WeakMap.))
(def ^:dynamic *collect* nil)
(defonce *enabled? (atom false))
(def query-limit 100)
(defn remember [reactions query reaction]
  (assoc (if (and (not (contains? reactions query)) (>= (count reactions) query-limit))
           (dissoc reactions (first (keys reactions))) reactions)
         query reaction))
(defn subscribe! [query reaction]
  (if *collect*
    (vswap! *collect* remember query reaction)
    ;; Complex defc :let bindings acquire before the measured body runs. Keep
    ;; those acquisitions on their same Reagent owner, without another watcher.
    (when-let [owner (r/current-component)]
      (.set *owners owner (update (or (.get *owners owner) {}) :reactions remember query reaction))))
  reaction)
(defn now [] (.now js/performance))
(defn cached-value [reaction]
  ;; Reagent 2.0.1 Reaction deref flushes globally outside a tracking context.
  ;; Its cached state is the only read-only value here: never deref, run, watch
  ;; or revive a subscription merely to explain a render. This version-specific
  ;; access stays in the dev adapter; unknown reaction types remain unresolved.
  (if (instance? ratom/Reaction reaction)
    (values/safe-value (.-state ^ratom/Reaction reaction))
    :debug/unavailable))
(defn render! [definition args render]
  (if-let [owner (when-not (or (string/starts-with? (:ns definition) "tolgraven.dev-console.")
                               (= "tolgraven.dev.stack" (:ns definition)))
                    (r/current-component))]
    (let [previous (.get *owners owner)
          *queries (volatile! (or (:reactions previous) {}))
          start (when @*enabled? (now))
          form (binding [*collect* *queries] (render))
          duration (when start (- (now) start))
          values (when @*enabled?
                   (into {} (map (fn [[q reaction]] [q (cached-value reaction)])) @*queries))
          args (when @*enabled? (values/safe-value args))
          changed (vec (for [[q value] values :when (not= value (get (:values previous) q ::missing))] q))
          reasons (cond-> []
                    (not (:rendered? previous)) (conj {:cause :mount})
                    (and (:rendered? previous) (not (:sampled? previous))) (conj {:cause :capture-start})
                    (and (:sampled? previous) (not= args (:args previous))) (conj {:cause :arguments})
                    (and (:sampled? previous) (seq changed)) (conj {:cause :subscription-results :queries changed})
                    (and (:sampled? previous) (= args (:args previous)) (empty? changed)) (conj {:cause :unclassified}))]
      (.set *owners owner {:rendered? true
                           :sampled? @*enabled?
                           :reactions @*queries
                           :values values
                           :args args
                           :duration duration
                           :start start
                           :end (when start (+ start duration))
                           :reasons reasons
                           :source (get-in definition [:options :source])})
      form)
    (render)))
(defn snapshot [owner]
  (let [value (.get *owners owner)]
    (assoc (dissoc value :reactions :args :rendered? :sampled?) :queries (vec (keys (:reactions value))))))
