(ns tolgraven.views
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    ; ["react-player" :as rp :refer (SoundCloud)]
    [reanimated.core :as anim]
    [tolgraven.loader :as l]
    [tolgraven.ui :as ui]
    [tolgraven.image :as img]
    [tolgraven.video :as vid]
    [tolgraven.cv.views :as cv]
    [tolgraven.db :as db]
    [tolgraven.macros :as m :include-macros true]
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

(defn- media-as-bg-smart "Detects whether media is image or video and uses appropriate component"
  [media-data]
  (let [src (:src media-data)]
    (if (and src (re-find #"\.(mp4|mov|webm)$" src))
      [vid/media-as-bg media-data]
      [img/media-as-bg media-data])))

(defn ui-carousel-bg "Intro (bg img only) jumbotron slider
                      Should be a generic system also working for page transitions etc"
  [img-attrs]
  [:div#top-banner.carousel ;{:class ""}
   ; (map-indexed )
    (for [img-data img-attrs] ^{:key (str "carousel-bg-" (:src img-data))}
         [media-as-bg-smart img-data])])
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
           [media-as-bg-smart img]]
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
                      (js/setTimeout update-2 time-next))))]
    (fn [{:keys [title text buttons logo-bg bg]}]
     [:section#intro
     [bg-logo logo-bg]
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


(defn ui-interlude "Banner across with some image or video or w/e
                    Partial content errors probably because stops buffering since we pause it.
                    Let's try a tricky trick"
  [interludes nr]
  (let [vid-ref (atom nil) ; docs says reg atom better but only updates w ratom, bc 2nd fn or? also .play no works
        controls (atom nil)
        on-hold (r/atom nil)
        in-view (r/atom 0.0)
        control-time 1000
        do-control (fn [action]
                     (when-let [video @vid-ref]
                       (when @controls
                         (@controls action)
                         (when (= action :play) ; set it to pause just before looping...
                           (when @on-hold
                             (reset! on-hold false)
                             (set! (.-currentTime @vid-ref) 0))
                           (js/setTimeout
                            #(@controls :pause)
                            (- (* 1000 (- (.-duration video) (.-currentTime video)))
                               control-time))))))
        on-change (fn [frac]
                    (reset! in-view frac)
                    (when (<= frac 0.35)
                      (do-control :pause)))
        observer (util/observer on-change)]
    (fn [interludes nr]
     (let [{:keys [title caption bg]} (get interludes nr)]
      [:section.nopadding
       {:id (str "interlude-" nr)
        :class "section-with-media-bg-wrapper parallax-wrapper"
        :on-mouse-enter #(do-control :play)
        :on-mouse-leave #(do-control :pause)
        :on-touch-start #(do-control :play)
        :on-touch-end   #(do-control :pause)}
       [vid/video-with-picture-poster
        (merge
         {:id (str "interlude-bg-" nr)
          :ref (fn [el]
                 (when (and el (not @vid-ref)) ;presumably everything torn down on nil anyways so?
                   (reset! vid-ref el)))
          :onCanPlay (fn []
                       (when (and (not @controls)
                                  @vid-ref)
                         (reset! controls (util/play-pauser
                                           @vid-ref
                                           :time-per-step (/ control-time 3)))
                         (reset! on-hold true)
                         (js/setTimeout #(do-control :play)
                                        8000))) ; should be read from css i guess to correspond with other anim
          :loop true
          :muted true}
         bg)
        (when-let [poster (-> bg second :poster)]
          {:src poster
           :alt "Video poster"
           :class (str "media media-as-bg " (when (false? @on-hold) "hidden"))
           :style {:z-index 1}})] ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
       [:div
        {:class "covering-faded widescreen-safe center-content parallax-group"
         :ref #(observer %) ;oh yeah check first el for :video cant work it's rendered at that point lol
         :style {:transition "opacity 4.5s"
                 :opacity (when-not (zero? @in-view)
                            "0.4")}}
        [:h1.h-responsive title]]
       [ui/inset caption nr]]))))


(defn ui-portfolio "GOT NO PPORTFOLIE" [])


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

(defn ui-services "List services on (fake) offer. Clicking one should bring it up to fill section..."
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

(defn ui-moneyshot "needs better name lol. what is hero img halfway down page?"
  [{:keys [title caption bg]}]
  (let [frac (r/atom 0.0)
        observer (util/observer #(reset! frac %))]
    (fn [{:keys [title caption bg]}]
        [:div#moneyshot {:class "section-with-media-bg-wrapper parallax-wrapper covering stick-up"
          :ref #(observer %)}
         [img/media-as-bg ; TODO try it as background-image instead of separate div, see if calms down...
          (merge bg {:class "darken-8 parallax-bg origin-toptop" ;origin-toptop
                     :style (merge (when (pos? @frac)
                                     {:opacity 1.0})
                                   #_{:transform (str "scale(1.125) translateZ(" (* @frac -0.5) "px)")})})] ; need to be above 1.1 or goes jumpy weirdshit clip UGH why
         [:section#intro-end.center-content
          ; {:class "darken-8 parallax-sm origin-toptop"
          ;  :style {:background-image (str "url(" (:src bg) ")")
          ;          :background-size "cover"}}
          [:h1.h0-responsive.parallax-fg
           {:style {:z-index 10
                    :transition "transform 8.5s ease"
                    #_:transform #_(str "translateZ(" (* 14 @frac) "px)")}}
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
    [:br] [:br]]
   [ui/fading :dir "bottom"]])


(defn ui-gallery "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (when @(rf/subscribe [:state [:gallery :loaded]])
      (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [img/picture (merge img {:class "media"})]))]]) ; TODO add captions and other features etc...

(defn ui-gallery-2 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel-normal"]
   [ui/carousel-normal :gallery-2-normal {:style {:height "40vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-2-normal-" (:src img))}
            [img/picture (merge img {:class "media"})]))]])

(defn ui-gallery-3 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel"]
   [ui/carousel :gallery-2 {:style {:height "30vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [img/picture (merge img {:class "media"})]))]])


(defn remote-player
  [url]
  [:div]
  #_[:> SoundCloud
   {:url url
    :width "100%"
    :height "100%"}])

(defn soundcloud-loading "A dummy to show before initing react-player"
  [artist song]
  [:div.soundcloud-player-loading
   [img/picture
    {:src "img/soundcloud-logo.png"
     :alt "SoundCloud"
     :class "center-content"}]
   [:h3 song]
   [:h4 artist]])

(defn soundcloud-player
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url (str base-url artist "/" song)]
    [ui/seen-anon "slide-in"
     (if @(rf/subscribe [:booted? :soundcloud])
       [ui/safe :player
        [remote-player url]]
       [soundcloud-loading artist song])]))

(defn ui-soundcloud "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [{:keys [url artist tunes]} @(rf/subscribe [:content [:soundcloud]]) ]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players
      (for [tune tunes] ^{:key (str "soundcloud-player-" tune)}
        [soundcloud-player artist tune])]]))


(declare sections)

(defn run-init
 [section]
 (let [dep (get-in sections [section :dep])
       event (or (get-in sections [section :init])
                 [:backend/init section])] ; initial refresh from service, then component runs its own init on mount once module loaded
   (when event
     [ui/lazy-load [:on-booted dep event]])))


(def sections
  {:intro       {:component ui-intro
                 :content :intro}
   :services    {:component ui-services
                 :content :services
                 :init [:state [:services :to-focus?] true]}
   :moneyshot   {:component ui-moneyshot
                 :content :moneyshot}
   :story       {:component ui-story
                 :content :story}
   :gallery     {:component ui-gallery
                 :content :gallery
                 :dep :site
                 :init [:state [:gallery :loaded] true]}
   :soundcloud  {:component ui-soundcloud
                 :dep :site
                 :init [:booted :soundcloud]}
   :strava      {:module :strava
                 :dep :firebase}
   :instagram   {:module :instagram
                 :dep :firebase}
   :github      {:module :github
                 :dep :site}
   :gpt         {:module :gpt}
   :chat        {:module :chat}
   :interlude   {:component ui-interlude
                 :content :interlude}
   :init        {:component run-init}})

; will want triggering all things to init
; when loading page halfway down so scroll pos stays correct
; so no lazy then
; will need to put sections in db and make a sub like
; (->> sections vals (map :init) (filter some?))
; and event
; (doall run/init @sub)

(def layouts
  {:main [:intro
          [:interlude 0]
          :services
          [:init :services] ; just focuses it
          [:interlude 1]
          :moneyshot
          [:init :instagram]
          [:init :strava]
          :story
          [:interlude 2]

          [:init :soundcloud]

          :strava
          [:init :gallery]
          :soundcloud
          :instagram
          :gallery
          :github
          :gpt
          :chat ]
   :desktop :something-splitty
   :joen :just-about-me/components
   :av :just-about-company })


(defn get-component "Get component, and its init event runner, if any."
  [id section-map]
  (let [{:keys [module component content args dep init]} section-map
        view (cond
               module (if @(rf/subscribe [:booted? module])
                        (into [l/<> {:module module
                                     :view   (or component :view)}]
                              args)
                        [:div {:id (str (name id) "-will-load")}])
               :else [component])]
    [:<>
     (when init
       [run-init id])
     (cond-> view
       content (conj @(rf/subscribe [:content [content]]))
       args    (conj args)
       true    vec)]))

(defn get-section "Get a section, from either a vector (with args) or a straight keyword"
  [section]
  (if (vector? section)
    (let [[id args] section]
      [get-component id (merge (get sections id)
                                {:args args})])
    [get-component section (get sections section)]))

(macroexpand '(m/defcomp <test-2>
  "test-comp-2"
  [spec]
  [:div "goodbye" (throw (js/Error. "test2"))]))
(m/defcomp <test-2>
  "test-comp-2"
  [spec]
  [:div "goodbye" (throw (js/Error. "test2"))])
(m/defcomp <test>
  "test-comp"
  [spec]
  [:div "hello" spec [<test-2> spec] ])

(defn ui-auto "Present main page UI. Should come from data structure.
               Should auto lazy load/init all components with such functionality at point,
               apart from the separate lazy loading done before-hand (if loads in middle of page etc)"
  []
  [:<>
   ; [<test> {:wah "cool"}]
   (for [[i component] (map-indexed vector (layouts :main))]
     ^{:key i}
     [get-section component])])


