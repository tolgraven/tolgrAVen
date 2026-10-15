(ns website.modules.content-editor.schema
  (:require [website.content.editor-contract :as contract])
  (:require-macros [website.build.site :refer [when-enabled]]))

(def draft
  [:map
   [:path [:vector [:or :keyword :int]]]
   [:expected [:maybe :string]]
   [:value :string]
   [:?identities [:vector [:map [:path [:vector [:or :keyword :int]]] [:id [:or :string :int]]]]]
   [:?status [:enum :draft :saving :uploading :error]]
   [:?error :string]
   [:?conflict? :boolean]
   [:?upload-id :string]])
(def cv-entry-draft
  (when-enabled :cv nil
    [:map
     [:column [:int {:min 0, :max 100}]]
     [:?column-id [:maybe :int]]
     [:category contract/timeline-category]
     [:parents [:vector {:max 6} [:int {:min 0, :max 999}]]]
     [:parent-keys [:vector {:max 6} [:string {:max 500}]]]
     [:expected-count [:int {:min 0, :max 999}]]
     [:fields [:map
               [:kind [:enum "regular" "container"]]
               [:position :string]
               [:from :string]
               [:to :string]
               [:what :string]
               [:where :string]
               [:how :string]]]
     [:status [:enum :draft :saving :error]]
     [:?error :string]
     [:?conflict? :boolean]]))

(def state
  (into [:map
         [:?owner [:maybe :string]]
         [:?enabled? :boolean]
         [:?settings [:map [:admin-url [:maybe :string]] [:writable? :boolean]]]
         [:?error :string]
         [:?fields [:map-of :string draft]]
         [:?queue [:vector :string]]]
        (when-enabled :cv [] [[:?cv-entry cv-entry-draft]])))
