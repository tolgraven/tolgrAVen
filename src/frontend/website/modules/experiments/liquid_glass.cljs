(ns website.modules.experiments.liquid-glass
  (:require [website.react :as r]
            [website.component]
            [website.component.registry]
            [website.macros :refer-macros [defc]]
            [website.components.ui.liquid-glass :as glass]))

(def initial-state
  {:shape :rounded-rect
   :profile :squircle
   :opacity 0.85
   :scale 1
   :index 1.5
   :light-opacity 0.35
   :outset "1rem"
   :name "Clear thinking"
   :clicks 0
   :instances 1})

(def state-schema
  [:map
   [:shape [:enum :rounded-rect :capsule :ellipse :polygon]]
   [:profile [:enum :convex :squircle :concave :lip]]
   [:opacity glass/unit-interval]
   [:scale [:and number? [:>= 0] [:<= 4]]]
   [:index [:and number? [:>= 1] [:<= 3]]]
   [:light-opacity glass/unit-interval]
   [:outset :string]
   [:name :string]
   [:clicks [:int {:min 0}]]
   [:instances [:int {:min 1, :max 24}]]])

(defc <playground>
  "A live test of interactive children, larger backgrounds and shared lenses."
  {:state {:initial initial-state, :schema state-schema}}
  []
  (let [*settings (<sub :comp [])
        settings (or @*settings initial-state)
        options (merge (select-keys settings [:shape :profile :opacity :scale :index :light-opacity :outset])
                       {:props {:class "glass-playground__panel", :id "glass-primary"}
                        :content-props {:class "glass-playground__panel-content"}})]
    [:div.glass-playground
     [:h2 "Liquid glass"]
     [:p "The background bends and catches light. The controls inside remain ordinary components."]
     [:div.glass-playground__controls
      (for [[field choices] [[:shape [:rounded-rect :capsule :ellipse :polygon]]
                             [:profile [:convex :squircle :concave :lip]]]]
        ^{:key field}
        [:label (name field)
         [:select {:value (name (get settings field))
                   :on-change #(>update *settings assoc field (keyword (.. % -target -value)))}
          (for [choice choices] ^{:key choice} [:option {:value (name choice)} (name choice)])]])
      (for [[field label low high step] [[:opacity "Glass opacity" 0 1 0.05]
                                        [:scale "Refraction strength" 0 4 0.1]
                                        [:index "Refractive index" 1 3 0.1]
                                        [:light-opacity "Rim light" 0 1 0.05]
                                        [:instances "Instances" 1 24 1]]]
        ^{:key field}
        [:label label
         [:input {:type "range"
                  :min low
                  :max high
                  :step step
                  :value (get settings field)
                  :on-change #(>update *settings assoc field (js/parseFloat (.. % -target -value)))}]
         [:output (str (get settings field))]])
      [:label "Background outset"
       [:select {:value (:outset settings)
                 :on-change #(>update *settings assoc :outset (.. % -target -value))}
        [:option {:value "0"} "Fits content"]
        [:option {:value "1rem"} "1rem larger"]
        [:option {:value "2rem"} "2rem larger"]]]]
     [:div.glass-playground__scene
      [glass/<liquid-glass> options
       [:h3 (:name settings)]
       [:p "A translucent lens with a background that can grow beyond its contents."]
       [:label "Panel title"
        [:input {:value (:name settings)
                 :on-change #(>update *settings assoc :name (.. % -target -value))}]]
       [:button {:on-click #(>update *settings update :clicks inc)}
        (str "Pressed " (:clicks settings) " times")]]
      [glass/<liquid-glass> (assoc options :mode :frosted :props {:class "glass-playground__panel", :id "glass-fallback"})
       [:h3 "Frosted fallback"]
       [:p "The same children and shape, without SVG backdrop refraction."]
       [:a {:href "/test/liquid-glass"} "A normal link"]]]
     [:div.glass-playground__instances
      (for [index (range (:instances settings))]
        ^{:key index}
        [glass/<liquid-glass>
         (assoc options :outset "0" :props {:class "glass-playground__sample"})
         [:strong (str "Lens " (inc index))]
         [:p "Matching geometry reuses its image maps."]])]]))
