(ns tolgraven.dev-console.capture
  "Lifecycle adapters only: bounded queued traces, Profiler commits and layout shifts."
  (:require [clojure.data :as data]
            [clojure.string :as string]
            [re-frame.tooling :as tooling]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.dev-console.state]
            [tolgraven.dev-console.components :as components]
            [tolgraven.dev.consumer :as consumer]
            [tolgraven.dev.values :as values]))

(defonce *pending (atom []))
(defonce *tick (atom nil))
(defonce *cleanup (atom nil))
(defonce *connected? (r/atom false))
(defonce *publishing? (atom false))
(defonce *recording? (atom false))
(defonce *flash? (atom false))
;; Probe lifecycles begin during hydration, before the interactive console mounts.
(defonce *instances (atom {}))
(defonce *component-parents (r/atom {}))
(defn resolve-instance [record]
  (merge (dissoc record :resolve!) (when-let [resolve! (:resolve! record)] (resolve!))))
(declare drain!)

(defn connect!
  "Enable queued instance tracking only while its console consumer is mounted."
  []
  (reset! *connected? true)
  (reset! *publishing? true)
  (drain!)
  (when (seq @*instances)
    (rf/dispatch [:dev-console/records (mapv resolve-instance (vals @*instances))]))
  (fn []
    (reset! *connected? false)
    (reset! *publishing? false)
    (reset! consumer/*enabled? false)
    (when @*tick (js/clearTimeout @*tick))
    (reset! *tick nil) (reset! *pending [])
    (rf/dispatch [:dev-console/disconnected])))
(defn own? [event]
  (boolean (some #(or (and (keyword? %) (or (= :dev-console %) (contains? #{"dev-console" "dev-stack" "dev-source"} (namespace %))))
                     (and (string? %) (string/starts-with? % "tolgraven.dev-console")))
                 (tree-seq coll? seq event))))
(defn drain! []
  (when @*tick (js/clearTimeout @*tick))
  (reset! *tick nil)
  ;; Early capture may precede the console's commit during selective hydration.
  ;; Buffer observations without invalidating its not-yet-mounted subscriptions.
  (when @*publishing?
    (let [batch @*pending]
      (reset! *pending [])
      (when (seq batch) (rf/dispatch [:dev-console/records batch])))))
(defn emit! [record]
  (when (and ^boolean goog.DEBUG (not context/*server?*))
    (case (:kind record)
      :mount (do (swap! *instances assoc (:instance record) record)
                 ;; Declaration-level placements are tiny metadata, retained so
                 ;; a conditional child stays discoverable after it unmounts.
                 (swap! *component-parents update (:component record)
                        (fn [parents] (set (take 16 (conj (or parents #{}) (:parent-component record)))))))
      :unmount (swap! *instances dissoc (:instance record))
      nil))
  (when (and ^boolean goog.DEBUG @*connected? (not context/*server?*))
    (swap! *pending #(vec (take-last 500 (conj % (if (= :mount (:kind record)) (resolve-instance record) record)))))
    (when (and @*publishing? (nil? @*tick))
      (reset! *tick (js/setTimeout drain! 200)))))
(defn public-db [db]
  (-> db (dissoc :dev-console)
      (update :component dissoc "tolgraven.dev-console.views")))

(defn preview
  "Bound retained debug payloads; never hold SDK objects or unbounded lazy data."
  ([value] (preview value 0))
  ([value depth]
   (cond
     (> depth 7) :debug/elided
     (string? value) (if (> (count value) 8000) (str (subs value 0 8000) "…") value)
     (map? value) (into {} (map (fn [[k v]] [k (if (values/sensitive? k) :debug/redacted (preview v (inc depth)))])) (take 40 value))
     (vector? value) (mapv #(preview % (inc depth)) (take 40 value))
     (set? value) (into #{} (map #(preview % (inc depth))) (take 40 value))
     (sequential? value) (doall (map #(preview % (inc depth)) (take 40 value)))
     (or (nil? value) (boolean? value) (number? value) (keyword? value) (symbol? value) (uuid? value)) value
     (fn? value) :debug/function
     :else :debug/object)))

(defn trace-records [traces]
  ;; Descendants of a console event may have no event tag of their own.
  (let [index (into {} (map (juxt :id identity)) traces)
        own-trace? (fn [trace]
                     (loop [trace trace seen #{}]
                       (cond
                         (nil? trace) false
                         (or (own? (get-in trace [:tags :event]))
                             (own? (get-in trace [:tags :query-v]))) true
                         (seen (:id trace)) false
                         :else (recur (get index (:child-of trace)) (conj seen (:id trace))))))]
    (for [trace traces
          :let [query (get-in trace [:tags :query-v])]
          :when (and (not (own-trace? trace))
                     (not (and (#{:sub/run :sub/create} (:op-type trace))
                               (#{:get :state :debug} (first query)))))]
      (-> (select-keys trace [:id :child-of :operation :op-type :start :end :duration])
          (assoc :kind :trace
                 :tags (preview (select-keys (:tags trace)
                                  [:event :query-v :cached? :source :re-frame/source :error :value])))))))

(defn epoch-record [epoch]
  (let [[removed added] (data/diff (public-db (:app-db/before epoch))
                                 (public-db (:app-db/after epoch)))]
    (-> (select-keys epoch [:event :event/original :dispatch-id :parent-dispatch-id
                           :start :end :duration :interceptors :event/source])
        (update :event preview)
        (cond-> (contains? epoch :event/original) (update :event/original preview))
        (update :interceptors preview)
        (assoc :kind :epoch :effects (preview (dissoc (:effects epoch) :db))
               :removed (preview removed) :added (preview added)))))

(defn settled-result [result]
  (cond-> (select-keys result [:ok? :reason :event :error])
    (:root-epoch result) (assoc :root-epoch (epoch-record (:root-epoch result)))
    (:cascaded-epochs result) (assoc :cascaded-epochs (mapv epoch-record (take 40 (:cascaded-epochs result))))
    (:captured-epochs result) (assoc :captured-epochs (mapv epoch-record (take 40 (:captured-epochs result))))))

(defn element-for [node]
  (if (= 1 (some-> node .-nodeType)) node (some-> node .-parentElement)))
(defn layout-source [source]
  (let [el (element-for (.-node source))]
    {:component (some-> el (.closest "[data-dev-component]") (.getAttribute "data-dev-component"))
     :inspector? (boolean (some-> el (.closest "[data-dev-console]")))
     :element (when el (str (.-tagName el)
                           (when-let [id (not-empty (.-id el))] (str "#" id))
                           (when-let [class (not-empty (.getAttribute el "class"))]
                             (str "." (string/replace (subs class 0 (min 160 (count class))) " " ".")))))
     :before (some-> (.-previousRect source) .toJSON (js->clj :keywordize-keys true))
     :after (some-> (.-currentRect source) .toJSON (js->clj :keywordize-keys true))}))
(defn layout-scope [sources]
  (cond (empty? sources) :unknown
        (every? :inspector? sources) :inspector
        (some :inspector? sources) :mixed
        :else :page))
(defn layout-record [entry]
  (let [sources (mapv layout-source (array-seq (.-sources entry)))
        start (.-startTime entry) input (.-lastInputTime entry)]
    {:kind :layout :start start :value (.-value entry) :scope (layout-scope sources)
     :path (when (exists? js/location) (.-pathname js/location))
     :user-input? (.-hadRecentInput entry)
     :last-input (when (and (number? input) (pos? input)) input)
     :since-input (when (and (number? input) (pos? input)) (- start input))
     :sources sources}))

(defn start!
  "One observer/callback set per console lifecycle, including hot reload cleanup."
  []
  (when-let [cleanup! @*cleanup] (cleanup!))
  (reset! *recording? true)
  (reset! consumer/*enabled? true)
  (when (and ^boolean goog.DEBUG (not context/*server?*))
    ;; Writing debug records must not recursively capture its own subscription
    ;; recomputations. Collect raw traces only in batches with an app event;
    ;; Profiler commits/layout shifts are captured independently below.
    (tooling/register-trace-cb :tolgraven-console
      #(when (some (fn [trace] (and (= :event (:op-type trace))
                                   (not (own? (get-in trace [:tags :event]))))) %)
         (doseq [record (trace-records %)] (emit! record))))
    (tooling/register-epoch-cb :tolgraven-console
      #(doseq [epoch % :when (not (own? (:event epoch)))]
         (let [record (epoch-record epoch)]
           (emit! record)
           (when @*flash?
             (let [instances (vec (for [[id {:keys [path]}] @*instances
                                        :when (and (seq path)
                                                   (not= (get-in (:app-db/before epoch) path)
                                                         (get-in (:app-db/after epoch) path)))] id))]
               (when (seq instances)
                 (rf/dispatch [:dev-console/flash-instances instances])))))))
    (let [observer (when (and (exists? js/PerformanceObserver)
                             (some #{"layout-shift"} (array-seq (.-supportedEntryTypes js/PerformanceObserver))))
                     (js/PerformanceObserver.
                       (fn [entries _]
                         (doseq [entry (array-seq (.getEntries entries))]
                           (let [record (layout-record entry)]
                             ;; Recording the inspector's changing record list would
                             ;; cause another shift and another record on each drain.
                             ;; Keep mixed/unknown entries: attribution is incomplete.
                             (when-not (= :inspector (:scope record)) (emit! record)))))))]
      (when observer (.observe observer #js {:type "layout-shift" :buffered false}))
      (let [cleanup! (fn []
                       (reset! *recording? false)
                       (reset! consumer/*enabled? false)
                       (tooling/remove-trace-cb :tolgraven-console)
                       (tooling/remove-epoch-cb :tolgraven-console)
                       (when observer (.disconnect observer))
                       ;; Lifecycle records still drain while timing capture is paused.
                       (reset! *cleanup nil))]
        (reset! *cleanup cleanup!) cleanup!))))

(defn bootstrap! []
  ;; Explicit development capture before the first application render. The host
  ;; adopts this lifecycle on mount and owns its eventual stop/cleanup.
  (when (= "1" (.get (js/URLSearchParams. (.-search js/location)) "diagnostics"))
    (reset! tolgraven.dev-console.state/*startup-capture? true)
    (reset! *connected? true)
    (reset! consumer/*enabled? true)
    (start!)))

(rf/reg-fx :dev-console/flash-component
  (fn [instance]
    (when-let [roots (seq (components/native-roots @*instances instance))]
      (rf/dispatch [:dev-console/flash-instances (vec roots)]))))
