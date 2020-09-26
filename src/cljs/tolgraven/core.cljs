(ns tolgraven.core
  (:require
    [day8.re-frame.http-fx]
    [reagent.dom :as rdom]
    [reagent.core :as r]
    [re-frame.core :as rf]
    [goog.events :as events]
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
    [tolgraven.views :as view]
    [tolgraven.blog :as blog]
    [reitit.core :as reitit]
    [reitit.frontend.easy :as rfe]
    [clojure.string :as string])
  (:import goog.History))


(defn page []
  [:div
    [navbar]
    [view/ui-header @(rf/subscribe [:content :header])]
    [:div.line.line-header] ; XXX oh yeah only actually outside header bc silly css tricks to get shit to play along. so, fuck that, and get it within
    (if-let [page @(rf/subscribe [:common/page])]
      [page])
    [ui/hud]])


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


(def router
  (reitit/router
    [["/" {:name        :home
           :view        #'home-page
           :controllers [{:start (fn [_] (rf/dispatch [:page/init-home]))}]}]
     ["/about" {:name :about
                :view #'about-page}]
     ["/docs" {:name :docs
               :view #'doc-page}]
     ["/blog" {:name :blog
               :view #'blog-page}]
     ["/log" {:name :log
              :view #'log-page}]]))

(defn start-router! []
  (rfe/start!
    router
    navigate!
    {}))

;; -------------------------
;; Initialize app
(defn mount-components []
  (rf/clear-subscription-cache!)
  (rdom/render [#'page] (.getElementById js/document "app")))

(defn init! []
  (start-router!)
  (rf/dispatch-sync [:init-db])
  (ajax/load-interceptors!)
  (mount-components))
