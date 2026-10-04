(ns tolgraven.components.heading
  "Heading markup shared by server rendering and the interactive application."
  (:require [tolgraven.image :as image]
            [tolgraven.macros :refer-macros [defc]]))

(defc <banner> [{:keys [title target bg tint]} & [navigate!]]
  [:<>
   [:div.fading-bg-heading
    {:class "section-with-media-bg-wrapper covering stick-up fullwidth"
     :on-click (when (and target navigate!) #(navigate! (keyword target)))}
    [:div.fader
     [image/media-as-bg bg]
     [:section.covering-faded.noborder
      {:style (when tint {:background (str "var(--" tint ")")
                          :filter "saturate(1.7) brightness(0.9)"})}
      [:h1.h-responsive {:style {:transform "translateY(-10%)"}} title]]]]
   [:div.fader>div.fade-to-black.bottom]])
