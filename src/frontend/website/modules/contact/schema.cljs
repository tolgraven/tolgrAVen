(ns website.modules.contact.schema
  (:require [website.schema.common :as c]))

(def state
  (c/optional-map {:show? :boolean
                   :sent? :boolean
                   :closing? :boolean
                   :response :any}))

(def fields
  (c/optional-map {:name :string
                   :email :string
                   :title :string
                   :message :string}))
