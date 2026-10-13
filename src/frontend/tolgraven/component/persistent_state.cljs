(ns tolgraven.component.persistent-state
  (:require [tolgraven.component.instrumentation :as instrumentation]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.validation :as value-validation]
            [clojure.string :as string]
            [tolgraven.content.contract :as content]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [reagent.ratom :as ratom]
            [tolgraven.schema.declarations :as declarations]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.storage :as storage]))

(def ^:dynamic *component* nil)
(def ^:dynamic *args* nil)
(def ^:dynamic *react-key* nil)

;; Native subscription references remain owned by re-frame. This weak index
;; carries only resolved write destinations; it contains no application values.
(defonce state-paths (js/WeakMap.))

(defn- entry-at [db path]
  (let [parent (get-in db (pop path))
        present? (and (associative? parent) (contains? parent (peek path)))]
    {:present? present?
     :value (when present? (get parent (peek path)))
     :revision (get-in db [:component-revisions path] 0)}))

(rf/reg-sub :component-state/entry (fn [db [_ path]] (entry-at db path)))
(rf/reg-event-db :component-state/init
  (fn [db [_ path value]]
    (if (:present? (entry-at db path)) db (assoc-in db path value))))
(rf/reg-event-db :component-state/restore
  (fn [db [_ path revision value]]
    (if (= revision (get-in db [:component-revisions path] 0)) (assoc-in db path value) db)))
(rf/reg-sub :component-state/value (fn [db [_ path]] (get-in db path)))
(defn- edited [db path]
  (update-in db [:component-revisions (or (:component-root (meta path)) path)] (fnil inc 0)))
(rf/reg-event-db :component-state/set
  (fn [db [_ path value]] (edited (assoc-in db path value) path)))
(defn declared-options [definition args]
  (let [configuration (get-in definition [:options :state])]
    (or (if (fn? configuration) (apply configuration args) configuration) {})))

