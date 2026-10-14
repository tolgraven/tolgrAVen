(ns website.components.shell
  (:require-macros [website.build.site :refer [when-enabled]])
  (:require
    [website.components.icon :as icon]
    [website.components.icon-catalog :as icon-catalog]
    [website.component.registry]
    [website.component.restore :as restore]
    [website.react :as r]
    [website.macros :as m :refer-macros [defc]]

    [reitit.frontend.easy :as rfe]
    [clojure.string :as string]
    [website.loader]
    [website.components.ui.controls :as ui-controls]
    [website.components.ui.loading :as ui-loading]
    [website.components.contact :as contact]
    [website.components.image :as img]
    [website.components.image.sources :as image-sources]
    [website.db :as db]
    [website.util :as util :refer [at]]))

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
  (let [state @(r/subscribe [:state [:component id]])
        stage (:stage state)
        hovered? (r/atom nil)
        ]
    (if (at model)
      (when-not (= :closed stage)
        [<stage> component stage])
      [ui-loading/<loading-spinner> model])))

;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defc <header-logo> [[text subtitle]]
  [:div.header-logo
   [:a {:href @(r/subscribe [:href :home])} ;works w/o reitit fiddle
    [:h1 text]]
   [:div.header-logo-text ; if I want this to do its flip when changes text, do I need to make a whole componentDidChange dance with it? :I
    (for [line subtitle] ^{:key (str "header-text-" line)}
      [:p line])]])

