(ns tolgraven.views.not-found
  (:require [tolgraven.ui :as ui]))

(defn not-found-page []
  [ui/with-heading [:common :banner-heading]
   [:div.center-content
    [:br] [:p "Four, oh four. Nothing to see here, move along."]]
   {:title "Not found" :tint "red"}])

