(ns tolgraven.ssr.client
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]
            [tolgraven.ssr.contract :as contract]
            [tolgraven.content.contract :as content-contract]
            [tolgraven.component]
            [tolgraven.modules.blog.comments :as comments]
            [tolgraven.modules.blog.data :as data]
            [tolgraven.supabase.scoped :as scoped]))

(def *snapshot context/*snapshot)

(rf/reg-event-db :page/install-public-state
  (fn [db [_ value]] (contract/merge-state db value)))

(rf/reg-event-fx :page/install-metadata
  (fn [_ [_ snapshot]]
    (when-let [title (:document-title snapshot)]
      {:document/set-title title})))

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
      (rf/dispatch-sync [:page/install-metadata snapshot])
      (reset! context/*interactive? false)
      (rf/dispatch-sync [:component-data/install [:options :supabase :trusted-author-ids]
                         (:trusted-author-ids snapshot)])
      (rf/dispatch-sync [:component-data/install [:state :blog :page] (dec (or (:page snapshot) 1))])
      (rf/dispatch-sync [:component-data/install [:state :blog :current-post-id] (or (:post-id snapshot) (get-in snapshot [:posts 0 :id]))])
      ;; Current page plans install exact caches with public state above. Keep
      ;; compatibility for older snapshots/fixtures that only carry display rows.
      (when-not (:app-db-edn snapshot)
        (when (:summaries snapshot)
          (let [opts data/summaries-query]
            (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                               {:docs (mapv #(hash-map :id (str (:id %)) :data %) (:summaries snapshot))}])))
        ;; Seed the bounded root window and its immediate replies before rendering.
        ;; Hydration must not briefly replace server comments with empty readers.
        (when (contains? snapshot :comments)
          (doseq [{:keys [post-id parent-id]}
                  (concat (map #(hash-map :post-id (:id %) :parent-id nil) (:posts snapshot))
                          (or (:comment-parents snapshot)
                              (for [row (:comments snapshot) :when (nil? (:parent-comment row))]
                                {:post-id (:parent-post row) :parent-id (:id row)})))]
            (let [opts (if parent-id (comments/thread-query post-id parent-id)
                           (comments/root-query post-id comments/page-size))
                  rows (filter #(and (= post-id (:parent-post %)) (= parent-id (:parent-comment %)))
                               (:comments snapshot))]
              (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                                 {:docs (mapv #(hash-map :id (str (:id %)) :data (dissoc % :author :date)) rows)}]))))
        ;; A missing permalink is a completed empty read too.
        (when (and (:missing? snapshot) (:post-id snapshot))
          (let [opts (data/post-query (:post-id snapshot))]
            (rf/dispatch-sync [:store/scoped (scoped/query-key opts) {:docs []}])))
        (when (and (:page snapshot) (not (:post-id snapshot)))
          (rf/dispatch-sync [:store/scoped
                             (scoped/query-key (data/page-query (dec (:page snapshot)) data/page-size))
                             {:docs (mapv #(hash-map :id (str (:id %)) :data (dissoc % :author :date))
                                          (:posts snapshot))}]))
        ;; Exact query cache only: a single post never marks a whole table loaded.
        (doseq [post (:posts snapshot)]
          (let [opts (data/post-query (:id post))]
            (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                               {:docs [{:id (str (:id post))
                                        :data (dissoc post :author :date)}]}])))
)
      snapshot)))

(r/defc <hydrate> [form]
  (rf/use-layout-effect
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
            (when (:hydrate? @restore/*context) (rf/dispatch [:dev-console/hydrated]))
            (rf/dispatch [:page/hydrated])
            (restore/hydrated!))))
       #(reset! *active? false))) #js [])
  form)
