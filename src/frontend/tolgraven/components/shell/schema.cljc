(ns tolgraven.components.shell.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def contact-state (c/optional-map {:show? :boolean
                                    :sent? :boolean
                                    :closing? :boolean
                                    :response :any}))
(def contact-fields (c/optional-map {:name :string
                                     :email :string
                                     :title :string
                                     :message :string}))
