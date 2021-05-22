(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [cljsjs.react-player]
   [tolgraven.ui :as ui]
   [tolgraven.strava.views :as strava]
   [tolgraven.db :as db]
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

(defn link-img-title "Link eith an image and a title, for posts for example"
  [{:as content :keys [title text url side]
    :or [side :left]}]
  (let []
    [:div.float-wrapper
     [:div.caption-inset {:class (str side)}
      [:p text]]
     [:h2 title]]))



(defn ui-fading "Hitherto just css but prog gen prob easier in some cases..."
  [& {:keys [fade-to dir content]
      :or {fade-to "fade-to-black" dir "light-from-below"}}]
  [:div.fader [:div {:class (str fade-to " " dir)}]])

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
  [:section#intro
   [bg-logo logo-bg]
   [:img#top-banner.media.media-as-bg (first bg)]

   [:<>
    [:h1.h-responsive title]
    (into [:<>] (ln->br text)) ; or just fix :pre css lol
    [:br]
    [:div.buttons
     (for [[text id] buttons] ^{:key (str "intro-button-" id)}
       [ui/button text id :link id :bg-div-class "blur-bg"])]]])


(defn ui-interlude "Banner across with some image or video or w/e
                    TODO should stick top-border on outer (which should be section anyways ugh)"
  [{:keys [title caption bg nr]}]
  (let [vid-ref (atom nil) ; docs says reg atom better but only updates w ratom, bc 2nd fn or? also .play no works
        controls (atom nil)
        in-view (r/atom 0.0)
        on-change (fn [frac]
                    (reset! in-view frac)
                    (when (and (<= frac 0.2) @controls)
                      (@controls :pause)))
        observer (util/observer on-change (str "interlude-" nr))]
    (fn [{:keys [title caption bg nr]}]
      [:section.nopadding {:id (str "interlude-" nr)
             :class "section-with-media-bg-wrapper parallax-wrapper"
             :on-mouse-enter #(when-let [video @vid-ref] (@controls :play))
             :on-mouse-leave #(when-let [video @vid-ref] (@controls :pause))}
       (util/add-attrs bg {:id (str "interlude-bg-" nr)
                           :ref (fn [el]
                                  (when el
                                    (reset! vid-ref el)
                                    (set! (.-muted el) true)
                                    (reset! controls (util/play-pauser el))))
                           :muted true}) ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
       [:div
        {:class "covering-faded widescreen-safe center-content parallax-group"
         :ref #(observer %) ;oh yeah check first el for :video cant work it's rendered at that point lol
         :style {:transition "opacity 4.5s"
                 :opacity (str "calc(0.95 - 0.55 *" @in-view ")")}} ;well dumb but
        [:h1.h-responsive  title]]
       [ui/inset caption nr]])))


(defn ui-portfolio "GOT NO PPORTFOLIE" [])


(defn carousel-idx-btns
  [idx-model amount]
  [:div.carousel-idxs
   (doall (for [idx (range amount)]
            [:button.carousel-btn.carousel-idx
             {:class (when (= @idx-model idx) "topborder")
              :on-click #(reset! idx-model idx)}
             "*"]))])

(defn carousel "Three-showing carousel with zoom up of center item, and animating changes.
                The generic enough stuff could go in a more general carousel-builder
                or we just make two."
  [id options content]
  (let [num-items-shown 3
        index (r/atom 1) ; (rf/subscribe [:state [:carousel id :index]])
        first-idx (max 0 (- @index
                            (/ (dec num-items-shown) 2))) ;get middle
        content-shown (subvec content
                              first-idx
                              (+ first-idx (dec num-items-shown)))
        dec-fn (fn []
                 (swap! index #(if (neg? (dec %))
                                 (dec (count content))
                                 (dec %)))
                 (rf/dispatch [:carousel/rotate id @index :dec]))
        inc-fn (fn []
                 (swap! index #(if (< (inc %) (count content))
                                 (inc %)
                                 0))
                 (rf/dispatch [:carousel/rotate id @index :inc]))
        moving (rf/subscribe [:state [:carousel id :direction]])
        left-content #(if (pos? %)
                       (get content (dec %))
                       (last content))
        right-content #(if (< % (dec (count content)))
                         (get content (inc %))
                         (first content))]
    (fn [id options content]
      [:div.carousel.carousel-three
       (merge options
              {:id (name id)})
       
       [:button.carousel-btn.carousel-prev-btn {:on-click dec-fn} "<"]
       
       [:ul.carousel-items
        [:li.carousel-item-left-pseudo
         {:class @moving}
         (if (pos? (dec @index))
           (get content (dec (dec @index)))
           (last content))]
        [:li.carousel-item-left
         {:class @moving
          :on-click dec-fn}
         (left-content @index)]
        
        [:li.carousel-item-middle
         {:class @moving}
         (get content @index)]
        
        [:li.carousel-item-right
         {:class @moving
          :on-click inc-fn}
         (right-content @index)]
        [:li.carousel-item-right-pseudo
         {:class @moving}
         (if (< (inc @index) (dec (count content)))
           (get content (inc (inc @index)))
           (first content))]]
       
       [:button.carousel-btn.carousel-next-btn {:on-click inc-fn} ">"]
       [carousel-idx-btns index (count content)] ])))