(defc <header-nav> "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  (let [put-links (fn [links]
                    (doall
                     (for [[title url page] links
                           :let [id (str "menu-link-" (string/lower-case title))
                                 href @(r/subscribe [:href (keyword page)])]
                           :when (seq href)]  ^{:key id}
                          [:li [:a {:href href
                                    :name title :id id
                                    :class (when (= (keyword page)
                                                    @(r/subscribe [:common/page-id]))
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
      ; [ui-controls/<toggle> [:state [:debug-layers]]]
      ; [input-toggle "debug-layers" [:debug [:layers]]]
      ; [:label.show-in-menu {:for "debug-layers"} "Debug"]
      ] ]))


(defc <header> {:features [:error-boundary]
                 :icons ["solid/cog" "solid/pen-fancy" "solid/user"]}
  [{:keys [text text-personal menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
  (let [menu-open? @(r/subscribe [:state [:menu]])
        initial-menu (r/use-ref menu-open?)
        [restored? set-restored!] (r/use-state #(restore/local-document?))
        initial-menu? (= menu-open? (.-current initial-menu))]
    ;; A restored shell is already visible. The first menu intent resumes motion.
    (r/use-effect
     (fn []
       (when-not initial-menu? (set-restored! false))
       nil)
     [initial-menu?])
    [:<>
     [ui-controls/<input-toggle> "nav-menu-open" [:menu] :class "burger-check"]
     (when @(r/subscribe [:fullscreen/any?])
       [:div.header-before
        {:class (when @(r/subscribe [:state [:scroll :past-top]])
                  "past-top")}])
     [:header
      {:data-restored-motion (and restored? initial-menu?)
       :class (when @(r/subscribe [:state [:hidden :header] ])
                "hide")}
      [:div.cover.cover-clip] ;covers around lines and that... XXX breaks when very wide tho.
      [<header-logo> @(r/subscribe [:header-text])]
      [<header-nav> menu]

      (when @(r/subscribe [:state [:menu]])
        [:div.line])

      (when-let [loading @(r/subscribe [:loading])]
        [ui-loading/<loading-spinner> (r/subscribe [:loading]) :still
         {:style {:position :absolute
                  :left     "-2.65em"                                   ; puts it to left of header-logo, only partly visible. looks nice.
                  :top      "0%"}}])
      [:div.header-icons
       [:a.button.blog-link-btn.noborder.nomargin
        {:href @(r/subscribe [:href :blog])
         :aria-label "My blog"
         :title "My blog"}
        [:i.fa.fa-pen-fancy]]
      (when-enabled :settings nil
       [:a.button.settings-btn.noborder.nomargin
       {:href @(r/subscribe [:href-add-query
                              {:settingsBox (not @(r/subscribe [:state [:settings :panel-open]]))}])
        :aria-label "Settings"
        :title "Settings"}
       [:i.settings-btn {:class "fa fa-cog"}]])

      (m/<> {:module :search
             :view :button
             :<before> (fn []
                         [:button.search-ui-btn.noborder.nomargin
                          {:name "Search"
                           :title "Search site"
                           ;; Preserve the click's intent while the parent acquires
                           ;; Search. Its module events do not exist yet.
                           :on-click #(r/dispatch [:state [:search :open?] true])}
                          [img/<picture> {:src   "svg/search-ico.svg"
                                        :alt   "Search"
                                        :style {:width  "1.2em" :height "1.2em"
                                                :filter "var(--light-to-dark)"}}]])})
      (when-enabled :user nil
       (if @(r/subscribe [:user/active-user])
        (m/<> :user/btn)
        (m/<> {:module :user
               :view :btn
               :<before> (fn []
                           [:a.button.user-btn.noborder
                            {:href @(r/subscribe [:href-add-query {:userBox true}])
                             :aria-label "User account"
                             :title "User account"}
                            [:i.user-btn.fa.fa-user]])})))
      [:label.burger {:for "nav-menu-open"}]]]

     [:div.fill-side-top
      [:div
       {:class (str (when-not @(r/subscribe [:state [:scroll :past-top]])
                      "hide ")
                    (when @(r/subscribe [:fullscreen/any?])
                      "adjust-for-fullscreen"))}]]

     [:div.fill-above-line-header
      {:class (when @(r/subscribe [:state [:hidden :header]])
              "fill ")}]

     [:div.line.line-header
      {:class (when @(r/subscribe [:state [:hidden :header]])
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
                      (when-enabled :icon-catalog nil
                        (when (and (seq icon) (not (icon-catalog/names icon)))
                          (m/<> {:module :icon-catalog
                                 :view :empty
                                 :load-on :immediate
                                 :<loading> [:<>]})))
                      [icon/<icon> "fab" icon {}]])])])])


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
  (let [hidden? @(r/subscribe [:state [:hidden :footer]])
        at-bottom? @(r/subscribe [:state [:scroll :at-bottom]])
        inactive? (or hidden? at-bottom?)]
    [:footer#footer-sticky.footer-sticky
     {:aria-hidden (when inactive? true)
      :inert (when inactive? true)
      :class (str (when hidden? "hide ")
                  (when at-bottom? "bottomed ")
                  (when @(r/subscribe [:fullscreen/any?])
                    "adjust-for-fullscreen"))}
     [<footer-content> content]]))

(defc <footer-full> "Render the full footer at bottom of page"
  [content]
  (let [[restored?] (r/use-state #(restore/local-document?))
        at-bottom? @(r/subscribe [:state [:scroll :at-bottom]])]
    [:footer#footer-end.footer-full
     {:class "full"
      ;; The shared scroll state activates this footer just before it enters
      ;; the viewport, at the same point that it retires the sticky copy.
      :aria-hidden (when-not at-bottom? true)
      :inert (when-not at-bottom? true)
      :data-restored-motion restored?}
     [<footer-content> content]
     [<post-footer> @(r/subscribe [:content [:post-footer]])]]))


(defc <to-top> "A silly arrow, and twice lol. why."
  {:icons ["solid/angle-double-up"]}
  [icon]
 (let [icon (or icon "angle-double-up")
       i [icon/<icon> "fas" icon {}]]
    [:a {:id "to-top"
         :class "to-top"
         :href @(r/subscribe [:href "#main"])
         :aria-label "Back to top"}
     (when-enabled :icon-catalog nil
       (when-not (icon-catalog/names icon)
         (m/<> {:module :icon-catalog
                :view :empty
                :load-on :immediate
                :<loading> [:<>]})))
     i]))

(defc <scrollbar> "Basic custom scroll indicator. Add full functionality later..."
  [spec]
  [:div.scrollbar
   (merge spec
          {:on-mouse-down (fn [e] (println "etc"))})
   [:div.scrollbar-thumb]])
