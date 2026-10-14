(ns website.modules.github.detail-schema
  "Consumed public commit details; immutable file patches stay on the detail owner."
  (:require [clojure.string :as string]
            [website.modules.github.archive-schema :as archive]))

(def reference [:re #"^[a-fA-F0-9]{7,64}$"])
(defn safe-path? [path]
  (boolean
    (and (string? path) (seq path) (<= (count path) 480)
         (not (re-find #"[\\\u0000]" path))
         (every? #(and (seq %) (not (#{"." ".."} %))) (string/split path #"/" -1)))))
(def file-path [:and :string [:fn safe-path?]])
(def source
  [:map {:closed true}
   [:type :string]
   [:encoding :string]
   [:content [:string {:max 2101248}]]
   [:size [:int {:min 0, :max 1048576}]]])
(def identity-fields
  [:map {:closed true}
   [:name :?string]
   [:date archive/timestamp]])
(def counts
  [:map {:closed true}
   [:additions [:int {:min 0}]]
   [:deletions [:int {:min 0}]]
   [:total [:int {:min 0}]]])
(def file
  [:map {:closed true}
   [:filename [:string {:max 4096}]]
   [:status :string]
   [:additions [:int {:min 0}]]
   [:deletions [:int {:min 0}]]
   [:changes [:int {:min 0}]]
   [:patch {:optional true} [:string {:max 4194304}]]
   [:previous_filename {:optional true} [:string {:max 4096}]]
   [:raw_url {:optional true} :string]
   [:blob_url {:optional true} :string]
   [:contents_url {:optional true} :string]])
(def result
  [:map {:closed true}
   [:sha archive/sha]
   [:html_url :string]
   [:author [:map {:closed true} [:login :?string] [:avatar_url :?string]]]
   [:commit [:map {:closed true}
             [:message [:string {:max 1048576}]]
             [:author identity-fields]
             [:committer identity-fields]]]
   [:parents [:vector {:max 100} [:map {:closed true} [:sha archive/sha] [:html_url :string]]]]
   [:stats counts]
   [:files [:vector {:max 300} file]]])
