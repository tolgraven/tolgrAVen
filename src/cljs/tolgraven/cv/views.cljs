(ns tolgraven.cv.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]
   [cljs-time.core :as ct]))

;; IDEA: make it possible for a box to contain its own timeline of boxes, so tapping it, "zooms in"
;; and can put like projects, roles/promotions, courses etc for that specific thing
;; either as a full new timeline of same type, or inside the existing big box (that'd be made bigger)
(defn box "One thing, accomplishment, employment, etc"
  [{:keys [from to what position how where logo color] :as all} pos size overlap-level]
  (let [expanded? (r/atom false)
        closing? (r/atom false)]
    (fn [{:keys [from to what position how where logo color] :as all} pos size overlap-level]
      [:div.cv-detail
       {:on-click (fn []
                    (swap! closing? not)
                    (js/setTimeout #(do (swap! expanded? not)
                                        (swap! closing? not))
                                   250))
        :style (merge {:background-color color}
                      {:left pos
                       :top 0}
                      (when-not @expanded?
                        {:top (str (+ 2.5 (* 19 overlap-level)) "%")
                         :width size}))
        :class (str (when @expanded? "cv-detail-expanded ")
                    (when @closing? "cv-detail-closing"))}
       [:div.cv-bg-logo
        {:style {:background-image (str "url(../../" logo ")")}}]
       [:p.cv-from from]
       [:p.cv-to (if (= from to)
                   ""
                   (or to "current"))]
       [:p.cv-what [:strong what]]
       (if @expanded?
         (for [item how]
           [:p.cv-how [:i.fas.fa-arrow-right] item])
         [:p.cv-position position])
       [:p.cv-where where]
       (when logo
         [:img {:src logo}])])))

(defn capabilities "The various skills"
  [skills]
  (let [topic (fn [id icon]
                [:div.cv-skill
                 {:class (str "cv-" (name id))}
                 [:h2 [:i {:class icon}] (str " " (string/capitalize (name id)))]
                 (for [line (id skills)] ^{:key line}
                   [:p.cv-skill-line "- " [:span line]])])
        software (topic :software "fas fa-code")
        digital (topic :digital "fas fa-calculator")
        general (topic :general "fas fa-globe")
        language (topic :language "fas fa-globe")]
    [:div.cv-skills
     {:style {:min-height "20em"}}
     [:h1 "Skills"]
     [ui/carousel-normal :cv/skills {} [software digital general language]]]))

(defn intro
  [cv]
  (let [win-fullscreen? @(rf/subscribe [:state [:window :fullscreen?]])]
    [:div.cv-intro
     [ui/seen-anon "slide-in zoom opacity extra-slow"
      [:img.fullwide
       {:src "img/logo/tolgraven-logo.png"}]]
     [:p (:intro cv)]
     [:div.center-content
      [:div.cv-howto
       [:div.flex
        [:p "Click a box for further details."]
        [:p.cv-caption (:caption cv)]]

       (if-not win-fullscreen?
         "Scroll sideways to look back, or click "
         "Done snooping? Click ")
       [:button#fullscreen-btn
        {:on-click #(rf/dispatch [:window/fullscreen! (not win-fullscreen?)])}
        (if-not win-fullscreen?
          "Fullscreen"
          "Exit fullscreen")]
       (if-not win-fullscreen?
         " or just tap your big ole' <Space>| to maximize your browser window. "
         " to return to your trusty desktop")]]]))


 ;; XXX TODO take out stuff specific to me (including categories, skill categories, logo etc) so is fully adaptable for others...
(defn cv "Main cv component"
  []
  (let [;_ (rf/dispatch [:ls/get-path [:cv-visited] [:state :cv :visited]])
        visited (rf/subscribe [:state [:cv :visited]])
        ref-fn (fn [el]
                 (when el
                   (rf/dispatch [:ls/store-val [:cv-visited] true])
                   (rf/dispatch [:dispatch-in/ms 2000 [:state [:fullscreen :cv] true]])
                   (when (not @visited)
                     (rf/dispatch [:dispatch-in/ms 3000 [:scroll/by 50]])
                     (rf/dispatch [:dispatch-in/ms 4500 [:scroll/by -37]])
                     (rf/dispatch [:dispatch-in/ms 5500 [:focus-element "fullscreen-btn"]]))))]
   (fn []
    (let [{:keys [title caption cv]} @(rf/subscribe [:content [:cv]])
        {:keys [timeline skills]} cv
        total-width "200em"
        base-height 9
        first-year (apply min (map :from (mapcat :things timeline)))
        last-year  (apply max (map #(if (number? %)
                                      %
                                      (+ 3 (ct/year (ct/now))))
                                   (map :to (mapcat :things timeline))))
        get-pos (fn [start end]
                  (str (* 95
                          (/ (- start first-year)
                             (- last-year first-year)))
                       "%"))
        get-size (fn [start end]
                   (let [end (if (number? end) end (inc (ct/year (ct/now))))]
                     (str (* 92
                             (/ (- end start)
                                (- last-year first-year)))
                          "%")))
        decades (range (first (filter #(= 0 (mod % 10))
                                      (take 10 (range first-year (+ 10 first-year)))))
                       (inc last-year) 10)
        curr-end (atom 1970) ; literally the dawn of time lols
        overlap-level (r/atom 0)
        gen-items (fn [things]
                    (doall (for [{:keys [from to level what] :as thing} things
                                 :let [last-end @curr-end
                                       new-end (reset! curr-end to)
                                       olevel (if (> last-end from)
                                               (swap! overlap-level inc)
                                               (reset! overlap-level 0))
                                       id (str from "-" to "-" what)]]
                            ^{:key id}
                            [box thing 
                             (get-pos from to)
                             (get-size from to)
                             (or level @overlap-level)])))
        fullscreen? (when @(rf/subscribe [:fullscreen/get :cv])
                      "fullscreen")]
    [:section#cv.cv.nopadding.noborder
     {:class fullscreen?
      :ref ref-fn}
     [intro cv]
     [:div.cv-boxes
      {:ref #(when %
               (set! (.-scrollLeft %) (.-scrollWidth %)))}
      [ui/close #(rf/dispatch [:state [:fullscreen :cv] false])]
      [:div.cv-decade-lines
       {:style {:width total-width}}
       (for [decade decades]
         [:div.cv-decade-line
          {:style {:left (get-pos decade decade)
                   :bottom 0}}
          [:div.cv-decade-label
           (str decade "s")]])]

      (doall (for [[i {:keys [category icon things height-ratio]}] (util/for-i (:timeline cv))]
               ^{:key (str "cv-category-" (name category))}
               [:div.cv-items
                {:class (str "cv-" (name category))
                 :style {:width total-width
                         :height (str (* height-ratio base-height) "em")
                         :background (str "linear-gradient(0deg, var(--bg-"
                                          (get (:background-color-indexes cv) (inc i)) ")" 
                                          ", var(--bg-" (get (:background-color-indexes cv) i) "))")}}
                [:h1 [:i {:class icon}] (name category)]
                (gen-items things)]))]
      [ui/fading :dir "bottom"]
     
     [capabilities skills]]))))

