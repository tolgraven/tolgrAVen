(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [markdown.core :refer [md->html]]
   [tolgraven.ui :as ui]
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

(defn ui-inset [caption nr]
  (let [pos (case (mod nr 4)
              0 "bottom right"  1 "bottom left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}
     caption]))

(defn link-img-title "Link eith an image and a title, for posts for example"
  [{:as content :keys [title text url side]
    :or [side :left]}]
  (let []
    [:div.float-wrapper
     [:div.caption-inset {:class (str side)}
      [:p text]]
     [:h2 title]]))

(defn input-toggle "Don't forget to put ze label - only was sep in first place due to css bs?"
  [id checked-path & {:keys [class label]}]
  [:input ;.toggle
   {:id id :class class ;must be outside header or breaks...
    :type "checkbox"    :default-checked @(rf/subscribe checked-path)
    :on-click (fn []
                (rf/dispatch (into checked-path
                                   [(not @(rf/subscribe checked-path))])))}])



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
                                  (reset! vid-ref el)
                                  (reset! controls (util/play-pauser el)))}) ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
       [:div
        {:class "covering-faded widescreen-safe center-content parallax-group"
         :ref #(observer %) ;oh yeah check first el for :video cant work it's rendered at that point lol
         :style {:transition "opacity 4.5s"
                 :opacity (str "calc(0.95 - 0.55 *" @in-view ")")}} ;well dumb but
        [:h1.h-responsive  title]]
       [ui-inset caption nr]]))) ; these arent showing up...


(defn ui-portfolio "GOT NO PPORTFOLIE" [])

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

       [:div.carousel-idxs
        (doall (for [idx (range (count content))]
                 [:button.carousel-btn.carousel-idx
                  {:class (when (= @index idx) "topborder")
                   :on-click #(reset! index idx)}
                  "*"]))] ])))

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
     [ui-inset caption 4] ;auto-gen
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
                                    :transform (str "scale(1.125) translateZ(" (* @frac -5) "px)")})})] ; need to be above 1.1 or goes jumpy weirdshit clip UGH why
         [:section#intro-end.center-content
          ; {:class "darken-8 parallax-sm origin-toptop"
          ;  :style {:background-image (str "url(" (:src bg) ")")
          ;          :background-size "cover"}}
          [:h1.h0-responsive.parallax-bg
           {:style {:z-index 10
                    :transition "transform 8.5s ease"
                    :transform (str "translateZ(" (* 35 @frac) "px)")}}
           title]] ;ideally want this also growing (and moving quicker upwards)]
         [ui-inset caption 3]
         [ui-inset (str "Fraction visible:" @frac) 2]
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

(defn zoom-to-modal "E.g. bring up image to semi-fullscreen when clicked.
                     Pass current dom elem so can animate smooth from its current pos and size."
  ; or well like what we can do is just set width to 80vw or whatever and anim to that.
  ; and pos absolute to break it out
  ; need to create an additional el anyways cause dont want it to disappear from current pos..
  [id]
  (when-let [component @(rf/subscribe [:state [:modal-zoom id :component]])]
    (let [opened (and @(rf/subscribe [:state [:modal-zoom id :opened]])
                      @(rf/subscribe [:state [:modal-zoom id :loaded]]))
          on-click #(rf/dispatch [:modal-zoom id :close])] ;actually guess container will handle click on img too. but later if add buttons etc, nyah
      [:div.modal-bg.center-content
       {:class (when opened "modal-bg-open")
        :on-click on-click }
       [:div#modal.modal-zoom.center-content
        {:class (when opened "modal-zoomed")
         :on-click on-click
         :ref #(when % (rf/dispatch [:modal-zoom id :loaded])) } ;to apply things only later
        (util/add-attrs component
                        {:class "modal-zoomed-item"}) ]])))

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

(defn cv "Write dat cv. Put it on the site. Probably not last? Dunno."
  []
  [:section.cv
   [:h1 "Resume"]
   [:p "Here goes the CV. Make it nice and funky with graphix n stuff."]])

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
     [ui-gallery @(rf/subscribe [:content [:gallery]])]
     [ui-gallery-2 @(rf/subscribe [:content [:gallery]])]
     [cv]]))

