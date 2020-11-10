(ns tolgraven.core
  (:require
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [re-frame.core :as rf]
    ; [kee-frame.core :as k]
    [com.degel.re-frame-firebase :as firebase]

    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.db :as db]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views :as view]
    [tolgraven.views-common :as common]
    [tolgraven.blog.views :as blog]
    [tolgraven.user.views :as user]
    [tolgraven.experiments :as experiment]
    [reitit.core :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string]
    [clojure.pprint :refer [pprint]])
  (:import goog.History))

(defn swapper "Swap between outgoing and incoming page view"
  [class comp-in comp-out]
  (let [swap @(rf/subscribe [:state [:swap]])]
    [:div.swapper
     [:div.swap-in
      {:class (str class " "
                   (when (or (not comp-out)
                             (:running swap))
                     "swapped-in")) }
      comp-in] ;will have to be behind for z then revealed by curr page moving out the way.
     (when comp-out
       [:div.swapped
        {:class (str class " "
                     (when (:running swap)
                       "swapped-out") " "
                     (when (:finished swap)
                       "removed") " ")
         :ref #(when % (rf/dispatch [:swap-trigger %]))} ; trigger anim out and deferred hiding
        ; getting duplicate triggers for some reason.
        comp-out])]))

(defn page "Render active page inbetween header, footer and general stuff." []
  [:<>
   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/safe :user [user/user-section @(rf/subscribe [:user/active-section])]]
   
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; TODO any time do nav or like trigger :is-loading, start timer, if not flag done set within timeout, also error
     [error-page]
     (if-let [page @(rf/subscribe [:common/page]) ]
       (let [page-prev @(rf/subscribe [:common/page :last]) ; prob rather ought to alternate bc transfering everything before transition seems dumb...
             swap @(rf/subscribe [:state [:swap]])]
         [:main.main-content.perspective-top
          [swapper "opacity"
                   [ui/safe :page [page]]
                   (when page-prev
                     [ui/safe :page-prev [page-prev]])]])
       [common/loading-spinner true :massive]))

   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]
   [:a {:name "bottom"}]])


(defn with-heading
  [heading-path component & [override]]
  [:<>
   [ui/fading-bg-heading (merge @(rf/subscribe [:content heading-path])
                                  override)]
   component])

