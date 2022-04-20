(ns tolgraven.cv.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))

(defn box "One thing, accomplishment, employment, etc"
  [{:keys [from to what position how where logo color] :as all} domain pos size overlap-level]
  (let [expanded? (r/atom false)]
    (fn [{:keys [from to what position how where logo color] :as all} domain pos size overlap-level]
      (if-not @expanded?
        [:div.cv-detail
         {:on-click #(reset! expanded? true)
          :style {:background-color color
                  :left pos
                  :top (str (+ 2.5 (* 24 overlap-level)) "%") ;only supports 4 tall then tho
                  :width size}}
         [:p.cv-from from]
         [:p.cv-to (if to to "-")]
         [:p.cv-what [:strong what]]
         [:p.cv-position position]
         [:p.cv-where where]
         [:img {:src logo}]]
        
        [:div.cv-detail.cv-detail-expanded
         {:style {:background-color color}
          :on-click #(reset! expanded? false)}
         [:p.cv-from from]
         [:p.cv-to (if to to "-")]
         [:h2.cv-what [:strong what]]
         [:p.cv-position position]
         (for [item how]
             [:span.cv-how item])
         [:p.cv-where where]
         [:img {:src logo}]]))))

(defn cv "Main cv component"
  []
  (let [{:keys [title caption cv]} @(rf/subscribe [:content [:cv]])
        {:keys [intro education work]} cv
        first-year (apply min (map :from (concat education work)))
        last-year  (apply max (map #(if (number? %)
                                      %
                                      2025)
                                   (map :to (concat education work))))
        get-pos (fn [start end]
                  (str (* 95
                          (/ (- start first-year)
                             (- last-year first-year)))
                       "%"))
        get-size (fn [start end]
                   (let [end (if (number? end) end 2023)]
                     (str (* 93
                             (/ (- end start)
                                (- last-year first-year)))
                          "%")))
        curr-end (atom 1970)
        overlap-level (r/atom 0)
        gen-items (fn [domain]
                    (doall (for [{:keys [from to level] :as all} (domain cv)
                                 :let [last-end @curr-end
                                       new-end (reset! curr-end to)
                                       olevel (if (> last-end from)
                                               (swap! overlap-level inc)
                                               (reset! overlap-level 0))]]
                             [box all domain
                              (get-pos from to)
                              (get-size from to)
                              (or level @overlap-level)])))
        boxes [:div.cv-boxes
               {:ref #(when % (set! (.-scrollLeft %) (.-scrollWidth %)))}
               [ui/close #(rf/dispatch [:modal-zoom :fullscreen :close])]
               [:div.cv-items.cv-education
                [:h1 [:i.fas.fa-solid.fa-graduation-cap] "education"]
                (gen-items :education)]
               [:div.cv-items.cv-work
                [:h1 [:i.fas.fa-solid.fa-briefcase] "work"]
                (gen-items :work)]
               [:div.cv-items.cv-life
                [:h1 [:i.fas.fa-book] "life"]
                (gen-items :life)]]]
    [:section#cv.cv.nopadding
     {:class (when @(rf/subscribe [:fullscreen/get :cv]) "fullscreen")}
     [:div.cv-intro
      [:img.fullwide {:src "img/tolgrav.png"}]
      [:h1 title]
      [:p (:intro cv)]
      [:div.center-content
       [:p.cv-howto
        "Scroll sideways to look back, or click "
        [:button
         {:on-click #(rf/dispatch [:toggle [:state :fullscreen :cv]])}
         "Fullscreen"]
        " and maximize your browser window."]]]
     boxes]))

