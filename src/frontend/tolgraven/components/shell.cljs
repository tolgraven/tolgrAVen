(ns tolgraven.components.shell
  (:require
    [tolgraven.component.registry]
    [tolgraven.component.restore :as restore]
    [reagent.core :as r]
    [tolgraven.macros :as m :refer-macros [defc]]
    [tolgraven.react :as rf]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string]
    [tolgraven.loader]
    [tolgraven.components.ui :as ui]
    [tolgraven.modules.contact.views :as contact]
    [tolgraven.components.image :as img]
    [tolgraven.components.image.sources :as image-sources]
    [tolgraven.db :as db]
    [tolgraven.util :as util :refer [at]]))

(defc <flashing-ersatz-text-like-everyone-uses>
  "Better than wee loading spinner no? Eg Docs, we know big page is coming
   so while loading should be expanded to that size already yo"
  [row-count])

(defc ^:private <stage>
  {:features [[:appear "slide-in"]]}
  [component stage]
  (util/add-attrs component {:class stage}))

(defc <component> "Standard wrapper for component. Would have fallback loading thing, appear anim, disappear anim somehow..."
  [id attrs model component]
  (let [state @(rf/subscribe [:state [:component id]])
        stage (:stage state)
        hovered? (r/atom nil)
        ]
    (if (at model)
      (when-not (= :closed stage)
        [<stage> component stage])
      [ui/<loading-spinner> model])))

;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defc <header-logo> [[text subtitle]]
  [:div.header-logo
   [:a {:href @(rf/subscribe [:href :home])} ;works w/o reitit fiddle
    [:h1 text]]
   [:div.header-logo-text ; if I want this to do its flip when changes text, do I need to make a whole componentDidChange dance with it? :I
    (for [line subtitle] ^{:key (str "header-text-" line)}
      [:p line])]])

