(ns tolgraven.views-common
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [reitit.frontend.easy :as rfe]
   [clojure.string :as string]
   [markdown.core :refer [md->html]]
   [tolgraven.ui :as ui]
   [tolgraven.views :as view]
   [tolgraven.db :as db :refer [<-db ->db]]
   [tolgraven.util :as util]))


;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defn header-logo [[text subtitle]]
  [:div.header-logo
   [:a {:href "#"} ;works w/o reitit fiddle
    [:h1 text]]
   [:div.header-logo-text
    (for [line subtitle] ^{:key (str "header-text-" line)}
      [:p line])]])

(defn header-nav "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  (let [put-links (fn [links]
                    (doall
                     (for [[title url page] links] ^{:key (str "menu-link-" title)}
                          [:li [:a {:href url :name title
                                    :data-reitit-handle-click false
                                    :class (when (= page
                                                    @(rf/subscribe [:common/page]))
                                             :is-active)} ;some (rfe/href ::about auto thing too)
                                (string/upper-case title)]])))]
    [:menu ; XXX put bg stuff in mwnu not nav...
     [:nav
      [:div.nav-section
       [:ul.nav-links
        (put-links (:work sections))]]

      [:div.nav-section
       [:ul.nav-links
        {:style {:position :absolute, :right 0, :top 0}}
        (put-links (:personal sections))]]]

     [:div.menu-toggles
      {:style {:position "absolute" :right 0 :bottom 0} }
      ; [input-toggle "theme-force-dark" [:state [:theme-force-dark]]]
      ; [:label.show-in-menu {:for "theme-force-dark" :class "theme-label"} "Theme"]
      ; [ui/toggle [:state [:debug-layers]]]
      ; [input-toggle "debug-layers" [:debug [:layers]]]
      ; [:label.show-in-menu {:for "debug-layers"} "Debug"]
      ] ]))


(defn header [{:keys [text text-personal menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
  [:<>
   [view/input-toggle "nav-menu-open" [:menu] :class "burger-check"]
   [:header
    [:div.cover.cover-clip] ;covers around lines and that... XXX breaks when very wide tho.
    [header-logo @(rf/subscribe [:header-text])]
    [header-nav menu]

    [view/loading-spinner (rf/subscribe [:state [:is-loading]])]   ; menu
    [view/user-menu]
    [:label.burger {:for "nav-menu-open"}]]
   [:div.line.line-header]])


(defn footer-sticky "Thinking just something tiny visible maybe not actually entire time but at whatever points, framing things in. Might be job for css tho dunno"
  [content]
  [:<>
    [:footer.footer-sticky>div.footer-content
      {:style {:position :fixed}}
      [:div.line.line-footer] ;cant this be outside main ugh
        (for [{:keys [title text id links] :as column} content
          :let [id (str "footer-stick-" id)]] ^{:key id}
          [:div.footer-column {:id id}
          [:h3 title]])]])

(defn footer "Might want to bail on left/middle/right just push whatever. do the current ids matter?"
  [content]
  [:footer.footer-sticky ; [:footer>div.footer-content
   [:div.line.line-footer] ;cant this be outside main ugh
   ; [logmsgs, cookie nurtice and bunch of deminimizable stuff]
   [:div.footer-content ;; XXX should adapt to available height, also disappear...
    (for [{:keys [title text id links img] :as column} content
          :let [id (str "footer-" id)]] ^{:key id}
         [:div.footer-column {:id id}

          [:h4 title]
          ; (when img [util/merge-attr img])
          (when text (for [line text] ^{:key (str id "-" line)}
                          [:h5 line]))
          (when links [:div.footer-icons
                       (for [{:keys [name href icon]} links] ^{:key (str "footer-link-" name)}
                            [:a {:href href :name name}
                             [:i.fab {:class (str "fa-" icon)}]])])])]])

(defn to-top "A silly arrow, and twice lol. why." [icon]
 (let [icon (or icon "angle-double-up")
       i [:i {:class (str "fas fa-" icon)}]]
   [:a {:id "to-top" :class "to-top" :href "#linktotop" :name "Up"} i]
   [:div {:id "to-top-bg" :class "to-top"} i]))   ; why not just double up within? )

