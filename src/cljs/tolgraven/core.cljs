(ns tolgraven.core
  (:require
    [goog.events]
    [tolgraven.react :as rf]
    [tolgraven.dev-console.views :as dev-console]
    [tolgraven.render-context :as context]
    [tolgraven.component.data :as component-data]
    [tolgraven.component :as component]
    [tolgraven.component.storage :as storage]
    [tolgraven.blog.cache :as blog-cache]
    [tolgraven.main.module :as main-module]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [tolgraven.ajax :as ajax]
    [tolgraven.content.client :as content]
    [tolgraven.ssr.client :as ssr]
    [tolgraven.components.oembed :as oembed]
    [tolgraven.components.init :as init-view]
    [tolgraven.component.restore :as restore]
    [tolgraven.service-status :as service-status]
    [tolgraven.events]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m :include-macros true]
    [tolgraven.component.registry]
    [tolgraven.routes :as routes]
    [tolgraven.page-preload :as page-preload]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views-common :as common]
    [tolgraven.views.page :as page-view]))

(def spec main-module/spec)

(def page page-view/<page>)

;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce *init-root (atom nil))
(defonce *shell-root (atom nil))

(defn clear-shell!
  "React owns disposal of the temporary server-rendered loading root."
  []
  (when-let [element (.getElementById js/document "ssr-shell")]
    (when-not @*shell-root
      (reset! *shell-root (rdomc/create-root element))
      (rf/flush-sync #(rdomc/render @*shell-root nil)))))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element rf/strict-mode
                      nil                ;; <-- props
                      (r/as-element [page]))
    [#'page]))

;; Profiling this host would include console commits and create capture feedback.
(m/defc <root-page> {:profile false} []
  [:<> [ssr/<hydrate> [page]] [page-preload/<background>]
   (when (and ^boolean goog.DEBUG @context/*interactive?) [dev-console/<console>])])

(defn render []
  (if @root
    (rdomc/render @root [<root-page>])
    (let [element (.getElementById js/document "app")]
      (if (:hydrate? @restore/*context)
        (reset! root (rdomc/hydrate-root element [<root-page>]
                       {:on-recoverable-error
                        (fn [error _]
                          (js/console.error "Hydration recovery" error)
                          (service-status/fail! :hydration "Page restoration failed"
                                               "The page was rebuilt in your browser. Reload if anything is missing."
                                               #(.reload js/location)))}))
        (do (reset! root (rdomc/create-root element))
            (rdomc/render @root [<root-page>]))))))

(defn mount-components "Called each update when developing" []
  (let [hot-reload? (some? @root)]
    ;; A first load keeps the browser's position through hydration. This save/
    ;; restore pair is only for replacing an already mounted development root.
    (when hot-reload? (rf/dispatch-sync [:scroll/save-position-dev]))
    (rf/clear-subscription-cache!)
    ;; A history return may lack a post snapshot. Start its managed data source
    ;; before waiting for page dependencies, rather than only after mounting.
    (when-not (:hydrate? @restore/*context) (rf/dispatch [:store/init]))
    (routes/start!) ; restart router on reload?
    (rf/dispatch [:reloaded])
    (util/log "Mounting root component")
    (-> (if (restore/skip-enter?)
          ;; Preserve existing server DOM until the selected route/module is ready.
          (js/Promise.all
           #js [(l/load! {:module :user})
                (l/load! {:module :link-preview})
                (component-data/ensure! {:source :subscription :query [:common/page-ready?] :ttl-ms 1})])
          (js/Promise.resolve nil))
        (.then (fn [_]
                 (render)
                 (when hot-reload? (rf/dispatch [:scroll/restore-position-dev 150])))))))

(defn init "Called only on page load" []
  ;; Remove duplicate shell IDs before routing, measurement, or hydration.
  (clear-shell!)
  (restore/begin! {:back? (or (restore/back-navigation?)
                               (= "true" (.getAttribute (.getElementById js/document "app") "data-restore")))
                   :hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))})
  (rf/dispatch-sync [:init/app-db])
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (letfn [(start! []
            (rf/dispatch-sync [:state [:page-init] {:status :loading}])
            (service-status/recover! :page-init)
            (-> (storage/ready!)
                (.then (fn [_]
                         (ssr/install!)
                         (blog-cache/restore!)
                         (rf/dispatch-sync [:ls/get-path [:scroll-position] [:state :scroll-position]])
                         ;; The server explicitly skipped SSR for this persisted
                         ;; return. Start restoration before React builds content.
                         (when (= "true" (.getAttribute (.getElementById js/document "app") "data-restore"))
                           (rf/dispatch [:scroll/restore-history (.-pathname js/location)]))))
                (.then (fn [_] (content/bootstrap!)))
                (.then (fn [_] (component-data/ensure-all! (:depends spec))))
                (.then (fn []
                         (-> (mount-components)
                             (.then (fn [_]
                                      (rf/dispatch [:state [:page-init] {:status :ready}])
                                      (js/setTimeout #(rf/dispatch [:init/init]) 16))))))
                (.catch (fn [_]
                          (service-status/fail! :page-init "Page initialization failed"
                                                "The page could not initialize. Existing server content is retained."
                                                start!)
                          (rf/dispatch [:state [:page-init] {:status :failed}])))))]
    (when-let [element (.getElementById js/document "page-init-status")]
      (when-not @*init-root (reset! *init-root (rdomc/create-root element)))
      (rdomc/render @*init-root [init-view/<fallback> start!]))
    (start!)))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
