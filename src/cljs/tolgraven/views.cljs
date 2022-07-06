(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [cljsjs.react-player]
   [reanimated.core :as anim]
   [tolgraven.ui :as ui]
   [tolgraven.strava.views :as strava]
   [tolgraven.chat.views :as chat]
   [tolgraven.github.views :as github]
   [tolgraven.instagram.views :as instagram]
   [tolgraven.cv.views :as cv]
   [tolgraven.db :as db]
   [tolgraven.views-common :as view]
   [tolgraven.util :as util :refer [at]]))

(defn ln->br "Ugh. UGH! Why"
  [text]
  (for [line (string/split-lines text)]
        [:p line]))

(defn bg-logo "Try to remember why had to put img in css/style..."
  [path]
  (fn [path]
    [:div#logo-top.logo-bg.stick-up ;.parallax-sm
     {:class "logo-tolgraven"
      :style {:background-image (str "url('" path "')")}} ])) ; cant remember why I did the weird path-in-css bs but anyways...

(defn ui-carousel-bg "Intro (bg img only) jumbotron slider
                      Should be a generic system also working for page transitions etc"
  [img-attrs]
  [:div#top-banner.carousel ;{:class ""}
   ; (map-indexed )
    (for [img img-attrs] ^{:key (str "carousel-bg-" (:src img))}
         [:img.media.media-as-bg img])])
         ; [:div.carousel-item
         ;  [:img.media.media-as-bg img]])])

(defn ui-carousel-bg-2 "Intro (bg img only) jumbotron slider"
  [img-attrs]
  [:div#top-banner.carousel.media-as-bg ;{:class ""}
   [:ol.carousel__viewport
    (map-indexed
     (fn [i img]
       (with-meta
         [:li.carousel__slide
           {:id (str "carousel__slide" (inc i))
            :tabindex 0}
           [:div.carousel__snapper
           [:a.carousel__prev {:href "#slide-fuckit-events are better4"}]
           [:a.carousel__next {:href "#carousel__slide2"} "Go to next"]] ;for nav
           [:img.media.media-as-bg img]]
         {:key (str "carousel-bg-" (inc i))}))
     img-attrs)]
   [:aside.carousel-nav>ol
    (for [i (->> img-attrs count inc (range 1))]
      [:li.carousel-nav-item
       [:a {:href (str "carousel__slide" i)}]])] ])


