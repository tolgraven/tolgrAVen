(ns tolgraven.core
  (:require
    [goog.events]
    [re-frame.core :as rf]
    [react :as react]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [tolgraven.ajax :as ajax]
    [tolgraven.events]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m]
    [tolgraven.routes :as routes]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views-common :as common]))

(def spec {:assets {}}) ; global assets

(defn swapper "Swap between outgoing and incoming page view. Deprecated: switch to CSS transition"
  [class comp-in comp-out]
  (let [swap (rf/subscribe [:state [:swap]])
        curr-page (rf/subscribe [:common/route ])
        prev-page (rf/subscribe [:common/route :last])
        force? (empty? (seq class))
        ref-fn #(when (and %
                           (not= (get-in @prev-page [:data :name])
                                 (:running @swap))
                           (not= (get-in @prev-page [:data :name]) (:finished @swap)))
                 (rf/dispatch [:swap/trigger prev-page]))] ; no transition
    (fn [class comp-in comp-out]
      (let [prev-page (get-in @prev-page [:data :name])]
        [:div.swapper
         [:div.swap-in
          {:class (str class " "
                       (when (or (not @swap)
                                 force?
                                 (not comp-out)
                                 (= (:running @swap) prev-page)
                                 (= (:finished @swap) prev-page))
                         "swapped-in"))}
          comp-in] ;will have to be behind for z then revealed by curr page moving out the way.
         (when comp-out
           [:div.swapped
            {:class (str (when (= (:running @swap) prev-page)
                           class) " "
                         (when (= (:running @swap) prev-page)
                           "swapped-out") " "
                         (when (or (= (:finished @swap) prev-page)
                                   (not @swap)
                                   force?)
                           "removed") " ")
             :ref #(do (when (and %
                                  (not= prev-page (:running @swap))
                                  (not= prev-page (:finished @swap)))
                         (rf/dispatch [:swap/trigger prev-page]))
                       (rf/dispatch [:history/set-referrer [nil 0]]))} ; trigger anim out and deferred hiding. triggers three(!) times each time but later no effect so.
            (when-not (:finished @swap)
              comp-out)])]))))

(defn page "Render active page inbetween header, footer and general stuff." 
  []
  (let [ext-back? @(rf/subscribe [:history/back-nav-from-external?])
        swap-class (if ext-back? "" "opacity")
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [l/<assets> {:css (some-> spec :assets :css)
                :js  (some-> spec :assets :js)}]

   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]]
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/zoom-to-modal :fullscreen]
   [ui/safe :user [l/<> {:module :user, :defer? true}]]
   [ui/safe :settings [common/settings]]
   [ui/safe :search [l/<> {:module :search, :defer? true}]]
   (if-let [error-page @(rf/subscribe [:state [:error-page]])] ; do it like this as to not affect url. though avoiding such redirects not likely actually useful for an SPA? otherwise good for archive.org check hehe
     [:main.main-content.perspective-top
      [error-page]]
     (if-let [page @(rf/subscribe [:common/page])]
       [:main.main-content.perspective-top
        {:id    "main"
         :class (when-not ext-back? "animate")}
        [swapper swap-class
         [ui/safe :page [page]]
         (when-let [page-prev @(rf/subscribe [:common/page :last])]
           [ui/safe :page-prev [page-prev]])]]
       [ui/loading-spinner true :massive]))                 ; removed since jars now that have hero in original html

   [:div#error-portal]

   [common/footer-full @(rf/subscribe [:content [:footer]])]
   [common/footer @(rf/subscribe [:content [:footer]])]
   [ui/safe :hud [ui/hud (rf/subscribe [:hud])]]
   [common/to-top]
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

;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element react/StrictMode
                      nil                ;; <-- props
                      (r/as-element [page]))
    [#'page]))

(defn render []
  (when-not @root
    (reset! root (rdomc/create-root (.getElementById js/document "app"))))
  (rdomc/render @root [#'page]))

(defn mount-components "Called each update when developing" []
  (rf/dispatch-sync [:scroll/save-position-dev])
  (rf/clear-subscription-cache!)
  (routes/start!) ; restart router on reload?
  (rf/dispatch [:reloaded])
  (util/log "Mounting root component")
  (render)
  (rf/dispatch [:scroll/restore-position-dev 150]))

(defn init "Called only on page load" []
  (rf/dispatch-sync [:init/app-db])
  (rf/dispatch-sync [:fb/init]) ;sync because number of early fetches depend on this... move back in here though because useless, all public anyways + not like it's secret after sent off ;P was just to test concept
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (mount-components)
  (js/setTimeout #(rf/dispatch [:init/init]) ; listeners and stuff that might depend on being mounted
                 16))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?

