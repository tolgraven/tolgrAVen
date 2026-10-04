(ns tolgraven.supabase.query
  (:require [clojure.string :as string]))

(def ^:private direct-read-collections
  #{"blog-comments" "blog-post-ids" "blog-posts" "chat" "users"})

(def ^:private user-document-collections #{"gpt" "gpt-threads"})

(def ^:private private-collections
  #{"auth" "imagor" "instagram" "secrets" "strapi" "strava" "typesense"})

(defn path-part [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (number? value) (str value)
    :else nil))

(defn normalize-query [opts]
  (reduce (fn [m k]
            (if (contains? m k)
              (update m k #(mapv path-part %))
              m))
          opts [:path-document :path-collection]))

(def public-columns
  {"site_users" "id,seq_id,name,avatar,bg_color,comment_count,karma"
   "blog_posts" "doc_id,id,permalink,user_id,title,text,tags,score,ts"
   "blog_comments" "id,seq_id,parent_post,parent_comment,user_id,title,text,score,path,ts"
   "chat_messages" "message_id,ts,user_id,text"
   "user_documents" "owner_id,collection,doc_id,data,updated_at"})

(defn- parse-long-safe [value]
  (cond
    (number? value) value
    (and (string? value) (re-matches #"-?\d+" value))
    (try
      #?(:clj (Long/parseLong value)
         :cljs (let [n (js/parseInt value 10)]
                 (when-not (js/isNaN n) n)))
      (catch #?(:clj Exception :cljs :default) _
        nil))
    :else nil))

(defn- path-collection-name [{:keys [path-document path-collection]}]
  (path-part (or (first path-document)
                 (first path-collection))))

(defn public-read-query? [query-map]
  (contains? direct-read-collections (path-collection-name query-map)))

(defn user-document-query? [query-map]
  (contains? user-document-collections (path-collection-name query-map)))

(defn direct-read-query? [query-map]
  (or (public-read-query? query-map) (user-document-query? query-map)))

(defn private-query? [query-map]
  (contains? private-collections (path-collection-name query-map)))

(defn- entry
  ([seed-key table]
   {:seed-key seed-key
    :table table
    :select (get public-columns table "*")})
  ([seed-key table filters]
   (cond-> (entry seed-key table)
     (seq filters) (assoc :filters filters))))

(def schemas
  {"blog-posts" {:table "blog_posts" :seed-key :blog_posts :document-key :doc_id
                 :fields {:id :id :user :user_id :ts :ts :permalink :permalink}
                 :summary "doc_id,id,permalink,user_id,title,tags,score,ts" :batch-field :id}
   "blog-comments" {:table "blog_comments" :seed-key :blog_comments :document-key :id
                    :fields {:id :id :user :user_id :ts :ts :parent-post :parent_post :parent-comment :parent_comment}
                    :batch-field :parent-comment
                    :reply-count {:table "blog_comments" :select "id,parent_comment" :parent-field :parent_comment}}
   "users" {:table "site_users" :seed-key :users :document-key :id
             :fields {:id :id :name :name} :batch-field :id}})

(defn scoped-query? [opts]
  (and (:scoped? opts) (contains? schemas (path-collection-name opts))))

(defn- scoped-plan [opts collection doc-id]
  (let [{:keys [table seed-key document-key fields summary]} (schemas collection)
        column! (fn [field]
                  (or (fields (keyword (path-part field)))
                      (throw (ex-info "Unsupported scoped field" {:field field}))))
        filters (mapv (fn [[field op value]]
                        (let [operator ({:== "eq" := "eq" :> "gt" :>= "gte" :< "lt" :<= "lte" :in "in"}
                                        (keyword (path-part op)))]
                          (when-not operator (throw (ex-info "Unsupported scoped predicate" {:op op})))
                          [(column! field) (if (and (= operator "eq") (nil? value)) "is" operator) value]))
                      (:where opts))]
    [(cond-> (entry seed-key table (cond-> filters doc-id (conj [document-key "eq" doc-id])))
       (:limit opts) (assoc :limit (:limit opts))
       (seq (:order-by opts)) (assoc :order-by (mapv (fn [[field direction]] [(column! field) direction]) (:order-by opts)))
       (and summary (:summary? opts)) (assoc :select summary))]))

(defn profile-query [id]
  {:path-collection [:users] :scoped? true :where [[:id :== id]]})

(defn batch-key [opts]
  (let [field (:batch-field (schemas (path-collection-name opts)))
        value (some #(when (and (= field (first %)) (= :== (second %))) (nth % 2)) (:where opts))]
    (if (and field (some? value) (nil? (:limit opts)) (nil? (:offset opts)))
      (update opts :where #(filterv (fn [predicate] (not= field (first predicate))) %))
      opts)))

(defn batch-query [queries]
  (let [base (first queries) field (:batch-field (schemas (path-collection-name base)))]
    (if (= 1 (count queries)) base
      (update base :where
        #(mapv (fn [[f _ _ :as predicate]]
                 (if (= f field)
                   [field :in (mapv (fn [opts] (some (fn [[f _ v]] (when (= f field) v)) (:where opts))) queries)]
                   predicate)) %)))))

(defn reply-count-plan [opts value]
  (when-let [{:keys [table select parent-field]} (and (:reply-counts? opts)
                                                    (:reply-count (schemas (path-collection-name opts))))]
    (when (seq (:docs value))
      {:table table :select select :filters [[parent-field "in" (mapv :id (:docs value))]]})))

(defn with-reply-counts [opts value rows]
  (let [parent-field (get-in schemas [(path-collection-name opts) :reply-count :parent-field])
        counts (frequencies (map parent-field rows))]
    (update value :docs #(mapv (fn [doc] (assoc-in doc [:data :reply-count] (get counts (:id doc) 0))) %))))

(defn seed-load-plan [opts]
  (let [{:keys [path-document path-collection] :as query-map} (normalize-query opts)
        collection (path-collection-name query-map)
        doc-id (second path-document)
        post-id (some-> doc-id parse-long-safe)]
    (cond
      (scoped-query? query-map)
      (scoped-plan query-map collection doc-id)

      (= collection "blog-posts")
      (cond-> [(if path-document
                 (entry :blog_posts "blog_posts" [[:doc_id "eq" (str doc-id)]])
                 (entry :blog_posts "blog_posts"))]
        true (conj (if (and path-document post-id)
                     (entry :blog_comments "blog_comments" [[:parent_post "eq" post-id]])
                     (entry :blog_comments "blog_comments"))))

      (= collection "blog-post-ids")
      [(entry :blog_posts "blog_posts")]

      (= collection "blog-comments")
      [(if path-document
         (entry :blog_comments "blog_comments" [[:id "eq" (str doc-id)]])
         (entry :blog_comments "blog_comments"))]

      (= collection "chat")
      [(entry :chat_messages "chat_messages")]

      (= collection "users")
      [(if path-document
         (entry :users "site_users" [[:id "eq" (str doc-id)]])
         (entry :users "site_users"))]

      (contains? user-document-collections collection)
      [(entry :store_documents "user_documents"
              (cond-> [[:collection "eq" collection]]
                path-document (conj [:doc_id "eq" (str doc-id)])))]

      (= collection "auth")
      [(entry :roles "auth_roles")]

      (contains? private-collections collection)
      [(if path-document
         (entry :service_configs "service_configs" [[:collection "eq" collection]
                                                    [:doc_id "eq" (str doc-id)]])
         (entry :service_configs "service_configs" [[:collection "eq" collection]]))]

      :else [])))

(defn realtime-tables [query-map]
  (->> (seed-load-plan query-map)
       (map :table)
       distinct
       vec))

(defn- kw-or-str [k]
  (cond
    (keyword? k) [(keyword (name k)) (name k)]
    (string? k) [k (keyword k)]
    :else [k]))

(defn- lookup [m k]
  (some #(get m %) (kw-or-str k)))

(defn- compare-op [op left right]
  (case (keyword (path-part op))
    :in (boolean (some #{left} right))
    :== (= left right)
    := (= left right)
    :> (> left right)
    :>= (>= left right)
    :< (< left right)
    :<= (<= left right)
    false))

(defn- apply-where [docs where]
  (reduce
   (fn [acc [field op value]]
     (filterv
      (fn [{:keys [data]}]
        (compare-op op (lookup data field) value))
      acc))
   (vec docs)
   where))

(defn- apply-order-by [docs order-by]
  (reduce
   (fn [acc [field direction]]
     (let [cmp (if (= (path-part direction) "desc") #(compare %2 %1) compare)]
       (sort-by #(lookup (:data %) field) cmp acc)))
   docs
   (reverse order-by)))

(defn- contract-docs [contract collection]
  (->> (or (get contract collection)
           (get contract (keyword collection))
           {})
       (map (fn [[id data]] {:id id :data data}))
       vec))

(defn query-contract [contract opts]
  (let [{:keys [path-document path-collection where order-by limit]} (normalize-query opts)]
   (cond
    path-document
    (let [[collection doc-id] path-document]
      (some-> (get-in contract [collection doc-id])
              (as-> data {:id doc-id :data data})))

    path-collection
    (let [[collection] path-collection
          docs (as-> (contract-docs contract collection) docs
                 (if (seq where) (apply-where docs where) docs)
                 (if (seq order-by) (vec (apply-order-by docs order-by)) docs)
                 (if limit (vec (take limit docs)) docs))]
      {:docs docs})

    :else nil)))
