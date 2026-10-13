(ns tolgraven.components.activity-schema)

(def day
  [:map
   [:date [:re #"^\d{4}-\d{2}-\d{2}$"]]
   [:count [:int {:min 0}]]
   [:level [:int {:min 0, :max 4}]]])
(def calendar [:vector {:max 371} day])
(def result [:map [:days calendar]])
