(ns tolgraven.supabase.schema
  "Transport-independent contracts for queries, public records and writes.
   Authorization remains in the service/RLS layer, never in these schemas."
  (:require [clojure.string :as string]
            [tolgraven.schema.common :as c]))

(defn bounded-text [limit]
  [:and [:string {:min 1 :max limit}]
   [:fn {:error/message "must contain non-whitespace text"} #(not (string/blank? %))]])
(def document-id [:and :string [:re #"^[A-Za-z0-9_-]+$"]])
(def title [:maybe [:string {:max 200}]])
(def chat-write [:map {:closed true} [:text (bounded-text 4000)]])
(def comment-create
  [:map {:closed true} [:post-id c/positive] [:text (bounded-text 20000)]
   [:parent-id {:optional true} [:maybe document-id]] [:title {:optional true} title]])
(def comment-edit
  [:map {:closed true} [:comment-id document-id] [:text (bounded-text 20000)]
   [:title {:optional true} title]])
(def vote-write [:map {:closed true} [:comment-id document-id] [:vote [:enum "up" "down" "none"]]])
(def post-write
  [:map {:closed true} [:title (bounded-text 200)] [:text (bounded-text 200000)]
   [:post-id {:optional true} [:maybe c/positive]] [:tags {:optional true} [:maybe (bounded-text 2000)]]])
(def private-data
  (c/optional-map {:time number? :messages [:sequential :string] :text :string :title :string :user c/id}))
(def document-write
  [:map {:closed true} [:path [:tuple [:enum "gpt" "gpt-threads"] [:or document-id c/positive]]]
   [:data private-data] [:merge-fields {:optional true} [:maybe [:sequential c/named]]]])
(def predicate [:tuple c/named [:enum :== := :> :>= :< :<= :in :tag "==" "=" ">" ">=" "<" "<=" "in" "tag"] :any])
(def query
  [:and
   (c/optional-map {:path-document [:tuple c/named c/id] :path-collection [:tuple c/named]
                    :where [:sequential predicate] :order-by [:sequential [:tuple c/named [:enum :asc :desc "asc" "desc"]]]
                    :limit c/positive :offset c/nonnegative :scoped? :boolean :summary? :boolean
                    :reply-counts? :boolean :doc-changes :boolean})
   [:fn {:error/message "provide exactly one document or collection path"}
    #(not= (boolean (:path-document %)) (boolean (:path-collection %)))]] )

(def profile
  (c/optional-map {:id c/id :seq-id [:maybe :int] :name [:maybe :string] :avatar [:maybe :string]
                   :bg-color [:maybe :string] :comment-count [:maybe c/nonnegative]
                   :karma [:maybe number?] :email [:maybe :string] :comments [:sequential c/id]
                   :comment-votes [:map-of c/id [:enum -1 0 1]] :voted :map}))
(def post
  (c/optional-map {:id c/id :permalink [:maybe :string] :user [:maybe c/id]
                   :title [:maybe :string] :text [:maybe :string] :tags [:maybe [:or :string c/strings]]
                   :score [:maybe number?] :ts [:maybe number?]}))
(def comment-record
  (c/optional-map {:id c/id :seq-id [:maybe :int] :parent-post [:maybe c/id]
                   :parent-comment [:maybe c/id] :user [:maybe c/id] :title [:maybe :string]
                   :text [:maybe :string] :score [:maybe number?] :ts [:maybe number?]
                   :path [:maybe [:sequential c/id]] :reply-count c/nonnegative}))
(def chat-message (c/optional-map {:text [:maybe :string] :time [:maybe number?] :user [:maybe c/id]}))
(def public-store
  (c/optional-map {"users" [:map-of c/id profile] "blog-posts" [:map-of c/id post]
                   "blog-comments" [:map-of c/id comment-record] "blog-post-ids" [:map-of c/id [:map-of c/id number?]]
                   "chat" [:map-of c/id [:map-of c/id chat-message]]}))
(def document [:map [:id c/id] [:data :map]])
(def query-result [:or :nil [:map [:docs [:sequential document]]] [:map [:data [:maybe :map]]]])
(def query-cache (c/optional-map {:value query-result :at c/milliseconds :expires-at c/milliseconds :owner [:maybe [:or :string :keyword [:vector :any]]] :generation c/nonnegative}))
(def store
  (c/optional-map {:public public-store :scoped [:map-of :string query-result]
                   :query-errors [:maybe [:map-of :string c/error]] :query-cache [:map-of :string query-cache]
                   :snapshot (c/optional-map {:seed [:map-of :keyword [:sequential :map]]
                                              :loaded [:set :string] :owner [:maybe [:or :string :keyword [:vector :any]]] :generation c/nonnegative})}))

(def profile-write
  [:map {:closed true}
   [:name {:optional true} [:maybe [:string {:max 2048}]]]
   [:avatar {:optional true} [:maybe [:string {:max 2048}]]]
   [:bg-color {:optional true} [:maybe [:string {:max 2048}]]]])

(def row-fields
  {"site_users" {:id c/id :seq_id [:maybe :int] :name [:maybe :string] :avatar [:maybe :string]
                 :bg_color [:maybe :string] :comment_count [:maybe c/nonnegative] :karma [:maybe number?]}
   "blog_posts" {:doc_id c/id :id c/id :permalink [:maybe :string] :user_id [:maybe c/id]
                  :title [:maybe :string] :text [:maybe :string] :tags [:maybe [:or :string c/strings]]
                  :score [:maybe number?] :ts [:maybe number?]}
   "blog_comments" {:id c/id :seq_id [:maybe :int] :parent_post [:maybe c/id] :parent_comment [:maybe c/id]
                     :user_id [:maybe c/id] :title [:maybe :string] :text [:maybe :string]
                     :score [:maybe number?] :path [:maybe [:sequential c/id]] :ts [:maybe number?]}
   "chat_messages" {:message_id c/id :ts [:maybe number?] :user_id [:maybe c/id] :text [:maybe :string]}
   "user_documents" {:owner_id :string :collection :string :doc_id c/id :data private-data :updated_at :string}})
(def projected-rows
  (memoize
   (fn [table select]
     (let [fields (get row-fields table)
           selected (if (= "*" select) (keys fields) (map keyword (string/split select #",")))]
       [:sequential (into [:map] (map (fn [field] [field (get fields field :any)])) selected)]))))
