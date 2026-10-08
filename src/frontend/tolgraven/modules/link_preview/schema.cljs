(ns tolgraven.modules.link-preview.schema
  (:require [tolgraven.schema.common :as c]))

(def link-candidate
  (c/optional-map {:candidate-id c/id
                   :container-id c/id
                   :url :string
                   :title :string
                   :trust [:enum :trusted :user :untrusted]
                   :status [:enum :preview :leaving :returning :expanding :navigating :navigate :expanded]}))
(def state
  (c/optional-map {:containers [:maybe [:map-of c/id [:map [:candidates [:sequential link-candidate]] [:count c/nonnegative]]]]
                   :active [:maybe link-candidate]
                   :prefetch [:maybe [:map-of :string [:enum :queued :prefetched]]]
                   :prefetch-queue [:sequential link-candidate]}))
