(ns tolgraven.github.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:pages-fetched [:sequential :int], :commits-fetched c/strings}))
(def options (c/optional-map {:user :string, :repo :string}))
