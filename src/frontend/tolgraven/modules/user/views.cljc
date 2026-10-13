(ns tolgraven.modules.user.views
  #?(:cljs (:require
    [tolgraven.supabase.schema :as schema]
    [tolgraven.component.registry]
    [tolgraven.react :as r]

    [tolgraven.component]
    [tolgraven.macros :as m :refer-macros [defc]]
    [tolgraven.components.image :as img]
    [tolgraven.loader :as l]
    [tolgraven.components.ui.controls :as ui-controls]
    [tolgraven.components.ui.inputs :as ui-inputs])))

#?(:cljs (do

(defc <back-btn> [] ;tho ideally we push states and pop them... so becomes, yeah
  (when (< 1 (count @(r/subscribe [:user/active-section])))
    [:button.user-back-btn.noborder.showing
     {:on-click #(r/dispatch [:user/to-last-section])
      :style {:position :absolute :left 0 :top 0}}
     [:i.fa.fa-chevron-left]]))

(defc <password-input> [& {:keys [placeholder path]
                         :or {placeholder "Password"
                              path [:form-field [:login :password]]}}]
  [ui-inputs/<input-text>
    :type (when-not @(r/subscribe [:state [:login-show-password]]) :password)
    :placeholder placeholder
    :attr {:autoComplete "password"}
    :path path])

(defc <sign-in-input> "Sign in component" []
  [:section>form
   [ui-inputs/<input-text>
    :placeholder "Email"
    :attr {:autoComplete "email"}
    :path [:form-field [:login :email]]]
   [:br]
   [<password-input>]
   [ui-controls/<toggle> [:state :login-show-password] "show"]])


(defc <sign-in> "Sign in or go to reg page"
  {:icons ["brands/google" "brands/github" "brands/facebook"]}
  []
  (let [disabled? (not @(r/subscribe [:login/valid-input?]))
        providers @(r/subscribe [:option [:supabase :providers]])
        with (fn [provider]
               (when (get providers provider)
                 [:button {:on-click #(r/dispatch [:user/sign-in provider])}
                  [:i.fab {:class (str "fa-" (name provider))}]]))]
    [:div.user-inner.noborder
     [:h2 "Please log in"]
     [<sign-in-input>]

     (when-let [error @(r/subscribe [:user/error])] ;should be a sub
       [:<>
        [:span {:style {:padding-top "0em" :color "var(--red)"}} "Error"]
        [:span ": " error] [:br]])

     [:div.user-sign-in-btns
      [:div
       [:button
        {:on-click #(r/dispatch [:user/request-login])
         :disabled disabled?
         :class (when disabled? "noborder")}
        "Sign in"]
       [:span "or "]

       [:button {:on-click #(r/dispatch [:user/request-register])
                 :disabled disabled?
                 :class (when disabled? "noborder")}
        "Register"]]
      [:div "or sign in without registration" [:br]
       [with :google] [with :github] [with :facebook]]]]))

(defc <register> "Registration component" [user]
  [:div.user-inner.user-register
   [:h2 "Register"]
   [<sign-in-input>] ;well need different validation here (not exists etc)
   [ui-inputs/<input-text>
    :path [:form-field [:register :email]]
    :placeholder "Email"]

   [:button
    {:on-click #(r/dispatch [:user/request-register])}
    "Sign up"] ])

(defc <profile> "User profile page" [user-id]
  [:div "USER PROFILE"])

(defc <section> "Wrap thing in user-inner etc"
  [heading & components]
  [:div.user-inner
   (when heading [:h2 heading])
   (into [:section] components)])

#?(:feature/blog-comments (defc <comments> "User comments page" [user]
  [<section> "User comments" ;will need to save comment id's to user when make new ones.
   (let [comments @(r/subscribe [:comments/for-user-q (:id user)])]
     (doall (for [{:keys [id title text ts score] :as comment} (vals comments)] ^{:key (str "user-" (:id user) "-comment-" id)}
              [:div.blog-comment>div.blog-comment-main
               [:h4.blog-comment-title title]
               (m/<> :blog/posted-by {:id id :user user :ts ts :score score})
               [:div.blog-comment-text
                (m/<> :link-preview/md text)]]))) ]))


(defc <change-password> "Change user password" [user]
  [<section> "Change password"
   [:form
    [<password-input> :placeholder "Current password"
     :path [:form-field [:change-password :current]]]
    [<password-input> :placeholder "New password"
     :path [:form-field [:change-password :new]]]
    [ui-controls/<toggle> [:state :login-show-password] "show"]]
   [:br]
   [:button
    {:on-click #(r/dispatch [:user/request-change-password])}
    "Change password"] ])

(defc <change-username> "Change username" [user]
  [<section> "Change username"
   [:p "Current username: " (:name user)]
   [ui-inputs/<input-text>
    :path [:form-field [:change :username]]
    :placeholder "New username"]
   [ui-controls/<button> "Change" :change-username
              :action #(r/dispatch [:user/set-field
                                     (:id user) :name
                                     @(r/subscribe [:form-field [:change :username]])])]])
(declare <avatar>)
(defc <change-avatar> "Change avatar" [user]
  [<section> nil
   [<avatar> user false]
   [:br]
   [:span "Upload file "]
   [:input {:type "file" :id "file" :name "file"
            :on-change
            #(r/dispatch [:user/upload-avatar
                           (-> % .-target .-files (aget 0))])}]
   [:br]
   [:span "Or from url "]
   [ui-inputs/<input-text>
    :path [:form-field [:change :avatar-url]]
    :placeholder "URL"]
   [ui-controls/<button> "Change" :change-avatar-url
    :action #(r/dispatch [:user/set-field (:id user) :avatar
                                          @(r/subscribe [:form-field [:change :avatar-url]])])]])
