(ns tolgraven.core
  (:require
    [day8.re-frame.http-fx]
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [goog.events :as gevents]
    [goog.object :as gobj]
    [goog.history.EventType :as HistoryEventType]
    [markdown.core :refer [md->html]]
    [cljs-time.core :as ct]
    [cljs-time.format :refer [formatters formatter unparse]]
    [cljsjs.smoothscroll-polyfill :as smooth]
    [react-highlight.js :as highlight]
    [cljsjs.highlight :as hljs]

    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.db :as db]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views :as view]
    [tolgraven.views-common :as common]
    [tolgraven.blog.views :as blog]
    [tolgraven.ui.user :as user]
    [tolgraven.experiments :as experiment]
    [reitit.core :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string]
    [clojure.pprint :refer [pprint]])
  (:import goog.History))

(defn safe "Error boundary for components. Also prints/logs error" [component]
 (let [exception (r/atom nil)] ;or reg not ratom or would cause etra re-render?
  (r/create-class
  {:display-name "Error boundary"
   :component-did-catch (fn [this error info] ;this should log instantly tho
                          (reset! exception {:error error :info info})) ; error and info are empty.
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
   (fn [args]
    (if-not @exception   ;state change downstream? then it gets easier to debug "in-page",
     args
     (let [[component state] args]
       (println (pprint ((js->clj component) state))) ;replace with better logging eh...
        [:section.component-failed
          [:p "Component exception"]
          [:pre (str "Error " (:error exception))]
          [:pre (str "Info " (:error exception))]
          [:div
           [:button {:on-click #(reset! exception nil)}
            "Attempt reload"]]])))})))

(defn page "Render active page inbetween header, footer and general stuff." []
  [:<>
   [common/header @(rf/subscribe [:content [:header]])] ;TODO smooth transition to personal
   [:a {:name "linktotop" :id "linktotop"}]

   (if-let [page @(rf/subscribe [:common/page])]
     [:main.main-content.perspective-top
      {:class (if @(rf/subscribe [:state [:transition]])
                "hidden"; "slide-in slide-out-left" ; hidden
                "visible")}; "slide-in ")} ; visible
      [safe [page]]]
     [view/loading-spinner true])

   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/hud (rf/subscribe [:hud])]
   [common/to-top]
   [:a {:name "bottom"}]])


(defn test-page []
  [experiment/parallax])

(defn home-page []
  [view/ui])

(defn doc-page []
  (let [docs @(rf/subscribe [:content [:docs]])]
    [:<>
      [view/fading-bg-heading (:heading docs)]

     (when-let [md (:md docs)]
       [:section.docs.solid-bg.hi-z
        {:ref #(when % (util/run-highlighter! "pre" %))} ; very nice way to get a did-mount
        [ui/md->div md]])]))


(defn blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [:<>
   (let [heading @(rf/subscribe [:content [:blog :heading]]) ]
     [:<>
      [view/fading-bg-heading heading]
      [blog/blog]])])


(defn log-page []
  (let [bg @(rf/subscribe [:content [:common :banner-heading]])]
    [:<>
     [view/fading-bg-heading (merge bg {:title "Log" :tint "blue"})]
     [ui/log (rf/subscribe [:option [:log]])
      (rf/subscribe [:get :diagnostics])]]))

; (defn login "Should be modal and trigger whenever needed, then popped and state same..."
;   []
;   [])

; ideally would be "component requiring login sesh" wrapper
; then all these make check (vs server, nyah spoof!) and trigger...
; actually makes most sense to do nav to where should (if full nav)
; then login on top _after_
; point is dont want to boiler every single thing yeah
;
(defn user-page "Should lead either to user page, or login-modal.
                 (or full-page, can work anyways w rfe/push-state or?)" []
  (let [login @(rf/subscribe [:state :login-session])]
    (if (:logged-in login)
      [user/user]
      [])))

(defn reg-page "Should lead either to user page, or login-modal" []
  (let [login @(rf/subscribe [:state :login-session])]
    [user/register]))


(def router ; XXX weird thing doesnt automatically scroll to top when change page...
  (reitit/router
    [["/"
      {:name        :home
       :view        #'home-page
       :controllers [{:start (fn [_]
                               (rf/dispatch [:state [:is-personal] true])
                               (rf/dispatch [:page/init-home]))
                      :stop (fn [_]
                              (util/log "exit home")
                              (rf/dispatch [:state [:is-personal] true]))}]}]
     ["/docs" {:name :docs
               :view #'doc-page
               :controllers [{:start (fn [_] (doall (map rf/dispatch [[:page/init-docs]
                                                                      [:state [:is-personal] true]])))}]}] ; really overkill this triggers each time. gotta be built-in solution somewhere? else work around
     ["/blog" {:name :blog
               :view #'blog-page
               }]
     ["#about" {:name :story
               :view #'home-page
               }]
     ["#link-services" {:name :services
               :view #'home-page
               }]
     ["/log" {:name :log
              :view #'log-page}]
     ["/test" {:name :test
              :view #'test-page}]
     ["/user" {:name :user
               :view #'user-page}]
     ["/register" {:name :register
                   :view #'reg-page}]]
    ))

(defn start-router! []
  (rfe/start!
    router
    (fn [match _] (rf/dispatch [:common/start-navigation match]))
    {:ignore-anchor-click? (fn [router e el uri] ;; Add additional check on top of the default checks
                           (and (rfh/ignore-anchor-click? router e el uri)
                                (not= "false" (gobj/get (.-dataset el) "reititHandleClick"))))}))
                                ; (not= "false" (HistoryEventType/get (.-dataset el) "reititHandleClick"))))}))
;; -------------------------
;; Initialize app
(defn mount-components "Called each update when developing" []
  (rf/clear-subscription-cache!)
  (rdom/render [#'page] (.getElementById js/document "app")))

(defn init! "Called only on page load" []
  (start-router!)
  (rf/dispatch-sync [:init-db])
  (ajax/load-interceptors!)
  (util/log "Init complete, mounting root component")
  (mount-components))
