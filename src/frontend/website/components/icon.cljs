(ns website.components.icon
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require [website.components.icon-catalog :as catalog]
            [website.loader.view :as loader-view]
            [website.macros :refer-macros [defc]]))

(defc <icon>
  "Known glyphs use the small sheet; new CMS names acquire the full catalog."
  [family :- [:enum "fa" "fas" "fab" "far"]
   name :- :?string
   attrs :- :?map]
  [:<>
   (when-enabled :icon-catalog nil
     (when (and (seq name) (not (catalog/names name)))
       (loader-view/form :icon-catalog :empty [] nil)))
   [:i (update attrs :class #(str family " fa-" name (when % (str " " %))))]])
