(ns tolgraven.component.persistent-state
  (:require [tolgraven.component.instrumentation :as instrumentation]
            [tolgraven.validation.runtime :as validation]
            [reagent.core :as r]
            [clojure.string :as string]
            [tolgraven.content.contract :as content]
            [tolgraven.react :as rf]
            [re-frame.db :as rfdb]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.storage :as storage]))

(def ^:dynamic *component* nil)
(def ^:dynamic *args* nil)
(def ^:dynamic *react-key* nil)

(rf/reg-sub :component-state/entry
  (fn [db [_ path]]
    (let [absent (js-obj) value (get-in db path absent)]
      {:present? (not (identical? absent value)) :value value
       :revision (get-in db [:component-revisions path] 0)})))
(rf/reg-event-db :component-state/init
  (fn [db [_ path value]]
    (let [absent (js-obj)]
      (if (identical? absent (get-in db path absent)) (assoc-in db path value) db))))
(rf/reg-event-db :component-state/restore
  (fn [db [_ path revision value]]
    (if (= revision (get-in db [:component-revisions path] 0)) (assoc-in db path value) db)))
(rf/reg-sub :component-state/value (fn [db [_ path]] (get-in db path)))
(defn- edited [db path]
  (update-in db [:component-revisions (or (:component-root (meta path)) path)] (fnil inc 0)))
(rf/reg-event-db :component-state/set
  (fn [db [_ path value]] (edited (assoc-in db path value) path)))
(defn path-for [options]
  (let [definition *component*
        options (merge (get-in definition [:options :state]) options)
        key (if (some? *react-key*) *react-key*
                (let [manual (:key options)]
                  (if (fn? manual) (apply manual *args*) manual)))]
    (when (and (nil? definition) (nil? (:id options)))
      (throw (js/Error. "State outside defc requires an explicit :id")))
    (let [path (cond-> [:component (or (:ns definition) "external")
                        (or (:id options) (:name definition))]
                 (some? key) (conj key)
                 (= :page (:scope options)) (conj :page (restore/page-key))
                 (= :user (get-in options [:persist :scope])) (conj :account @storage/*identity))]
      (with-meta path
        {:component-path true :component-root path
         :private? (= :user (get-in options [:persist :scope]))
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
           root (cond-> root
                  (= :user (get-in options [:persist :scope])) (conj :account @storage/*identity))
           full (into root path)]
       (with-meta full {:component-path true :component-root full
                        :private? (= :user (get-in options [:persist :scope]))
                        :owner @storage/*identity})))))

(defn path-of
  "Accept a scoped state handle, a resolved path, or a scope-qualified path.
   [:global ...] routes to shared :state; [:comp ...] uses current component."
  [target]
  (if-let [path (:state-path (meta target))]
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

;; One immutable reference for snapshot producers; components still read subs.
(defonce *snapshot-state (atom @rfdb/app-db))
(defn- initialize! [path options]
  (when-let [schema (:schema options)]
    (validation/register-sections! {path schema} {:dynamic? true}))
  (let [persistence (:persist options)
        persistence (when persistence (merge {:scope :public} (when (map? persistence) persistence)))
        id [:state path]
        entry (rf/subscribe [:component-state/entry path])]
    (when (and (:present? @entry) (:schema options))
      (validation/check! "component state" (:schema options) (:value @entry)))
    (when-not (:present? @entry)
      (let [saved (when persistence (storage/read! id persistence))
            revision (:revision @entry)
            value (if saved (:value saved) (:initial options))]
        (when-let [schema (:schema options)] (validation/check! "component state" schema value))
        (rf/dispatch-sync [:component-state/init path value])
        (when (and persistence (nil? saved))
          (-> (storage/ready! persistence)
              (.then (fn [_]
                       (when (accessible-path? path)
                         (when-let [snapshot (storage/read! id persistence)]
                           (rf/dispatch-sync [:component-state/restore path revision (:value snapshot)])))))))))
    (when persistence
      (storage/track! id #(get-in @*snapshot-state path storage/missing) persistence))))

(rf/reg-sub :component-state/scoped-value
  (fn [db [_ path]] (when (accessible-path? path) (get-in db path))))

(defn <sub
  "Return a reactive, writable state handle. @handle reads the subscription;
   >update/>reset accept the handle itself or its path-of path. Scopes: :comp,
   :module, :page, :global (:shared). Persistence is opt-in."
  ([scope path] (<sub scope path {}))
  ([scope path options]
   (let [component? (#{:comp :component} scope)
         defaults (when component? (get-in *component* [:options :state]))
         root-options (merge defaults (if (and component? (seq path)) (dissoc options :initial) options))
         full (scoped-path scope path root-options)
         root (if component? (path-for root-options) full)]
     (initialize! root root-options)
     (when (and component? (seq path) (contains? options :initial))
       (rf/dispatch-sync [:component-state/init full (:initial options)]))
     ;; Dereferencing captures the re-frame reaction normally, so its lifecycle
     ;; follows the last consumer. Routing lives in metadata, never in the value.
     (with-meta
       (r/cursor (fn
                   ([_] @(rf/subscribe [:component-state/scoped-value full]))
                   ([_ value]
                    (when (accessible-path? full)
                      (rf/dispatch-sync [:component-state/reset full value])))) [])
       {:state-path full}))))

(defn state
  "Compatibility convenience for the entire component state."
  ([] (<sub :comp []))
  ([options] (<sub :comp [] options)))
(defn dump! [] (storage/flush! :state))

(add-watch rfdb/app-db ::persist-state
           (fn [_ _ before after]
             (reset! *snapshot-state after)
             (when (some #(not (identical? (get before %) (get after %))) [:component :module :page :state])
               (storage/schedule!))))
