(ns tolgraven.blog.schema)

(def state
  [:map
   [:page {:optional true} [:int {:min 0}]]
   [:comment-limit {:optional true} [:map-of :any [:int {:min 1}]]]
   [:comment-thread-expanded {:optional true} [:map-of :any :boolean]]])
(def options [:map [:posts-per-page {:optional true} [:int {:min 1}]]])
(def sections {[:state :blog] state [:options :blog] options})


(def post-header-spec [:map [:children [:sequential :any]]])
(def page-extension
  [:map [:selection {:optional true}
         [:map [:page {:optional true} [:int {:min 1}]]]]])
