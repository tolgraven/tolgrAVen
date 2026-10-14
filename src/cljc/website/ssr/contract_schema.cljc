(ns website.ssr.contract-schema
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require #?(:clj [website.build.site :refer [when-enabled]]) [website.schema.common :as c]
            [website.content.schema :as content]
            [website.supabase.schema :as store]))

(def module-views [:map-of c/named [:vector c/named]])
(def render-response [:map [:html :string] [:module-views module-views]])

(def snapshot
  (into [:map [:path :string]]
        (rest (c/optional-map
                {:kind c/named :content content/content :posts [:sequential (or (when-enabled :blog nil store/post) :map)]
                 :summaries [:sequential (or (when-enabled :blog nil store/post) :map)] :comments [:sequential (or (when-enabled :blog :comments store/comment-record) :map)]
                 :page [:maybe c/positive] :post-id [:maybe c/id] :shell? :boolean :missing? :?boolean
                 :comment-id :?string
                 :standalone-comment? :boolean
                 :missing-comment? :?boolean
                 :route-parameters (c/optional-map {:path c/query-params :query c/query-params})
                 :app-db-edn :string :module-views module-views :query-params c/query-params :document-title :string
                 :trusted-author-ids [:?sequential c/id]}))))
(def settings
  (c/optional-map {:enabled :boolean :streaming :boolean :render-workers [:int {:min 1 :max 4}]
                   :worker c/text :node-binary c/text :shell-refresh-seconds c/positive
                   :cache-ttl-ms c/nonnegative}))
