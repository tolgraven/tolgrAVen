(ns tolgraven.component.persistent-state
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
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
(defn >creset [path value]
  (rf/dispatch [:component-state/reset (expand-path path) value]))
(defn >cupdate [path f & args]
  (when-not (fn? f) (throw (js/Error. "Component update requires a function")))
  (rf/dispatch [:component-state/update (expand-path path) f args]))

;; One immutable reference for the storage queue, updated in O(1). Components
;; read registered subscriptions; no per-update walk over all mounted instances.
(defonce *snapshot-state (atom (:component @rfdb/app-db)))

(defn state
  "Subscription-backed, writable cursor. Call during render; state survives unmount.
   :id/:key distinguish instances; :scope :page isolates URLs; :persist opts in."
  ([] (state {}))
  ([options]
   (let [options (merge (get-in *component* [:options :state]) options)
         path (path-for options)
         persistence (:persist options)
         persistence (when persistence (merge {:scope :public} (when (map? persistence) persistence)))
         id [:state path]
         owner (storage/owner persistence)
         accessible? #(or (not= :user (:scope persistence)) (= owner @storage/*identity))
         entry (rf/subscribe [:component-state/entry path])]
     (when-not (:present? @entry)
       (let [saved (when persistence (storage/read! id persistence))
             revision (:revision @entry)]
         (rf/dispatch-sync [:component-state/init path (if saved (:value saved) (:initial options))])
         (when (and persistence (nil? saved))
           (-> (storage/ready! persistence)
               (.then (fn [_]
                        (when (accessible?)
                          (when-let [snapshot (storage/read! id persistence)]
                            (rf/dispatch-sync [:component-state/restore path revision (:value snapshot)])))))))))
     (when persistence
       (storage/track! id #(get-in @*snapshot-state (rest path) storage/missing) persistence))
     (r/cursor (fn
                 ([_] (when (accessible?) @(rf/subscribe [:component-state/value path])))
                 ([_ value] (when (accessible?)
                              (rf/dispatch-sync [:component-state/set path value])
                              (when persistence (storage/schedule!))))) []))))
(defn dump! [] (storage/flush! :state))

(add-watch rfdb/app-db ::persist-state
           (fn [_ _ before after]
             (when (not= (:component before) (:component after))
               (reset! *snapshot-state (:component after))
               (storage/schedule!))))

(rf/reg-sub :component-state/scoped-value
  (fn [db [_ path]] (when (accessible-path? path) (get-in db path))))
(defn <csub
  "Return [subscription absolute-path]. Destructure [sub] for read-only use or
   [sub path] for >creset/>cupdate. Options may provide a leaf :initial value."
  ([path] (<csub path {}))
  ([path options]
   ;; Initialize/restore and track the whole instance once, not each leaf.
   (state (if (seq path) (dissoc options :initial) options))
   (let [path (expand-path path options)]
     (when (contains? options :initial)
       (rf/dispatch-sync [:component-state/init path (:initial options)]))
     [(rf/subscribe [:component-state/scoped-value path]) path])))
