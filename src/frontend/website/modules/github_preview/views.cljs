(ns website.modules.github-preview.views
  (:require [website.macros :refer-macros [defc]]
            [website.modules.search.model :as model]
            [website.modules.search.presentation :as presentation]))

(defc <commit> [{:keys [document title excerpt]} :- model/hit]
  [:article.search-instant-result.github-commit
   [:div.github-commit-message
    [:div.info (:repository document) " · " (:author document)]
    [:div.github-commit-titles
     [:a.title {:href (:href document)} [presentation/<fragments> title]]]]
   [:p [presentation/<fragments> excerpt]]])
