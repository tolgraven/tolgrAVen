(ns website.modules.neovim.policy
  "The same isolation headers cover network and synthesized return documents."
  (:require [clojure.string :as string]))

(def isolation-headers
  {"Cross-Origin-Opener-Policy" "same-origin"
   "Cross-Origin-Embedder-Policy" "require-corp"
   "Cross-Origin-Resource-Policy" "same-origin"})

(defn headers-for [path]
  (when (or (= "/neovim" path) (string/starts-with? path "/neovim/github/") (string/starts-with? path "/vendor/neovim/"))
    isolation-headers))

(defn document-navigation?
  "Isolation changes require a document response, in both directions."
  [current-isolated? path]
  (not= (boolean current-isolated?) (boolean (headers-for path))))
