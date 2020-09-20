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
   [tolgraven.db :as db :refer [<-db ->db]]))

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


; (defn log "Show an expandable log thingy. Prob dumb here but good base for any sorta feed thingy I guess!" []
;  (let [options (rf/subscribe [:get :options :display :log])
;        diag    (rf/subscribe [:get :diagnostics])
;        time-format (formatters :hour-minute-second)
;        ; time-format (formatters "HH-mm-ss.SSS")
;        table-ref (atom nil) ; scroll (r/atom nil)
;        line (fn [{:keys [time level title message] :as msg}]
;              [:tr.log-message
;               [:td (unparse time-format time)]
;               [:td {:class (str "message " (name level))} (name level)]
;               [:td title]
;               [:td.message #_{:style {:position :relative :left "1em"}} (str message)]])]
;   (r/create-class
;    {:display-name "Log"
;     :component-did-update (fn [this] ; (r/dom-node this)
;                            ; (println "Log updated!" (.-scrollHeight @table-ref))
;                            ; (reset! scroll (.-scrollHeight @table-ref))
;                            (set! (.-scrollTop @table-ref) (.-scrollHeight @table-ref))) ;resort to this since :scroll-top @ratom in the actual element doesnt work...
;     :reagent-render
;     (fn []
;      [:div.log-container
;       [ui/minimize [:options :display :log]] ;this also needs to send an event to scroll-top the fucker...
;       [:div.log-inner {:ref (fn [el] (reset! table-ref el))
;                        :style {:max-height (if (:minimized @options) "1.2rem" "20em")}
;                        ; :scroll-top @scroll ;wonder why this doesnt work
;                        #_:style #_{:max-height @scroll}}
;        [:table>tbody.log
;         (for [msg (map (:messages @diag)
;                        (sort (keys (:messages @diag)))
;                        #_(if (:minimized @options)
;                                           [(count (:messages @diag))]
;                                           (sort (keys (:messages @diag)))))]
;          ^{:key (str (:id msg))}
;          [line msg])]]])})))

; (defn modal "Container for anything modal, taking care of common stuff. USE FOR COOKIE NOTICE LOL YES"
;  [component & [on-outside-click]]
;  (let []
;   (db/set [:modal] true)
;   [:div#modal-container
;    [:div#modal-bg {:on-click on-outside-click
;                    :style {:position :fixed
;                            :width "100%" :height "100%" :top 0 :left 0
;                            :background "rgb(30, 30, 30, 0.5)"}}]
;    [:div#modal {:class (when (db/get [:modal]) "modal-is-open")}
;     component]]))

; (defn hud-modal "Show more info about a specific HUD message"
;  [] ;doesnt really have to be modal but wanted to implement that, so...
;  (if-let [msg @(rf/subscribe   [:hud :modal])]
;   (let [to-close #(rf/dispatch [:hud :modal :remove])]
;    [modal [:div.hud-modal-main
;            {:class (str "hud-message " (name (:level msg)))}
;            [:h3  (:title   msg)]
;            [:p   (str (:message msg))]
;            [:p   (str (:time    msg))]
;            [ui/close to-close]]
;     to-close])
;   (db/set [:modal] false))) ;eww gross

; (defn hud "Render a HUD sorta like figwheel's but at reagent/re-frame level" []
;  (let [to-show @(rf/subscribe [:hud])
;        one-msg (fn [{:keys [level title message time actions id]}]
;                 (let [class (str "hud-message " (name level))]
;                  [:div.hud-message
;                   {:class class
;                    :style {:position :relative}
;                    :on-click #(rf/dispatch (or (:on-click actions)
;                                                [:hud :modal id])) }
;                   [:span title]
;                   [ui/close (fn [e]
;                              (.stopPropagation e) ;it's causing a click on hud-message as well...
;                              (rf/dispatch [:diag/unhandled :remove id]))]]))]
;   [:div.hud.hidden
;    {:class (when (seq to-show) "visible")}
;    [ui/flip-move
;     {:class "hud-messages"
;      :duration 200 :staggerDelayBy 20 :staggerDurationBy 30}
;     (for [msg to-show]
;      [one-msg msg])]]))


; (defn divider []) ;need nice generic solution

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

(defn bg-logo [path]
    [:div#logo-top.logo-bg.parallax-sm
      {:class "logo-tolgraven"
       :style {:background (str "url('" path "')")}}]) ; cant remember why I did the weird path-in-css bs but anyways...

(defn ui-inset [caption nr]
  (let [pos (case (mod nr 4)
              0 "right"  1 "left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}]))
(defn float-img "Needs to go within a float-wrapper..."
  [id img-attr & [caption pos]]
  [:figure.float-with-caption {:id id :class (or pos "left")}
      [:img.media.image-inset img-attr]
      (when caption [:figcaption caption])])

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

(defn ui-header-nav [sections]
  [:menu
   [:nav
    [:input {:type "checkbox" :id "show-menu"}]
    [:label.menu-toggle {:for "show-menu"}]
    [:ul.nav-links
     (for [[title url] sections]
       [:li [:a {:href url :name title}
                (string/upper-case title)]])]]
   #_[:label {:for "theme-toggle" :class "theme-label show-in-menu"}
      "Theme"]])

; (defn ui-header [& {:keys [text menu]}]
(defn ui-header [{:keys [text menu]}]
  [:header
   ; [:p text]
    [:input {:class "burger-check" :id "burger-check" :type "checkbox"}]
    [:div.cover.cover-clip] ;covers around lines and that...
    (println menu)
    [ui-header-logo text]
    ; [ui-header-logo ["tola" ["wha" "ba"]]]
    [ui-header-nav menu]   ; menu
    [:label.burger {:for "burger-check"}]])

(defn ui-button "Pass text and id, plus either link anchor or action..."
  [text id & {:keys [type bg-div-class link action]
              :or   {type "button" bg-div-class "blur-bg" link (str "#" id)}}]
  [:button {:id (str "btn-" id) :type type}
   (when bg-div-class [:div {:class bg-div-class}])
   [:label {:for (str "btn-" id)}
    (if action
      true
      [:a {:href link} text]) ]])

(defn ui-fader "Hitherto just css but prog gen prob easier in some cases..."
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
         [:ul
          (into [:li
                  [:i {:class (str "fas " "fa-" icon-name)}]
                  [:h3 title]]
                (for [line lines]
                  [:li line]))])]]]))

;; TODO rethinking things a bit.
;; it's more like, what common attributes do whatever things have
;; and some stuff that should go together is in a div before or after
;; so remember :<>
(defn put "A section or smthing. Wrap some shit reg whatever"
  [])
; tho should do hiccup pre-render server side then just inject news feed and whatnots
(defn ui []
  ; (let [{:keys [logo-header logo-bg intro-text services-text]} (<-db [:content])
  ; (let [{:keys [header- intro- services- story- interlude-] :as content} (<-db [:content])
  ; (let [{:keys [header- intro- services- story- interlude-] :as content} @(rf/subscribe [:content])
  (let [{:keys [header intro services story interlude] :as content} @(rf/subscribe [:content])
        interlude-counter (atom 0)]
    [:<> ;      #_{:class (when (db/<- [:modal]) "modal-is-open")}
      ; [ui-header header]
      [ui-header @(rf/subscribe [:content :header])]
      ; (println @(rf/subscribe [:content :header]))
      [:div.line.line-header] ; XXX oh yeah only actually outside header bc silly css tricks to get shit to play along. so, fuck that, and get it within

      [:main.main-content.perspective-top
       [:a {:name "linktotop"}]
       [bg-logo (:logo-bg intro)]
       [:img#top-banner.media.media-as-bg (:bg intro)] ; can we get this within intro plz?

       ; [intro intro-]
       [ui-intro @(rf/subscribe [:content :intro])]

       ; [ui-interlude (merge (nth interlude 0)
       ;                   {:nr 1})]
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
      [ui-fader]]

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
                         {:nr (swap! interlude-counter inc)})]

      #_[ui-section "intro-end"
       {:class "covering stick-up"}
       {:class "covering-faded"}
               [:<> [:h1.h1-responsive.parallax.bg "YOU"]]
               [:img {:src "img/crowd-lbp.JPG"
                      :class "media-as-bg fade-5 parallax-sm origin-toptop"}]
               [inset "Happy people hospitality blabla." 3]]
      ]]))