(defc <header-nav> "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  (let [put-links (fn [links]
                    (doall
                     (for [[title url page] links
                           :let [id (str "menu-link-" (string/lower-case title))]]  ^{:key id}
                          [:li [:a {:href @(rf/subscribe [:href (keyword page)])
                                    :name title :id id
                                    :class (when (= (keyword page)
                                                    @(rf/subscribe [:common/page-id]))
                                             "is-active")}
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


(defc <header> {:features [:error-boundary]}
  [{:keys [text text-personal menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
  (let [menu-open? @(rf/subscribe [:state [:menu]])
        initial-menu (rf/use-ref menu-open?)
        [restored? set-restored!] (rf/use-state #(restore/local-document?))
        initial-menu? (= menu-open? (.-current initial-menu))]
    ;; A restored shell is already visible. The first menu intent resumes motion.
    (rf/use-effect
     (fn []
       (when-not initial-menu? (set-restored! false))
       js/undefined)
     #js [initial-menu?])
    [:<>
     [ui/<input-toggle> "nav-menu-open" [:menu] :class "burger-check"]
     (when @(rf/subscribe [:fullscreen/any?])
       [:div.header-before
        {:class (when @(rf/subscribe [:state [:scroll :past-top]])
                  "past-top")}])
     [:header
      {:data-restored-motion (and restored? initial-menu?)
       :class (when @(rf/subscribe [:state [:hidden :header] ])
                "hide")}
      [:div.cover.cover-clip] ;covers around lines and that... XXX breaks when very wide tho.
      [<header-logo> @(rf/subscribe [:header-text])]
      [<header-nav> menu]

      (when @(rf/subscribe [:state [:menu]])
        [:div.line])

      (when-let [loading @(rf/subscribe [:loading])]
        [ui/<loading-spinner> (rf/subscribe [:loading]) :still
         {:style {:position :absolute
                  :left     "-2.65em"                                   ; puts it to left of header-logo, only partly visible. looks nice.
                  :top      "0%"}}])
      [:div.header-icons
       [:a.button.blog-link-btn.noborder.nomargin
        {:href @(rf/subscribe [:href :blog])
         :aria-label "My blog"
         :title "My blog"}
        [:i.fa.fa-pen-fancy]]
      [:a.button.settings-btn.noborder.nomargin
       {:href @(rf/subscribe [:href-add-query
                              {:settingsBox (not @(rf/subscribe [:state [:settings :panel-open]]))}])
        :aria-label "Settings"
        :title "Settings"}
       [:i.settings-btn {:class "fa fa-cog"}]]

      (m/<> {:module :search
             :view :button
             :<before> (fn []
                         [:button.search-ui-btn.noborder.nomargin
                          {:name "Search"
                           :title "Search site"
                           ;; Preserve the click's intent while the parent acquires
                           ;; Search. Its module events do not exist yet.
                           :on-click #(rf/dispatch [:state [:search :open?] true])}
                          [img/<picture> {:src   "svg/search-ico.svg"
                                        :alt   "Search"
                                        :style {:width  "1.2em" :height "1.2em"
                                                :filter "var(--light-to-dark)"}}]])})
      (m/<> :user/btn)
      [:label.burger {:for "nav-menu-open"}]]]

     [:div.fill-side-top
      [:div
       {:class (str (when-not @(rf/subscribe [:state [:scroll :past-top]])
                      "hide ")
                    (when @(rf/subscribe [:fullscreen/any?])
                      "adjust-for-fullscreen"))}]]

     [:div.fill-above-line-header
      {:class (when @(rf/subscribe [:state [:hidden :header]])
              "fill ")}]

     [:div.line.line-header
      {:class (when @(rf/subscribe [:state [:hidden :header]])
               "hide")}]]))


(defn- footer-image-attrs [image]
  (let [{:keys [width height]} (image-sources/dimensions (:src image))]
    ;; Footer icons render at two em high; source selection follows their ratio.
    (merge {:class "img-icon"
            :loading "lazy"}
           (when (and width height) {:sizes (str (* 2 (/ width height)) "em")})
           image)))

(defc <footer-content> "Upper content (first few rows) of footer"
  [content]
  [:div.footer-content ;; XXX should adapt to available height, also disappear...
   (for [{:keys [title email text id links logo] :as column} content
         :let [id (str "footer-" id)]] ^{:key id}
     [:div.footer-column {:id id}

      (when (:src logo)
        [img/<picture> (footer-image-attrs logo)])
      (when (or title email (seq text))
        [:div
         (when title [:h2.footer-title title])
         (when email [contact/<contact-ways> email])
         (when text (for [line text] ^{:key (str id "-" line)}
                      [:p.footer-note line]))])
      (when links [:div.footer-icons
                   (for [{:keys [name href icon]} links] ^{:key (str "footer-link-" name)}
                     [:a {:href href :name name :aria-label name}
                      [:i.fab {:class (str "fa-" icon)}]])])])])


(defc <post-footer> "Extra stuff after the basic footer. Not very useful for me but for other sites."
  [content]
  [:div.footer-content.post-footer-content ;; XXX should adapt to available height, also disappear...
    (for [{:keys [title text id links img] :as column} content
          :let [id (str "post-footer-" id)]] ^{:key id}
         [:div.footer-column {:class (:id column)
                              :id id}

          (when title [:h2.footer-title title])
          (when text (for [line text] ^{:key (str id "-" line)}
                          [:p.footer-note line]))
          (when links [:div.footer-links
                       (for [{:keys [name href info]} links] ^{:key (str "post-footer-link-" name)}
                            [:a {:href href :name name}
                             [:div.footer-link-with-text
                              [:p name] [:p info]]])])
          (when img (for [img-data img]  ^{:key (str id "-" (:src img-data))}
                      [img/<picture> (footer-image-attrs img-data)]))])])


(defc <footer> "The sticky footer visible at load or when scrolling up."
  [content]
  [:footer#footer-sticky.footer-sticky
   {:class (str (when @(rf/subscribe [:state [:hidden :footer]])
                  "hide ")
                (when @(rf/subscribe [:state [:scroll :at-bottom]])
                  "bottomed ")
                (when @(rf/subscribe [:fullscreen/any?])
                   "adjust-for-fullscreen"))}
   [<footer-content> content]])

(defc <footer-full> "Render the full footer at bottom of page"
  [content]
  (let [[restored?] (rf/use-state #(restore/local-document?))]
    [:footer#footer-end.footer-full
     {:class "full"
      :data-restored-motion restored?}
     [<footer-content> content]
     [<post-footer> @(rf/subscribe [:content [:post-footer]])]]))


(defc <to-top> "A silly arrow, and twice lol. why." [icon]
 (let [icon (or icon "angle-double-up")
       i [:i {:class (str "fas fa-" icon)}]]
    [:a {:id "to-top"
         :class "to-top"
         :href @(rf/subscribe [:href "#main"])
         :aria-label "Back to top"} i]))

(defc <scrollbar> "Basic custom scroll indicator. Add full functionality later..."
  [spec]
  [:div.scrollbar
   (merge spec
          {:on-mouse-down (fn [e] (println "etc"))})
   [:div.scrollbar-thumb]])
