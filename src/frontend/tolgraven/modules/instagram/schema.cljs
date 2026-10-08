(ns tolgraven.modules.instagram.schema
  "Consumed provider fields; provider extension fields remain open."
  (:require [tolgraven.schema.common :as c]))

(def post
  (c/optional-map {:id :string :caption :string :media_type :string :media_url :string
                   :permalink :string :timestamp :string :thumbnail_url :string}))
