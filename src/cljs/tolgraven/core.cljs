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


(defn nav-link [uri title page]
  [:a.navbar-item
   {:href   uri
    :class (when (= page @(rf/subscribe [:common/page])) :is-active)}
   title])

(defn navbar []
  (r/with-let [expanded? (r/atom false)]
              [:nav.navbar.is-info>div.container
               [:menu
                [:a.navbar-item {:href "/" :style {:font-weight :bold}} "tolgraven"]
                [:span.navbar-burger.burger
                 {:data-target :nav-menu
                  :on-click #(swap! expanded? not)
                  :class (when @expanded? :is-active)}
                 [:span][:span][:span]]]
               [:div#nav-menu.navbar-menu
                {:class (when @expanded? :is-active)}
                [:div.navbar-start
                 [nav-link "#/" "Home" :home]
                 [nav-link "#/about" "About" :about]
                 [nav-link "#/docs" "Docs" :docs]
                 [nav-link "#/blog" "Blog" :blog]
                 [nav-link "#/log" "Log" :blog]]]]))
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

(defn page []
  (if-let [page @(rf/subscribe [:common/page])]
    [:div
     [navbar]
     [page]]))


(defn home-page []
  [view/ui])

(defn doc-page []
  [:section.section>div.container>div.content
   (when-let [docs @(rf/subscribe [:docs])]
     [:div {:dangerouslySetInnerHTML {:__html (md->html docs)}}])])

(defn blog-page []
  [blog/blog @(rf/subscribe [:content :blog])])

(defn log-page []
  [ui/log])

(defn navigate! [match _]
  (rf/dispatch [:common/navigate match]))

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
