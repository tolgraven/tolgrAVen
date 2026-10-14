(ns website.modules.blog-editor.draft-schema
  "Canonical draft values; native documents, Files and pending anchors are transient."
  (:require [website.schema.common :as common]
            [website.media.schema :as media]))

(def form
  [:map [:title :string] [:text :string] [:tags [:or :string [:vector :string]]]
   [:image-assets [:map-of :string media/asset]]])
(def entry [:map [:id :string] [:form form]])
(def entries [:map-of :string entry])
(def collection [:map [:owner-id common/id] [:entries entries]])
(def working
  [:map
   [:owner-id common/id]
   [:document-id :string]
   [:named? :boolean]
   [:?form form]])
(def state
  [:map [:owner :string] [:?account common/id] [:?token :string]
   [:?document-id :string] [:?named? :boolean] [:?entries entries]
   [:?working-id [:vector :any]]
   [:status [:enum :idle :loading :saving :saved :restored :failed]]
   [:?operation [:enum :save :load]]
   [:?saved-at [:int {:min 0}]] [:?warning :string] [:?error :string]])
