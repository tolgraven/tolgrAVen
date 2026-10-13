(ns website.supabase.schema
  "Transport-independent contracts for queries, public records and writes.
   Authorization remains in the service/RLS layer, never in these schemas."
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require [website.media.schema :as media]
            #?(:clj [website.build.site :refer [when-enabled]]) [clojure.string :as string]
            [website.schema.common :as c]))

(defn bounded-text [limit]
  [:and [:string {:min 1 :max limit}]
   [:fn {:error/message "must contain non-whitespace text"} #(not (string/blank? %))]])
(def document-id [:and :string [:re #"^[A-Za-z0-9_-]+$"]])
(def title [:?string {:max 200}])
(when-enabled :chat nil
  (def chat-write [:map {:closed true} [:text (bounded-text 4000)]]))
(when-enabled :blog :comments
  (def comment-create
  [:map {:closed true} [:post-id c/positive] [:text (bounded-text 20000)]
   [:?parent-id [:maybe document-id]] [:?title title]]))
(when-enabled :blog :comments
  (def comment-edit
  [:map {:closed true} [:comment-id document-id] [:text (bounded-text 20000)]
   [:?title title]]))
(when-enabled :blog :comments
  (def vote-write [:map {:closed true} [:comment-id document-id] [:vote [:enum "up" "down" "none"]]]))
(when-enabled :blog nil
  (def post-write
  [:map {:closed true} [:title (bounded-text 200)] [:text (bounded-text 200000)]
   [:?post-id [:maybe c/positive]] [:?tags [:maybe (bounded-text 2000)]]]))
(when-enabled :gpt nil
  (def private-data
  (c/optional-map {:time number? :messages [:sequential :string] :text :string :title :string :user c/id})))
(when-enabled :gpt nil
  (def document-write
  [:map {:closed true} [:path [:tuple [:enum "gpt" "gpt-threads"] [:or document-id c/positive]]]
   [:data private-data] [:?merge-fields [:?sequential c/named]]]))
(def predicate [:tuple c/named [:enum :== := :> :>= :< :<= :in :tag "==" "=" ">" ">=" "<" "<=" "in" "tag"] :any])
(def query
  [:and
   (c/optional-map {:path-document [:tuple c/named c/id] :path-collection [:tuple c/named]
                    :where [:sequential predicate] :order-by [:sequential [:tuple c/named [:enum :asc :desc "asc" "desc"]]]
                    :limit c/positive :offset c/nonnegative :scoped? :boolean :summary? :boolean
                    :reply-counts? :boolean :doc-changes :boolean})
   [:fn {:error/message "provide exactly one document or collection path"}
    #(not= (boolean (:path-document %)) (boolean (:path-collection %)))]] )

(when-enabled :user nil
  (def profile
  (c/optional-map {:id c/id :seq-id :?int :name :?string :avatar :?string
                   :bg-color :?string :comment-count [:maybe c/nonnegative]
                   :karma [:maybe number?] :email :?string :comments [:sequential c/id]
                   :comment-votes [:map-of c/id [:enum -1 0 1]] :voted :map})))
(when-enabled :blog nil
  (def post
  (c/optional-map {:id c/id :permalink :?string :user [:maybe c/id]
                   :title :?string :text :?string :tags [:?or :string c/strings]
                   :score [:maybe number?] :ts [:maybe number?]
                  :image-assets [:map-of :string media/asset]})))
(when-enabled :blog :comments
  (def comment-record
  (c/optional-map {:id c/id :seq-id :?int :parent-post [:maybe c/id]
                   :parent-comment [:maybe c/id] :user [:maybe c/id] :title :?string
                   :text :?string :score [:maybe number?] :ts [:maybe number?]
                   :path [:?sequential c/id] :reply-count c/nonnegative})))
(def chat-message (c/optional-map {:text :?string :time [:maybe number?] :user [:maybe c/id]}))
(def public-store
  (c/optional-map
   (into {} (remove (comp nil? val))
         {"users" (when-enabled :user nil [:map-of c/id profile])
          "blog-posts" (when-enabled :blog nil [:map-of c/id post])
          "blog-comments" (when-enabled :blog :comments [:map-of c/id comment-record])
          "blog-post-ids" (when-enabled :blog nil [:map-of c/id [:map-of c/id number?]])
          "chat" (when-enabled :chat nil [:map-of c/id [:map-of c/id chat-message]])})))
(def document [:map [:id c/id] [:data :map]])
(def query-result [:or :nil [:map [:docs [:sequential document]]] [:map [:data :?map]]])
(def query-cache (c/optional-map {:value query-result :at c/milliseconds :expires-at c/milliseconds :owner [:?or :string :keyword [:vector :any]] :generation c/nonnegative}))
(def store
  (c/optional-map {:public public-store :scoped [:map-of :string query-result]
                   :query-errors [:?map-of :string c/error] :query-cache [:map-of :string query-cache]
                   :snapshot (c/optional-map {:seed [:map-of :keyword [:sequential :map]]
                                              :loaded [:set :string] :owner [:?or :string :keyword [:vector :any]] :generation c/nonnegative})}))

(when-enabled :user nil
  (def profile-write
  [:map {:closed true}
   [:?name [:?string {:max 2048}]]
   [:?avatar [:?string {:max 2048}]]
   [:?bg-color [:?string {:max 2048}]]]))

(def row-fields
  (into {} (remove (comp nil? val)) {"site_users" (when-enabled :user nil {:id c/id :seq_id :?int :name :?string :avatar :?string
                 :bg_color :?string :comment_count [:maybe c/nonnegative] :karma [:maybe number?]})
   "blog_posts" (when-enabled :blog nil {:doc_id c/id :id c/id :permalink :?string :user_id [:maybe c/id]
                  :title :?string :text :?string :tags [:?or :string c/strings]
                  :score [:maybe number?] :ts [:maybe number?]
                  :image_assets [:map-of :string media/asset]})
   "site_media" (when-enabled :main :media {:id media/id :source_url :?string
                                             :canonical_src :?string :status media/status
                                             :asset [:maybe media/asset] :updated_at :string})
   "blog_comments" (when-enabled :blog :comments {:id c/id :seq_id :?int :parent_post [:maybe c/id] :parent_comment [:maybe c/id]
                     :user_id [:maybe c/id] :title :?string :text :?string
                     :score [:maybe number?] :path [:?sequential c/id] :ts [:maybe number?]})
   "chat_messages" (when-enabled :chat nil {:message_id c/id :ts [:maybe number?] :user_id [:maybe c/id] :text :?string})
   "user_documents" (when-enabled :gpt nil {:owner_id :string :collection :string :doc_id c/id :data private-data :updated_at :string})}))

(def projected-rows
  (memoize
   (fn [table select]
     (let [fields (get row-fields table)
           selected (if (= "*" select) (keys fields) (map keyword (string/split select #",")))]
       [:sequential (into [:map] (map (fn [field] [field (get fields field :any)])) selected)]))))

(def options (c/optional-map {:url :?string
                              :anon-key :?string
                              :trusted-author-ids [:?sequential c/id]}))

(def init-status [:enum :loading :ready :failed])

(def writes [:map-of :any [:or :boolean [:map-of :any :boolean]]])
