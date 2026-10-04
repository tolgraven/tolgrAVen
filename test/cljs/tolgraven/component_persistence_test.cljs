(ns tolgraven.component-persistence-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [react-dom :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [re-frame.db :as rfdb]
            [re-frame.core :as rf]
            [re-frame.tooling :as tooling]
            [tolgraven.component :as component]
            [tolgraven.component.data :as data]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.loading :as loading]
            [tolgraven.component.persistent-state :as state]
            [tolgraven.macros :refer-macros [defc]]))

(defn- render! [root form] (react-dom/flushSync #(.render root (r/as-element form))))
(defn- tick! [] (js/Promise. (fn [resolve _] (js/setTimeout resolve 35))))
(defn- fixture []
  (let [before @rfdb/app-db context @restore/*context tracked @storage/*tracked
        element (.createElement js/document "div") root (dom/create-root element)]
    (.appendChild (.-body js/document) element)
    {:root root :element element
     :close! #(do (react-dom/flushSync (fn [] (dom/unmount root))) (.remove element)
                  (reset! rfdb/app-db before) (reset! restore/*context context)
                  (reset! storage/*tracked tracked))}))
(defc <counter> {:state {:id :persist-test :initial 0 :persist true}} []
  (let [*count (component/state)]
    [:button {:on-click #(swap! *count inc)} (str @*count)]))
(defc <ready> {:features [[:appear "opacity"]]
               :depends [{:source :url :url "/never-needed" :into [:persist-test :content]}]} []
  [:article (get-in @rfdb/app-db [:persist-test :content])])
(defc <pending> {:depends [{:source :persist-test}]}
  [] [:article "Loaded"])
(defc <skeleton> {:depends [{:source :persist-test}]
                  :loading [:section {:role "status" :aria-label "Loading article"}
                            [loading/<avatar>] [loading/<h1>] [loading/<lines>]]}
  [] [:article "Loaded"])

(deftest writable-subscription-state-survives-unmount-and-storage-restore
  (async done
    (let [{:keys [root element close!]} (fixture)
          id [:state [:component "tolgraven.component-persistence-test" :persist-test]]]
      (storage/remove! id {:scope :public})
      (render! root [<counter>])
      (.click (.querySelector element "button"))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= "1" (.-textContent element)))
                   (render! root [:p "Unmounted"])
                   (render! root [<counter>])
                   (is (= "1" (.-textContent element)))
                   (state/dump!)
                   (render! root [:p "New document"])
                   (swap! rfdb/app-db dissoc :component)
                   (render! root [<counter>])
                   (is (= "0" (.-textContent element)) "Explicit deletion invalidates the local snapshot")))
          (.catch #(is false (str %)))
          (.finally (fn [] (storage/remove! id {:scope :public}) (close!) (done)))))))

(deftest page-state-isolates-pages-and-instance-keys
  (let [definition {:ns "fixture" :name "panel" :options {:state {:scope :page :key identity}}}]
    (binding [state/*component* definition state/*args* [42]]
      (is (= [:component "fixture" "panel" 42 :page (restore/page-key)] (state/path-for {}))))))

(deftest storage-validates-expiry-schema-corruption-and-account-scope
  (let [id [:test (random-uuid)] options {:scope :public :version 2} old @storage/*identity]
    (try
      (is (storage/write! id false options))
      (is (false? (:value (storage/read! id options))))
      (is (nil? (storage/read! id (assoc options :version 3))))
      (storage/write! id :expired (assoc options :ttl-ms -1))
      (is (nil? (storage/read! id options)))
      (.setItem js/localStorage (storage/storage-key id options) "not valid [")
      (is (nil? (storage/read! id options)))
      (reset! storage/*identity :alice)
      (storage/write! id :private {:scope :user})
      (reset! storage/*identity :bob)
      (is (nil? (storage/read! id {:scope :user})))
      (reset! storage/*identity :alice)
      (is (= :private (:value (storage/read! id {:scope :user}))))
      (storage/remove! id {:scope :user})
      (finally (storage/remove! id options) (reset! storage/*identity old)))))

(deftest ready-hydration-data-skips-network-spinner-and-entrance
  (async done
    (let [{:keys [root element close!]} (fixture)]
      (restore/begin! {:hydrate? true})
      (swap! rfdb/app-db assoc :persist-test {:content "Server content"})
      (render! root [<ready>])
      (is (= "Server content" (.-textContent element)))
      (is (nil? (.querySelector element "[role=status]")))
      (is (.contains (.-classList (.querySelector element "article")) "appeared"))
      (-> (tick!)
          (.then (fn [_] (is (= :ready (data/state [{:source :url :url "/never-needed" :into [:persist-test :content]}])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (data/invalidate! #(= "/never-needed" (:url %))) (close!) (done)))))))

(deftest valid-resource-cache-bypasses-loader-and-retry-forces-refresh
  (async done
    (let [resource {:source :persist-test :persist {:scope :public} :into [:persist-test :cached]}
          id [:resource (dissoc resource :persist)] before @rfdb/app-db *calls (atom 0)]
      (storage/write! id "Cached" {:scope :public})
      (data/register-source! :persist-test {:load! (fn [_] (swap! *calls inc) "Fresh")})
      (-> (data/ensure! resource)
          (.then (fn [value]
                   (is (= "Cached" value))
                   (is (= 0 @*calls))
                   (is (= "Cached" (get-in @rfdb/app-db [:persist-test :cached])))
                   (data/retry! [resource])))
          (.then (fn [_] (is (= 1 @*calls)) (is (= "Fresh" (get-in @rfdb/app-db [:persist-test :cached])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (data/invalidate! #{resource}) (storage/remove! id {:scope :public})
                      (swap! storage/*tracked dissoc id)
                      (swap! data/*sources dissoc :persist-test) (reset! rfdb/app-db before) (done)))))))

(deftest spinner-and-skeleton-are-only-shown-for-missing-data
  (async done
    (let [{:keys [root element close!]} (fixture) *resolve (atom nil)]
      (data/register-source! :persist-test {:load! (fn [_] (js/Promise. (fn [resolve _] (reset! *resolve resolve))))})
      (render! root [<pending>])
      (is (some? (.querySelector element ".component-spinner[role=status]")))
      (render! root [<skeleton>])
      (is (some? (.querySelector element ".component-skeleton--avatar")))
      (is (some? (.querySelector element "h1.component-skeleton")))
      (-> (tick!)
          (.then (fn [_] (@*resolve :loaded) (tick!)))
          (.then (fn [_] (react-dom/flushSync #(r/flush)) (is (= "Loaded" (.-textContent element)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (data/invalidate! #(= :persist-test (:source %)))
                      (swap! data/*sources dissoc :persist-test) (close!) (done)))))))

(deftest hydration-keeps-the-existing-dom-node
  (async done
    (let [before @rfdb/app-db context @restore/*context
          element (.createElement js/document "div")]
      (.appendChild (.-body js/document) element)
      (set! (.-innerHTML element) "<article class=\"appear-wrapper opacity appeared\">Server content</article>")
      (restore/begin! {:hydrate? true})
      (swap! rfdb/app-db assoc :persist-test {:content "Server content"})
      (let [server-node (.-firstElementChild element) root (dom/hydrate-root element [<ready>])]
        (-> (tick!)
            (.then (fn [_]
                     (react-dom/flushSync #(r/flush))
                     (is (identical? server-node (.-firstElementChild element)))
                     (is (nil? (.querySelector element "[role=status]")))
                     (is (= "Server content" (.-textContent element)))))
            (.catch #(is false (str %)))
            (.finally (fn [] (react-dom/flushSync #(dom/unmount root)) (.remove element)
                        (data/invalidate! #(= "/never-needed" (:url %)))
                        (reset! rfdb/app-db before) (reset! restore/*context context) (done))))))))

(deftest metadata-keys-isolate-state-and-survive-remount
  (async done
    (let [{:keys [root element close!]} (fixture)]
      (swap! rfdb/app-db dissoc :component)
      (doseq [key ["first" "second"]]
        (storage/remove! [:state [:component "tolgraven.component-persistence-test" :persist-test key]] {:scope :public}))
      (render! root [:div ^{:key "first"} [<counter>] ^{:key "second"} [<counter>]])
      (let [buttons (.querySelectorAll element "button")]
        (.click (aget buttons 0)))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= ["1" "0"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "button")))))
                   (render! root [:p "Away"])
                   (tick!))) ; Reagent 2 disposes reactions on a microtask for StrictMode.
          (.then (fn [_]
                   (is (not-any? (fn [query]
                                   (and (#{:component-state/scoped-value :component-state/entry} (first query))
                                        (= :persist-test (nth (second query) 2 nil))))
                                 (tooling/live-query-vs))
                       "Unmount releases both re-frame subscriptions, not their cached values")
                   (render! root [:div ^{:key "first"} [<counter>] ^{:key "second"} [<counter>]])
                   (is (= ["1" "0"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "button")))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))

(deftest storage-coalesces-disk-io-and-deduplicates-unchanged-dumps
  (async done
    (let [original-read storage/read-disk! original-write storage/write-disk!
          identity-before @storage/*identity account (random-uuid)
          options {:scope :user} *reads (atom 0) *writes (atom [])]
      (reset! storage/*identity account)
      (set! storage/read-disk! (fn [_] (swap! *reads inc) nil))
      (set! storage/write-disk! (fn [key value]
                                  (when (= key (storage/storage-key nil options))
                                    (swap! *writes conj [key value]))))
      (dotimes [i 50]
        (storage/read! i options)
        (storage/write! i {:value i} options)
        (storage/write! i {:value i} options))
      (is (= 0 @*reads) "No disk reads in component creation")
      (is (empty? @*writes) "No disk writes in component updates")
      (-> (tick!)
          (.then (fn [_]
                   (is (= 1 @*reads) "All component reads share one owner envelope")
                   (is (= 1 (count @*writes)) "All component writes share one owner envelope")
                   (dotimes [i 50] (storage/write! i {:value i} options))
                   (tick!)))
          (.then (fn [_] (is (= 1 (count @*writes)) "Unchanged dumps never write again")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! storage/read-disk! original-read) (set! storage/write-disk! original-write)
                      (swap! storage/*buckets dissoc account) (swap! storage/*reads dissoc account)
                      (swap! storage/*ready disj account) (swap! storage/*dirty disj account)
                      (reset! storage/*identity identity-before) (done)))))))

;; No :state declaration or helper imports: defc recognizes the scoped helpers.
(defc <scoped-toggle> []
  :let [*setting (<sub :comp [:opts :setting] {:initial false})]
  [:button {:on-click #(>update *setting not)} (str @*setting)])
(defc <keyed-setting> {:state {:key identity :persist true}} [id]
  :let [*setting (<sub :comp [:opts :setting] {:initial 0})]
  [:button {:data-id id
            :on-click #(do (>update *setting + 2) (>update *setting + 3))}
   (str @*setting)])

(deftest scoped-let-subscriptions-expand-paths-and-release-readers
  (async done
    (let [{:keys [root element close!]} (fixture)
          base [:component "tolgraven.component-persistence-test" "<scoped-toggle>"]]
      (render! root [:div [<scoped-toggle>] [<scoped-toggle>]])
      (is (false? (get-in @rfdb/app-db (into base [:opts :setting]))))
      (.click (aget (.querySelectorAll element "button") 0))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= ["true" "true"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "button")))))
                   (render! root [:p "Away"])
                   (tick!)))
          (.then (fn [_]
                   (is (not-any? (fn [query]
                                   (and (= :component-state/scoped-value (first query))
                                        (= "<scoped-toggle>" (nth (second query) 2 nil))))
                                 (tooling/live-query-vs)))
                   (is (true? (get-in @rfdb/app-db (into base [:opts :setting]))))
                   (render! root [:div [<scoped-toggle>] [<scoped-toggle>]])
                   (is (= ["true" "true"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "button")))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))

(deftest queued-component-updates-use-current-value-and-explicit-key
  (async done
    (let [{:keys [root element close!]} (fixture)
          path [:component "tolgraven.component-persistence-test" "<keyed-setting>" "first" :opts :setting]
          resolved (binding [state/*component* {:ns "tolgraven.component-persistence-test" :name "<keyed-setting>"
                                                :options {:state {:key "first"}}}]
                     (state/expand-path [:opts :setting]))]
      (storage/remove! [:state (subvec path 0 4)] {:scope :public})
      (is (= path resolved))
      (render! root [<keyed-setting> "first"])
      (.click (.querySelector element "button"))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= "5" (.-textContent element)) "Queued updates do not overwrite each other")
                   (component/>reset resolved 12)
                   (tick!)))
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= "12" (.-textContent element)))
                   (is (= 12 (get-in @rfdb/app-db path)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))

(defc <cold-setting> {:state {:persist {:scope :user}}} []
  :let [*setting (<sub :comp [:opts :setting] {:initial false})]
  [:p (str @*setting)])

(deftest cold-storage-restores-over-leaf-defaults
  (async done
    (let [{:keys [root element close!]} (fixture)
          old-owner @storage/*identity owner (random-uuid)
          original storage/read-disk!
          root-path [:component "tolgraven.component-persistence-test" "<cold-setting>" :account owner]
          snapshot {[:state root-path] {:version 1 :schema 1 :expires-at (+ (.now js/Date) 60000)
                                       :value {:opts {:setting true}}}}]
      (reset! storage/*identity owner)
      (set! storage/read-disk! (fn [key]
                                (if (= key (storage/storage-key nil {:scope :user}))
                                  (pr-str snapshot) (original key))))
      (render! root [<cold-setting>])
      (storage/flush!) ; A prior autosave must not persist defaults over a cold read.
      (is (= "false" (.-textContent element)))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= "true" (.-textContent element))
                       (str "disk=" (pr-str (get @storage/*buckets owner))
                            " state=" (pr-str (get-in @rfdb/app-db (subvec root-path 0 3)))
                            " revisions=" (pr-str (:component-revisions @rfdb/app-db))))
                   (is (true? (get-in @rfdb/app-db (into root-path [:opts :setting]))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! storage/read-disk! original)
                      (close!) (reset! storage/*identity old-owner)
                      (swap! storage/*reads dissoc owner) (swap! storage/*buckets dissoc owner)
                      (swap! storage/*ready disj owner) (swap! storage/*dirty disj owner)
                      (done)))))))

(deftest late-restoration-preserves-explicit-edits-even-when-equal-to-default
  (let [before @rfdb/app-db
        root (binding [state/*component* {:ns "restore-test" :name "setting"}]
               (state/path-for {}))
        leaf (into root [:opts :setting])]
    (try
      (rf/dispatch-sync [:component-state/init root nil])
      (rf/dispatch-sync [:component-state/init leaf false])
      (rf/dispatch-sync [:component-state/restore root 0 {:opts {:setting true}}])
      (is (true? (get-in @rfdb/app-db leaf)) "Cached content replaces initialization defaults")
      (rf/dispatch-sync [:component-state/reset leaf false])
      (rf/dispatch-sync [:component-state/restore root 0 {:opts {:setting true}}])
      (is (false? (get-in @rfdb/app-db leaf)) "An explicit edit wins over a late disk read")
      (finally (reset! rfdb/app-db before)))))

(defc <wrapped-key-setting>
  {:features [:error-boundary] :depends []
   :state {:key (fn [_] (throw (js/Error. "Metadata must take precedence")))}}
  [manual-key]
  :let [*value (<sub :comp [] {:initial 0})]
  [:button {:on-click #(>update *value inc)} (str @*value)])

(deftest react-metadata-key-wins-through-data-and-boundary-wrappers
  (async done
    (let [{:keys [root element close!]} (fixture)
          base [:component "tolgraven.component-persistence-test" "<wrapped-key-setting>"]]
      (render! root ^{:key 0} [<wrapped-key-setting> "manual"])
      (.click (.querySelector element "button"))
      (-> (tick!)
          (.then (fn [_]
                   (react-dom/flushSync #(r/flush))
                   (is (= 1 (get-in @rfdb/app-db (conj base 0))))
                   (is (nil? (get-in @rfdb/app-db (conj base "manual"))))
                   (is (= "1" (.-textContent element)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))

(deftest explicit-deletions-patch-snapshots-and-do-not-reappear-on-flush
  (async done
    (let [before @rfdb/app-db tracked @storage/*tracked old-owner @storage/*identity
          owner (random-uuid) options {:scope :user}
          path [:component "deletion-test" "panel"] id [:state path]]
      (reset! storage/*identity owner)
      (swap! rfdb/app-db assoc-in path {:keep false :remove "Old value"})
      (storage/track! id #(get-in @rfdb/app-db path storage/missing) options)
      (storage/write! id {:keep false :remove "Old value"} options)
      (swap! rfdb/app-db update-in path dissoc :remove)
      (is (= {:keep false} (:value (storage/read! id options))) "Nested deletion preserves siblings")
      (swap! rfdb/app-db update-in [:component "deletion-test"] dissoc "panel")
      (is (nil? (storage/read! id options)))
      (storage/flush!)
      (-> (tick!)
          (.then (fn [_]
                   (is (nil? (storage/read! id options)))
                   (is (not (.includes (or (storage/read-disk! (storage/storage-key id options)) "") "Old value")))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (.removeItem js/localStorage (storage/storage-key id options))
                      (reset! storage/*tracked tracked) (reset! rfdb/app-db before)
                      (swap! storage/*buckets dissoc owner) (swap! storage/*reads dissoc owner)
                      (swap! storage/*ready disj owner) (swap! storage/*dirty disj owner)
                      (reset! storage/*identity old-owner) (done)))))))

(deftest deletion-before-a-cold-read-is-also-written-back-to-disk
  (async done
    (let [before @rfdb/app-db old-owner @storage/*identity owner (random-uuid)
          options {:scope :user} path [:component "cold-delete" "panel"] id [:state path]
          snapshot {id {:version 1 :schema 1 :expires-at (+ (.now js/Date) 60000)
                        :value "Deleted before loading storage"}}]
      (reset! storage/*identity owner)
      (storage/write-disk! (storage/storage-key id options) (pr-str snapshot))
      (swap! rfdb/app-db assoc-in path "Deleted before loading storage")
      (swap! rfdb/app-db update-in [:component "cold-delete"] dissoc "panel")
      (-> (storage/ready! options)
          (.then (fn [_] (tick!)))
          (.then (fn [_]
                   (is (nil? (storage/read! id options)))
                   (is (not (.includes (storage/read-disk! (storage/storage-key id options)) "Deleted before loading storage")))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (.removeItem js/localStorage (storage/storage-key id options))
                      (reset! rfdb/app-db before)
                      (swap! storage/*buckets dissoc owner) (swap! storage/*reads dissoc owner)
                      (swap! storage/*ready disj owner) (swap! storage/*dirty disj owner)
                      (swap! storage/*deleted-paths dissoc owner)
                      (reset! storage/*identity old-owner) (done)))))))

(defonce *scope-handles (atom nil))
(defc <scope-controls> {:module :blog} []
  :let [*comp (<sub :comp [:count] {:initial 0})
        *module (<sub :module [:handle-test] {:initial 10})
        *page (<sub :page [:handle-test] {:page :test-page :initial 20})
        *global (<sub :global [:handle-test] {:initial 30})]
  (reset! *scope-handles [*comp *module *page *global])
  [:button {:on-click #(doseq [*value [*comp *module *page *global]]
                        (>update *value inc)
                        (>update (component/path-of *value) + 2))}
   (pr-str [@*comp @*module @*page @*global])])

(deftest state-handles-deref-values-and-route-writes-across-scopes
  (async done
    (let [{:keys [root element close!]} (fixture)]
      (render! root [<scope-controls>])
      (let [[*comp *module *page *global] @*scope-handles]
        (is (= [0 10 20 30] (mapv deref @*scope-handles)))
        (is (= [:component "tolgraven.component-persistence-test" "<scope-controls>" :count]
               (component/path-of *comp)))
        (is (= [:module :blog :handle-test] (component/path-of *module)))
        (is (= [:page :test-page :handle-test] (component/path-of *page)))
        (is (= [:state :handle-test] (component/path-of *global)))
        (reset! *comp 4)
        (swap! *comp inc)
        (is (= 5 @*comp) "Native atom operations route through re-frame too")
        (.click (.querySelector element "button"))
        (-> (tick!)
            (.then (fn [_]
                     (react-dom/flushSync #(r/flush))
                     (is (= "[8 13 23 33]" (.-textContent element)))
                     (component/>update [:global :handle-test] inc)
                     (component/>reset [:module :blog :handle-test] 42)
                     (tick!)))
            (.then (fn [_]
                     (react-dom/flushSync #(r/flush))
                     (is (= "[8 42 23 34]" (.-textContent element)))
                     (render! root [:p "Away"])
                     (tick!)))
            (.then (fn [_]
                     (is (not-any? (fn [query]
                                     (and (= :component-state/scoped-value (first query))
                                          (some #{(second query)} (map component/path-of @*scope-handles))))
                                   (tooling/live-query-vs)))
                     (render! root [<scope-controls>])
                     (is (= "[8 42 23 34]" (.-textContent element)) "Unmount retains state for every scope")))
            (.catch #(is false (str %)))
            (.finally (fn [] (reset! *scope-handles nil) (close!) (done))))))))
