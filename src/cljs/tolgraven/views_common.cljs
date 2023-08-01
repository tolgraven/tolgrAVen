(ns tolgraven.views-common
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reitit.frontend.easy :as rfe]
   [clojure.string :as string]
   [markdown.core :refer [md->html]]
   [tolgraven.ui :as ui]
   [tolgraven.db :as db]
   [tolgraven.search.views :as search]
   [tolgraven.util :as util :refer [at]]))

(defn flashing-ersatz-text-like-everyone-uses
  "Better than wee loading spinner no? Eg Docs, we know big page is coming
   so while loading should be expanded to that size already yo"
  [row-count])

(defn component "Standard wrapper for component. Would have fallback loading thing, appear anim, disappear anim somehow..."
  [id attrs model component]
  (let [state @(rf/subscribe [:state [:component id]])
        stage (:stage state)
        hovered? (r/atom nil)
        ]
    (if (at model)
      (when-not (= :closed stage)
        [ui/appear-merge "slide-in"
         (util/add-attrs component {:class stage})])
      [ui/loading-spinner model])))

;; TODO curr 1px gap between outer lines and img. Fix whatever causing this by mistake (think lines are half-width)
;; BUT also retain (and try 2px?) bc looks rather nice actually
(defn header-logo [[text subtitle]]
  [:div.header-logo
   [:a {:href @(rf/subscribe [:href :home])} ;works w/o reitit fiddle
    [:h1 text]]
   [:div.header-logo-text ; if I want this to do its flip when changes text, do I need to make a whole componentDidChange dance with it? :I
    (for [line subtitle] ^{:key (str "header-text-" line)}
      [:p line])]])