(defn path-for [options]
  (let [definition *component*
        options' (merge (declared-options definition *args*) options)
        key (if (some? *react-key*) *react-key*
                (let [manual (:key options')]
                  (if (fn? manual) (apply manual *args*) manual)))]
    (when (and (nil? definition) (nil? (:id options')))
      (throw (js/Error. "State outside defc requires an explicit :id")))
    (let [path (cond-> [:component (or (:ns definition) "external")
                        (or (:id options') (:name definition))]
                 (some? key) (conj key)
                 (= :page (:scope options')) (conj :page (restore/page-key))
                 (= :user (get-in options' [:persist :scope])) (conj :account @storage/*identity))]
      (with-meta path
        {:component-path true :component-root path
         :private? (= :user (get-in options' [:persist :scope]))
         :owner @storage/*identity}))))

(instrumentation/register-path-resolver!
  (fn [definition args key]
    (binding [*component* definition *args* args *react-key* key]
      (path-for {}))))

(defn- accessible-path? [path]
  (or (not (:private? (meta path))) (= (:owner (meta path)) @storage/*identity)))
(defn expand-path
  "Resolve a relative component path now; returned absolute paths can be passed
   to event callbacks after the dynamic component context has ended."
  ([path] (expand-path path {}))
  ([path options]
   (when-not (vector? path) (throw (js/Error. "Component paths must be vectors")))
   (if (:component-path (meta path)) path
       (into (path-for options) path))))
(rf/reg-event-db :component-state/update
  (fn [db [_ path f args]]
    (if (accessible-path? path) (edited (apply update-in db path f args) path) db)))
(rf/reg-event-db :component-state/reset
  (fn [db [_ path value]]
    (if (accessible-path? path) (edited (assoc-in db path value) path) db)))
(defn- module-id [options]
  (or (:module options) (get-in *component* [:options :module])
      (when-let [ns-name (:ns *component*)]
        (or (some (fn [part]
                    (let [id (keyword part)] (when (contains? content/module-dependencies id) id)))
                  (rest (string/split ns-name #"\.")))
            :main))
      (throw (js/Error. "Module state outside a component requires :module"))))

(defn scoped-path
  "Resolve scope now. Returned paths retain routing and account metadata."
  ([scope path] (scoped-path scope path {}))
  ([scope path options]
   (when-not (vector? path) (throw (js/Error. "State paths must be vectors")))
   (if (#{:comp :component} scope)
     (expand-path path options)
     (let [root (case scope
                  :module [:module (module-id options)]
                  :page [:page (or (:page options) (restore/page-key))]
                  (:global :shared) [:state]
                  (throw (ex-info "Unknown state scope" {:scope scope})))
           root' (cond-> root
                   (= :user (get-in options [:persist :scope])) (conj :account @storage/*identity))
           full (into root' path)]
       (with-meta full {:component-path true :component-root full
                        :private? (= :user (get-in options [:persist :scope]))
                        :owner @storage/*identity})))))

(defn path-of
  "Accept a scoped state handle, a resolved path, or a scope-qualified path.
   [:global ...] routes to shared :state; [:comp ...] uses current component."
  [target]
  (if-let [path (or (when (and (some? target) (= "object" (goog/typeOf target)))
                     (.get state-paths target))
                   (:state-path (meta target)))]
    path
    (do
      (when-not (vector? target) (throw (js/Error. "Expected a state handle or path vector")))
      (cond
        (:component-path (meta target)) target
        (= :comp (first target)) (scoped-path :comp (subvec target 1))
        (#{:global :shared} (first target)) (scoped-path :global (subvec target 1))
        (#{:component :module :page :state} (first target))
        (with-meta target {:component-path true :component-root target})
        :else (expand-path target)))))
(defn >reset [target value]
  (rf/dispatch [:component-state/reset (path-of target) value]))
(defn >update [target f & args]
  (when-not (ifn? f) (throw (js/Error. "State update requires a callable function")))
  (rf/dispatch [:component-state/update (path-of target) f args]))

(rf/reg-fx :component-state/initialize
  (fn [{:keys [path options restore? revision]}]
    (when-let [schema (:schema options)]
      (validation/register-sections! {path schema} {:dynamic? true}))
    (when-let [persistence (:persist options)]
      (let [persistence' (merge {:scope :public} (when (map? persistence) persistence))
            id [:state path]]
        (when-not (= persistence' (get-in @storage/*tracked [id :options]))
          (storage/track! id #(storage/state-value path) persistence'))
        (when restore?
          (-> (storage/ready! persistence')
              (.then (fn [_]
                       (when (accessible-path? path)
                         (when-let [saved (storage/read! id persistence')]
                           (if (and (:schema options)
                                    (value-validation/explain (:schema options) (:value saved)))
                             (storage/remove! id persistence')
                             (rf/dispatch [:component-state/restore path revision (:value saved)]))))))))))))

(defn- initialization-effects [{:keys [db]} [_ path options]]
    (when (accessible-path? path)
      (let [{:keys [present? value revision]} (entry-at db path)
            value' (if present? value (:initial options))]
        (when-let [schema (:schema options)]
          (validation/check! "component state" schema value'))
        {:db (if present? db (assoc-in db path value'))
         :component-state/initialize {:path path
                                      :options options
                                      :restore? (not present?)
                                      :revision revision}})))

(doseq [event [:component-state/initialize :component-state/load]]
  (rf/reg-event-fx event
    {:args [:cat declarations/path declarations/state-options]}
    initialization-effects))

(defn use-initialization!
  "Initialize declared state after commit. Persistent subscriptions may acquire
   the same idempotent load earlier; both paths share the registered effect."
  [definition args react-key]
  (let [options (declared-options definition args)
        initialize? (or (contains? options :initial) (:schema options) (:persist options))
        path (when initialize?
               (binding [*component* definition *args* args *react-key* react-key]
                 (path-for options)))]
    (rf/use-effect
      (fn [] (when path (rf/dispatch [:component-state/initialize path options])))
      [path options])))

(def scoped-query-options
  [:map
   [:?initial :any]
   [:?load [:map [:path declarations/path] [:options declarations/state-options]]]])

(rf/reg-sub-raw :component-state/scoped-value
  {:args [:cat declarations/path [:? scoped-query-options]]}
  (fn [*db query]
    (let [[_ path fallback] (or (:re-frame/query-v query) query)]
      ;; Acquisition may request storage once. Derived reads stay pure, and the
      ;; event/effect owns defaults, disk I/O, deduplication and result delivery.
      ;; SSR observes supplied state rather than loading browser storage.
      (when-let [{load-path :path options :options} (:load fallback)]
        (when-not context/*server?*
          (rf/dispatch [:component-state/load load-path options])))
      (ratom/make-reaction
        #(when (accessible-path? path)
           (let [entry (entry-at @*db path)]
             (if (:present? entry) (:value entry) (:initial fallback))))))))

(defn <sub
  "Return a native re-frame value subscription. :initial is a pure fallback.
   :persist acquires storage through the idempotent load event; eager owners use
   :component-state/initialize. >update/>reset accept the handle or path-of path."
  ([scope path] (<sub scope path {}))
  ([scope path options]
   (let [component? (#{:comp :component} scope)
         defaults (when component? (declared-options *component* *args*))
         root-options (merge defaults (if (and component? (seq path)) (dissoc options :initial) options))
         full (scoped-path scope path root-options)
         root (if component? (path-for root-options) full)
         initial (if (and component? (seq path)) (entry-at (:initial root-options) path)
                     {:present? (contains? root-options :initial), :value (:initial root-options)})
         fallback (cond
                    (:present? initial) {:initial (:value initial)}
                    (contains? options :initial) (select-keys options [:initial])
                    :else {})
         root-options' (if (and component? (seq path) (not (:present? initial))
                                (contains? options :initial))
                         (update root-options :initial assoc-in path (:initial options))
                         root-options)
         fallback' (cond-> fallback
                     (:persist root-options')
                     (assoc :load {:path root, :options root-options'}))
         query (cond-> [:component-state/scoped-value full] (seq fallback') (conj fallback'))
         subscription (rf/subscribe query)]
     (.set state-paths subscription full)
     subscription)))

(defn state
  "Compatibility convenience for the entire component state."
  ([] (<sub :comp []))
  ([options] (<sub :comp [] options)))
(defn dump! [] (storage/flush! :state))
