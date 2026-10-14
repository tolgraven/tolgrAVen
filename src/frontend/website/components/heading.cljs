(ns website.components.heading
  "Heading markup shared by server rendering and the interactive application."
  (:require
    [website.content.schema :as schema]
    [website.content.fields :as fields]
    [website.components.image :as image]
    [website.macros :refer-macros [defc]]))

(defc <banner> [{:keys [title target bg tint] :as content} :- schema/heading & [navigate!] :- [:maybe fn?]]
  [:<>
   [:div.fading-bg-heading
    {:class "section-with-media-bg-wrapper covering stick-up fullwidth"
     :on-click (when (and target navigate!) #(navigate! (keyword target)))}
    [:div.fader
     [image/<media-as-bg> (merge (fields/media content [:bg]) {:fetchPriority "high" :loading "eager"})]
     [:section.covering-faded.noborder
      {:style (when tint {:background (str "var(--" tint ")")
                          :filter "saturate(1.7) brightness(0.9)"})}
      [fields/<text> :h1.h-responsive content [:title] {:style {:transform "translateY(-10%)"}}]]]]
   [:div.fader>div.fade-to-black.bottom]])
