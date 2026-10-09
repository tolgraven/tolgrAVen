(ns tolgraven.modules.link-preview.article
  "Readable public preview transport; text is rendered as text, never remote HTML.")

(def query [:map [:url [:string {:min 1 :max 4096}]]])
(def block
  [:map
   [:kind [:enum "heading" "paragraph" "quote" "code" "item"]]
   [:text [:string {:min 1 :max 3000}]]
   [:level {:optional true} [:int {:min 1 :max 4}]]])
(def result
  [:map
   [:status [:enum "ready" "unavailable"]]
   [:url :string]
   [:title [:string {:max 300}]]
   [:description [:string {:max 600}]]
   [:image {:optional true} :string]
   [:blocks [:vector {:max 32} block]]
   [:truncated? :boolean]
   [:frame-policy [:enum "none" "same-origin" "blocked"]]])
