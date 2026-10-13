(ns website.modules.carousel.gallery
  "Preserved gallery prototypes using the carousel module's rendered views."
  (:require [website.content.schema :as schema]
            [website.components.image :as image]
            [website.macros :refer-macros [defc]]
            [website.modules.carousel.views :as carousel]))

(defc <gallery-2> "Gallery carousel"
  [img-attrs :- [:sequential schema/media]]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel-normal"]
   [carousel/<carousel-normal> :gallery-2-normal {:style {:height "40vh"}}
    (into []
          (for [img img-attrs]
            ^{:key (str "gallery-2-normal-" (:src img))}
            [image/<picture> (merge img {:class "media"})]))]])

(defc <gallery-3> "Gallery carousel"
  [img-attrs :- [:sequential schema/media]]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel"]
   [carousel/<carousel> :gallery-2 {:style {:height "30vh"}}
    (into []
          (for [img img-attrs]
            ^{:key (str "gallery-" (:src img))}
            [image/<picture> (merge img {:class "media"})]))]])
