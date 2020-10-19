(ns tolgraven.core
  (:require
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
    [tolgraven.user.views :as user]
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
   
   [safe [user/user-section @(rf/subscribe [:user/active-section])]]

   (if-let [page @(rf/subscribe [:common/page])]
     [:main.main-content.perspective-top
      {:class (if @(rf/subscribe [:state [:transition]])
                "hidden"; "slide-in slide-out-left" ; hidden
                "visible")}; "slide-in ")} ; visible
      ; should be: outgoing page put at like :common/last-page, plus a flag
      ; render both, wrapped in div each. outgoing starts middle goes left/right,
      ; incoming starts left/right animates to middle (simultaneously)
      ; finish -> flag unset -> kill/novisible last page div.
      [safe [page]]]
     [view/loading-spinner true])

   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/hud (rf/subscribe [:hud])]
   [common/to-top]
   [:a {:name "bottom"}]])


(defn test-page []
  [:<>
   [view/fading-bg-heading (merge @(rf/subscribe [:content [:common :banner-heading]])
                                  {:title "Experiments" :tint "green"})]
   [:section.experiments.solid-bg.fullwide.noborder
    [experiment/parallax]]])

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
               :controllers [{:start (fn [_] (rf/dispatch [:page/init-blog]))}]}]
     ["#about" {:name :story
               :view #'home-page}]
     ["#link-services" {:name :services
               :view #'home-page}]
     ["/log" {:name :log
              :view #'log-page}]
     ["/test" {:name :test
              :view #'test-page}] ]))

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
