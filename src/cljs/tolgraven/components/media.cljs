(ns tolgraven.components.media
  (:require
   [re-frame.core :as rf]
   [reagent.core :as r]
   [tolgraven.image :as img]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util]
   [tolgraven.video :as vid]))

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

(defn carousel-bg "Intro (bg img only) jumbotron slider
                      Should be a generic system also working for page transitions etc"
  [img-attrs]
  [:div#top-banner.carousel ;{:class ""}
   ; (map-indexed )
    (for [img-data img-attrs] ^{:key (str "carousel-bg-" (:src img-data))}
         [media-as-bg-smart img-data])])
         ; [:div.carousel-item
         ;  [:img.media.media-as-bg img]])])

(defn carousel-bg-2 "Intro (bg img only) jumbotron slider"
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



(defn interlude "Banner across with some image or video or w/e
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

(defn gallery "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (when @(rf/subscribe [:state [:gallery :loaded]])
      (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [img/picture (merge img {:class "media"})]))]]) ; TODO add captions and other features etc...

(defn gallery-2 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel-normal"]
   [ui/carousel-normal :gallery-2-normal {:style {:height "40vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-2-normal-" (:src img))}
            [img/picture (merge img {:class "media"})]))]])

(defn gallery-3 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel"]
   [ui/carousel :gallery-2 {:style {:height "30vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [img/picture (merge img {:class "media"})]))]])


(defn moneyshot "needs better name lol. what is hero img halfway down page?"
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
         [ui/fading]])))
