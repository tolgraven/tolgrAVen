(ns tolgraven.components.home
  (:require
    [tolgraven.component.registry]
    [clojure.string :as string]
    [tolgraven.react :as rf]
    [tolgraven.macros :refer-macros [defc]]
    [reagent.core :as r]
    [tolgraven.components.timer :as anim]
    [tolgraven.component.restore :as restore]
    [tolgraven.components.media :as media]
    [tolgraven.image :as img]
    [tolgraven.ui :as ui]))

(defn ln->br "Ugh. UGH! Why"
  [text]
  (for [line (string/split-lines text)]
        [:p line]))

(defc <intro-letter> {:features [[:appear "opacity"]]}
  [{:keys [letter] :as spec}]
  [:span letter])

(defn title-delay-ms [title shown]
  (if (zero? shown) 2000
    (+ 25 (* shown 18)
       (if (= (nth title (dec shown)) " ") 500 0)
       (if (= shown (dec (count title))) 600 0))))

(defc <intro> [{:keys [title text buttons logo-bg bg] :as spec}]
  :let [restored? (restore/skip-enter?)
        *showing-title (r/atom (if restored? (count title) 0))]
  [:section#intro {:data-restored (when restored? true)}
   [media/<bg-logo> logo-bg]
   [img/<media-as-bg> (merge (first bg) {:id "top-banner"})]
   [:div.h1-wrapper.center-content
    [:h1.h-responsive.h-intro
     ;; A keyed timer owns each step, so leaving the page cancels the animation.
     (when (and (not restored?) (< @*showing-title (count title)))
       ^{:key @*showing-title}
       [anim/<timeout> #(swap! *showing-title inc) (title-delay-ms title @*showing-title)])
     (if (< @*showing-title 1)
       "•"
       (map-indexed
        (fn [i letter]
          ^{:key (str "intro-letter-" i)} [<intro-letter> {:letter letter}])
        (take @*showing-title title)))]]
   (into [:<>] (ln->br text))
   [:br]
   [:div.buttons
    (for [[text what] buttons] ^{:key (str "intro-button-" text)}
      [:button.background
       {:on-click (when (vector? what) #(rf/dispatch what))}
       [:div {:class "blur-bg"}]
       (if (string? what) [:a {:href what} text] [:label text])])]])


(defc <portfolio> "GOT NO PPORTFOLIE" [])


(defc <service-category-full>
  "Fullscreen version of a services category. Should eventually be like a mini-site/portfolio
   listing any projects done in each..."
  [])

(defc <service-category> {:features [[:seen "zoom-x"]]}
  [{:keys [title icon-name lines full-screened? on-click] :as spec}]
  ;; Preserve the existing category box: moving the transform onto its list
  ;; would change sizing and fullscreen transitions. Motion adds no extra DOM.
  [:div
   (into [:ul {:class (cond (= full-screened? title) "service-fullscreen"
                            full-screened? "service-minimized")
               :on-click on-click}
          [:li [:i {:class (str "fas fa-" icon-name)}] [:h3 title]]]
         (for [line lines] ^{:key (str "service-" title "-" line)} [:li line]))])

(defc <service-categories> [{:keys [categories] :as spec}]
  (let [{:keys [to-focus? full-screened?]} @(rf/subscribe [:state [:services]])]
    (rf/use-effect
     (fn []
       (when to-focus? (rf/dispatch [:focus-element "services-bg"]))
       js/undefined)
     #js [to-focus?])
    [:div#services>div.categories
     {:class (when full-screened? "categories-fullscreened")
      :on-click #(rf/dispatch [:state [:services :full-screened?] nil])
      :ref #(when % (rf/dispatch [:focus-element "services-bg"]))}
     (for [[title icon-name lines] categories] ^{:key (str "service-" title)}
       [<service-category>
        {:title title :icon-name icon-name :lines lines :full-screened? full-screened?
         :on-click (fn [event]
                     (.stopPropagation event)
                     (rf/dispatch [:state [:services :full-screened?]
                                   (when-not full-screened? title)]))}])]))

(defc <services-carousel> "yo"
  [categories]
  (let [full-screened? @(rf/subscribe [:state [:services]]) ]
    [:div#services>div.categories
     (for [[title icon-name lines] categories ;(if-not full-screened categories (filter #(= (first %) full-screened) categories)) ;XXX change to keys!!
           :let [id (str "service-" title)]] ^{:key id}
       (into [:ul
               [:li
                [:i {:class (str "fas " "fa-" icon-name)}]
                [:h3 title]]]
              (for [line lines] ^{:key (str "service-" title "-" line)}
                [:li line])))]))

(defc <services> "List services on (fake) offer. Clicking one should bring it up to fill section..."
  [{:keys [categories bg caption] :as spec}]
  [:section#section-services
    {:class "link-anchor stick-up section-with-media-bg-wrapper"} ; want to  focus elem to zoomy zoom slow after reaching scroll
    [:a {:name "link-services"}]
     [ui/<inset> caption 4] ;auto-gen
     [img/<media-as-bg>
      (merge bg {:id "services-bg"
                 :class "darken-5 parallax-bg"
                 :ref #(when % (rf/dispatch [:focus-element "services-bg"]))})]
     [<service-categories> {:categories categories}]])


(defc <story> "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [ui/<fading-bg-heading> heading]]
   [:div.fader>div.fade-to-black.between]

   [:a {:name "about"}]
   [:section#about.anim-gradient-bg.noborder
    [:h1 (:title content)]
    [:br]
    [ui/<auto-layout-text-imgs> content]
    [:br] [:br]]
   [ui/<fading> :dir "bottom"]])
