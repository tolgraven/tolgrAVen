(ns tolgraven.blog.schema
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
(def page-extension
  [:map [:selection {:optional true}
         [:map [:page {:optional true} [:int {:min 1}]]]]])

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