(defn test-page []
  [with-heading [:common :banner-heading]
   (let [routes {:parallax experiment/parallax :codemirror experiment/code-mirror :broken [:div]}
          tab @(rf/subscribe [:state [:experiments]])]
     [:section.experiments.solid-bg.fullwide.noborder
      [:ul.tabs-container.flex
       (for [tab-key [:parallax :codemirror :broken]] ^{:key tab-key}
         [:li [:button {:class (if (= tab tab-key) "bottomborder" "topborder")
                        ; :on-click #(rf/dispatch [:state [:experiments] tab-key])}
                        :on-click #(rf/dispatch [:common/navigate! :test-tab {:tab tab-key}])}
               tab-key]])]
      [ui/safe :experiments [(tab routes)]]])
    {:title "Experiments" :tint "green"}])


(defn doc-page []
  (let [docs @(rf/subscribe [:content [:docs]])]
    [with-heading [:docs :heading]
     [:section.docs.solid-bg.hi-z
      {:ref #(when % (util/run-highlighter! "pre" %))} ; very nice way to get a did-mount
      [ui/md->div (:md docs)]]]))


(defn blog-page []
  [with-heading [:blog :heading] [blog/blog-container [blog/blog-feed]]])
(defn post-blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [with-heading [:blog :heading] [blog/post-blog]])
(defn blog-archive-page []
  [with-heading [:blog :heading] [blog/blog-archive]])
(defn blog-post-page []
  [with-heading [:blog :heading] [blog/blog-single-post]])

(defn log-page []
  [with-heading [:common :banner-heading]
   [ui/log (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(defn not-found-page []
  [with-heading [:common :banner-heading]
   [:div.center-content
    [:br] [:p "Four, oh four. Nothing to see here, move along."]]
   {:title "Not found" :tint "red"}])

(def router ; XXX weird thing doesnt automatically scroll to top when change page...
  (reitit/router
    ["/"
     [""
      {:name        :home
       :view        #'view/ui
       :controllers [{:start (fn [_]
                               (rf/dispatch [:page/init-home]))
                      :stop (fn [_]
                              (rf/dispatch [:state [:is-personal] true]))}]}]
     ["docs" {:name :docs
               :view #'doc-page
               :controllers [{:start (fn [_] (doall (map rf/dispatch [[:page/init-docs]
                                                                      [:state [:is-personal] true]])))}]}] ; really overkill this triggers each time. gotta be built-in solution somewhere? else work around
     ["blog" {:controllers [{:start (fn [_] (rf/dispatch [:page/init-blog]))}] }
      [""     {:name :blog
               :view #'blog-page
               :controllers [{:start (fn [_] (rf/dispatch [:blog/set-posts-per-page 3]))}]}] ; should also allow 0 = infinite scroll
      ["/page/:nr"
       {:name :blog-page
        :view #'blog-page
        :controllers
        [{:parameters {:path [:nr]}
          :start (fn [{:keys [path]}]
                   (rf/dispatch [:blog/nav-page (:nr path)]))
          :stop (fn [{:keys [path]}]
                  (js/console.log "stop" "blog-page controller" (:nr path)))}]}]
      ["/post/:permalink"
       {:name :blog-post
        :view #'blog-post-page
        :controllers
        [{:parameters {:path [:permalink]}
          :start (fn [{:keys [path]}]
                   (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                     (rf/dispatch [:blog/state [:current-post-id] id])))
          :stop (fn [{:keys [path]}]
                  (js/console.log "stop" "blog-post controller" (:permalink path)))}]}]
      ["/archive" {:name :blog-archive
                   :view #'blog-archive-page}]]
     ["post-blog" {:name :post-blog
                   :view #'post-blog-page
                   :controllers [{:start (fn [_] (rf/dispatch [:page/init-post-blog]))}]}]        
     ["log" {:name :log
              :view #'log-page}]
     ["test" 
      ["" {:name :test
           :view #'test-page}]
      ["/:tab"
       {:name :test-tab
        :view #'test-page
       :controllers [{:parameters {:path [:tab]}
                      :start (fn [{:keys [path]}]
                               (rf/dispatch [:state [:experiments] (keyword (:tab path))])
                               (rf/dispatch [:exception [:experiments] nil]))}]}]]
     ["not-found" {:name :not-found
                   :view #'not-found-page}]
     {:data {:controllers [{:start (util/log :debug "start" "root-controller")
                            :stop  (util/log :debug "stop" "root controller")}]}}]))

(defn on-nav [match _]
  (util/log :debug "Match:" match)
  (if match ; cant do fallback route in router apparently, but we get nil matches so can use that
    (rf/dispatch [:common/navigate match])
    (rf/dispatch [:state [:error-page] not-found-page])))

(def router-settings
  {:use-fragment true ;doesnt do nuffin without (tho still takes over) so dunno point?
   :ignore-anchor-click? rfh/ignore-anchor-click?})

(defn start-router! []
  (rfe/start! router on-nav router-settings))

(def cookie-notice [:diag/new :info "Cookie notice"
                     {:what "Better way?"
                      :than :like-this
                      3 {:just "add buttons"}}
                     {:sticky? true}])

(def firebase-app-info
  {:apiKey        "AIzaSyBsqgWFXZfLq4W8FPRfSVd3AzSt183w9HQ"
   :projectId     "tolgraven-8fd35"
   :authDomain    "tolgraven-8fd35.firebaseapp.com"
   :databaseURL   "https://tolgraven-8fd35.firebaseio.com"
   :storageBucket "tolgraven-8fd35.appspot.com"})
(def firebase-app-info-test
  {:apiKey        "AIzaSyAvjzBy68cWrdbLGOs8Yx4hD2JkfoHhjAg"
   :projectId     "tolgraven-test"
   :authDomain    "tolgraven-test.firebaseapp.com"
   :databaseURL   "https://tolgraven-test.firebaseio.com"
   :storageBucket "tolgraven-test.appspot.com"})

(defn init-firebase []
  (firebase/init :firebase-app-info      (case @(rf/subscribe [:option [:firebase :project]])
                                           :main firebase-app-info
                                           :test firebase-app-info-test)
                 :firestore-settings     @(rf/subscribe [:option [:firebase :settings]]) ; Shouldn't be used on later versions. See: https://firebase.google.com/docs/reference/js/firebase.firestore.Settings
                 :get-user-sub           [:fb/get-user]
                 :set-user-event         [:fb/set-user]
                 :default-error-handler  [:fb/error])
  (rf/dispatch [:fb/fetch-users])) ; or more like, dont wait til blog init but can also defer a bit...

;; -------------------------
;; Initialize app
(defn mount-components "Called each update when developing" []
  (rf/clear-subscription-cache!)
  (rf/dispatch-sync [:exception nil])
  (rf/dispatch [:reloaded])
  (rdom/render [#'page] (.getElementById js/document "app")))

(defn init! "Called only on page load" []
  (start-router!)
  (rf/dispatch-sync [:init-db])
  (ajax/load-interceptors!)

  (init-firebase)
  (rf/dispatch [:init]) ; dispatch firebase general fetches, test case is silly id counter persistence. which then needs to be fetched into app-db once ready
  (rf/dispatch cookie-notice)
  (util/log "Init complete, mounting root component")
  (mount-components))

