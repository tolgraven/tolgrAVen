(ns website.modules.home.schema
  "Home-owned display state retained in local document pairs."
  (:require [website.schema.common :as c]))

(def gallery (c/optional-map {:loaded :boolean}))
(def return-gallery-keys [:loaded])
