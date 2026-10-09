(ns tolgraven.ssr.contract-schema
  (:require [tolgraven.schema.common :as c]
            [tolgraven.content.schema :as content]
            [tolgraven.supabase.schema :as store]))

(def module-views [:map-of c/named [:vector c/named]])
(def render-response [:map [:html :string] [:module-views module-views]])

(def snapshot
  (into [:map [:path :string]]
        (rest (c/optional-map
                {:kind c/named :content content/content :posts [:sequential store/post]
                 :summaries [:sequential store/post] :comments [:sequential store/comment-record]
                 :page [:maybe c/positive] :post-id [:maybe c/id] :shell? :boolean :missing? [:maybe :boolean]
                 :app-db-edn :string :module-views module-views :query-params c/query-params :document-title :string
                 :trusted-author-ids [:maybe [:sequential c/id]]}))))
(def settings
  (c/optional-map {:enabled :boolean :streaming :boolean :render-workers [:int {:min 1 :max 4}]
                   :worker c/text :node-binary c/text :shell-refresh-seconds c/positive
                   :cache-ttl-ms c/nonnegative}))
