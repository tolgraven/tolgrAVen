(ns website.modules.content-editor.schema)

(def draft
  [:map
   [:path [:vector [:or :keyword :int]]]
   [:expected [:maybe :string]]
   [:value :string]
   [:?status [:enum :draft :saving :uploading :error]]
   [:?error :string]
   [:?upload-id :string]])
(def state
  [:map
   [:?owner [:maybe :string]]
   [:?enabled? :boolean]
   [:?settings [:map [:admin-url [:maybe :string]] [:writable? :boolean]]]
   [:?error :string]
   [:?fields [:map-of :string draft]]])
