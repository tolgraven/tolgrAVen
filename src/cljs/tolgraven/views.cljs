(ns tolgraven.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
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
    [:p.caption-inset {:class pos}
     caption]))


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
  (let [put-links (fn [links]
                    (for [[title url] links] ^{:key (str "menu-link-" title)}
                         [:li [:a {:href url :name title}
                               (string/upper-case title)]]))]
    [:menu
     [:nav
      [:div.nav-section
       ; [:p "work"]
       [:ul.nav-links
         (put-links (:work sections))]]

      [:div.big-slash] ; a line that goes across

      [:div.nav-section
       ; [:p "personal"]
       [:ul.nav-links
          {:style {:position :absolute, :right 0}}
          (put-links (:personal sections))]]]
     #_[:label {:for "theme-toggle" :class "theme-label show-in-menu"}
        "Theme"]]))

(defn ui-header [{:keys [text menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
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
   [:h1.h-responsive title]
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
    [:h1.h-responsive title]]
   [ui-inset caption nr]]) ; these arent showing up...


(defn ui-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [md]
  [:div {:dangerouslySetInnerHTML {:__html (md->html md)}}])

(defn ui-blog "all the blogs"
  [blogs]
  [:section.blogs.fullwide
   [:h1 "MY BLOGS"]
   (for [blog blogs]
    [ui-post (:md blog)])])

(defn ui-portfolio "GOT NO PPORTFOLIE" [])

(defn ui-services "Let's start by just straight porting some stuff"
  [{:keys [categories bg caption]}]
  (let []
    [:div#section-services
     {:class "link-anchor stick-up section-with-media-bg-wrapper"}
     [ui-inset caption 4] ;auto-gen
     [:img (merge bg {:class "media-as-bg fade-3 parallax-bg"})] ;wait how durp properly
     [:div#link-services.link-anchor]
     [:section#services
      [:div#categories
       (for [[title icon-name lines] categories] ^{:key (str "service-" title)}
         [:ul
          (into [:li
                  [:i {:class (str "fas " "fa-" icon-name)}]
                  [:h3 title]]
                (for [line lines] ^{:key (str "service-" title "-" line)}
                  [:li line]))])]]]))

(defn ui-moneyshot "needs better name lol. what is hero img halfway down page?"
  [{:keys [title caption bg]}]
  [:div {:class "section-with-media-bg-wrapper covering stick-up"}
       [:img.media-as-bg  (merge bg {:class "fade-5 parallax-sm origin-toptop"})]
       [:section#intro-end.center-content
        [:h1.h0-responsive.parallax-bg title]]
       [ui-inset caption 3]
      [ui-fading]])

(defn ui-story "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [:div.fader
    [:img.media.media-as-bg (:bg heading)]
    [:section.covering-faded
      [:h1.h-responsive (:title heading)]]]]
  [:div.fader>div.fade-to-black.between]

  [:section#about-story.anim-gradient-bg.noborder
    [:h1#about.link-anchor]
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
   [:div.footer-content
    (for [{:keys [title text id links] :as column} content
          :let [id (str "footer-" id)]] ^{:key id}
         [:div.footer-column {:id id}
          [:h3 title]

          (when text (for [line text] ;^{:key (str id "-" )}
                       [:p line]))
          (when links [:div.footer-icons
                       (for [{:keys [name href icon]} links] ^{:key (str "footer-link-" name)}
                            [:a {:href href :name name}
                             [:i.fab {:class (str "fa-" icon)}]])])])]])

; tho should do hiccup pre-render server side then just inject news feed and whatnots
; TODO for good separation of frontpage / personal/bloggy, and leveraging "line all the way to right"
; make entire view scroll sideways and basically flip geometry
; so literally parallel pages
; logo text opposite side and changes to "tolgraven actual physical" or w/e,
; colors bit different,
(defn ui []
  ; <<< dont forget did upgrade on proj + buncha profiles shit so roll back before get stuck debugging
  (let [{:keys [intro interlude] :as content} @(rf/subscribe [:content])
        interlude-counter (atom 0)
        get-lewd #(merge (nth interlude @interlude-counter)
                         {:nr (swap! interlude-counter inc)})]
    [:<> ;      #_{:class (when (db/<- [:modal]) "modal-is-open")}

      ; [:div.padder.fullwidth {:style {:min-height @(rf/subscribe [:get-css-var "--header-height-current"])}}]

      [:main.main-content.perspective-top
        [:a {:name "linktotop"}]
        [bg-logo (:logo-bg intro)]
        [:img#top-banner.media.media-as-bg (:bg intro)] ; can we get this within intro plz?

        [ui-intro @(rf/subscribe [:content :intro])]
        [ui-interlude (get-lewd)]
        [ui-services @(rf/subscribe [:content :services])]
        [ui-interlude (get-lewd)]
        [ui-moneyshot @(rf/subscribe [:content :moneyshot])] ; need to watch div and show/hide para laxy as appropriate - both bc now fucking compositor and ugly clipping etc
        [ui-story @(rf/subscribe [:content :story])]
        [ui-interlude (get-lewd)]
        [ui-gallery @(rf/subscribe [:content :gallery])]

        ; [:div.line.line-footer] ;cant this be outside main ugh
      ]

        ; [ui-footer-sticky @(rf/subscribe [:content :footer])]
      [ui-footer @(rf/subscribe [:content :footer])]

      [:a {:id "to-top" :class "to-top" :href "#linktotop" :name "Up"}
        [:i {:class "fas fa-angle-double-up"}]]
      [:div {:id "to-top-bg" :class "to-top"}
        [:i {:class "fas fa-angle-double-up"}]]
      [:a {:name "bottom"}]
     ]))

