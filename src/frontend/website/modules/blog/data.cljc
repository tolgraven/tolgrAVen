(ns website.modules.blog.data
  "Shared public read declarations for blog subscriptions, route preloading and SSR."
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require #?(:clj [website.build.site :refer [when-enabled]]) [clojure.string :as string]
            #?(:feature/blog-comments [website.modules.blog.comments :as comments])
            [website.supabase.plan :as read-plan]
            [website.supabase.query :as query]))

#?(:clj (when-enabled :blog :comments
           (require '[website.modules.blog.comments :as comments])))

(def page-size 3)
(def summaries-query
  (merge {:path-collection [:blog-posts]
          :scoped? true
          :summary? true}
         (when-enabled :blog :comments {:reply-counts? true})))
(defn post-query [id]
  {:path-collection [:blog-posts] :scoped? true :where [[:id :== id]] :doc-changes true})

(defn summary-query [id]
  (assoc summaries-query :where [[:id :== id]]))

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

(when-enabled :blog :comments
  (defn comment-limit [limits id]
  (let [amount (get limits id)]
    (if (and (int? amount) (pos? amount)) (max comments/page-size amount) comments/page-size))))

(when-enabled :blog :comments
  (defn visible-roots [{:keys [roots comment-limits]}]
  (mapcat (fn [[id rows]]
            (take (comment-limit comment-limits id) (reverse (sort-by (juxt :ts :id) rows))))
          (sort-by key (group-by :parent-post roots)))))

(when-enabled :blog :comments
  (defn page-roots [values]
    (let [root (comments/focus-root values)
          rows (if (:standalone-comment? values) (:focus values) (visible-roots values))]
      (vals (into {} (map (juxt :id identity)) (cond-> (vec rows) root (conj root)))))))

(when-enabled :blog :comments
  (defn expanded-for-page [values]
    (if-let [row (when-not (:standalone-comment? values) (first (:focus values)))]
      (comments/focus-expansion (:thread-expanded values) row false)
      (:thread-expanded values))))

(def plan
  (vec (concat
   (when-enabled :blog :comments
     [{:id :focus
       :queries (fn [{:keys [comment-id post-id]}]
                  (if comment-id [(comments/comment-query comment-id post-id)] []))}
      {:id :ancestors
       :depends [:focus]
       :queries (fn [{:keys [focus standalone-comment?]}]
                  (if standalone-comment? []
                    (mapv #(comments/comment-query % (:parent-post (first focus)))
                          (map str (rest (:path (first focus)))))))}
      {:id :ancestor-replies
       :depends [:ancestors]
       :queries (fn [{:keys [ancestors] :as values}]
                  (mapv #(comments/thread-query (:parent-post %) (:id %))
                        (filter #(comments/expanded? (expanded-for-page values) (comments/full-path %))
                                ancestors)))}])
   [{:id :summaries
     :queries (fn [{:keys [standalone-comment?]}] (if standalone-comment? [] [summaries-query]))}
   {:id :posts
    :depends (or (when-enabled :blog :comments [:focus]) [])
    :queries (fn [{:keys [post-id page size standalone-comment? focus]}]
               (let [id (or post-id (when standalone-comment? (:parent-post (first focus))))]
                 (cond id [(if standalone-comment? (summary-query id) (post-query id))]
                       standalone-comment? []
                       :else [(page-query (dec (or page 1)) (or size page-size))])))}]
   (when-enabled :blog :comments
     [{:id :roots
       :depends [:posts :focus :ancestors]
       :queries (fn [{:keys [posts comment-limits standalone-comment?]}]
                  (if standalone-comment? []
                    (mapv #(comments/root-query (:id %) (comment-limit comment-limits (:id %))) posts)))
       :transform (fn [values results]
                    (let [root (comments/focus-root values)]
                      (cond-> (read-plan/rows results) root (conj root))))}
      {:id :children
       :depends [:roots]
       :queries (fn [values]
                  (mapv #(comments/thread-query (:parent-post %) (:id %))
                        (filter #(and (pos? (:reply-count % 0))
                                      (comments/expanded? (expanded-for-page values) [(:parent-post %) (:id %)]))
                                (page-roots values))))}
      {:id :grandchildren
       :depends [:children]
       :queries (fn [{:keys [children standalone-comment?] :as values}]
                  (mapv #(comments/thread-query (:parent-post %) (:id %))
                        (filter #(and (pos? (:reply-count % 0))
                                      (comments/expanded? (expanded-for-page values)
                                                          (if standalone-comment?
                                                            [(:parent-post %) (:parent-comment %) (:id %)]
                                                            (comments/full-path %))))
                                children)))}
      {:id :focus-branches
       :depends [:ancestor-replies :children :grandchildren]
       :queries (fn [{:keys [focus ancestor-replies standalone-comment?] :as values}]
                  (if standalone-comment? []
                    (->> (concat focus ancestor-replies)
                         (filter #(and (pos? (:reply-count % 0))
                                       (comments/expanded? (expanded-for-page values) (comments/full-path %))))
                         (map #(comments/thread-query (:parent-post %) (:id %)))
                         distinct vec)))}])
   [{:id :authors
     :depends (into [:posts] (when-enabled :blog :comments
                              [:roots :children :grandchildren :focus :ancestors :ancestor-replies :focus-branches]))
     :queries (fn [{:keys [posts roots children grandchildren focus ancestors ancestor-replies focus-branches]}]
                (mapv query/profile-query
                      (sort (distinct (keep :user (concat posts roots children grandchildren
                                                        focus ancestors ancestor-replies focus-branches))))))}])))

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
  (let [{:keys [posts roots children grandchildren summaries authors page post-id focus ancestors ancestor-replies focus-branches comment-id standalone-comment?]} values
        authors' (into {} (map (juxt :id identity)) authors)
        decorate (fn [row] (assoc row :author (into {} (remove (comp nil? val)) (select-keys (get authors' (:user row)) [:id :name :avatar :bg-color]))
                                     :date (display-date (:ts row) now)))]
    {:page page
     :post-id (or post-id (when standalone-comment? (:id (first posts))))
     :comment-id comment-id
     :standalone-comment? (boolean standalone-comment?)
     :missing-comment? (and comment-id (empty? focus))
     :more? (and page (< (* page page-size) (count summaries)))
     :missing? (or (and post-id (empty? posts))
                   (and standalone-comment? (or (empty? focus) (empty? posts))))
     :posts (mapv #(-> % decorate (update :tags (fn [tags] (vec (distinct (remove string/blank? (if (string? tags) (string/split tags #"\s+") tags))))))) posts)
     :comments (mapv decorate (vals (into {} (map (juxt :id identity))
                                        (concat roots children grandchildren focus ancestors ancestor-replies focus-branches))))
     :summaries summaries
     :comment-parents (or (when-enabled :blog :comments
                       (mapv #(hash-map :post-id (:parent-post %) :parent-id (:id %))
                           (concat (page-roots values) ancestors
                                   (filter #(comments/expanded? (expanded-for-page values)
                                                                (if standalone-comment?
                                                                  [(:parent-post %) (:parent-comment %) (:id %)]
                                                                  (comments/full-path %))) children)))) [])
     :app-db-edn (pr-str (cond-> {:store {:scoped cache}}
                          (seq focus) (assoc-in [:state :blog :comment-thread-expanded]
                                               (or (when-enabled :blog :comments (expanded-for-page values)) {}))))}))
