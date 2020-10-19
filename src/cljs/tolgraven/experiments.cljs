(ns tolgraven.experiments
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [react-transition-group :as rtg]))

; (def css-trans-group (r/adapt-react-class rtg/CSSTransitionGroup))
(def css-trans (r/adapt-react-class rtg/CSSTransition))

; (defn transition-class-crap "ugly hack version of react-transition-group?"
;   [class stage]
;   (let [elem] (-> (js/document.querySelectorAll class)
;       .-classList
;       (.add "elem-enter"))))
(defn transition-group "Might be good exercise in React to try implement in cljs?
                        Though understanding what causing issues / moving away from cljsjs would
                        likely be even better exercise in productivity. Q is what sought"
  [attrs & components])

(defn parallax []
  [:<>
   [:div.parallax-ui ;.fullwide
    [:div "Pure CSS parallax scroll demo #3 by Keith Clark -> tolgraven"]
    ; [css-trans {:transitionName "spawn"
    [:> rtg/CSSTransition
     {:timeout 500
      :name "elem"
      :entertimeout 800
      :appear true
      :appeartimeout 500}
     [:div.elem
      (for [i (range 3)] ^{:key i}
        [:span (str i " - ")])]]
    [:label [:input {:type "checkbox"
                     :on-click #(-> (js/document.querySelector "main")
                                    .-classList
                                    (.toggle "debug-on"))}]
     "Debug"]]

   [:div.parallax
    (map-indexed
     (fn [i group] ^{:key (str "parallax-group-" (inc i))}
       [:div.parallax__group {:id (str "group" (inc i))}
        (for [layer group] ^{:key (str "parallax-group-" (inc i) "-layer-" layer)}
          [:section.noborder.parallax__layer
           {:class (str "parallax__layer--" layer)}
           [:div  (str layer " layer")]])])
     [["base"]
      ["base" "back"]
      ["fore" "base"]
      ["base" "back" "deep"]
      ["fore" "base"]
      ["back" "base"]
      ["base"]])] ])
