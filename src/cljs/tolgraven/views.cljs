(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [reitit.frontend.easy :as rfe]
   [clojure.string :as string]
   [markdown.core :refer [md->html]]
   [cljs-time.core :as ct]
   [tolgraven.ui :as ui]
   [cljs-time.format :refer [formatters formatter unparse]]
   [tolgraven.db :as db :refer [<-db ->db]]
   [tolgraven.util :as util]))

(defn ln->br "Ugh. UGH! Why"
  [text]
  (for [line (string/split-lines text)]
        [:p line]))


(defn bg-logo "Try to remember why had to put img in css/style..." [path]
    [:div#logo-top.logo-bg.parallax-sm
      {:class "logo-tolgraven"
       :style {:background-image (str "url('" path "')")}}]) ; cant remember why I did the weird path-in-css bs but anyways...

(defn ui-inset [caption nr]
  (let [pos (case (mod nr 4)
              0 "bottom right"  1 "bottom left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}
     caption]))


;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defn ui-header-logo [[text subtitle]]
  [:div.header-logo
   [:a {:href "#"} ;works w/o reitit fiddle
    [:h1 text]]
   [:div.header-logo-text
    (for [line subtitle] ^{:key (str "header-text-" line)}
      [:p line])]])

(defn ui-header-nav "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  (let [put-links (fn [links]
                    (doall (for [[title url page] links] ^{:key (str "menu-link-" title)}
                         [:li [:a {:href url :name title
                                   :data-reitit-handle-click false
                                   :class (when (= page @(rf/subscribe [:common/page])) :is-active)} ;some (rfe/href ::about auto thing too)
                               (string/upper-case title)]])))]
    [:menu
     [:nav
      [:div.nav-section
       [:ul.nav-links
         (put-links (:work sections))]]

      [:div.big-slash] ; a line that goes across

      [:div.nav-section
       [:ul.nav-links
          {:style {:position :absolute, :right 0, :top 0}}
          (put-links (:personal sections))]]]
     #_[:label {:for "theme-toggle" :class "theme-label show-in-menu"}
        "Theme"]]))

