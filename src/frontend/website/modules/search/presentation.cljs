(ns website.modules.search.presentation
  "Plain hit fragments shared by module-owned lightweight previews."
  (:require [website.macros :refer-macros [defc]]
            [website.modules.search.model :as model]))

(defc <fragments>
  [segments :- [:sequential model/segment]]
  (into [:<>]
        (map-indexed (fn [index {:keys [text marked?]}]
                       (if marked? ^{:key index} [:mark text] text)))
        segments))

(defc <fallback> [hit :- model/hit]
  [:article.search-instant-result
   [:h3 [:a {:href (get-in hit [:document :href])} [<fragments> (:title hit)]]]
   [:p [<fragments> (:excerpt hit)]]])
