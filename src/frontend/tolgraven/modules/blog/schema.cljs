(ns tolgraven.modules.blog.schema
  (:require [tolgraven.schema.common :as c]
            [tolgraven.schema.declarations :as declarations]
            [tolgraven.supabase.schema :as store]
            [malli.util :as mu]))

(def state
  [:map
   [:current-post-id {:optional true} [:maybe c/id]]
   [:viewing-tag {:optional true} [:maybe :string]]
   [:comments-expanded {:optional true} [:map-of c/id :boolean]]
   [:adding-comment {:optional true} [:map-of c/path [:maybe :boolean]]]
   [:page {:optional true} [:int {:min 0}]]
   [:comment-limit {:optional true} [:map-of :any [:int {:min 1}]]]
   [:comment-thread-expanded {:optional true} [:map-of :any :boolean]]])
(def options [:map [:posts-per-page {:optional true} [:int {:min 1}]]])
(def sections {[:state :blog] state [:options :blog] options})


(def post-header-spec [:map [:children [:sequential :any]]])
(def displayed-post
  (mu/merge store/post [:map [:user {:optional true} [:maybe [:or c/id store/profile]]]]))
(def post-spec (declarations/extend-spec [:map [:post displayed-post]]))
(def displayed-comment
  (mu/merge store/comment-record [:map [:user {:optional true} [:maybe [:or c/id store/profile]]]]))
(def comment-spec (declarations/extend-spec [:map [:path c/path] [:comment displayed-comment]
                                            [:visible? {:optional true} [:maybe c/derefable]]]))
(def parent-spec (declarations/extend-spec [:map [:parent-path c/path]]))
(def posted-by-spec
  (declarations/extend-spec
   (c/optional-map {:id [:maybe c/id] :user [:maybe [:or c/id store/profile]]
                    :ts [:maybe number?] :score [:maybe number?]})))
(def tag-spec [:map [:tag :string]])

(def post-fields [:maybe (c/optional-map {:title :string
                                          :text :string
                                          :tags [:or :string c/strings]})])
(def comment-fields [:map-of c/path [:maybe (c/optional-map {:title [:maybe :string]
                                                             :text [:maybe :string]})]])

(def post-records [:maybe [:map-of c/id displayed-post]])
(def comment-records [:maybe [:map-of c/id displayed-comment]])
(def post-list [:maybe [:sequential displayed-post]])
(def navigation-action [:or [:enum :prev :next] c/positive])
(def no-args [:tuple])
(def id-args [:tuple c/id])
(def selected-id-args [:tuple [:maybe c/id]])
(def path-args [:tuple c/path])
(def thread-args [:cat c/id [:? [:maybe c/id]]])
(def page-args [:tuple c/nonnegative c/positive])
(def root-page
  [:map
   [:records comment-records]
   [:loading? :boolean]
   [:more? :boolean]])

(def event-args
  {:blog/init no-args
   :blog/init-posting no-args
   :blog/edit-post [:tuple displayed-post]
   :blog/cancel-edit no-args
   :blog/state [:tuple c/path :any]
   :blog/set-posts-per-page [:tuple c/positive]
   :blog/nav-action [:tuple navigation-action]
   :blog/nav-page [:tuple c/positive]
   :blog/submit [:tuple post-fields [:maybe displayed-post]]
   :blog/post-saved [:tuple post-fields :map]
   :blog/edit-comment [:tuple c/path displayed-comment]
   :blog/cancel-comment path-args
   :blog/comment-submit [:tuple c/path [:map [:text :string]] [:maybe displayed-comment]]
   :blog/comment-saved [:tuple c/path :map :map]
   :blog/write-failed [:tuple [:or :keyword c/path] :map]
   :blog/comment-vote [:tuple [:maybe [:or c/id store/profile]] store/profile c/path [:enum :up :down]]
   :blog/vote-saved [:tuple :string [:map [:vote [:enum -1 0 1]]]]
   :blog/cache-state-changed no-args
   :blog/expand-comment-thread [:tuple c/path :boolean]
   :blog/adding-comment [:tuple c/path [:maybe :boolean]]
   :blog/load-more-comments id-args})
