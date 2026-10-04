(ns tolgraven.core
  (:require
    [goog.events]
    [re-frame.core :as rf]
    [re-frame.db :as rfdb]
    [tolgraven.component.data :as component-data]
    [tolgraven.component.storage :as storage]
    [tolgraven.main.module :as main-module]
    [react :as react]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [tolgraven.ajax :as ajax]
    [tolgraven.content.client :as content]
    [tolgraven.blog.ssr-client :as blog-ssr]
    [tolgraven.component.restore :as restore]
    [tolgraven.service-status :as service-status]
    [tolgraven.events]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m]
    [tolgraven.routes :as routes]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views-common :as common]))

(def spec main-module/spec)

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
  (let [ext-back? (or (restore/skip-enter?) @(rf/subscribe [:history/back-nav-from-external?]))
        swap-class (if ext-back? "" "opacity")
        click-evt @(rf/subscribe [:state [:global-clicked]])]
  [:<>
   [l/<assets> {:css (some-> spec :assets :css)
                :js  (some-> spec :assets :js)}]

   [ui/safe :header [common/header @(rf/subscribe [:content [:header]])]]
   [service-status/<notices>]
   [:a {:name "linktotop" :id "linktotop"}]
   
   [ui/zoom-to-modal :fullscreen]
   [l/<> {:module :link-preview}]
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

(defn <root-page> []
  (if (blog-ssr/active?) [blog-ssr/<page>] [page]))

(defn render []
  (if @root
    (rdomc/render @root [#'<root-page>])
    (let [element (.getElementById js/document "app")]
      (if (:hydrate? @restore/*context)
        (reset! root (rdomc/hydrate-root element [#'<root-page>]
                       {:on-recoverable-error
                        (fn [error _]
                          (js/console.error "Hydration recovery" error)
                          (service-status/fail! :hydration "Page restoration failed"
                                               "The page was rebuilt in your browser. Reload if anything is missing."
                                               #(.reload js/location)))}))
        (do (reset! root (rdomc/create-root element))
            (rdomc/render @root [#'<root-page>]))))))

(defn mount-components "Called each update when developing" []
  (rf/dispatch-sync [:scroll/save-position-dev])
  (rf/clear-subscription-cache!)
  (routes/start!) ; restart router on reload?
  (rf/dispatch [:reloaded])
  (util/log "Mounting root component")
  (-> (if (restore/skip-enter?)
        ;; Preserve existing server DOM until the selected route/module is ready.
        (component-data/wait-for! rfdb/app-db
          #(hash-map :ready? (some? (get-in @rfdb/app-db [:common/route :data :view]))) 15000)
        (js/Promise.resolve nil))
      (.then (fn [_] (render) (rf/dispatch [:scroll/restore-position-dev 150])))))

(defn init "Called only on page load" []
  (restore/begin! {:back? (restore/back-navigation?)
                   :hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))})
  (rf/dispatch-sync [:init/app-db])
  (rf/dispatch-sync [:store/init])
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (letfn [(start! []
            (when-let [error (.getElementById js/document "page-init-error")] (.remove error))
            (service-status/recover! :page-init)
            (-> (storage/ready!)
                (.then (fn [_] (blog-ssr/install!)))
                (.then (fn [_] (content/bootstrap!)))
                (.then (fn [_] (component-data/ensure-all! (:depends spec))))
                (.then (fn []
                         (.removeAttribute (.getElementById js/document "app") "role")
                         (-> (mount-components)
                             (.then (fn [_] (js/setTimeout #(rf/dispatch [:init/init]) 16))))))
                (.catch (fn [_]
                          ;; Keep the server skeleton visible until a complete
                          ;; content snapshot is ready, with a usable retry.
                          (let [element (.getElementById js/document "app")
                                error (.createElement js/document "div")
                                message (.createElement js/document "p")
                                button (.createElement js/document "button")]
                            (service-status/fail! :page-init "Page initialization failed"
                                                  "The page could not initialize. Existing server content is retained."
                                                  start!)
                            (when-not (= "true" (.getAttribute element "data-hydrate"))
                              (set! (.-textContent element) ""))
                            (set! (.-id error) "page-init-error")
                            (.setAttribute error "role" "alert")
                            (set! (.-textContent message) "The page could not initialize. Check your connection and retry. The failure has been recorded in the webpage log.")
                            (set! (.-textContent button) "Retry")
                            (set! (.-onclick button) start!)
                            (.appendChild error message)
                            (.appendChild error button)
                            ;; Keep retry UI outside React's hydration container.
                            (.insertAdjacentElement element "afterend" error))))))]
    (start!)))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
