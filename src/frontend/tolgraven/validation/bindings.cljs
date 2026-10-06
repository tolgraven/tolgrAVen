(ns tolgraven.validation.bindings
  "Contract boundary used by tolgraven.react. Domain computations stay ordinary
   re-frame handlers; this adapter owns coercion and bounded diagnostic delivery."
  (:require [tolgraven.validation :as validation]
            [re-frame.core :as rf]
            [reagent.ratom :as ratom]))

(defonce *enabled? (atom goog.DEBUG))
(defonce *contracts (atom {:event {}, :sub {}}))
(defonce ^:private *pending (atom {}))
(defonce ^:private *scheduled? (atom false))
(defonce ^:private *reported (atom {}))

(defn unregister! [kind id]
  ;; Re-registering a handler without a contract must also remove the previous
  ;; declaration, as happens when editing a namespace during hot reload.
  (swap! *contracts update kind dissoc id))

(defn register! [kind id options]
  (doseq [key [:coerce :result-coerce]
          :let [value (get options key)] :when value]
    (when-not (#{:string :json} value)
      (throw (ex-info "Unknown contract coercion" {:id id, :option key}))))
  (when-not (contains? #{nil :warn :error} (:on-error options))
    (throw (ex-info "Unknown contract failure policy" {:id id})))
  (when @*enabled?
    ;; Catch misspelled owner schema references during registration, not only
    ;; when a rarely used event or subscription eventually runs.
    (doseq [key [:args :result]
            :when (contains? options key)]
      (validation/compiled (get options key))))
  (swap! *contracts assoc-in [kind id] options)
  options)

(defn- report! [report]
  ;; Warnings at a subscription boundary must not synchronously dispatch while
  ;; React is rendering. Multiple consumers of one failure share the next tick.
  (when-not (= (:issues report) (get @*reported (:contract report)))
    (swap! *reported #(assoc (if (< (count %) 512) % {}) (:contract report) (:issues report)))
    (swap! *pending assoc (:contract report) report)
    (when (compare-and-set! *scheduled? false true)
      (js/queueMicrotask
        (fn []
          (let [reports (vals @*pending)]
            (reset! *pending {})
            (reset! *scheduled? false)
            (doseq [report reports] (rf/dispatch [:validation/report report]))))))))

(defn- failure [kind id options issues]
  {:contract (str (name kind) " " id)
   :severity (or (:on-error options) :error)
   :issues (vec issues)})

(defn query
  "Normalize a subscription vector before cache lookup, including alpha's map
   form. Equivalent coerced queries share re-frame's ordinary cached reaction."
  [query]
  (let [vector-form (if (map? query) (:re-frame/query-v query) query)
        id (first vector-form)
        {:keys [args coerce], :as options} (get-in @*contracts [:sub id])]
    (if-not args query
      (let [value (validation/decode args (vec (rest vector-form)) coerce)
            issues (when @*enabled? (validation/explain args value))]
        (when (seq issues)
          (let [report (failure :subscription-arguments id options issues)]
            (if (= :warn (:severity report)) (report! report)
                (throw (ex-info (validation/message issues)
                                (assoc report :type :validation/failed))))))
        (let [normalized (with-meta (into [id] value) (meta vector-form))]
          (if (map? query) (assoc query :re-frame/query-v normalized) normalized))))))

(defn event-interceptor [id {:keys [args coerce], :as options}]
  (rf/->interceptor
    :id :validation/event-arguments
    :before
    (fn [context]
      (if-not args context
        (let [event (rf/get-coeffect context :event)
              value (validation/decode args (vec (rest event)) coerce)
              issues (when @*enabled? (validation/explain args value))]
          (if (seq issues)
            ;; Retain the already-entered fx interceptor, but never reach user
            ;; interceptors or the handler. Invalid input cannot cause effects.
            (assoc context :queue #queue []
                   :effects {:dispatch [:validation/report
                                        (failure :event-arguments id options issues)]})
            (assoc-in context [:coeffects :event]
                      (with-meta (into [(first event)] value) (meta event)))))))))

(defn result [id {:keys [result result-coerce], :as options} value]
  (if-not result value
    (let [value (validation/decode result value result-coerce)
          issues (when @*enabled? (validation/explain result value))]
      (if-not (seq issues) value
        (let [report (failure :subscription-result id options issues)]
          (if (= :warn (:severity report))
            (do (report! report) value)
            (throw (ex-info (str "Invalid " (:contract report) "\n" (validation/message issues))
                            (assoc report :type :validation/failed)))))))))

(defn computation [id options handler]
  (fn [& inputs] (result id options (apply handler inputs))))

(defn source [id options handler]
  (fn [db q & dynamic-values]
    (let [input (apply handler db (query q) dynamic-values)]
      ;; Reagent tracks the source normally and releases its watch with the last
      ;; consumer. Never force-dispose a source another subscription may own.
      (ratom/make-reaction #(result id options @input)))))
