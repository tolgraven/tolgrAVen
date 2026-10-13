(ns website.modules.chat.schema
  (:require [website.schema.common :as c]))

(def state (c/optional-map {:visible :boolean}))
(def form-field :?string)
