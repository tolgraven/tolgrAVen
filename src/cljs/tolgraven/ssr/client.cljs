(ns tolgraven.ssr.client
  (:require [react :as react]
            [clojure.string :as string]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.ssr.contract :as contract]
            [tolgraven.content.contract :as content-contract]
            [tolgraven.component]
            [tolgraven.supabase.scoped :as scoped]))

(def *snapshot context/*snapshot)

(rf/reg-event-db :page/install-public-state
  (fn [db [_ value]] (contract/merge-state db value)))

(defn leave! [path]
  (let [path (first (string/split path #"\?"))]
    (when (and @*snapshot (not= path (:path @*snapshot)))
      (if (and (= :landing (some-> (:kind @*snapshot) keyword)) (contract/page-spec path))
        (swap! *snapshot assoc :path path)
        (reset! *snapshot nil)))))

(defn install! []
  (when-let [element (.getElementById js/document "ssr-bootstrap")]
    (let [snapshot (-> (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)
                       (update :content content-contract/normalize-content))]
      (when-not (and (= 3 (:renderer-version snapshot))
                     (= (.-pathname js/location) (:path snapshot))
                     (vector? (:posts snapshot)))
        (throw (js/Error. "Invalid page hydration snapshot")))
      (rf/dispatch-sync [:page/install-public-state (contract/snapshot-state snapshot)])
      (reset! *snapshot snapshot)
      (reset! context/*interactive? false)
      (rf/dispatch-sync [:component-data/install [:options :supabase :trusted-author-ids]
                         (:trusted-author-ids snapshot)])
      (rf/dispatch-sync [:component-data/install [:state :blog :page] (dec (or (:page snapshot) 1))])
      (rf/dispatch-sync [:component-data/install [:state :blog :current-post-id] (or (:post-id snapshot) (get-in snapshot [:posts 0 :id]))])
      (when (:summaries snapshot)
        (let [opts {:path-collection [:blog-posts] :scoped? true :summary? true}]
          (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                             {:docs (mapv #(hash-map :id (str (:id %)) :data %) (:summaries snapshot))}])))
      ;; Seed every thread (including empty leaves) before the first render.
      ;; Hydration must not briefly replace server comments with empty readers.
      (when (contains? snapshot :comments)
        (doseq [post (:posts snapshot)
                parent (cons nil (map :id (filter #(= (:id post) (:parent-post %)) (:comments snapshot))))]
          (let [opts {:path-collection [:blog-comments] :scoped? true
                      :where [[:parent-post :== (:id post)] [:parent-comment :== parent]]
                      :order-by [[:ts :desc]] :doc-changes true}
                rows (filter #(and (= (:id post) (:parent-post %)) (= parent (:parent-comment %)))
                             (:comments snapshot))]
            (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                               {:docs (mapv #(hash-map :id (str (:id %)) :data (dissoc % :author :date)) rows)}]))))
      ;; A missing permalink is a completed empty read too.
      (when (and (:missing? snapshot) (:post-id snapshot))
        (let [opts {:path-collection [:blog-posts] :scoped? true
                    :where [[:id :== (:post-id snapshot)]] :doc-changes true}]
          (rf/dispatch-sync [:store/scoped (scoped/query-key opts) {:docs []}])))
      ;; Exact query cache only: a single post never marks a whole table loaded.
      (doseq [post (:posts snapshot)]
        (let [opts {:path-collection [:blog-posts] :scoped? true
                    :where [[:id :== (:id post)]] :doc-changes true}]
          (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                             {:docs [{:id (str (:id post))
                                      :data (dissoc post :author :date)}]}])))
      snapshot)))

(r/defc <hydrate> [form]
  (react/useLayoutEffect
   (fn []
     ;; Run after the hydration commit, before the browser's next paint. Reagent
     ;; flushes with React.flushSync, which cannot run inside a React lifecycle.
     (let [*active? (atom true)]
       (js/queueMicrotask
        (fn []
          (when @*active?
            (reset! context/*interactive? true)
            (r/flush)
            (rf/dispatch [:store/init])
            (rf/dispatch [:page/hydrated]))))
       #(reset! *active? false))) #js [])
  form)
