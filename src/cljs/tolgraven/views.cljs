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
  [:video {:class "media media-as-bg"
           ; :playinline true :autoplay true :loop true :muted true ;, how these attrs set??
           :src src}])
   ; :playinline :autoplay :loop :muted
  ; (->> {:class "media media-as-bg"
  ;      ; :playinline true :autoplay true :loop true :muted true ;, how these attrs set??
  ;      :src src}
  ;     ; (into {:src src})
  ;     (into [:video])))

(defn bg-logo [path]
    [:div#logo-top.logo-bg.parallax-sm
      {:class "logo-tolgraven"
       ; :style {:background "url('img/tolgrav.png')"}}]) ; cant remember why I did the weird path-in-css bs but anyways...
       :style {:background (str "url('" path "')")}}]) ; cant remember why I did the weird path-in-css bs but anyways...

(defn inset [caption nr]
  (let [pos (condp (mod nr 4)
              1 "right"
              2 "left"
              3 "top right"
              4 "top left")]
    [:p.caption-inset {:class "right" #_pos}]))


;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defn section "Curr some sections are raw some wrapped in outer div - eww. Wrap all? Inject link anchor etc"
  [id inner & outer]
  (let [sectioned (into [:section {:id id}] ; TODO just give each section its own id nr and run stuff like inset pos on that yea
                        inner)]
    (-> [:div.section-wrapper]
        (into outer)
        (into sectioned))))

(defn header-logo [[text subtitle]]
  [:div.header-logo
   [:a {:href "#linktotop"} [:h1 text]]
   [:div.header-logo-text (for [line subtitle] [:p line])]])
   ; [:div.header-logo-subtitle (map subtitle )]])
;
(defn header-nav [sections]
  [:menu
   [:nav
    [:input {:type "checkbox" :id "show-menu"}]
    [:label.menu-toggle {:for "show-menu"}]
    ; [:input {:type :checkbox :id :show-menu}]
    ; [:label.menu-toggle {:for :show-menu}]

    [:ul.nav-links
     (for [[title url] sections]
       [:li [:a {:href url :name title}
                (string/upper-case title)]])]]
   #_[:label {:for "theme-toggle" :class "theme-label show-in-menu"}
    "Theme"]])

(defn header [text-logo sections]
  [:header
   [:div.cover.cover-clip]
   [header-logo text-logo]
   [header-nav sections]

   [:label.burger {:for "burger-check"}]])

(defn button
  [id text & {:keys [type bg-div-class]
              :or   {type "button" bg-div-class "blur-bg"}}]
  [:button {:id (str "btn-" id) :type type}
   (when bg-div-class [:div {:class bg-div-class}])
   [:label {:for (str "btn-" id)} text]])

(defn intro [title text buttons]
  [:section#intro
   [:h1.h1-responsive title]
   (into [:p] (ln->br text))
   ; (for [line (string/split-lines text)]
   ;   [:p line])
   [:br]
   [:div.buttons
    (for [[id text] buttons]
      [button id text])]])

(defn interlude "Banner across with some image or video or w/e"
  [title inset bg nr]
  [:div {:id (str "interlude-" nr)
         :class "section-with-media-bg-wrapper"}
   bg ; but if support both img/video already must be defd so ugly splice in or. also single attrs how work w map?
   [:section {:class "covering-faded widescreen-safe center-content"}
    [:h1.h1-responsive title]]
   [:p.caption-inset {:class "right"}
    inset]])

(defn blog [])
(defn portfolio [])

(defn services "Let's start by just straight porting some stuff"
  [categories bg-img-attr caption]
  (let []
    [:div#section-services
     {:class "link-anchor stick-up section-with-media-bg-wrapper"}
     [:p.caption-inset caption]
     ; [(first bg-img) (-> bg-img second (merge {:class "media-as-bg fade-3 parallax-bg"}))] ;wait how durp properly
     [:img (merge bg-img-attr {:class "media-as-bg fade-3 parallax-bg"})] ;wait how durp properly
     [:section#services
      [:div#categories
       (for [[title icon-name lines] categories]
         [:ul
          (into [:li
                  [:i {:class (str "fas " "fa-" icon-name)}]
                  [:h3 title]]
                (for [line lines]
                  [:li line]))])]]]))


(defn put "A section or smthing. Wrap some shit reg whatever"
  []
  )

; tho should do hiccup pre-render server side then just inject news feed and whatnots
(defn ui []
  (let [{:keys [logo-header logo-bg intro-text services-text]} (<-db [:content])
        top-banner-img {:src "img/foggy-shit-small.jpg" :alt "Purple enthusiast"}]
    [:<>
     ; [:body.fullwide {:class "container themable framing-shadow sticky-footer-container"}
     ;      #_{:class (when (db/<- [:modal]) "modal-is-open")}
      ; [header logo-header]
      ; [header-nav [["Services"  "#link-services"]
      [:p (first intro-text)]
      ; [:p services-text]
      [header logo-header  [["Services"  "#link-services"]
                            ["Story"     "#about"]
                            ["Tools"     "#tools"]
                            ["Portfolio" "#portfolio"]]]

      [:div.line.line-header] ; XXX oh yeah only actually outside header bc silly css tricks to get shit to play along. so, fuck that, and get it within

      [:main.main-content.perspective-top
       [:a {:name "linktotop"}]

       [bg-logo logo-bg]

       [:img#top-banner.media.media-as-bg top-banner-img]

       (-> [intro]
           (into intro-text)
           (into [["contact"  "Join me"]
                  ["down"     "Fix these buttons"]]))
       ; (into [intro] intro-text
       ;   [["contact"  "Join me"]
       ;    ["down"     "Fix these buttons"]])

       [interlude "What does that mean?"
        "Stage light blala"
        [video-bg "media/fog-3d-small.mp4"]
        1]

       [services services-text
        ; [:img {:src "media/vim-code-small.jpg" :alt "neovim editor" :class "media-as-bg fade-3 parallax-bg"}]
        {:src "img/vim-code-small.jpg" :alt "neovim editor"}
        "neovim"]

      [interlude "For who?"
                 "Nihiloxica video shoot, Kampala"
                 [video-bg "media/nihil-shoot2.mp4"]
                 2]
      ]]))