(defn header-nav "PLAN: / across with personal stuf on other side. Fade between logos depending on mouse hover..."
  [sections]
  (let [put-links (fn [links]
                    (doall
                     (for [[title url page] links
                           :let [id (str "menu-link-" (string/lower-case title))]]  ^{:key id}
                          [:li [:a {:href @(rf/subscribe [:href page])
                                    :name title :id id
                                    :class (when (= page
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


(defn header [{:keys [text text-personal menu]}] ; [& {:keys [text menu]}] ; wtf since when does this not work? not that these are optional anyways but...
  [:<>
   [ui/input-toggle "nav-menu-open" [:menu] :class "burger-check"]
   (when @(rf/subscribe [:fullscreen/any?])
     [:div.header-before
      {:class (when @(rf/subscribe [:state [:scroll :past-top]])
                "past-top")}])
   [:header
    {:class (when @(rf/subscribe [:state [:hidden :header] ])
              "hide")}
    [:div.cover.cover-clip] ;covers around lines and that... XXX breaks when very wide tho.
    [header-logo @(rf/subscribe [:header-text])]
    [header-nav menu]

    (when @(rf/subscribe [:state [:menu]])
      [:div.line])

    [ui/loading-spinner (rf/subscribe [:loading]) :still
     {:style {:position :absolute
              :left "-2.65em" ; puts it to left of header-logo, only partly visible. looks nice.
              :top "0%"}}]
    
    [:div.header-icons
     [:a {:href @(rf/subscribe [:href :blog])}
     [:button.blog-link-btn.noborder.nomargin
      {:title "My blog"}
      [:i.fa.fa-pen-fancy]]]
    [:a {:href @(rf/subscribe [:href-add-query  
                               {:settingsBox (not @(rf/subscribe [:state [:settings :panel-open]]))}])}
     [:button.settings-btn.noborder.nomargin
      [:i.settings-btn {:class "fa fa-cog"}]]]
    [search/button]
    [ui/user-btn]
    [:label.burger {:for "nav-menu-open"}]]]

   [:div.fill-side-top
    {:class (str (when-not @(rf/subscribe [:state [:scroll :past-top]])
                   "hide ")
                 (when @(rf/subscribe [:fullscreen/any?])
                   "adjust-for-fullscreen"))}]
   
   [:div.line.line-header
    {:class (when @(rf/subscribe [:state [:hidden :header]])
             "hide")}]])



(defn contact-form-popup
  []
  (let [inited? (r/atom nil)
        submit-hovered? (r/atom false)]
    (fn []
      (let [{:keys [show? sent? closing?]} @(rf/subscribe [:state [:contact-form]])
            loading? (rf/subscribe [:loading :post])
            contents (rf/subscribe [:form-field [:contact]])]
        (when show?
          [:section.contact-form-popup
           {:class (str
                    (when closing? "closing ")
                    (when (or sent? @loading?) "result ")
                    (when @inited? "inited"))
            :ref #(reset! inited? (boolean %))}
           [ui/close #(rf/dispatch [:contact/close])]
           [:h2 "Get in touch"]
           [:p "Whether for work, collaboration or something else, I'll do my best to accomodate you."]
           (when sent?
             [:div
              [:br] [:br]
              [ui/appear-merge "slide-in"
               [:h2 "Your message has been sent!"]]
              [:br]
              [ui/appear-merge "opacity"
               [:h3 "I'll get back to you shortly."]]])
           [ui/loading-spinner loading?]

           [:form.contact-form-form
            {:style {:height (when (or sent? @loading?) 0)}}
            [ui/input-text
             :placeholder "Name"
             :width "50%"
             :path [:form-field [:contact :name]]]
            [ui/input-text
             :input-type :input.email
             :type "email" :placeholder "Email"
             :width "50%"
             :path [:form-field [:contact :email]]]
            [ui/input-text
             :placeholder "Title"
             :width "100%"
             :path [:form-field [:contact :title]]]
            [ui/appear-anon "slide-in slow"
             [ui/input-text
             :placeholder "Message"
             :input-type :textarea
             :width "100%"
             :height "15em"
             :min-rows 15
             :path [:form-field [:contact :message]]]]
            (let [disabled? (or (string/blank? (:email @contents))
                                (not (string/index-of (:email @contents) "@"))
                                (string/blank? (:message @contents)))]
              [:div.flex
               [:div 
                {:on-mouse-enter #(reset! submit-hovered? true)
                 :on-mouse-leave #(reset! submit-hovered? false) }
                [:input
                {:type "submit" :id "submit-contact"
                 :disabled disabled?
                 :title (when-not disabled? "Ready to go!")
                 :on-click #(rf/dispatch [:contact/send-request])}]]
               (when (and disabled? @submit-hovered?) ; mouseLeave never fires (wtf??) but still good enough I suppose
                 [ui/appear-merge "slide-in"
                  [:label {:for "submit-contact"}
                   "Must enter at least email and message"]])])]])))))

(defn contact-ways [email]
  (let [show-mail-form? @(rf/subscribe [:state [:contact-form :show?]])]
    [:div
     [contact-form-popup]
     [:h4 
      [:span [:a {:href (str "mailto:" email)
                  :style {:font-size "85%"}}
              email]]
      [:span {:style {:color "var(--fg-6)"}}
       " | "]
      [:button.nomargin.nopadding.noborder
           {:title "Contact us by form"
            :on-click #(rf/dispatch (if show-mail-form? [:contact/close] [:contact/open]))
            :style {:color "var(--fg-5)"}}
           [:i.fas.fa-envelope]]]]))
            
(defn footer-content "Upper content (first few rows) of footer"
  [content]
  [:div.footer-content ;; XXX should adapt to available height, also disappear...
   (for [{:keys [title email text id links logo] :as column} content
         :let [id (str "footer-" id)]] ^{:key id}
     [:div.footer-column {:id id}

      (when logo
        [:img.img-icon logo])
      [:div
       (when title [:h4 title])
       (when email [contact-ways email])
       (when text (for [line text] ^{:key (str id "-" line)}
                    [:h5 line]))]
      (when links [:div.footer-icons
                   (for [{:keys [name href icon]} links] ^{:key (str "footer-link-" name)}
                     [:a {:href href :name name}
                      [:i.fab {:class (str "fa-" icon)}]])])])])


(defn post-footer "Extra stuff after the basic footer. Not very useful for me but for other sites."
  [content]
  [:div.footer-content.post-footer-content ;; XXX should adapt to available height, also disappear...
    (for [{:keys [title text id links img] :as column} content
          :let [id (str "post-footer-" id)]] ^{:key id}
         [:div.footer-column {:class (:id column)
                              :id id}

          (when title [:h4 title])
          (when text (for [line text] ^{:key (str id "-" line)}
                          [:h5 line]))
          (when links [:div.footer-links
                       (for [{:keys [name href info]} links] ^{:key (str "post-footer-link-" name)}
                            [:a {:href href :name name}
                             [:div.footer-link-with-text
                              [:p name] [:p info]]])])
          (when img (for [img img]  ^{:key (str id "-" (:src img))}
                      [:img.img-icon img]))])])


(defn footer "The sticky footer visible at load or when scrolling up."
  [content]
  [:footer#footer-sticky.footer-sticky
   {:class (str (when @(rf/subscribe [:state [:hidden :footer]])
                  "hide ")
                (when @(rf/subscribe [:state [:scroll :at-bottom]])
                  "bottomed ")
                (when @(rf/subscribe [:fullscreen/any?])
                   "adjust-for-fullscreen"))}
   [footer-content content]])

(defn footer-full "Render the full footer at bottom of page"
  [content]
  (when @(rf/subscribe [:state [:hidden :footer]])
  [:footer#footer-end.footer-full
   {:class "full"}
   
   [footer-content content]
   [post-footer @(rf/subscribe [:content [:post-footer]])]]))


(defn to-top "A silly arrow, and twice lol. why." [icon]
 (let [icon (or icon "angle-double-up")
       i [:i {:class (str "fas fa-" icon)}]]
    [:a {:id "to-top" :class "to-top" :href @(rf/subscribe [:href "#main"]) :name "Up"} i]))

(defn settings "Settings panel for theme and stuff"
  []
  (let [open? @(rf/subscribe [:state [:settings :panel-open]])
        vars {:line-width         {:unit "px"   :min 0     :max 15}
              :line-width-vert    {:unit "px"   :min 0     :max 15}
              :section-rounded    {:unit "%"    :min 0     :max 10}
              :space              {:unit "rem"  :min 0.0   :max 4.0 :step 0.1}
              :space-lg           {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}
              :space-top          {:unit "rem"  :min 0.0   :max 6.0 :step 0.1}}]
    [:div.settings-panel
     {:class (when open? "opened")
      :style {:position :sticky }}
     
     [:h2 [:i {:class "fa fa-cog"}] " Settings"]
     [:div
      [:button {:on-click #(rf/dispatch [:toggle-class! nil "theme-light"])}
       "Light/dark"]]

     [:div.settings-numbers
      (doall (for [[k {:keys [unit min max step]}] vars]
        ^{:key (str "settings-input-var-" (name k))}
        [:div.settings-number
         [:input
          {:id (str (name k) "-input")
           :type :number
           :min min :max max :step step
           :default-value (str (js/parseFloat @(rf/subscribe [:get-css-var (name k)])))
           :on-change #(rf/dispatch [:->css-var! (name k) (-> % .-target .-value (str unit))])}]
         [:label {:for (str (name k) "-input")}
          (-> (name k)
              (string/replace "-" " ")
              (string/capitalize))]]))]
     
     #_[:blog posts per page incl lazy-load option]
     #_[:palette in general?
     #_[:other css vars...]
     #_[:idea to let customize as much as possible and eventually turn into a kinda interactive site-builder]]]))

