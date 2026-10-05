(ns tolgraven.views.page
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as rf]
    [tolgraven.component :as component]
    [tolgraven.component.restore :as restore]
    [tolgraven.main.module :as main-module]
    [tolgraven.loader :as l]
    [tolgraven.ui :as ui]
    [tolgraven.views-common :as common]))

(def spec main-module/spec)

(defc <page> "Render active page inbetween header, footer and general stuff."
  []
  (let [ext-back? (restore/skip-enter?)
        debug @(rf/subscribe [:state [:debug]])
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [l/<assets> {:css (some-> spec :assets :css)
                :js  (some-> spec :assets :js)}]

   [ui/<safe> :header [common/<header> @(rf/subscribe [:content [:header]])]]
   [:a {:name "linktotop" :id "linktotop"}]

   [ui/<zoom-to-modal> :fullscreen]
   [l/<> {:module :link-preview}]
   [ui/<safe> :user [l/<> {:module :user, :defer? true}]]
   [ui/<safe> :settings [common/<settings>]]
   [ui/<safe> :search [l/<> {:module :search, :defer? true}]]
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       [:main.main-content.perspective-top
        {:id    "main"
         :data-restored (when ext-back? true)
         :data-stream-enter (when (restore/initial-enter?) true)
         :class (str (when (and (not ext-back?)
                                     (= (:page @restore/*context) (restore/page-key)))
                            "animate ")
                     (when (:layers debug) "debug-layers ")
                     (when (:parallax debug) "debug-on"))}
        [ui/<safe> :page [(component/resolve-view page)]
         (:path @(rf/subscribe [:common/route]))]]
       [ui/<loading-spinner> true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   [common/<footer-full> @(rf/subscribe [:content [:footer]])]
   [common/<footer> @(rf/subscribe [:content [:footer]])]
   [ui/<safe> :hud [ui/<hud> (rf/subscribe [:hud])]]
   [common/<to-top>]
   ; [[:div.ripple-on-click
   ;    {:class (when click-evt "ripple")
   ;     :style {:left (str "calc(" (if click-evt
   ;                                  (.-pageX click-evt)
   ;                                  0)
   ;                        "px - " 7 "em)")
   ;             :top (str "calc(" (if click-evt
   ;                                 (.-pageY click-evt)
   ;                                 0)
   ;                       "px - " 5 "em)")}}]common/scrollbar {}]

   [:a {:name "bottom" :id "bottom"}]]))
