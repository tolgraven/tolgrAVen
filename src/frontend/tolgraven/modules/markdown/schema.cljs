(ns tolgraven.modules.markdown.schema)

(def options
  [:maybe
   [:map
    [:allow-images? {:optional true} :boolean]
    [:allow-raw? {:optional true} :boolean]
    [:default-language {:optional true} [:maybe :string]]
    [:auto-languages {:optional true} [:maybe [:vector :string]]]]])
