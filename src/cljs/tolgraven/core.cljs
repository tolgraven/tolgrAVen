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
    [react-highlight.js :as highlight]
    [cljsjs.highlight :as hljs]

    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.db :as db]
    [tolgraven.subs :as sub]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views :as view]
    [tolgraven.blog :as blog]
    [reitit.core :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string])
  (:import goog.History))


(defn page []
  [:<>
   [view/ui-header @(rf/subscribe [:content :header])]

   [:div.line.line-header] ; XXX oh yeah only actually outside header bc silly css tricks to get shit to play along. so, fuck that, and get it within

   (if-let [page @(rf/subscribe [:common/page])]
     [:main.main-content.perspective-top
      [page] ; XXX should limit the lines-all-around stuff to certain subpages.
       ; apart from when edges very tiny it looks v cool with just horiz lines and vert straight cutoff. adds to understated glossy maximinimalist vibe going for... and sorta less "sony presents"
       [:div.footer-spacer]]) ; could post-main spacer-for-footer go in footer part somehow? but makes sense in main afa DOM, just irks
   [view/ui-footer @(rf/subscribe [:content :footer])]
   [ui/hud]
   [view/ui-to-top]
   [:a {:name "bottom"}]])


(defn about-page []
  [:section.section>div.container>div.content
   [:img {:src "/img/warning_clojure.png"}]])

(defn home-page []
  [view/ui])

(defn doc-page []
  (let [docs @(rf/subscribe [:content :docs])]
    [:<>
     [:div {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
      [view/fading-bg-heading docs]]
     [:div.fader>div.fade-to-black.between]
     [:br] [:br]
     (when-let [md (:md docs)]
       [:section.solid-bg.hi-z
        {:ref #(when % (util/run-highlighter! "pre" %))} ; very nice way to get a did-mount
        [ui/md->div md]])]))

(defn blog-page []
  [blog/blog @(rf/subscribe [:content :blog])])

(defn log-page []
  [ui/log])


(def router ; XXX weird thing doesnt automatically scroll to top when change page...
  (reitit/router
    [["/"
      {:name        :home
       :view        #'home-page
       :controllers [{:start (fn [_] (rf/dispatch [:page/init-home])
                                    (rf/dispatch [:set [:state :is-personal] false]))
                      :stop (fn [_]
                              (rf/dispatch [:set [:state :is-personal] true]))}]}]
     ["/about" {:name :about
                :view #'about-page}]
     ["/docs" {:name :docs
               :view #'doc-page
               :controllers [{:start (fn [_] (rf/dispatch [:init-docs]))}]}] ; really overkill this triggers each time. gotta be built-in solution somewhere? else work around
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
              :view #'log-page}]]))

(defn start-router! []
  (rfe/start!
    router
    (fn navigate! [match _]
      (rf/dispatch [:common/navigate match]))
    ; {:ignore-anchor-click? rfh/ignore-anchor-click?}))
    {:ignore-anchor-click? (fn [router e el uri]
                           ;; Add additional check on top of the default checks
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
  (mount-components))