(defn ui-header [{:keys [text text-personal menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
  [:<>
   [:input {:id "nav-menu-open" :class "burger-check" ;must be outside header or breaks...
            :type "checkbox"    :default-checked @(rf/subscribe [:menu])
            :on-click (fn []
                        (rf/dispatch [:menu (not @(rf/subscribe [:menu]))])
                        ; #_(rfe/push-state yada)
                        ) }] ; can push url state also for menu open etc
   [:header
    [:div.cover.cover-clip] ;covers around lines and that... XXX breaks when very wide tho.
    [ui-header-logo @(rf/subscribe [:header-text])]
    [ui-header-nav menu]

    [:div.loading-spinner
     (when @(rf/subscribe [:state :is-loading])
       [:i {:class "fa fa-spinner fa-spin"}])]   ; menu
    [:label.burger {:for "nav-menu-open"}]]])

(defn ui-button "Pass text and id, plus either link anchor or action..."
  [text id & {:keys [type bg-div-class link action]
              :or   {type "button" bg-div-class "blur-bg" link (str "#" id)}}]
  [:button {:id (str "btn-" id) :type type}
   (when bg-div-class [:div {:class bg-div-class}])
   [:label {:for (str "btn-" id)}
    (if action
      true
      [:a {:href link} text]) ]])

(defn ui-fading "Hitherto just css but prog gen prob easier in some cases..."
  [& {:keys [fade-to dir content]
      :or {fade-to "fade-to-black" dir "light-from-below"}}]
  [:div.fader [:div {:class (str fade-to " " dir)}]])


(defn ui-intro [{:keys [title text buttons]}]
  [:section#intro
   [:h1.h-responsive title]
   (into [:<>] (ln->br text)) ; or just fix :pre css lol
   [:br]
   [:div.buttons
    (for [[id text] buttons] ^{:key (str "intro-button-" id)}
      [ui-button id text])]])

(defn ui-interlude "Banner across with some image or video or w/e
                    TODO if video, autoplay once when (re-)seen, or cont if clicked
                    using ref and stuff"
  [{:keys [title caption bg nr]}]
  (let [!bg (atom nil)]
    (fn [{:keys [title caption bg nr]}]
      [:div {:id (str "interlude-" nr)
             :class "section-with-media-bg-wrapper"
             :on-hover (when-let [video @!bg]
                         (try
                           (if (.-paused video)
                             (.play video)
                             (.pause video))
                           (catch js/Exception e)))}
       (util/add-attrs bg {:ref #(reset! !bg %)}) ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
       [:section {:class "covering-faded widescreen-safe center-content"}
        [:h1.h-responsive title]]
       [ui-inset caption nr]]))) ; these arent showing up...



(defn ui-portfolio "GOT NO PPORTFOLIE" [])

(defn ui-services "Let's start by just straight porting some stuff"
  [{:keys [categories bg caption]}]
  [:div#section-services
     {:class "link-anchor stick-up section-with-media-bg-wrapper"}
     [ui-inset caption 4] ;auto-gen
     [:img (merge bg {:class "media-as-bg fade-3 parallax-bg"})] ;wait how durp properly
     [:section#services
      [:div#categories
       (for [[title icon-name lines] categories]
         (into ^{:key (str "service-" title)}
               [:ul ; XXX mistakenly wrapped everything in additional :li. Made stuff smaller, in some ways looked better (but also broken spacing). Tweak css!
                [:li
                 [:i {:class (str "fas " "fa-" icon-name)}]
                 [:h3 title]]]
                (for [line lines] ^{:key (str "service-" title "-" line)}
                  [:li line])))]]])

(defn ui-moneyshot "needs better name lol. what is hero img halfway down page?"
  [{:keys [title caption bg visible]}]
  (let [div-ref (r/atom nil) ; gotta ratom so can give empty first? cause wont be mounted when building etc?
  ; (let [div-ref (atom nil) ; yes is true
        observer (util/observe div-ref #(rf/dispatch [:set [:moneyshot :visible] %]))] ;reason to actually keep this would be disposal. so r/with-let...
    (fn [{:keys [title caption bg]}]
      ; (let [observer (util/observe div-ref #(rf/dispatch [:set [:moneyshot :visible] %]))]
      (let []
        [:div {:class "section-with-media-bg-wrapper covering stick-up"}
               ; :ref #(reset! div-ref %)
         ; or only works in create-class biggum? well any case we need on-mount so heh
         [:img.media-as-bg (merge bg {:class "fade-5 parallax-sm origin-toptop"
                                      :style (when-not visible {:visibility "hidden"})}
                                  ; or only works in create-class biggum? well any case we need on-mount so heh
                                  )]
         [:section#intro-end.center-content
          [:h1.h0-responsive.parallax-bg
           {:ref #(reset! div-ref %)} ;why no go
           title]] ;ideally want this also growing (and moving quicker upwards)]
         [ui-inset caption 3]
         [ui-fading]]))))

;; TODO basically figure out neatest way of getting gotten rid of in-the-way bg layers...
;; parallax makes things tricky apparently - cue-db viz it works both from above and below...
;; but here only get 0 above, stuck on 1 below.
;; guess full gamut of did-mount for better observer, plus loadsa self-modding killing parallax?
;; just look at how people normally do it in react i guess.
;;
;; but clearly "out there fkn content" + mostly clipping div then tracking that leaky container div
;; must be right course of action.
(defn fading-bg-heading [{:keys [title bg] :as content}]
  [:div.fader
   [:img.media.media-as-bg bg]
   [:section.covering-faded
    [:h1.h-responsive title]]])


(defn ui-story "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [fading-bg-heading heading]]
   [:div.fader>div.fade-to-black.between]

   [:section#about-story.anim-gradient-bg.noborder
    [:h1#about.link-anchor (:title content)]
    [ui/auto-layout-text-imgs content]]])

(defn ui-gallery "Stupid css thing slides sidewayus x) Make it go out left side would be cool"
  [img-attrs]
  [:section#gallery.covering.fullwide
   [:div.sideways
    (for [img img-attrs] ^{:key (str "gallery-" (:src img))}
         [:img.media img])]])

(defn ui-footer-sticky "Thinking just something tiny visible maybe not actually entire time but at whatever points, framing things in. Might be job for css tho dunno"
  [content]
  [:<>
    [:footer.footer-sticky>div.footer-content
      ; {:style {:position :fixed}}
      [:div.line.line-footer] ;cant this be outside main ugh
        (for [{:keys [title text id links] :as column} content
          :let [id (str "footer-stick-" id)]] ^{:key id}
          [:div.footer-column {:id id}
          [:h3 title]])]])

(defn ui-footer "Might want to bail on left/middle/right just push whatever. do the current ids matter?"
  ; [{:keys [left middle right]}]
  [content]
  ; [:footer>div.footer-content
  [:footer.footer-sticky.fullwide
   [:div.line.line-footer] ;cant this be outside main ugh
   ; [logmsgs, cookie nurtice and bunch of deminimizable stuff]
   [:div.footer-content ;; XXX should adapt to available height, also disappear...
    (for [{:keys [title text id links] :as column} content
          :let [id (str "footer-" id)]] ^{:key id}
         [:div.footer-column {:id id}
          [:h3 title]

          (when text (for [line text] ^{:key (str id "-" line)}
                          [:p line]))
          (when links [:div.footer-icons
                       (for [{:keys [name href icon]} links] ^{:key (str "footer-link-" name)}
                            [:a {:href href :name name}
                             [:i.fab {:class (str "fa-" icon)}]])])])]])

(defn ui-to-top "A silly arrow, and twice lol. why." [icon]
 (let [icon (or icon "angle-double-up")
       i [:i {:class (str "fas fa-" icon)}]]
   [:a {:id "to-top" :class "to-top" :href "#linktotop" :name "Up"} i]
   [:div {:id "to-top-bg" :class "to-top"} i]))   ; why not just double up within? )

; tho should do hiccup pre-render server side then just inject news feed and whatnots
; TODO for good separation of frontpage / personal/bloggy, and leveraging "line all the way to right"
; make entire view scroll sideways and basically flip geometry
; so literally parallel pages
; logo text opposite side and changes to "tolgraven actual physical" or w/e,
; colors bit different,
(defn ui []
  (let [{:keys [intro interlude] :as content} @(rf/subscribe [:content])
        interlude-counter (atom 0)
        get-lewd #(merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})]
    [:<>
        ; [:div.padder.fullwidth {:style {:min-height @(rf/subscribe [:get-css-var "--header-height-current"])}}]
        [:a {:name "linktotop"}]
        [bg-logo (:logo-bg intro)]

        [:img#top-banner.media.media-as-bg (:bg intro)] ; can we get this within intro plz?
        [ui-intro @(rf/subscribe [:content :intro])]

        [ui-interlude (get-lewd)]
        [ui-services @(rf/subscribe [:content :services])]

        [ui-interlude (get-lewd)]
        [ui-moneyshot @(rf/subscribe [:content :moneyshot])]
        ; need to watch div and show/hide para laxy bee gees as appropriate - both bc now fucking compositor and ugly clipping etc
        ; also sidesteps "omg each new div higher z" induced problem
        [ui-story @(rf/subscribe [:content :story])]
        [ui-interlude (get-lewd)]
        [ui-gallery @(rf/subscribe [:content :gallery])]
        [ui-interlude (get-lewd)]]))

