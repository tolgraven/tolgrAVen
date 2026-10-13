(ns website.modules.instagram.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [website.schema.common :as c]))

(def post
  (c/optional-map {:id :string
                   :caption :string
                   :media_type :string
                   :media_url :string
                   :permalink :string
                   :timestamp :string
                   :thumbnail_url :string}))
