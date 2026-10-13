(ns tolgraven.deployment.schema)

(def metadata
  [:map
   [:commit [:maybe [:re #"^[a-fA-F0-9]{40}$"]]]
   [:deployed-at [:maybe :string]]
   [:commit-url [:maybe :string]]])
