(ns website.modules.github.archive-schema
  "Portable archive records shared by server acquisition and browser responses.")

(def sha [:re #"^[a-fA-F0-9]{40,64}$"])
(def timestamp [:and :string [:re #"^\d{4}-\d{2}-\d{2}T"]])
(def row
  [:map
   [:sha sha]
   [:message :string]
   [:authored_at timestamp]
   [:committed_at timestamp]
   [:html_url [:re #"^https://github\.com/"]]
   [:author_name :?string]
   [:author_login :?string]
   [:avatar_url :?string]
   [:category :?string]
   [:subcategory :?string]])
(def branch [:map [:name :string] [:sha sha]])
(def checkpoint
  [:map
   [:stage [:enum "metadata" "branches" "tags" "commits"]]
   [:heads {:optional true} [:vector branch]]
   [:branches {:optional true} [:vector branch]]
   [:pending {:optional true} [:vector branch]]
   [:base {:optional true} sha]
   [:full? {:optional true} :boolean]
   [:default-branch {:optional true} :?string]
   [:page {:optional true} [:int {:min 1}]]])
(def result
  [:map
   [:commits [:vector
              [:map [:sha sha] [:html_url :string]
               [:author [:map [:login :?string] [:avatar_url :?string]]]
               [:commit [:map [:message :string]
                         [:author [:map [:name :?string] [:date timestamp]]]
                         [:committer [:map [:date timestamp]]]]]]]]
   [:total [:int {:min 0}]]
   [:next-cursor :?string]
   [:complete :boolean]])
