(ns website.components.not-found
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc defpage]]
    [website.components.ui.layout :as ui-layout]))

(defpage <not-found-page>
  {:depends [{:source :strapi :keys [:common]}]}
  []
  [ui-layout/<with-heading> [:common :banner-heading]
   [:div.center-content
    [:br] [:p "Four, oh four. Nothing to see here, move along."]]
   {:title "Not found" :tint "red"}])
