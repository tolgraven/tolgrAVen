(ns tolgraven.views.page
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as rf]
    [reagent.core :as r]
    [tolgraven.component :as component]
    [tolgraven.component.restore :as restore]
    [tolgraven.main.module :as main-module]
    [tolgraven.loader :as l]
    [tolgraven.ui :as ui]
    [tolgraven.views-common :as common]))

(def spec main-module/spec)

(defc <swapper> "Keep page instances keyed while moving them through the crossfade."
  [& _]
  (let [*started (atom nil)]
    (fn [class comp-in comp-out current previous]
      (let [swap @(rf/subscribe [:state [:swap]])
            current-key [(:path current) (:query-params current)]
            previous-key [(:path previous) (:query-params previous)]
            transition [previous-key current-key]
            force? (or (empty? class) (nil? comp-out) (= current-key previous-key))
            running? (= transition (:running swap))
            finished? (= transition (:finished swap))
            start! (fn [element]
                     (when (and element (not force?) (not running?) (not finished?)
                                (not= transition @*started))
                       (reset! *started transition)
                       (js/requestAnimationFrame
                        #(js/requestAnimationFrame
                          (fn []
                            (when (and (.-isConnected element) (= transition @*started))
                              (rf/dispatch [:swap/trigger transition])))))))]
        [:div.swapper
         ;; Stable sibling keys retain the actual outgoing DOM/component state.
         ;; Moving an old page into a new wrapper remounted its entrance effects.
         (for [[key active? form] (cond-> [[current-key true comp-in]]
                                  (and comp-out (not force?) (not finished?))
                                  (conj [previous-key false comp-out]))]
           ^{:key (pr-str key)}
           [:div {:class (if active?
                           (str "swap-in " class " " (when (or force? running? finished?) "swapped-in"))
                           (str "swapped " (when running? (str class " swapped-out"))))
                  :ref (when active? start!)}
            form])]))))

(defc <page> "Render active page inbetween header, footer and general stuff."
  []
  (let [ext-back? (or (restore/skip-enter?) @(rf/subscribe [:history/back-nav-from-external?]))
        swap-class (if ext-back? "" "opacity")
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
         :class (str (when-not ext-back? "animate ")
                     (when (:layers debug) "debug-layers ")
                     (when (:parallax debug) "debug-on"))}
        [<swapper> swap-class
         [ui/<safe> :page [(component/resolve-view page)]
          (:path @(rf/subscribe [:common/route]))]
         (when-let [page-prev @(rf/subscribe [:common/page :last])]
           [ui/<safe> :page [(component/resolve-view page-prev)]
            (:path @(rf/subscribe [:common/route :last]))])
         @(rf/subscribe [:common/route])
         @(rf/subscribe [:common/route :last])]]
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
