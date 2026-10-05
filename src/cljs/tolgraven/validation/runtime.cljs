(ns tolgraven.validation.runtime
  "Development checks at declaration/event boundaries, never in subscriptions."
  (:require [tolgraven.react :as rf]
            [tolgraven.validation :as validation]
            [tolgraven.schema.app-db :as app-db]
            [tolgraven.schema.declarations :as declarations]))

(defn configured? []
  ;; Server emits only this public boolean. No browser APIs run in Node SSR.
  (let [override (cond
                   (exists? js/document)
                   (some-> (.querySelector js/document "meta[name=validation-enabled]")
                           (.getAttribute "content"))
                   (exists? js/process) (aget js/process.env "VALIDATION_ENABLED"))]
    (validation/enabled? {:validation-enabled override} goog.DEBUG)))
(defonce *enabled? (atom (configured?)))
(defonce *sections (atom app-db/sections))
(defonce ^:private *dynamic-paths (atom #{}))
(defonce ^:private *installed? (atom false))

(defn check! [contract schema value]
  (when @*enabled? (validation/check! contract schema value))
  value)

(defn register-sections!
  ([sections] (register-sections! sections {}))
  ([sections {:keys [dynamic?]}]
   (when @*enabled?
     ;; Compile once, at registration, so invalid schemas fail at their owner.
     (doseq [[_ schema] sections] (validation/compiled schema))
     (swap! *dynamic-paths #(if dynamic? (into % (keys sections)) (apply disj % (keys sections))))
     (swap! *sections merge sections))))

(defn- forget-sections! [paths]
  (swap! *sections #(apply dissoc % paths))
  (swap! *dynamic-paths #(apply disj % paths)))
(rf/reg-fx :validation/forget-sections forget-sections!)

(defn module! [spec]
  (check! (str "module " (:id spec)) (declarations/extend-module (:schema spec)) spec)
  (when-let [sections (:db-schema spec)] (register-sections! sections))
  spec)

(rf/reg-event-fx :validation/report
  (fn [{:keys [db]} [_ report]]
    (let [history (vec (get-in db [:diagnostics :validation]))
          duplicate? (= report (dissoc (peek history) :count))]
      (cond->
        {:db (assoc-in db [:diagnostics :validation]
                       (if duplicate?
                         (update-in history [(dec (count history)) :count] inc)
                         (->> (conj history (assoc report :count 1)) (take-last 20) vec)))}
        (not duplicate?)
        (assoc :dispatch [:diag/new :error (str "Schema validation: " (:contract report))
                          (validation/message (:issues report))
                          {:custom-id [:validation (:contract report) (:event report)] :sticky? true}])))))
(rf/reg-event-db :validation/dismiss
  (fn [db _] (update db :diagnostics dissoc :validation)))
(rf/reg-sub :validation/errors
  (fn [db _] (get-in db [:diagnostics :validation])))

(defn intercept [context]
  (let [before (rf/get-coeffect context :db)
        after (rf/get-effect context :db)
        event (first (rf/get-coeffect context :event))]
    (if (and @*enabled? (contains? (:effects context) :db) (not= event :validation/report))
      (if-let [issues (seq (app-db/changed-errors @*sections before after))]
        ;; Reject the whole transaction: running its network/timer effects with
        ;; an invalid db would break the same invariants we are checking.
        (assoc context :effects
               {:dispatch [:validation/report {:contract :app-db :event event
                                                :issues (vec issues)}]})
        (let [removed (app-db/removed-sections @*dynamic-paths before after)]
          ;; Run only for an accepted transaction, after re-frame commits :db.
          (cond-> context
            (seq removed) (update-in [:effects :fx] (fnil conj [])
                                    [:validation/forget-sections removed]))))
      context)))

(defn install! []
  (reset! *enabled? (configured?))
  (swap! *sections merge app-db/sections)
  ;; Hot reload replaces by ID. Disabled builds do no per-event validation work.
  (if @*enabled?
    (do (rf/reg-global-interceptor
         (rf/->interceptor :id :validation/app-db :after intercept))
        (reset! *installed? true))
    (do
      (forget-sections! @*dynamic-paths)
      (when @*installed?
        (rf/clear-global-interceptor :validation/app-db)
        (reset! *installed? false)))))

(defn validate-routes! [routes _]
  (when @*enabled?
    (doseq [[path data] routes]
      (check! (str "route " path) (declarations/extend-page (:schema data)) data))))
