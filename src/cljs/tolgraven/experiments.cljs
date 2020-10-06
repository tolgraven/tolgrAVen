(ns tolgraven.experiments)

(defn parallax []
  [:section
   [:div "Pure CSS parallax scroll demo #3 by Keith Clark -> tolgraven"]
   [:label [:input {:type "checkbox"
                    :on-click #(-> (js/document.querySelector "main")
                                   .-classList
                                   (.toggle "debug-on"))}]
    "Debug"]

   [:div.parallax
    (map-indexed
     (fn [i group]
       [:div.parallax__group {:id (str "group" (inc i))}
        (for [layer group]
          [:section.parallax__layer
           {:class (str "parallax__layer--" layer)}
           [:div  (str layer " layer")]])])
     [["base"]
      ["base" "back"]
      ["fore" "base"]
      ["base" "back" "deep"]
      ["fore" "base"]
      ["back" "base"]
      ["base"]])] ])
