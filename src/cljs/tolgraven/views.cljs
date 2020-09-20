(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [clojure.string :as string]
   ; [goog.events :as events]
   ; [mount.core :as mount :refer [defstate]]
   ; [clojure.core.async :refer [pub sub chan go go-loop >! <! timeout close! unsub unsub-all sliding-buffer]]
   ; [reitit.core :as reitit]
   [markdown.core :refer [md->html]]
   [cljs-time.core :as ct]
   [tolgraven.ui :as ui]
   [cljs-time.format :refer [formatters formatter unparse]]
   [tolgraven.db :as db :refer [<-db ->db]]
   [tolgraven.util :as util]))

;; WHAT NEEDED?
;; website be like, front page bit like now but from here...
;; FIXING:
;; menu bottom as anchor/move page down with it..
;; drop layers so perf stops being awful
;;    also in general (tho guess can be done by css better anyways) adapt fanciness to hw...
;;
;;
;; portfolio section for when actually got something to show..
;;
;; some kinda blog/post thingy -> make a crappy CMS, log in/write md/spit out
;;   nice syntax hl, the cljs live eval thing etc
;; while doing above might as well try make basic commenting functionality..
;;
;; WORKFLOW?
;; not much from cue-db makes sense here, and what would isnt generalized enough
;; but some things can be used, prob best to massage/generalize them here then backport there
;; ideally make a lil lib with common utils and preferred part structure
;;

;; FIGURE OUT:
;; seems massively overdumbshit to use db for everything and whatnot on a page like this,
;; but, still, it should be used for maybe some dynamic content and obvs state (menu expanded etc)

;; https://ma.ttias.be/adding-a-sticky-table-of-contents-in-hugo-to-posts/#offsetting-the-anchor-links-from-the-top
;; might be nice for bloggy posty and when wide monitor...

(defn ln->br "Ugh. UGH! Why"
  [text]
  (->> (for [line (string/split-lines text)]
        [text [:br]])
        ; (into [:p])
        flatten))

(defn video-bg "Ze reg attrs for shitty perf sinks yo"
  [src]
  [:video {:class "media media-as-bg" ; :playinline true :autoplay true :loop true :muted true ;, how these attrs set??
           :src src}])

(defn bg-logo "Try to remember why had to put img in css/style..." [path]
    [:div#logo-top.logo-bg.parallax-sm
      {:class "logo-tolgraven"
       :style {:background (str "url('" path "')")}}]) ; cant remember why I did the weird path-in-css bs but anyways...

