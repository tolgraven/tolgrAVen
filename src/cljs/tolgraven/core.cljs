(ns tolgraven.core
  (:require
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.degel.re-frame-firebase :as firebase]
    [goog.events :as gevents]
    [goog.object :as gobj]
    [goog.history.EventType :as HistoryEventType]
    [markdown.core :refer [md->html]]
    [cljs-time.core :as ct]
    [cljs-time.format :refer [formatters formatter unparse]]
    [cljsjs.smoothscroll-polyfill :as smooth]
    [cljsjs.highlight :as hljs]
    [react-transition-group :as react-transition-group]

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

(defn safe "Error boundary for components. Also prints/logs error"
  [category & children]
 (let [exception (rf/subscribe [:exception [category]])] ;or reg not ratom or would cause etra re-render?
  (r/create-class
  {:display-name (str "Boundary: " (name category))
   :component-did-catch (fn [error info] ;apparently no :this oi!
                          (util/log :error "Component" (pr-str info))
                          (rf/dispatch [:exception [category]
                                        {:error error :info info}])) ; error and info are empty.
    ; :get-derived-state-from-error ;"defined as instance method and will be ignored. define as static"
   ; (fn [error] ;this should update state to serve like, an error page (for render) "if using getDerivedState methods, the state has to be plain JS object as React implementation uses Object.assign to merge partial state into the current state."
   ;  (clj->js ;cant get it working. but is supposed to used this, console warns...
   ;   [:div.component-failed
   ;    [:p "Component exception:"]
   ;    [:pre (str "Error: " (:error exception)
   ;               "\nInfo: " (:info exception))]
   ;    [:div "Click to attempt reload"
   ;     [:button {:on-click #(reset! exception nil)}]]]))
   :reagent-render
   (fn [category component]
    (if-not @exception   ;state change downstream? then it gets easier to debug "in-page",
     component
     (let [[component state] component] ;cant remember why this is
        [:section.component-failed
          [:p "Component exception"]
          [:pre (-> @exception :info pr-str pprint)]
          [:div
           [:button {:on-click #(rf/dispatch [:state [:exception category] nil])}
            "Attempt reload"]]])))})))


(defn page "Render active page inbetween header, footer and general stuff." []
  [:<>
   [safe :header [common/header @(rf/subscribe [:content [:header]])]] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]
   
   [safe :user [user/user-section @(rf/subscribe [:user/active-section])]]

   (if-let [page @(rf/subscribe [:common/page])]
     [:main.main-content.perspective-top
      {:class (if @(rf/subscribe [:state [:transition]])
                "hidden"; "slide-in slide-out-left" ; hidden
                "visible")}; "slide-in ")} ; visible
      ; should be: outgoing page put at like :common/last-page, plus a flag
      ; render both, wrapped in div each. outgoing starts middle goes left/right,
      ; incoming starts left/right animates to middle (simultaneously)
      ; finish -> flag unset -> kill/novisible last page div.
      [safe :page [page]]] ; this doesnt reload when switch page. need to do manually...
     ; so will need to track in db not ratom, then reset on nav.
     ; also get-css-var still ticks every 500ms whenever safe triggers/any unhandled errors(?)... wtf?
     [common/loading-spinner true :massive])

   [common/footer @(rf/subscribe [:content [:footer]])]
   [safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]
   [:a {:name "bottom"}]])


(defn with-heading
  [heading-path component & [override]]
  [:<>
   [view/fading-bg-heading (merge @(rf/subscribe [:content heading-path])
                                  override)]
   component])

(defn test-page []
  [:<>
   [view/fading-bg-heading (merge @(rf/subscribe [:content [:common :banner-heading]])
                                  {:title "Experiments" :tint "green"})]
   [:section.experiments.solid-bg.fullwide.noborder
    (let [routes {:parallax #'experiment/parallax :codemirror #'experiment/code-mirror}
          tab @(rf/subscribe [:state [:experiments]])]
    [:ul.tabs-container.flex
     (for [tab-key [:parallax :codemirror :broken]] ^{:key tab-key}
            [:li [:button {:class (if (= tab tab-key)
                                    "noborder"
                                    "topborder")
                           :on-click #(rf/dispatch [:state [:experiments] tab-key])}
                  tab-key]])])]])

(defn home-page []
  [view/ui])

(defn doc-page []
  (let [docs @(rf/subscribe [:content [:docs]])]
    [with-heading [:docs :heading]
     [:section.docs.solid-bg.hi-z
      {:ref #(when % (util/run-highlighter! "pre" %))} ; very nice way to get a did-mount
      [ui/md->div (:md docs)]]]))


(defn blog-page []
  [with-heading [:blog :heading]
   [blog/blog]])

(defn post-blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [with-heading [:blog :heading] 
   [blog/post-blog]])

(defn blog-archive-page []
  [with-heading [:blog :heading]
   [blog/blog-archive]])

(defn blog-post-page []
  [with-heading [:blog :heading]
   [blog/blog-single-post]])

(defn log-page []
  [with-heading [:common :banner-heading]
   [ui/log (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(defn broken-link []
  [with-heading [:common :banner-heading]
   [:p "Nothing to see here, move along."]
   {:title "404" :tint "red"}])

(def router ; XXX weird thing doesnt automatically scroll to top when change page...
  (reitit/router
    ["/"
     [""
      {:name        :home
       :view        #'home-page
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
               :controllers [{:start (fn [_] (rf/dispatch [:blog/set-posts-per-page 2]))}]}]
      ["/page/:nr"
       {:name :blog-page
        :view #'blog-page
        :controllers
        [{:parameters {:path [:nr]}
          :start (fn [{:keys [path]}]
                   (rf/dispatch [:blog/set-posts-per-page 1])
                   (rf/dispatch [:blog/nav-page (dec (:nr path))]))
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
     ["test" {:name :test
              :view #'test-page}]
     ; ["{*path}" {:name :404
     ;               :view #'broken-link}]
     {;:conflicts nil
      :data {:controllers [{:start (util/log :debug "start" "root-controller")
                            :stop  (util/log :debug "stop" "root controller")}]}}]))



(defn start-router! []
  (rfe/start!
    router
    (fn [match _]
      (when match (rf/dispatch [:common/start-navigation match])))
    {:use-fragment true ;doesnt do nuffin without (tho still takes over) so dunno point?
     :ignore-anchor-click? rfh/ignore-anchor-click?}))

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
  (rf/dispatch [:id-counters/fetch]) ; dispatch firebase general fetches, test case is silly id counter persistence. which then needs to be fetched into app-db once ready
  (rf/dispatch cookie-notice)
  (util/log "Init complete, mounting root component")
  (mount-components))

