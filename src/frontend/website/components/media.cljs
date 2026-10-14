(ns website.components.media
  (:require
    [clojure.string :as string]
    [website.content.schema :as schema]
    [website.content.fields :as fields]
    [website.media.schema :as media-schema]
    [website.component.registry]
    [website.macros :refer-macros [defc]]
    [website.react :as r]

    [website.components.image :as img]
    [website.components.ui.layout :as ui-layout]
    [website.util :as util]
    [website.components.video :as vid]))

(defc <bg-logo>
  "Keep the decorative logo's container and entrance; shared pictures own pixels."
  [path :- :?string & [asset] :- [:maybe media-schema/asset]]
  (when-not (string/blank? path)
    [:div#logo-top.logo-bg.stick-up
     {:class "logo-tolgraven" :aria-hidden true}
     [img/<picture> {:src path
                     :asset asset
                     :alt ""
                     :class "logo-picture"
                     :loading "eager"}]]))

(defc ^:private <media-as-bg-smart> "Detects whether media is image or video and uses appropriate component"
  [media-data :- schema/media]
  (let [src (:src media-data)]
    (if (and src (re-find #"\.(mp4|mov|webm)$" src))
      [vid/<media-as-bg> media-data]
      [img/<media-as-bg> media-data])))

(defc <carousel-bg> "Intro (bg img only) jumbotron slider
                      Should be a generic system also working for page transitions etc"
  [img-attrs :- [:sequential schema/media]]
  [:div#top-banner.carousel ;{:class ""}
   ; (map-indexed )
    (for [img-data img-attrs] ^{:key (str "carousel-bg-" (:src img-data))}
         [<media-as-bg-smart> img-data])])
         ; [:div.carousel-item
         ;  [:img.media.media-as-bg img]])])

(defc <carousel-bg-2> "Intro (bg img only) jumbotron slider"
  [img-attrs :- [:sequential schema/media]]
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
           [<media-as-bg-smart> img]]
         {:key (str "carousel-bg-" (inc i))}))
     img-attrs)]
   [:aside.carousel-nav>ol
    (for [i (->> img-attrs count inc (range 1))]
      [:li.carousel-nav-item
       [:a {:href (str "carousel__slide" i)}]])] ])



(defc <interlude> "Banner across with some image or video or w/e
                    Partial content errors probably because stops buffering since we pause it.
                    Let's try a tricky trick"
  [interludes :- [:sequential schema/heading] nr :- [:int {:min 0}]]
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
       (if (re-find #"\.(mp4|mov|webm)$" (or (:src bg) ""))
         [vid/<video-with-picture-poster>
          (merge
           {:id (str "interlude-bg-" nr)
            :class "media media-as-bg"
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
            :loading "lazy"
            :muted true}
           bg)
          (when-let [poster (:poster bg)]
            {:poster poster
             :alt "Video poster"
             :class (str "media media-as-bg " (when (false? @on-hold) "hidden"))
             :style {:z-index 1}})]
         [img/<media-as-bg> (assoc bg :id (str "interlude-bg-" nr))])
       [:div
        {:class "covering-faded widescreen-safe center-content parallax-group"
         :ref #(observer %) ;oh yeah check first el for :video cant work it's rendered at that point lol
         :style {:transition "opacity 4.5s"
                 :opacity (when-not (zero? @in-view)
                            "0.4")}}
        [fields/<text> :h1.h-responsive (get interludes nr) [:title]]]
       [ui-layout/<content-inset> (get interludes nr) [:caption] nr]]))))

(defc <gallery> "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs :- [:sequential schema/media]]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (when @(r/subscribe [:state [:gallery :loaded]])
      (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [img/<picture> (merge img {:class "media"})]))]]) ; TODO add captions and other features etc...

(defc <moneyshot> "needs better name lol. what is hero img halfway down page?"
  [{:keys [title caption bg] :as content}]
  (let [frac (r/atom 0.0)
        observer (util/observer #(reset! frac %))]
    (fn [{:keys [title caption bg] :as content}]
        [:div#moneyshot {:class "section-with-media-bg-wrapper parallax-wrapper covering stick-up"
          :ref #(observer %)}
         [img/<media-as-bg> ; TODO try it as background-image instead of separate div, see if calms down...
          (merge (fields/media content [:bg]) {:class "darken-8 parallax-bg origin-toptop" ;origin-toptop
                     :style (merge (when (pos? @frac)
                                     {:opacity 1.0})
                                   #_{:transform (str "scale(1.125) translateZ(" (* @frac -0.5) "px)")})})] ; need to be above 1.1 or goes jumpy weirdshit clip UGH why
         [:section#intro-end.center-content
          ; {:class "darken-8 parallax-sm origin-toptop"
          ;  :style {:background-image (str "url(" (:src bg) ")")
          ;          :background-size "cover"}}
          [fields/<text> :h1.h0-responsive.parallax-fg content [:title]
           {:style {:z-index 10
                    :transition "transform 8.5s ease"
                    #_:transform #_(str "translateZ(" (* 14 @frac) "px)")}}
           ]] ;ideally want this also growing (and moving quicker upwards)]
         [ui-layout/<content-inset> content [:caption] 3]
         [ui-layout/<fading>]])))
