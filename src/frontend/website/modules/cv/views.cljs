(ns website.modules.cv.views
  (:require
    [website.content.schema :as schema]
    [website.content.fields :as fields]
    [website.media.schema :as media-schema]
    [website.modules.cv.schema :as cv-schema]
    [website.component.registry]
    [clojure.string :as string]
    [website.react :as r]
    [website.loader :as loader]

    [website.components.image :as img]
    [website.components.icon-catalog :as icon-catalog]
    [website.components.portal :as portal]
    [website.components.ui.controls :as ui-controls]
    [website.components.ui.layout :as ui-layout]
    [website.util :as util])
  (:require-macros
    [website.build.site :refer [when-enabled]]
    [website.macros :as m]))

(defn icon-name [classes]
  (some (fn [token]
          (when (and (string/starts-with? token "fa-")
                     (not (#{"fa-solid" "fa-regular" "fa-brands"} token)))
            (subs token 3)))
        (string/split (or classes "") #"\s+")))

(m/defc <timeline-icon> [classes :- [:maybe :string]]
  (let [name (icon-name classes)]
    [:<>
     (when-enabled :icon-catalog nil
       (when (and (seq name) (not (icon-catalog/names name)))
         [loader/<trigger> {:module :icon-catalog :lead-pages 0.25}]))
     [:i {:class classes}]]))

(defn bounds [element]
  (let [rect (.getBoundingClientRect element)]
    {:left (.-left rect), :top (.-top rect), :width (.-width rect), :height (.-height rect)}))

(defn origin-transform [{:keys [left top width height]} target-width target-height viewport-width viewport-height]
  (str "translate(-50%, -50%) translate(" (* 100 (/ (- (+ left (/ width 2)) (/ viewport-width 2)) viewport-width)) "vw, "
       (* 100 (/ (- (+ top (/ height 2)) (/ viewport-height 2)) viewport-height)) "vh) scale("
       (/ width target-width) ", " (/ height target-height) ")"))

(declare <box>)

(defn parent-key [entry]
  (js/JSON.stringify (clj->js (if (some? (:id entry)) [(:id entry)]
                              [(:from entry) (:to entry) (:what entry)]))))

(m/defc <group-logo> [entry :- schema/timeline-item asset :- [:maybe media-schema/asset]]
  (when (or (seq (:logo entry)) (fields/editing? entry))
    [img/<picture> (fields/image-attrs entry [:logo]
                    {:src (:logo entry), :asset asset, :alt "Group logo", :class "cv-group-logo", :sizes "4rem"})]))

(defn logo-src [logo asset]
  (or (:src asset)
      (if (re-find #"^(?:https?://|/)" (or logo "")) logo (str "../../" logo))))

(m/defc <box-content> [all :- schema/timeline-item expanded? :- :boolean asset :- [:maybe media-schema/asset]]
  (let [{:keys [from to what position how where logo]} all]
    [:<>
       [:div.cv-bg-logo
        {:aria-hidden true
         :style (when-not (string/blank? logo)
                  {:background-image (str "url(" (pr-str (logo-src logo asset)) ")")})}]
       [:p.cv-from from]
       [:p.cv-to (if (= from to)
                   ""
                   (or to "current"))]
       [:p.cv-what [fields/<text> :strong all [:what]]]
       (if expanded?
         (for [[i item] (map-indexed vector (if (and (fields/editing? all) (empty? how)) [nil] how))]
           ^{:key i} [:p.cv-how [:i.fas.fa-arrow-right]
                     (if (fields/editing? all) [fields/<text> :span all [:how i]] item)])
         [fields/<text> :p.cv-position all [:position]])
       [fields/<text> :p.cv-where all [:where]]
       (when (or (not (string/blank? logo)) (fields/editing? all))
         [img/<picture> (fields/image-attrs all [:logo] {:src logo
                         :asset asset
                         :alt "Company logo"
                         :class "cv-logo-picture"
                         :sizes (if expanded? "8rem" "4rem")})])]))

(m/defc <add-entry> [context :- cv-schema/zoom-context count :- :int]
  (let [editor @(r/subscribe [:get :state :content-editor])]
    [:button.cv-add-entry
     {:disabled (or (fields/busy? editor) (some? (:cv-entry editor)) (>= count 1000)
                    (> (:column context) 100))
      :title (when (>= count 1000) "This timeline has reached its entry limit.")
      :on-click (fn [event]
                  (.stopPropagation event)
                  (r/dispatch [:content-editor/cv-open (:column context) (:category context) count
                                (:parents context) (:parent-keys context) (:column-id context)]))}
     "Add entry"]))

(m/defc <container-label> [entry :- schema/timeline-item asset :- [:maybe media-schema/asset]]
  [:<>
   [:p.cv-from (:from entry)]
   [:p.cv-to (or (:to entry) "current")]
   [:p.cv-what [fields/<text> :strong entry [:what]]]
   [fields/<text> :p.cv-position entry [:position]]
   [<group-logo> entry asset]
   [:div.cv-container-glyphs {:aria-hidden true}
    (for [[index child] (map-indexed vector (take 6 (:children entry)))]
      ^{:key (or (:id child) index)}
      [:span.cv-container-glyph {:style {:background-color (or (:color child) "var(--fg-4)")}}
       (if (seq (:logo child))
         [img/<picture> {:src (:logo child), :alt "", :sizes "1.5rem"}]
         [:span (subs (:what child) 0 (min 1 (count (:what child))))])])
    (when (> (count (:children entry)) 6) [:span.cv-container-more "+" (- (count (:children entry)) 6)])]
   [:p.cv-container-hint "↳ Open timeline"]])

(m/defc <container-timeline> [entry :- schema/timeline-item asset :- [:maybe media-schema/asset]
                             context :- [:maybe cv-schema/zoom-context]]
  (let [children (:children entry)
        current-year (.getUTCFullYear (js/Date.))
        first-year (apply min (:from entry) (map :from children))
        last-year (apply max (inc first-year) (or (:to entry) (inc current-year))
                         (map #(or (:to %) (inc current-year)) children))
        span (- last-year first-year)]
    [:section.cv-mini-timeline
     [fields/<text> :h2 entry [:what]]
     [fields/<text> :p entry [:position]]
     [<group-logo> entry asset]
     [:p.cv-mini-range first-year "–" last-year]
     (when (and context (fields/editing? entry) (<= (count (:parents context)) 6))
       [<add-entry> context (count children)])
     (if (seq children)
       [:div.cv-boxes.cv-mini-scroll
        [:div.cv-mini-track
         [:div.cv-mini-axis
          (for [year (range first-year (inc last-year) (if (> span 15) 5 1))]
            ^{:key year} [:span {:style {:left (str (* 90 (/ (- year first-year) span)) "%")}} year])]
         [:div.cv-items
          {:style {:height (str (* 13 (inc (apply max 0 (map #(or (:level %) 0) children)))) "em")}}
          [:h1 (or (:category context) "Timeline")]
         (for [[index child] (map-indexed vector children)]
           ^{:key (or (:id child) index)}
            [<box> child
             (str (* 90 (/ (- (:from child) first-year) span)) "%")
             (str (max 12 (* 90 (/ (- (or (:to child) (inc current-year)) (:from child)) span))) "%")
             (or (:level child) 0) nil
             (when context (-> context (update :parents conj index) (update :parent-keys conj (parent-key child))))])]]]
       (when (fields/editing? entry) [:p "Add projects, roles, courses or other entries inside this box."]))]))

(m/defc <box-overlay> [entry :- schema/timeline-item asset :- [:maybe media-schema/asset]
                      context :- [:maybe cv-schema/zoom-context] source origin :- cv-schema/bounds close! :- fn?]
  (let [[phase set-phase!] (r/use-state :measuring)
        [transform set-transform!] (r/use-state nil)
        surface (r/use-ref nil)
        closing (r/use-ref false)
        transform-for (fn [origin]
                        (let [style (js/getComputedStyle (.-current surface))]
                          (origin-transform origin (js/parseFloat (.-width style)) (js/parseFloat (.-height style))
                                            (.-innerWidth js/window) (.-innerHeight js/window))))
        begin-close! (fn []
                       (when-not (.-current closing)
                         (set! (.-current closing) true)
                         (set-transform! (transform-for (bounds source)))
                         (set-phase! :closing)))]
    ;; The scrolling/transformed timeline clips fixed descendants. A body portal
    ;; owns this measured FLIP transition; React still owns every style and node.
    (r/use-layout-effect
      (fn []
        (set-transform! (transform-for origin))
        (set-phase! :origin)) [])
    (r/use-layout-effect
      (fn []
        ;; The measured surface is hidden; focus only after its visible commit.
        (when (= :origin phase)
          (.focus (.-current surface) #js {:preventScroll true}))) [phase])
    (r/use-effect
      (fn []
        (when (= :origin phase)
          (let [second-frame (atom nil)
                frame (js/requestAnimationFrame
                        #(reset! second-frame (js/requestAnimationFrame (fn [] (set-phase! :open)))))]
            (fn []
              (js/cancelAnimationFrame frame)
              (when @second-frame (js/cancelAnimationFrame @second-frame)))))) [phase])
    (r/use-effect
      (fn []
        (when (= :closing phase)
          ;; Transitionend can be absent for reduced motion or interrupted paint.
          (let [timer (js/setTimeout close! 400)]
            #(js/clearTimeout timer)))) [phase close!])
    (r/use-effect
      (fn [] #(when (.-isConnected source) (.focus source #js {:preventScroll true}))) [source])
    [portal/<portal> js/document.body
     [:div.cv-detail-layer
      {:on-click (fn [event] (when (= (.-target event) (.-currentTarget event)) (begin-close!)))}
      [:div.cv-detail.cv-detail-expanded.cv-detail-surface
       {:ref surface
        :role "dialog"
        :aria-label (:what entry)
        :tab-index -1
        :class (str (when (#{:open :closing} phase) "cv-detail-surface--moving ")
                    (when (= "container" (:kind entry)) "cv-container-surface"))
        :style {:background-color (:color entry)
                :visibility (when (= :measuring phase) "hidden")
                :transform (if (= :open phase) "translate(-50%, -50%)" transform)}
        :on-key-down (fn [event]
                       (when (and (= "Escape" (.-key event)) (not (.-defaultPrevented event)))
                         (.stopPropagation event)
                         (.preventDefault event)
                         (begin-close!)))
        :on-transition-end (fn [event]
                             (when (and (= :closing phase) (= (.-target event) (.-currentTarget event))
                                        (= "transform" (.-propertyName event))) (close!)))}
       (when (= "container" (:kind entry))
         [:button.cv-zoom-back {:on-click begin-close!} "← Zoom back out"])
       (if (= "container" (:kind entry))
         [<container-timeline> entry asset context]
         [<box-content> entry true asset])]]]))

(m/defc <box> "One thing, accomplishment, employment, etc"
  {:args-schema [:cat schema/timeline-item [:or :string number?] [:or :string number?] :int
                 [:? [:maybe media-schema/asset]] [:? [:maybe cv-schema/zoom-context]]]}
  [all pos size overlap-level & [asset context]]
  (let [expanded (r/atom nil)]
    (fn [all pos size overlap-level & [asset context]]
      (let [open! (fn [event]
                    (when-not (.closest (.-target event) "input, textarea, button, a, label")
                      (let [element (.-currentTarget event)]
                        (reset! expanded {:source element, :origin (bounds element)}))))]
        [:<>
         [:div.cv-detail
          {:role "button"
           :class (when (= "container" (:kind all)) "cv-container")
           :tab-index 0
           :aria-expanded (boolean @expanded)
           :on-click open!
           :on-key-down (fn [event]
                          (when (and (= (.-target event) (.-currentTarget event))
                                     (#{"Enter" " "} (.-key event)))
                            (.preventDefault event)
                            (open! event)))
           :style {:background-color (:color all)
                   :left pos
                   :top (if (> (count (:parents context)) 1)
                          (str (+ 1 (* 12 overlap-level)) "em")
                          (str (+ 2.5 (* 19 overlap-level)) "%"))
                   :width size}}
          (if (= "container" (:kind all)) [<container-label> all asset]
              [<box-content> all false asset])]
         (when @expanded
           [<box-overlay> all asset context (:source @expanded) (:origin @expanded) #(reset! expanded nil)])]))))

(m/defc <capabilities> "The various skills"
  [skills :- [:map-of [:or :string :keyword] [:sequential :string]]]
  (let [topic (fn [id icon]
                [:div.cv-skill
                 {:class (str "cv-" (name id))}
                 [:h2 [<timeline-icon> icon] (str " " (string/capitalize (name id)))]
                 (for [[i line] (map-indexed vector (id skills))] ^{:key i}
                   [:p.cv-skill-line "- " [fields/<text> :span skills [id i]]])])
        software (topic :software "fas fa-code")
        digital (topic :digital "fas fa-calculator")
        general (topic :general "fas fa-globe")
        language (topic :language "fas fa-globe")]
    [:div.cv-skills
     {:style {:min-height "20em"}}
     [:h1 "Skills"]
     (m/<> {:module :carousel, :view :normal, :load-on :immediate}
           :cv/skills {} [software digital general language])]))

(m/defc ^:private <intro-logo>
  {:features [[:seen "slide-in zoom opacity extra-slow"]]}
  []
  [:div [img/<picture>
       {:src "img/logo/tolgraven-logo.png"
        :alt "tolgrAVen"
        :class "fullwide"}]])

(m/defc <intro>
  [cv :- schema/cv-body]
  (let [win-fullscreen? @(r/subscribe [:state [:window :fullscreen?]])]
    [:div.cv-intro
     [<intro-logo>]
     [fields/<text> :p cv [:intro]]
     [:div.center-content
      [:div.cv-howto
       [:div.flex
        [:p "Click a box for further details."]
        [fields/<text> :p.cv-caption cv [:caption]]]

       (if-not win-fullscreen?
         "Scroll sideways to look back, or click "
         "Done snooping? Click ")
       [:button#fullscreen-btn
        {:on-click #(r/dispatch [:window/fullscreen! (not win-fullscreen?)])}
        (if-not win-fullscreen?
          "Fullscreen"
          "Exit fullscreen")]
       (if-not win-fullscreen?
         " or just tap your big ole' <Space>| to maximize your browser window. "
         " to return to your trusty desktop")]]]))


 ;; XXX TODO take out stuff specific to me (including categories, skill categories, logo etc) so is fully adaptable for others...
(m/defc <cv> "Main cv component"
  []
  (let [;_ (r/dispatch [:ls/get-path [:cv-visited] [:state :cv :visited]])
        visited (r/subscribe [:state [:cv :visited]])
        timer-key (keyword (str (gensym "cv-demo-")))
        cancel! #(r/dispatch [:dispatch-in/cancel timer-key])
        timeline-ref (fn [element]
                       (when element (set! (.-scrollLeft element) (.-scrollWidth element))))
        ref-fn (fn [el]
                 (if el
                   (do
                     (r/dispatch [:ls/store-val [:cv-visited] true])
                     (doseq [[ms event] (cond-> [[2000 [:state [:fullscreen :cv] true]]]
                                         (not @visited)
                                         (into [[3000 [:scroll/by 50]]
                                                [4500 [:scroll/by -37]]
                                                [5500 [:focus-element "fullscreen-btn"]]]))]
                       (r/dispatch [:dispatch-in/set {:k timer-key
                                                     :ms ms
                                                     :dispatch-n [event]}]))
                     ;; React owns this native ref's lifetime, including ref
                     ;; replacement and StrictMode's setup/cleanup replay.
                     cancel!)
                   (cancel!)))]
   (fn []
    (let [{:keys [title caption cv image-assets]} @(r/subscribe [:content/view [:cv]])
        {:keys [timeline skills]} cv
        total-width "200em"
        base-height 9
        first-year (apply min (map :from (mapcat :things timeline)))
        last-year  (apply max (map #(if (number? %)
                                      %
                                      (+ 3 (.getUTCFullYear (js/Date.))))
                                   (map :to (mapcat :things timeline))))
        get-pos (fn [start end]
                  (str (* 95
                          (/ (- start first-year)
                             (- last-year first-year)))
                       "%"))
        get-size (fn [start end]
                   (let [end (if (number? end) end (inc (.getUTCFullYear (js/Date.))))]
                     (str (* 92
                             (/ (- end start)
                                (- last-year first-year)))
                          "%")))
        decades (range (first (filter #(= 0 (mod % 10))
                                      (take 10 (range first-year (+ 10 first-year)))))
                       (inc last-year) 10)
        curr-end (atom 1970) ; literally the dawn of time lols
        overlap-level (r/atom 0)
        gen-items (fn [things column category column-id]
                    (doall (for [[entry-index {:keys [from to level what] :as thing}] (util/for-i things)
                                 :let [last-end @curr-end
                                       new-end (reset! curr-end to)
                                       olevel (if (> last-end from)
                                               (swap! overlap-level inc)
                                               (reset! overlap-level 0))
                                       id (or (:id thing) (str entry-index "-" from "-" to))]]
                            ^{:key id}
                            [<box> thing
                             (get-pos from to)
                             (get-size from to)
                             (or level @overlap-level)
                             (get image-assets (:logo thing))
                             {:column column
                              :column-id column-id
                              :category (name category)
                              :parents [entry-index]
                              :parent-keys [(parent-key thing)]}])))
        fullscreen? (when @(r/subscribe [:fullscreen/get :cv])
                      "fullscreen")]
    [:section#cv.cv.nopadding.noborder
     {:class fullscreen?
      :ref ref-fn}
     [<intro> cv]
     [:div.cv-boxes
      {:ref timeline-ref}
      [ui-controls/<close> #(r/dispatch [:state [:fullscreen :cv] false])]
      [:div.cv-decade-lines
       {:style {:width total-width}}
       (for [decade decades]
         ^{:key decade}
         [:div.cv-decade-line
          {:style {:left (get-pos decade decade)
                   :bottom 0}}
          [:div.cv-decade-label
           (str decade "s")]])]

      (doall (for [[i {:keys [category icon things height-ratio] :as column}] (util/for-i (:timeline cv))]
               ^{:key (str "cv-category-" (name category))}
               [:div.cv-items
                {:class (str "cv-" (name category))
                 :style {:width total-width
                         :height (str (* height-ratio base-height) "em")
                         :background (str "linear-gradient(0deg, var(--bg-"
                                          (get (:background-color-indexes cv) (inc i)) ")"
                                          ", var(--bg-" (get (:background-color-indexes cv) i) "))")}}
                [:h1 [<timeline-icon> icon] (name category)
                 (when (fields/editing? cv)
                   [<add-entry> {:column i
                                 :column-id (:id column)
                                 :category (name category)
                                 :parents []
                                 :parent-keys []} (count things)])]
                (gen-items things i category (:id column))]))]
      [ui-layout/<fading> :dir "bottom"]

     [<capabilities> skills]]))))

(m/defpage <page>
  {:depends [{:source :strapi :keys [:cv]}]
   :loading-prefab :lines :loading-tag :section.cv}
  []
  [ui-layout/<with-heading> [:cv :heading] [<cv>]])