(defn ui-inset [caption nr]
  (let [pos (case (mod nr 4)
              0 "right"  1 "left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}]))


;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defn ui-section "Curr some sections are raw some wrapped in outer div - eww. Wrap all? Inject link anchor etc"
  [id wrapper-attrs section-attrs inner & outer]
  (let [sectioned (into [:section (merge {:id id}
                                         section-attrs)] ; TODO just give each section its own id nr and run stuff like inset pos on that yea
                        inner)]
    (-> [:div.section-with-media-bg-wrapper wrapper-attrs] ;;assuming universal...
        (into outer)
        (into sectioned))))

(defn ui-header-logo [[text subtitle]]
  [:div.header-logo
   [:a {:href "#linktotop"}
    [:h1 text]]
   [:div.header-logo-text (for [line subtitle] [:p line])]])

(defn ui-header-nav "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  [:menu
   [:nav
    ; [:input {:type "checkbox" :id "show-menu"}] ;this doesnt actually do anything right? prob remnant
    ; [:label.menu-toggle {:for "show-menu"}]
    [:ul.nav-links
     (for [[title url] sections]
       ^{:key (str "menu-link-" title)}
       [:li [:a {:href url :name title}
                (string/upper-case title)]])]]
   #_[:label {:for "theme-toggle" :class "theme-label show-in-menu"}
      "Theme"]])

; (defn ui-header [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
(defn ui-header [{:keys [text menu]}]
  [:<>
   [:input {:class "burger-check" :id "burger-check" :type "checkbox"
            :on-click #(rf/dispatch [:menu (not @(rf/subscribe [:menu]))])}] ;must be outside header or breaks...
   [:header
    [:div.cover.cover-clip] ;covers around lines and that...
    [ui-header-logo text]
    [ui-header-nav menu]   ; menu
    ; [:div#search>i.fas.fa-search]
    #_[ui/burger
      (rf/subscribe [:menu])
      #(rf/dispatch [:menu %])]
    [:label.burger {:for "burger-check"}]]])

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
   [:h1.h1-responsive title]
   (into [:p] (ln->br text)) ; or just fix :pre css lol
   [:br]
   [:div.buttons
    (for [[id text] buttons]
      [ui-button id text])]])

(defn ui-interlude "Banner across with some image or video or w/e"
  [{:keys [title caption bg nr]}]
  [:div {:id (str "interlude-" nr)
         :class "section-with-media-bg-wrapper"}
   bg ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
   [:section {:class "covering-faded widescreen-safe center-content"}
    [:h1.h1-responsive title]]
   [ui-inset caption nr]])

(defn ui-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [])
(defn ui-portfolio [])

(defn ui-services "Let's start by just straight porting some stuff"
  [{:keys [categories bg caption]}]
  (let []
    [:div#section-services
     {:class "link-anchor stick-up section-with-media-bg-wrapper"}
     [ui-inset caption 4] ;auto-gen
     [:p.caption-inset caption]
     [:img (merge bg {:class "media-as-bg fade-3 parallax-bg"})] ;wait how durp properly
     [:section#services
      [:div#categories
       (for [[title icon-name lines] categories]
         ^{:key (str "service-" title)}
         [:ul
          (into [:li
                  [:i {:class (str "fas " "fa-" icon-name)}]
                  [:h3 title]]
                (for [line lines]
                  ^{:key (str "service-" title "-" line)}
                  [:li line]))])]]]))

; tho should do hiccup pre-render server side then just inject news feed and whatnots
; TODO for good separation of frontpage / personal/bloggy, and leveraging "line all the way to right"
; make entire view scroll sideways and basically flip geometry
; so literally parallel pages
; logo text opposite side and changes to "tolgraven actual physical" or w/e,
; colors bit different,
(defn ui []
  (let [{:keys [header intro services story interlude] :as content} @(rf/subscribe [:content])
        interlude-counter (atom 0)]
    [:<> ;      #_{:class (when (db/<- [:modal]) "modal-is-open")}
      ; [ui-header header]
      [ui-header @(rf/subscribe [:content :header])]
      [:div.line.line-header] ; XXX oh yeah only actually outside header bc silly css tricks to get shit to play along. so, fuck that, and get it within

      ; [:div.padder.fullwidth {:style {:min-height @(rf/subscribe [:get-css-var "--header-height-current"])}}]

      [:main.main-content.perspective-top
       [:a {:name "linktotop"}]
       [bg-logo (:logo-bg intro)]
       [:img#top-banner.media.media-as-bg (:bg intro)] ; can we get this within intro plz?

       [ui-intro @(rf/subscribe [:content :intro])]

       [ui-interlude (merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})]

       [ui-services services]

       [ui-interlude (merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})]

      [:div.section-with-media-bg-wrapper {:class "covering stick-up"}
       [:img.media-as-bg  {:src "img/crowd-lbp.JPG" :class "fade-5 parallax-sm origin-toptop"}]
       [:section#intro-end.center-content
        [:h1.h-responsive.parallax-bg] "YOU"]
       [ui-inset "Happy people enjoying blabla" 1]
      [ui-fading]]

      [:div#about-intro.section-with-media-bg-wrapper {:class "covering stick-up fullwidth"}
       [:div.fader
        [:img.media.media-as-bg {:src "img/wide-spot-ctrl-small.jpg"}]
        [:section.covering-faded
          [:h1.h-responsive "Breaking things down"]]]]
      [:div.fader>div.fade-to-black.between]

      [:section#about-story.anim-gradient-bg.noborder
       [:h1#about.link-anchor]
       [ui/auto-layout-text-imgs @(rf/subscribe [:content :story])]]

      [ui-interlude (merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})] ]]))

