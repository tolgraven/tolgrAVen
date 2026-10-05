(ns tolgraven.schema.http
  (:require [reitit.coercion.malli :as malli]))

;; Open maps preserve application extension/query keys. Only declared fields are
;; coerced; do not strip OAuth/debug query parameters or untyped operation bodies.
(def coercion (malli/create {:compile (fn [schema _] schema)
                             :strip-extra-keys false
                             :error-keys #{:type :in :humanized}}))
(def page-number [:int {:min 1 :max 999999}])
(def document-name [:and :string [:re #"^[A-Za-z0-9_.-]+$"]
                    [:fn {:error/message "must name a documentation page"} #(not= ".." %)]])
(def blog-page [:map [:nr page-number]])
(def blog-post [:map [:permalink [:and :string [:re #"^(?:.*-)?\d{1,15}$"]]]])
(def blog-tag [:map [:tag [:string {:min 1 :max 200}]]])
(def docs-page [:map [:doc document-name]])
(def page-query [:map [:userBox {:optional true} :boolean]
                     [:settingsBox {:optional true} :boolean]])
(def docs-query [:map [:path document-name]])
(def url-query [:map [:url [:string {:min 1}]]])
(def messages [:map [:messages [:sequential :any]]])
(def contact [:map [:name :string] [:email :string] [:title :string] [:message :string]])
(def operands [:map [:x :int] [:y :int]])
(def positive-total [:map [:total pos-int?]])

(def upload
  [:map {:swagger/type "file"}
   [:filename :string] [:content-type :string] [:size [:int {:min 0}]]
   [:tempfile #?(:clj [:fn {:error/message "must be an uploaded file"}
                       #(instance? java.io.File %)] :cljs :any)]])
