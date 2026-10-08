(ns tolgraven.modules.blog.data
  "Shared public read declarations for blog subscriptions, route preloading and SSR."
  (:require [clojure.string :as string]
            [tolgraven.modules.blog.comments :as comments]
            [tolgraven.supabase.query :as query]))

(def page-size 3)
(def summaries-query {:path-collection [:blog-posts] :scoped? true :summary? true})
(defn post-query [id]
  {:path-collection [:blog-posts] :scoped? true :where [[:id :== id]] :doc-changes true})

(defn page-query [index size]
  {:path-collection [:blog-posts] :scoped? true :doc-changes true
   :order-by [[:id :desc]] :offset (* index size) :limit size})

(defn tag-query [tag]
  {:path-collection [:blog-posts] :scoped? true :doc-changes true
   :where [[:tags :tag tag]] :order-by [[:id :desc]]})

(defn selected-ids [{:keys [post-id page summaries size]}]
  (if post-id [post-id]
    (->> summaries (map :id) (sort >)
         (drop (* (or size page-size) (dec (or page 1)))) (take (or size page-size)) vec)))

(defn comment-limit [limits id]
  (let [amount (get limits id)]
    (if (and (int? amount) (pos? amount)) (max comments/page-size amount) comments/page-size)))

(defn visible-roots [{:keys [roots comment-limits]}]
  (mapcat (fn [[id rows]]
            (take (comment-limit comment-limits id) (reverse (sort-by (juxt :ts :id) rows))))
          (sort-by key (group-by :parent-post roots))))

(def plan
  [{:id :summaries :queries (fn [_] [summaries-query])}
   {:id :posts :queries (fn [{:keys [post-id page size]}]
                         [(if post-id (post-query post-id)
                              (page-query (dec (or page 1)) (or size page-size)))])}
   {:id :roots :depends [:posts]
    :queries (fn [{:keys [posts comment-limits]}]
               (mapv #(comments/root-query (:id %) (comment-limit comment-limits (:id %))) posts))}
   {:id :children :depends [:roots]
    :queries (fn [{:keys [thread-expanded] :as values}]
               (mapv #(comments/thread-query (:parent-post %) (:id %))
                     (filter #(and (pos? (:reply-count % 0))
                                   (comments/expanded? thread-expanded [(:parent-post %) (:id %)]))
                             (visible-roots values))))}
   {:id :grandchildren :depends [:children]
    :queries (fn [{:keys [children thread-expanded]}]
               (mapv #(comments/thread-query (:parent-post %) (:id %))
                     (filter #(and (pos? (:reply-count % 0))
                                   (comments/expanded? thread-expanded
                                                       [(:parent-post %) (:parent-comment %) (:id %)]))
                             children)))}
   {:id :authors :depends [:posts :roots :children :grandchildren]
    :queries (fn [{:keys [posts roots children grandchildren]}]
               (mapv query/profile-query (sort (distinct (keep :user (concat posts roots children grandchildren))))))}])

(defn- display-date [ts now]
  (when ts
    (let [minutes (quot (- now ts) 60000)
          relative (fn [n unit] (str n " " unit (when (not= 1 n) "s") " ago"))]
      (cond
        (zero? minutes) "now"
        (< minutes 60) (relative minutes "minute")
        (< minutes 1440) (relative (quot minutes 60) "hour")
        :else #?(:clj (.format java.time.format.DateTimeFormatter/ISO_LOCAL_DATE
                               (.atZone (java.time.Instant/ofEpochMilli (long ts)) java.time.ZoneOffset/UTC))
                 :cljs (subs (.toISOString (js/Date. ts)) 0 10))))))

(defn snapshot
  "Project the shared plan into public display rows and exact subscription caches."
  [{:keys [values cache]} now]
  (let [{:keys [posts roots children grandchildren summaries authors page post-id thread-expanded]} values
        authors (into {} (map (juxt :id identity)) authors)
        decorate (fn [row] (assoc row :author (into {} (remove (comp nil? val)) (select-keys (get authors (:user row)) [:id :name :avatar :bg-color]))
                                     :date (display-date (:ts row) now)))]
    {:page page :post-id post-id
     :more? (and page (< (* page page-size) (count summaries)))
     :missing? (and post-id (empty? posts))
     :posts (mapv #(-> % decorate (update :tags (fn [tags] (vec (distinct (remove string/blank? (if (string? tags) (string/split tags #"\s+") tags))))))) posts)
     :comments (mapv decorate (concat roots children grandchildren))
     :summaries summaries
     :comment-parents (mapv #(hash-map :post-id (:parent-post %) :parent-id (:id %))
                           (concat (visible-roots values)
                                   (filter #(comments/expanded? thread-expanded
                                                                [(:parent-post %) (:parent-comment %) (:id %)]) children)))
     :app-db-edn (pr-str {:store {:scoped cache}})}))
