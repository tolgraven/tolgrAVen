(ns tolgraven.components.oembed-schema)

(def result
  [:map
   [:html {:optional true} :string]
   [:title {:optional true} :string]
   [:height {:optional true} [:and number? [:> 0]]]])
