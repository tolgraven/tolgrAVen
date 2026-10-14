(ns website.modules.github.configuration-schema
  "Public repository selection shared by the module and server adapter.")

(def selection
  [:map
   [:user [:re #"^[A-Za-z0-9][A-Za-z0-9-]{0,38}$"]]
   [:repo [:and [:re #"^[A-Za-z0-9_.-]{1,100}$"] [:not [:enum "." ".."]]]]])

(def result [:map [:settings [:maybe selection]]])
