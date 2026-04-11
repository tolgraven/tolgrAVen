(ns tolgraven.store.contract)

(def ^:private service-docs
  [{:collection "auth" :doc-id "roles" :service "auth/roles"}
   {:collection "imagor" :doc-id "auth" :service "imagor/auth"}
   {:collection "instagram" :doc-id "auth" :service "instagram/auth"}
   {:collection "instagram" :doc-id "posts" :service "instagram/posts"}
   {:collection "secrets" :doc-id "instagram" :service "secrets/instagram"}
   {:collection "secrets" :doc-id "strava" :service "secrets/strava"}
   {:collection "secrets" :doc-id "typesense" :service "secrets/typesense"}
   {:collection "strapi" :doc-id "auth" :service "strapi/auth"}
   {:collection "strava" :doc-id "auth" :service "strava/auth"}
   {:collection "typesense" :doc-id "auth" :service "typesense/auth"}])

(def ^:private normalized-collections
  #{"auth" "blog-comments" "blog-post-ids" "blog-posts" "chat" "users"})

(def ^:private service-doc-keys
  (into #{}
        (map (juxt :collection :doc-id))
        service-docs))

(defn- doc-id-str [id]
  (cond
    (keyword? id) (name id)
    (string? id) id
    :else (str id)))

(defn- with-doc-id [doc]
  (let [{:keys [id data]} doc]
    [(doc-id-str id) data]))

(defn collection-doc-map [firebase-export collection]
  (into {}
        (map with-doc-id)
        (or (get firebase-export collection)
            (get firebase-export (keyword collection))
            [])))

(defn- keep-present
  [m k v]
  (if (nil? v) m (assoc m k v)))

(defn- drop-nils [m]
  (reduce-kv
   (fn [acc k v] (keep-present acc k v))
   {}
   m))

(defn- merge-prefer-non-nil
  [& maps]
  (reduce
   (fn [acc m]
     (reduce-kv (fn [inner k v] (keep-present inner k v)) acc m))
   {}
   maps))

(defn- comparable-id [id]
  (let [s (doc-id-str id)]
    (try
      [0 #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))]
      (catch #?(:clj Exception :cljs :default) _
        [1 s]))))

(defn- sort-comment-entries [comments]
  (sort-by (comp comparable-id key) comments))

(defn flatten-comment-tree
  ([post-id comments]
   (flatten-comment-tree post-id nil [post-id] comments))
  ([post-id parent-comment path comments]
   (mapcat
    (fn [[doc-id comment]]
      (let [comment-id (or (:id comment) doc-id)
            next-path (conj path comment-id)
            comment' (-> comment
                         (assoc :id comment-id)
                         (assoc :parent-post (or (:parent-post comment) post-id))
                         (assoc :parent-comment (if (contains? comment :parent-comment)
                                                  (:parent-comment comment)
                                                  parent-comment))
                         (assoc :path (or (:path comment) path))
                         (dissoc :comments))]
        (cons comment'
              (flatten-comment-tree post-id
                                    comment-id
                                    next-path
                                    (:comments comment)))))
    (sort-comment-entries comments))))

(defn- flatten-post-comments [posts]
  (mapcat
   (fn [[_ post]]
     (flatten-comment-tree (:id post) (:comments post)))
   posts))

(defn- indexed-comments [comments]
  (reduce
   (fn [acc comment]
     (let [comment-id (doc-id-str (:id comment))]
       (update acc comment-id merge-prefer-non-nil comment)))
   {}
   comments))

(defn- child-map [comment-rows]
  (reduce
   (fn [acc comment]
     (update acc (:parent-comment comment) (fnil conj []) comment))
   {}
   comment-rows))

(declare build-comment-tree)

(defn- build-comment-node [children-by-parent comment]
  (let [comment-id (:id comment)
        children (build-comment-tree children-by-parent comment-id)
        comment' (-> comment
                     (dissoc :parent-post :parent-comment :path)
                     drop-nils)]
    (if (seq children)
      (assoc comment' :comments children)
      comment')))

(defn build-comment-tree [children-by-parent parent-comment]
  (->> (get children-by-parent parent-comment)
       (sort-by (juxt #(or (:seq-id %) 0)
                      #(or (:ts %) 0)
                      (comp doc-id-str :id)))
       (map (fn [comment]
              [(doc-id-str (:id comment))
               (build-comment-node children-by-parent comment)]))
       (into {})))

(defn- root-comments [post-id comments]
  (let [children-by-parent (child-map comments)
        roots (->> comments
                   (filter #(= post-id (:parent-post %)))
                   (filter #(nil? (:parent-comment %)))
                   (sort-by (juxt #(or (:seq-id %) 0)
                                  #(or (:ts %) 0)
                                  (comp doc-id-str :id))))]
    (into {}
          (map (fn [comment]
                 [(doc-id-str (:id comment))
                  (build-comment-node children-by-parent comment)]))
          roots)))

(defn firebase-export->contract [firebase-export]
  (let [posts (collection-doc-map firebase-export "blog-posts")
        flat-comments (vals (collection-doc-map firebase-export "blog-comments"))
        nested-comments (flatten-post-comments posts)
        merged-comments (indexed-comments (concat flat-comments nested-comments))]
    {"auth" (collection-doc-map firebase-export "auth")
     "blog-comments" merged-comments
     "blog-post-ids" (collection-doc-map firebase-export "blog-post-ids")
     "blog-posts" posts
     "chat" (collection-doc-map firebase-export "chat")
     "imagor" (collection-doc-map firebase-export "imagor")
     "instagram" (collection-doc-map firebase-export "instagram")
     "secrets" (collection-doc-map firebase-export "secrets")
     "strapi" (collection-doc-map firebase-export "strapi")
     "strava" (collection-doc-map firebase-export "strava")
     "typesense" (collection-doc-map firebase-export "typesense")
     "users" (collection-doc-map firebase-export "users")}))

(defn- role-rows [contract]
  (let [roles (get-in contract ["auth" "roles"])]
    (mapcat
     (fn [[role user-ids]]
       (map (fn [user-id]
              {:role (name role)
               :user_id user-id})
            user-ids))
     roles)))

(defn- user-rows [contract]
  (map
   (fn [[id user]]
     {:id id
      :seq_id (:seq-id user)
      :name (:name user)
      :email (:email user)
      :avatar (:avatar user)
      :bg_color (:bg-color user)
      :comment_count (:comment-count user)
      :karma (:karma user)
      :comments (vec (:comments user))
      :voted (or (:voted user) {})
      :raw user})
   (sort-by key (get contract "users"))))

(defn- post-rows [contract]
  (map
   (fn [[doc-id post]]
     {:doc_id doc-id
      :id (:id post)
      :permalink (:permalink post)
      :user_id (:user post)
      :title (:title post)
      :text (:text post)
      :tags (:tags post)
      :score (:score post)
      :ts (:ts post)
      :raw (dissoc post :comments)})
   (sort-by (comp comparable-id key) (get contract "blog-posts"))))

(defn- comment-rows [contract]
  (map
   (fn [[doc-id comment]]
     {:id doc-id
      :seq_id (:seq-id comment)
      :parent_post (:parent-post comment)
      :parent_comment (some-> (:parent-comment comment) doc-id-str)
      :user_id (:user comment)
      :title (:title comment)
      :text (:text comment)
      :score (:score comment)
      :path (vec (:path comment))
      :ts (:ts comment)
      :raw (dissoc comment :comments)})
   (sort-by (comp comparable-id key) (get contract "blog-comments"))))

(defn- chat-rows [contract]
  (let [messages (get-in contract ["chat" "messages"] {})]
    (map
     (fn [[message-id message]]
       {:message_id (doc-id-str message-id)
        :ts (:time message)
        :user_id (:user message)
        :text (:text message)
        :raw message})
     (sort-by (comp comparable-id key) messages))))

(defn- service-config-rows [contract]
  (keep
   (fn [{:keys [collection doc-id service]}]
     (when-let [data (get-in contract [collection doc-id])]
       {:service service
        :collection collection
        :doc_id doc-id
        :config data}))
   service-docs))

(defn- generic-doc-rows [contract]
  (mapcat
   (fn [[collection docs]]
     (when-not (normalized-collections collection)
       (keep
        (fn [[doc-id data]]
          (when-not (contains? service-doc-keys [collection doc-id])
            {:collection collection
             :doc_id doc-id
             :data data}))
        docs)))
   contract))

(declare contract->seed)

(defn firebase-export->seed [firebase-export]
  (let [contract (firebase-export->contract firebase-export)]
    (contract->seed contract)))

(defn contract->seed [contract]
  {:roles (vec (role-rows contract))
   :users (vec (user-rows contract))
   :blog_posts (vec (post-rows contract))
   :blog_comments (vec (comment-rows contract))
   :chat_messages (vec (chat-rows contract))
   :service_configs (vec (service-config-rows contract))
   :store_documents (vec (generic-doc-rows contract))})

(defn- contract-posts [seed]
  (let [comment-rows (map
                      (fn [comment]
                        {:id (:id comment)
                         :seq-id (:seq_id comment)
                         :parent-post (:parent_post comment)
                         :parent-comment (:parent_comment comment)
                         :user (:user_id comment)
                         :title (:title comment)
                         :text (:text comment)
                         :score (:score comment)
                         :path (:path comment)
                         :ts (:ts comment)})
                      (:blog_comments seed))]
    (into {}
          (map
           (fn [post]
             [(:doc_id post)
              (cond-> (drop-nils {:id (:id post)
                                  :permalink (:permalink post)
                                  :user (:user_id post)
                                  :title (:title post)
                                  :text (:text post)
                                  :tags (:tags post)
                                  :score (:score post)
                                  :ts (:ts post)})
                (seq (root-comments (:id post) comment-rows))
                (assoc :comments (root-comments (:id post) comment-rows)))])
           (:blog_posts seed)))))

(defn- contract-post-ids [seed]
  {"id" (into {}
              (map (fn [post] [(:id post) (:ts post)]))
              (:blog_posts seed))})

(defn- contract-comments [seed]
  (into {}
        (map
         (fn [comment]
           [(:id comment)
            (drop-nils
             {:id (:id comment)
              :seq-id (:seq_id comment)
              :parent-post (:parent_post comment)
              :parent-comment (:parent_comment comment)
              :user (:user_id comment)
              :title (:title comment)
              :text (:text comment)
              :score (:score comment)
              :path (:path comment)
              :ts (:ts comment)})]))
        (:blog_comments seed)))

(defn- contract-users [seed]
  (into {}
        (map
         (fn [user]
           [(:id user)
            {:seq-id (:seq_id user)
             :avatar (:avatar user)
             :name (:name user)
             :id (:id user)
             :bg-color (:bg_color user)
             :email (:email user)
             :comments (vec (:comments user))
             :comment-count (:comment_count user)
             :karma (:karma user)
             :voted (or (:voted user) {})}]))
        (:users seed)))

(defn- contract-roles [seed]
  {"roles"
   (reduce
    (fn [acc {:keys [role user_id]}]
      (update acc (keyword role) (fnil conj []) user_id))
    {}
    (:roles seed))})

(defn- contract-chat [seed]
  {"messages"
   (into {}
         (map
          (fn [message]
            [(:message_id message)
             {:text (:text message)
              :time (:ts message)
              :user (:user_id message)}]))
         (:chat_messages seed))})

(defn- contract-services [seed collection]
  (into {}
        (comp
         (filter #(= collection (:collection %)))
         (map (fn [{:keys [doc_id config]}] [doc_id config])))
        (:service_configs seed)))

(defn- contract-generic-docs [seed]
  (reduce
   (fn [acc {:keys [collection doc_id data]}]
     (assoc-in acc [collection doc_id] data))
   {}
   (:store_documents seed)))

(defn seed->contract [seed]
  (merge-with merge
              {"auth" (contract-roles seed)
               "blog-comments" (contract-comments seed)
               "blog-post-ids" (contract-post-ids seed)
               "blog-posts" (contract-posts seed)
               "chat" (contract-chat seed)
               "imagor" (contract-services seed "imagor")
               "instagram" (contract-services seed "instagram")
               "secrets" (contract-services seed "secrets")
               "strapi" (contract-services seed "strapi")
               "strava" (contract-services seed "strava")
               "typesense" (contract-services seed "typesense")
               "users" (contract-users seed)}
              (contract-generic-docs seed)))
