(ns tolgraven.modules.chat.schema
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:visible :boolean}))
(def form-field [:maybe :string])