(defn carousel-normal "Don't fuck up with fancy hot swaps for transitions, just stuff everything in."
  [id options content]
  (let [num-items-shown 3
        index (r/atom 1) ; (rf/subscribe [:state [:carousel id :index]])
        first-idx (max 0 (- @index
                            (/ (dec num-items-shown) 2))) ;get middle
        content-shown (subvec content
                              first-idx
                              (+ first-idx (dec num-items-shown)))
        dec-fn (fn []
                 (swap! index #(if (neg? (dec %))
                                 (dec (count content))
                                 (dec %)))
                 (rf/dispatch [:carousel/rotate id @index :dec]))
        inc-fn (fn []
                 (swap! index #(if (< (inc %) (count content))
                                 (inc %)
                                 0))
                 (rf/dispatch [:carousel/rotate id @index :inc]))
        moving (rf/subscribe [:state [:carousel id :direction]])
        left-content #(if (pos? %)
                       (get content (dec %))
                       (last content))
        right-content #(if (< % (dec (count content)))
                         (get content (inc %))
                         (first content))]
    (fn [id options content]
      [:div.carousel.carousel-normal
       (merge options
              {:id (name id)})

       [:button.carousel-btn.carousel-prev-btn {:on-click dec-fn} "<"]

       (into [:ul.carousel-items]
        (map-indexed
         (fn [i item]
           [:li.carousel-item-min ; XXX still need to fix extra for left/right so those not display: hidden or w/e
            {:class (condp = @index
                      i "carousel-item-main"
                      (inc i) "carousel-item-prev"
                      (dec i) "carousel-item-next"
                      nil)
             :on-click #([:dispatch-prev-next-or-fullscreen])}
            item])
         content))

       [:button.carousel-btn.carousel-next-btn {:on-click inc-fn} ">"]
       [carousel-idx-btns index (count content)] ])))

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
     ; [carousel :services-carousel {} ["one" "two" "three" "four" "five"]]
     ; [:div] ;grid

     (for [[title icon-name lines] categories ;(if-not full-screened categories (filter #(= (first %) full-screened) categories)) ;XXX change to keys!!
           :let [on-click (fn [e] (.stopPropagation e)
                            (rf/dispatch [:state [:services]
                                          (when-not full-screened title)]))
                 id (str "service-" title)]] ^{:key id}
       [ui/seen id "zoom-x"
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
        observer (util/observer #(reset! frac %) :moneyshot)]
    (fn [{:keys [title caption bg]}]
        [:div#moneyshot {:class "section-with-media-bg-wrapper parallax-wrapper covering stick-up"
          :ref #(observer %)}
         [:img.media-as-bg ; TODO try it as background-image instead of separate div, see if calms down...
          (merge bg {:class "darken-8 parallax-sm" ;origin-toptop
                   :style (merge (when-not (pos? @frac)
                                   {:opacity 0})
                                   {:transition "transform 8.5s ease, opacity 3s"
                                    :transform (str "scale(1.125) translateZ(" (* @frac -1.5) "px)")})})] ; need to be above 1.1 or goes jumpy weirdshit clip UGH why
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
         [ui-fading]])))


(defn ui-story "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [ui/fading-bg-heading heading]]
   [:div.fader>div.fade-to-black.between]

   [:a {:name "about"}]
   [:section#about-story.anim-gradient-bg.noborder
    [:h1 (:title content)]
    [:br]
    [ui/auto-layout-text-imgs content]
    [:br] [:br]]])


(defn ui-gallery "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [:img.media img])]])

(defn ui-gallery-2 "Gallery carousel"
  [img-attrs]
  [:section#gallery-2.covering.fullwide {:style {:z-index 12}}
   [:div "test carousel"]
   [carousel :gallery-2 {:style {:height "30vh"} }
    (into []
          (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [:img.media img]))]])

(defn ui-insta "Future insta gallery"
  [srcs]
  [:section#gallery-3.fullwide.covering
   [:div.covering.gallery-insta
    (for [src srcs
          :let [item [:img #_.media {:src src} ]]]
      ^{:key (str "gallery-" src)}
         [:div.gallery-insta-item
          {:on-click #(rf/dispatch [:modal-zoom :fullscreen :open item])}
          item ])]])

(defn cv "Write dat cv. Put it on the site. Probably not last? Dunno."
  []
  [:section.cv
   [:h1 "Resume"]
   [:p "Here goes the CV. Make it nice and funky with graphix n stuff."]])


(defn remote-player
  [url]
  [:> js/ReactPlayer
   {:url url
    :width "100%"
    :height "100%"}])

(defn soundcloud-player
  [artist song]
  (let [base-url "https://soundcloud.com/"
        url (str base-url artist "/" song)]
    [ui/seen-anon "slide-in"
     [remote-player url]]))

(defn ui-soundcloud "Soundcloud feed, plus selected tunes. Bonus if can do anything fun with it"
  []
  (let [base-url "https://soundcloud.com/tolgraven/"
        artist "tolgraven"
        tunes ["pop-music-for-cool-people-sketch-1-session-1"
               "stateless-nearing-completion-messy-mix"
               "a-taste-of-what-i-will-sound-like-live"]]
    [:section.soundcloud.fullwide.covering-3
     [:div.soundcloud-players
      (for [tune tunes
            :let [url (str base-url tune)]]
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

     [ui-interlude (get-lewd)]
     [ui-moneyshot @(rf/subscribe [:content [:moneyshot]])]
     [ui-story @(rf/subscribe [:content [:story]])]
     [ui-interlude (get-lewd)]
     
     ; [ui-gallery-2 @(rf/subscribe [:content [:gallery]])]

     [strava/strava ]
     [ui-soundcloud]
     [ui-insta @(rf/subscribe [:instagram/posts-urls 24])]
     [ui-gallery @(rf/subscribe [:content [:gallery]])]
     ; [cv]
     ]))

