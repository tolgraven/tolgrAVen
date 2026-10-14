(ns website.modules.github.routes
  "Portable commit component paths; no GitHub implementation is loaded here."
  #?(:cljs (:require [website.react :as r])))

(def commit-sha [:and :string [:re #"^[a-fA-F0-9]{7,40}$"]])
(def commit-path [:map [:sha commit-sha]])

(def spec
  [["/github/commits/:sha"
    {:name :github-commit
     :parameters {:path commit-path}
     #?@(:cljs [:controllers [{:parameters {:path [:sha]}
                              :start (fn [_] (r/dispatch [:navigation/activate-component]))}]])
     :component-route {:module :github
                       :view :view
                       :action (fn [{:keys [path]}] [:github/navigate-commit (:sha path)])}}]])
