(ns tolgraven.components.not-found
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc defpage]]
    [tolgraven.components.ui.layout :as ui-layout]))

(defpage <not-found-page>
  {:depends [{:source :strapi :keys [:common]}]}
  []
  [ui-layout/<with-heading> [:common :banner-heading]
   [:div.center-content
    [:br] [:p "Four, oh four. Nothing to see here, move along."]]
   {:title "Not found" :tint "red"}])
