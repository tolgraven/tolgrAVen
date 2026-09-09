(ns tolgraven.components.home 
  (:require
   [clojure.string :as string]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [reanimated.core :as anim]
   [tolgraven.components.media :as media]
   [tolgraven.image :as img]
   [tolgraven.ui :as ui]))

(defn ln->br "Ugh. UGH! Why"
  [text]
  (for [line (string/split-lines text)]
        [:p line]))

(defn intro [{:keys [title text buttons logo-bg bg]}]
  (let [showing-title (r/atom 0)
        updater-2 (fn update-2 []
                    (let [frac (/ (count title)
                                  (swap! showing-title inc))
                          time-next (+ 25 (* @showing-title 18)
                                       (when (= (nth title (dec @showing-title)) " ")
                                         500)
                                       (when (= @showing-title (dec (count title)))
                                         600))]
                    (when-not (= @showing-title (count title))
                      (js/setTimeout update-2 time-next))))]
    (fn [{:keys [title text buttons logo-bg bg]}]
      [:section#intro
       [media/bg-logo logo-bg]
       [img/media-as-bg (merge (first bg) {:id "top-banner"})]

       [:div.h1-wrapper.center-content
        [:h1.h-responsive.h-intro
         [anim/timeout updater-2 2000]
         (if (< @showing-title 1)
           "•"
           (map-indexed
            (fn [i letter]
              (with-meta
               [ui/appear-merge "opacity" [:span letter]]
               {:key (str "intro-letter-" i)}))
            (take @showing-title title)))]]

       (into [:<>] (ln->br text)) ; or just fix :pre css lol
       [:br]
       [:div.buttons
        (for [[text what] buttons] ^{:key (str "intro-button-" text)}
          [:button.background
           {:on-click (when (vector? what)
                        #(rf/dispatch what)) }
           [:div {:class "blur-bg"}]
           (if (string? what)
             [:a {:href what} text]
             [:label text])])]])))


(defn portfolio "GOT NO PPORTFOLIE" [])


(defn service-category-full
  "Fullscreen version of a services category. Should eventually be like a mini-site/portfolio
   listing any projects done in each..."
  [])

(defn services-fullscreenable "yo"
  [categories]
  (let [{:keys [to-focus? full-screened?]} @(rf/subscribe [:state [:services]]) ]
    (when to-focus?
      (rf/dispatch [:focus-element "services-bg"]))
    [:div#services>div.categories
     {:class (when full-screened? "categories-fullscreened")
      :on-click #(rf/dispatch [:state [:services :full-screened?] nil])
      :ref #(when % (rf/dispatch [:focus-element "services-bg"]))}

     (for [[title icon-name lines] categories ;(if-not full-screened? categories (filter #(= (first %) full-screened?) categories)) ;XXX change to keys!!
           :let [on-click (fn [e] (.stopPropagation e)
                            (rf/dispatch [:state [:services :full-screened?]
                                          (when-not full-screened? title)]))
                 id (str "service-" title)]] ^{:key id}
       [ui/seen-anon "zoom-x"
        (into [:ul {:class (cond (= full-screened? title) "service-fullscreen"
                                 full-screened? "service-minimized")
                    :on-click on-click}
               [:li 
                [:i {:class (str "fas " "fa-" icon-name)}]
                [:h3 title]]]
              (for [line lines] ^{:key (str "service-" title "-" line)}
                [:li line]))])]))

(defn services-carousel "yo"
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

(defn services "List services on (fake) offer. Clicking one should bring it up to fill section..."
  [{:keys [categories bg caption]}]
  [:section#section-services
    {:class "link-anchor stick-up section-with-media-bg-wrapper"} ; want to  focus elem to zoomy zoom slow after reaching scroll
    [:a {:name "link-services"}]
     [ui/inset caption 4] ;auto-gen
     [img/media-as-bg
      (merge bg {:id "services-bg"
                 :class "darken-5 parallax-bg"
                 :ref #(when % (rf/dispatch [:focus-element "services-bg"]))})]
     [services-fullscreenable categories]])


(defn story "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [ui/fading-bg-heading heading]]
   [:div.fader>div.fade-to-black.between]

   [:a {:name "about"}]
   [:section#about.anim-gradient-bg.noborder
    [:h1 (:title content)]
    [:br]
    [ui/auto-layout-text-imgs content]
    [:br] [:br]]
   [ui/fading :dir "bottom"]])

