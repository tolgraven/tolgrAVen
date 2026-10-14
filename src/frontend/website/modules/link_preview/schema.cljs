(ns website.modules.link-preview.schema
  (:require [website.schema.common :as c]))

(def link-candidate
  (c/optional-map {:candidate-id c/id
                   :container-id c/id
                   :url :string
                   :title :string
                   :trust [:enum :trusted :user :untrusted]
                   :status [:enum :preview :leaving :returning :expanding :navigating :navigate :expanded]}))
(def return-state-keys [:visited])

(def state
  (c/optional-map {:visited [:set :string]
                   :containers [:?map-of c/id [:map [:candidates [:sequential link-candidate]] [:count c/nonnegative]]]
                   :active [:maybe link-candidate]
                   :prefetch [:?map-of :string [:enum :queued :prefetched]]
                   :prefetch-queue [:sequential link-candidate]}))
