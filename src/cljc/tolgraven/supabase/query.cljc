(ns tolgraven.supabase.query
  #?(:cljs (:require [clojure.string :as string])))

(def ^:private direct-read-collections
  #{"blog-comments" "blog-post-ids" "blog-posts" "chat" "users"})

(def ^:private private-collections
  #{"auth" "imagor" "instagram" "secrets" "strapi" "strava" "typesense"})

(defn- parse-long-safe [value]
  (cond
    (number? value) value
    (string? value)
    (try
      #?(:clj (Long/parseLong value)
         :cljs (let [n (js/parseInt value 10)]
                 (when-not (js/isNaN n) n)))
      (catch #?(:clj Exception :cljs :default) _
        nil))
    :else nil))

(defn- path-collection-name [{:keys [path-document path-collection]}]
  (or (first path-document)
      (first path-collection)))

(defn direct-read-query? [query-map]
  (contains? direct-read-collections (path-collection-name query-map)))

(defn private-query? [query-map]
  (contains? private-collections (path-collection-name query-map)))

(defn- entry
  ([seed-key table]
   {:seed-key seed-key
    :table table})
  ([seed-key table filters]
   (cond-> (entry seed-key table)
     (seq filters) (assoc :filters filters))))

(defn seed-load-plan [{:keys [path-document path-collection] :as query-map}]
  (let [collection (path-collection-name query-map)
        doc-id (second path-document)
        post-id (some-> doc-id parse-long-safe)]
    (cond
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
  (case op
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
     (let [cmp (if (= direction :desc) #(compare %2 %1) compare)]
       (sort-by #(lookup (:data %) field) cmp acc)))
   docs
   (reverse order-by)))

(defn- contract-docs [contract collection]
  (->> (or (get contract collection)
           (get contract (keyword collection))
           {})
       (map (fn [[id data]] {:id id :data data}))
       vec))

(defn query-contract [contract {:keys [path-document path-collection where order-by limit]}]
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

    :else nil))
