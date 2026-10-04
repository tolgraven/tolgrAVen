(ns tolgraven.blog.comments)

(def page-size 10)
(defn thread-query [post-id parent-id]
  {:path-collection [:blog-comments] :scoped? true :reply-counts? true
   :where [[:parent-post :== post-id] [:parent-comment :== parent-id]]
   :order-by [[:ts :desc] [:id :desc]] :doc-changes true})
(defn root-query [post-id amount]
  (assoc (thread-query post-id nil) :limit (inc amount)))
