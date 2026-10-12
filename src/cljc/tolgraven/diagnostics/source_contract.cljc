(ns tolgraven.diagnostics.source-contract
  "Source browser transport contracts; only development consumers acquire these.")

(def file-path [:string {:min 1 :max 512}])
(def file-query [:map [:file file-path]])
(def entry [:map [:path file-path] [:namespace :?string] [:language :string]])
(def catalog [:vector entry])
(def document
  [:map
   [:path file-path]
   [:namespace :?string]
   [:language :string]
   [:content [:string {:max 524288}]]])