(defn ui-intro [{:keys [title text buttons logo-bg bg]}]
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
                      (js/setTimeout #(update-2) time-next))))]
    (fn [{:keys [title text buttons logo-bg bg]}]
     [:section#intro
     [bg-logo logo-bg]
     [:img#top-banner.media.media-as-bg (first bg)]

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
        [:button
          {:on-click (when (vector? what)
                        #(rf/dispatch what)) }
          [:div {:class "blur-bg"}]
          (if (string? what)
            [:a {:href what} text]
            [:label text])])]])))


(defn ui-interlude "Banner across with some image or video or w/e
                    TODO should stick top-border on outer (which should be section anyways ugh)"
  [{:keys [title caption bg nr]}]
  (let [vid-ref (atom nil) ; docs says reg atom better but only updates w ratom, bc 2nd fn or? also .play no works
        controls (atom nil)
        in-view (r/atom 0.0)
        control-time 1000
        do-control (fn [action]
                     (when-let [video @vid-ref]
                       (when @controls
                         (@controls action)
                         (when (= action :play)
                           (js/setTimeout
                            #(@controls :pause)
                            (- (* 1000 (- (.-duration video) (.-currentTime video)))
                               control-time))))))
        on-change (fn [frac]
                    (reset! in-view frac)
                    (when (<= frac 0.2)
                      (do-control :pause)))
        observer (util/observer on-change)]
    (fn [{:keys [title caption bg nr]}]
      [:section.nopadding
       {:id (str "interlude-" nr)
        :class "section-with-media-bg-wrapper parallax-wrapper"
        :on-mouse-enter #(do-control :play)
        :on-mouse-leave #(do-control :pause)
        :on-touch-start #(do-control :play)
        :on-touch-end   #(do-control :pause)}
       (util/add-attrs bg {:id (str "interlude-bg-" nr)
                           :ref (fn [el]
                                  (when (and el (not @vid-ref)) ;presumably everything torn down on nil anyways so?
                                    (reset! vid-ref el)
                                    (set! (.-muted el) true)))
                           :onLoadedMetadata #(set! (.-muted %) true)
                           :onCanPlay (fn []
                                        (when-not @controls
                                          (reset! controls (util/play-pauser
                                                            @vid-ref
                                                            :time-per-step (/ control-time 3)))
                                          (set! (.-currentTime @vid-ref) 0)
                                          (.pause @vid-ref)
                                          (js/setTimeout #(do-control :play) 8000))) ; should be read from css i guess to correspond with other anim
                           :loop true
                           :muted true}) ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
       [:div
        {:class "covering-faded widescreen-safe center-content parallax-group"
         :ref #(observer %) ;oh yeah check first el for :video cant work it's rendered at that point lol
         :style {:transition "opacity 4.5s"
                 :opacity (when-not (zero? @in-view)
                            "0.4")}}
        [:h1.h-responsive  title]]
       [ui/inset caption nr]])))


(defn ui-portfolio "GOT NO PPORTFOLIE" [])


(defn service-category-full
  "Fullscreen version of a services category. Should eventually be like a mini-site/portfolio
   listing any projects done in each..."
  [])

(defn services-fullscreenable "yo"
  [categories]
  (let [full-screened @(rf/subscribe [:state [:services]]) ]
    [:div#services>div.categories
     {:class (when full-screened "categories-fullscreened")
      :on-click #(rf/dispatch [:state [:services] nil])}

     (for [[title icon-name lines] categories ;(if-not full-screened categories (filter #(= (first %) full-screened) categories)) ;XXX change to keys!!
           :let [on-click (fn [e] (.stopPropagation e)
                            (rf/dispatch [:state [:services]
                                          (when-not full-screened title)]))
                 id (str "service-" title)]] ^{:key id}
       [ui/seen-anon "zoom-x"
        (into [:ul {:class (cond (= full-screened title) "service-fullscreen"
                                 full-screened "service-minimized")
                    :on-click on-click}
               [:li 
                [:i {:class (str "fas " "fa-" icon-name)}]
                [:h3 title]]]
              (for [line lines] ^{:key (str "service-" title "-" line)}
                [:li line]))])]))

(defn services-carousel "yo"
  [categories]
  (let [full-screened @(rf/subscribe [:state [:services]]) ]
    [:div#services>div.categories
     (for [[title icon-name lines] categories ;(if-not full-screened categories (filter #(= (first %) full-screened) categories)) ;XXX change to keys!!
           :let [id (str "service-" title)]] ^{:key id}
       (into [:ul 
               [:li 
                [:i {:class (str "fas " "fa-" icon-name)}]
                [:h3 title]]]
              (for [line lines] ^{:key (str "service-" title "-" line)}
                [:li line])))]))

(defn ui-services "List services on (fake) offer. Clicking one should bring it up to fill section..."
  [{:keys [categories bg caption]}]
  [:section#section-services
    {:class "link-anchor stick-up section-with-media-bg-wrapper"}
    [:a {:name "link-services"}]
     [ui/inset caption 4] ;auto-gen
     [:img (merge bg {:class "media-as-bg darken-5 parallax-bg"})]
     [services-fullscreenable categories]])

(defn ui-moneyshot "needs better name lol. what is hero img halfway down page?"
  [{:keys [title caption bg]}]
  (let [frac (r/atom 0.0)
        observer (util/observer #(reset! frac %))]
    (fn [{:keys [title caption bg]}]
        [:div#moneyshot {:class "section-with-media-bg-wrapper parallax-wrapper covering stick-up"
          :ref #(observer %)}
         [:img.media-as-bg ; TODO try it as background-image instead of separate div, see if calms down...
          (merge bg {:class "darken-8 parallax-sm" ;origin-toptop
                     :style (merge (when (pos? @frac)
                                     {:opacity 1.0})
                                   #_{:transform (str "scale(1.125) translateZ(" (* @frac -0.5) "px)")})})] ; need to be above 1.1 or goes jumpy weirdshit clip UGH why
         [:section#intro-end.center-content
          ; {:class "darken-8 parallax-sm origin-toptop"
          ;  :style {:background-image (str "url(" (:src bg) ")")
          ;          :background-size "cover"}}
          [:h1.h0-responsive.parallax-bg
           {:style {:z-index 10
                    :transition "transform 8.5s ease"
                    :transform (str "translateZ(" (* 14 @frac) "px)")}}
           title]] ;ideally want this also growing (and moving quicker upwards)]
         [ui/inset caption 3]
         [ui/inset (str "Fraction visible:" @frac) 2]
         [ui/fading]])))


(defn ui-story "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [ui/fading-bg-heading heading]]
   [:div.fader>div.fade-to-black.between]

   [:a {:name "about"}]
   [:section#about.anim-gradient-bg.noborder
    [:h1 (:title content)]
    [:br]
    [ui/auto-layout-text-imgs content]
    [:br] [:br]]])


(defn ui-gallery "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (when @(rf/subscribe [:state [:gallery :loaded]])
      (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [:img.media img]))]]) ; TODO add captions and other features etc...

(defn ui-gallery-2 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel-normal"]
   [ui/carousel-normal :gallery-2-normal {:style {:height "40vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-2-normal-" (:src img))}
            [:img.media img]))]])

(defn ui-gallery-3 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel"]
   [ui/carousel :gallery-2 {:style {:height "30vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [:img.media img]))]])


(defn remote-player
  [url]
  [:> js/ReactPlayer
   {:url url
    :width "100%"
    :height "100%"}])

(defn soundcloud-loading "A dummy to show before initing react-player"
  [artist song]
  [:div.soundcloud-player-loading
   [:img.center-content
    {:src "img/soundcloud-logo.png"}]
   [:h3 song]
   [:h4 artist]])

(defn soundcloud-player
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url (str base-url artist "/" song)]
    [ui/seen-anon "slide-in"
     (if @(rf/subscribe [:booted? :soundcloud])
       [remote-player url]
       [soundcloud-loading artist song])]))

(defn ui-soundcloud "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [{:keys [url artist tunes]} @(rf/subscribe [:content [:soundcloud]]) ]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players
      (for [tune tunes] ^{:key (str "soundcloud-player-" tune)}
        [soundcloud-player artist tune])]]))


(defn ui []
  (let [interlude @(rf/subscribe [:content [:interlude]])
        interlude-counter (atom 0)
        get-lewd #(merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})]
    [:<>
     [ui-intro @(rf/subscribe [:content [:intro]])]

     [ui-interlude (get-lewd)]
     [ui-services @(rf/subscribe [:content [:services]])]

     [ui/lazy-load [:on-booted :firebase [:strava/init]]]
     [ui-interlude (get-lewd)]
     [ui/lazy-load [:on-booted :site [:booted :soundcloud]]]
     [ui-moneyshot @(rf/subscribe [:content [:moneyshot]])]
     [ui/lazy-load [:on-booted :firebase [:instagram/init]]]
     [ui-story @(rf/subscribe [:content [:story]])]
     [ui/fading :dir "bottom"]
     [ui-interlude (get-lewd)]
     
     [strava/strava]
     [ui/lazy-load [:on-booted :site [:state [:gallery :loaded] true]]]
     [ui-soundcloud]
     [instagram/instagram]
     [ui/lazy-load [:on-booted :site [:github/init "tolgraven" "tolgraven"]]]
     [ui-gallery @(rf/subscribe [:content [:gallery]])]
     [github/commits]
     [chat/chat]]))

