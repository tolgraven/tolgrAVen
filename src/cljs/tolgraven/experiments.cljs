(ns tolgraven.experiments)

(defn parallax []
  [:section>div.content
   ; [:img {:src "/img/warning_clojure.png"}]

 [:div {:class "debug"}
    [:label [:input {:type "checkbox"
                     :on-click #(-> (js/document.querySelector "main")
                                    .-classList
                                    (.toggle "debug-on"))}]
     "Debug"]]
  [:div {:class "demo__info"}
    "Pure CSS parallax scroll demo #3 by Keith Clark."]

  [:div {:class "parallax"}
    [:div.parallax__group {:id "group1"}
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]]
    [:div.parallax__group {:id "group2"}
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]
      [:section.parallax__layer {:class "parallax__layer--back"}
        [:div.parallax-title   "Background Layer"]]]
    [:div.parallax__group {:id "group3"}
      [:section.parallax__layer {:class "parallax__layer--fore"}
        [:div.parallax-title   "Foreground Layer"]]
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]]
    [:div.parallax__group {:id "group4"}
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]
      [:section.parallax__layer {:class "parallax__layer--back"}
        [:div.parallax-title   "Background Layer"]]
      [:section.parallax__layer {:class "parallax__layer--deep"}
        [:div.parallax-title   "Deep Background Layer"]]]
    [:div.parallax__group {:id "group5"}
      [:section.parallax__layer {:class "parallax__layer--fore"}
        [:div.parallax-title   "Foreground Layer"]]
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]]
    [:div.parallax__group {:id "group6"}
      [:section.parallax__layer {:class "parallax__layer--back"}
        [:div.parallax-title   "Background Layer"]]
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]]
    [:div.parallax__group {:id "group7"}
      [:section.parallax__layer {:class "parallax__layer--base"}
        [:div.parallax-title   "Base Layer"]]]]
   ])
