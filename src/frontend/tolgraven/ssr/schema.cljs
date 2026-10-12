(ns tolgraven.ssr.schema
  (:require [tolgraven.schema.common :as c]
            [tolgraven.ssr.contract-schema :as contract]))

(def snapshot contract/snapshot)
(def settings contract/settings)
(def return-snapshot
  [:map [:version [:= 1]] [:url :string] [:build [:or :int :string]]
   [:saved-at c/milliseconds] [:state-edn :string]
   [:?modules [:sequential c/named]]
   [:?module-views contract/module-views]
   [:?title :string] [:?html :string]])
(def storage-snapshot
  [:map [:version [:= 1]] [:schema c/positive] [:expires-at c/milliseconds] [:value :any]])
(def storage-envelope [:map-of :any storage-snapshot])
(def state (c/optional-map {:hydrating? :boolean, :dates [:map-of number? :string]}))

(def capture-request
  [:map
   [:state :map]
   [:route [:map [:path :string] [:query-params {:optional true} :map]
                 [:parameters {:optional true} :map]]]
   [:modules [:vector :keyword]]
   [:snapshot [:maybe :map]]
   [:styles :map]
   [:template :string]
   [:build [:or :int :string]]
   [:owner :any]
   [:url :string]
   [:title :string]])
