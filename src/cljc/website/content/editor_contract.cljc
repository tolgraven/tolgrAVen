(ns website.content.editor-contract
  "The bounded field-write contract shared by the browser and server.")

(def field-path [:vector {:min 2, :max 12} [:or [:string {:min 1, :max 80}] [:int {:min 0, :max 1000}]]])
(def write-request
  [:map {:closed true}
   [:path field-path]
   [:expected [:maybe [:string {:max 20000}]]]
   [:value [:string {:max 20000}]]])
(def settings [:map [:admin-url [:maybe :string]] [:writable? :boolean]])

(defn data-path [path]
  (mapv #(if (string? %) (keyword %) %) path))
