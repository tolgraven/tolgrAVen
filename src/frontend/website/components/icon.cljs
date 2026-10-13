(ns website.components.icon
  (:require [website.macros :refer-macros [defc]]))

(defc <icon>
  "Render a glyph. Dynamic consumers explicitly acquire their catalog stylesheet."
  [family :- [:enum "fa" "fas" "fab" "far"]
   name :- :?string
   attrs :- :?map]
  [:i (update attrs :class #(str family " fa-" name (when % (str " " %))))])
