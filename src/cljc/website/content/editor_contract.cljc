(ns website.content.editor-contract
  "The bounded editorial write contracts shared by the browser and server."
  #?(:clj (:require [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]])))

(def field-path [:vector {:min 2, :max 24} [:or [:string {:min 1, :max 80}] [:int {:min 0, :max 1000}]]])
(def write-request
  [:map {:closed true}
   [:path field-path]
   [:?identities [:vector {:max 8} [:map {:closed true} [:path field-path] [:id [:or :int [:string {:max 80}]]]]]]
   [:expected [:maybe [:string {:max 20000}]]]
   [:value [:string {:max 20000}]]])
(def settings [:map [:admin-url [:maybe :string]] [:writable? :boolean]])
(def timeline-category [:string {:min 1, :max 80}])

(def cv-entry
  (when-enabled :cv nil
    [:and
     [:map {:closed true}
      [:from [:int {:min 1600, :max 3000}]]
      [:to [:maybe [:int {:min 1600, :max 3000}]]]
      [:what [:string {:min 1, :max 200}]]
      [:kind [:enum "regular" "container"]]
      [:position [:string {:max 2000}]]
      [:where [:string {:max 200}]]
      [:how [:vector {:max 32} [:string {:max 2000}]]]]
     [:fn {:error/message "End year cannot precede start year"}
      (fn [{:keys [from to]}]
        (or (nil? to) (and (number? from) (number? to) (<= from to))))]]))
(def create-cv-entry
  (when-enabled :cv nil
    [:map {:closed true}
     [:column [:int {:min 0, :max 100}]]
     [:?column-id [:maybe :int]]
     [:category timeline-category]
     [:parents [:vector {:max 6} [:int {:min 0, :max 999}]]]
     [:parent-keys [:vector {:max 6} [:string {:max 500}]]]
     [:expected-count [:int {:min 0, :max 999}]]
     [:entry cv-entry]]))

(defn data-path [path]
  (mapv #(if (string? %) (keyword %) %) path))