(declare <user-avatar>)
(defc <avatar> "Display user avatar and option to change it"
  [user-map allow-edit?]
  [:div.user-avatar-wrapper
   (when allow-edit?
     [:div.user-avatar-change
      [:i.fa.fa-edit {:on-click #(r/dispatch [:user/active-section :change-avatar])}]])
   [<user-avatar> (merge user-map {:no-zoom true})] ])

(defc <admin> "User admin page" [user]
  (let [admin? @(r/subscribe [:user/has-role? :admins])
        section-btn (fn [text k section]
                      [ui-controls/<button> text k
                                 :action #(r/dispatch [:user/active-section section])])]
    [:div.user-inner
     [:section
      [:div.flex
       [<avatar> user true]
       [:div.user-info
        [:h3 {:style {:display :inline}}
         (:name user)]
        (when admin? ; some way to sep / hl this...
           [:span {:style {:font-size "80%"}}
            "admin"])
        [:span [:em (:email user)]]
        #?(:feature/blog-comments [:p {:style {:font-size "80%"}}
         (:karma user) " karma"])
        #?(:feature/blog-comments [:button
         {:on-click #(r/dispatch [:user/active-section :comments])}
         (str (or (:comment-count user) 0) " comments (view)")])]]]

     [:div.user-change-options
      {:style {:position :relative}}
      [:span "Change "]
      [section-btn "Username"  :username :change-username]
      [section-btn "Password"  :password :change-password]

     [:button.border
      {:on-click #(r/dispatch [:user/sign-out])
       :style {:position "relative" :right 0}}
      "Log out"]]]))


(defc <user-avatar>
  "Render one avatar. Reserve its space while the profile is pending; use the
   default only for a resolved profile without an avatar or a failed image."
  [user-map :- [:maybe schema/profile] & [extra-class] :- :?string]
  (r/with-let [*failed-sources (r/atom #{})]
    (let [fallback @(r/subscribe [:user/default-avatar])
          avatar (:avatar user-map)
          src (when user-map
                (if (contains? @*failed-sources avatar) fallback (or avatar fallback)))
          zoom? (and avatar (= src avatar) (not (:no-zoom user-map)))
          *image (r/use-ref nil)]
      (r/use-effect
        (fn []
          ;; SSR images may fail before hydration attaches on-error. Wait for
          ;; Reagent's mount commit before updating its local reactive state.
          ;; <picture> still owns retries of failed modern sources.
          (let [frame (js/requestAnimationFrame
                        (fn [_]
                          (let [image (.-current *image)]
                            (when (and image avatar (= src avatar)
                                       (.-complete image) (zero? (.-naturalWidth image))
                                       (= (.-currentSrc image)
                                          (.-href (js/URL. src (.-baseURI image)))))
                              (swap! *failed-sources conj avatar)))))]
            #(js/cancelAnimationFrame frame)))
        [avatar src])
      [:div.user-avatar-container
       ;; A fallback overlay flashes during hydration even when SSR already
       ;; knows the author. Keep a single image and let the browser load it.
       [img/<picture>
        {:class (str "user-avatar " extra-class)
         :src src
         :ref *image
         :on-error (fn [_] (when avatar (swap! *failed-sources conj avatar)))
         :alt (if user-map (str (:name user-map) " profile picture") "")
         :on-click (when zoom?
                     #(r/dispatch [:modal-zoom :fullscreen :open
                                    [img/<picture> {:src src :alt "Profile picture"}]]))
         :style (cond-> {}
                  (nil? src) (assoc :visibility "hidden")
                  zoom? (assoc :cursor "pointer"))}]])))

(defc <user-btn> {:icons ["solid/user"]}
  [model]
  [:a.button.user-btn.noborder
   {:href @(r/subscribe [:href-add-query
                          {:userBox (not @(r/subscribe [:user/ui-open?]))}])
    :aria-label "User account"
    :title "User account"}
    (if-let [user @(r/subscribe [:user/active-user])]
      [<user-avatar> (merge user {:no-zoom true}) "btn-img"]
      [:i.user-btn {:class "fa fa-user"}])])


(defc <user-box> "Wrapper for user views"
  [user component]
  [:section.noborder
   [<back-btn>]
   [:a {:href @(r/subscribe [:href-add-query {:userBox (not @(r/subscribe [:user/ui-open?]))}])}
    [:button.close-btn.noborder
     [:i.fa.fa-times]]]
   (when (not= component :none)
     [component user])])

(defc <user-panel>
  {:features [[:appear nil] [:exit {:timeout-ms 1000}]]}
  [{:keys [section user] :as spec}]
  [:div.user-section-wrapper.stick-up.hi-z
   [:div.user-section
    [<user-box> user
     (case section
       :login <sign-in>
       :register <register>
       :admin <admin>
       :comments #?(:feature/blog-comments <comments> :default :none)
       :change-avatar <change-avatar>
       :change-password <change-password>
       :change-username <change-username>
       :none)]]])

(defc <user-section> {:features [:error-boundary :presence]} []
  (let [sections @(r/subscribe [:user/active-section])
        user @(r/subscribe [:user/active-user])]
    [:<>
     (when (and (seq sections) (not (some #{:closed} sections)))
       ;; Presence retains these inputs while closing, so the outgoing panel
       ;; keeps its contents without a second app-db transition or timer event.
       ^{:key :user-panel} [<user-panel> {:section (last sections) :user user}])]))

))
