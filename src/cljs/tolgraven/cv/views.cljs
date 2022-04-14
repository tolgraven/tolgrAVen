(ns tolgraven.cv.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))

(defn box "One thing, accomplishment, employment, etc"
  [[from to what how where] pos size overlap-level]
  (let [expanded? (r/atom false)]
    (fn [[from to what how where] pos size overlap-level]
      (if-not @expanded?
        [:div.cv-detail
         {:on-click #(reset! expanded? true)
          :style {:left pos
                  :top (str (* 25 overlap-level) "%") ;only supports 4 tall then tho
                  :width size}}
         [:p from]
         [:p.cv-to (if to to "-")]
         [:p.cv-what what]
         [:p.cv-where where]]
        
        [:div.cv-detail.cv-detail-expanded
         {:on-click #(reset! expanded? false)}
         [:p from]
         [:p.cv-to (if to to "-")]
         [:p.cv-what what]
         (for [item how]
             [:span.cv-how item])
         [:p.cv-where where]]))))

(defn cv "Main cv component"
  []
  (let [{:keys [title caption cv]} @(rf/subscribe [:content [:cv]])
        {:keys [intro education work]} cv
        first-year (apply min (map first (concat education work)))
        last-year  (apply max (map #(if (number? %)
                                      %
                                      2025)
                                   (map second (concat education work))))
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
        curr-end (atom "2000")
        overlap-level (r/atom 0)
        gen-items (fn [domain]
                    (doall (for [[from to what how where :as all] (domain cv)
                                 :let [last-end @curr-end
                                       new-end (reset! curr-end to)
                                       level (if (>= last-end new-end)
                                               (swap! overlap-level inc)
                                               (reset! overlap-level 0))]]
                             [box all
                              (get-pos from to)
                              (get-size from to)
                              @overlap-level])))]
    [:div.cv
     [:h1 title]
     [:p (:intro cv)]
     [:div.cv-boxes
      [:div.cv-items.cv-education
       (gen-items :education)]
      [:div.cv-items.cv-work
       (gen-items :work)]]]))

