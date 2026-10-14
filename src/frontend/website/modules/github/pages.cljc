(ns website.modules.github.pages
  "An isolated document is required for shared-memory Neovim."
  (:require [website.site :as site]
            #?(:cljs [website.react :as r])))

(def parameters
  [:map
   [:user [:re #"^[A-Za-z0-9_-]+$"]]
   [:repo [:and [:re #"^[A-Za-z0-9_.-]+$"] [:not [:enum "." ".."]]]]
   [:sha [:re #"^[0-9a-fA-F]{40}$"]]])

(def spec
  (if (site/enabled? :neovim)
    [["/neovim/github/:user/:repo/:sha"
      {:name :github-diff
       :module :github
       :page :diff
       :ssr true
       :streaming false
       :parameters {:path parameters}
       #?@(:cljs [:controllers
                  [{:parameters {:path [:user :repo :sha]}
                    :start (fn [{:keys [path]}]
                             (r/dispatch [:github/diff-open [(:user path) (:repo path) (:sha path)]]))}]])}]]
    []))
