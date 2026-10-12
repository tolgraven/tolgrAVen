(ns tolgraven.components.icon
  (:require [tolgraven.components.icon-catalog :as catalog]
            [tolgraven.loader.view :as loader-view]
            [tolgraven.macros :refer-macros [defc]]))

(defc <icon>
  "Known glyphs use the small sheet; new CMS names acquire the full catalog."
  [family name attrs]
  [:<>
   (when-not (catalog/names name)
     (loader-view/form :icon-catalog :empty [] nil))
   [:i (update attrs :class #(str family " fa-" name (when % (str " " %))))]])
