(ns tolgraven.core
  (:require
    [goog.events]
    [tolgraven.react :as rf]
    [tolgraven.component.data :as component-data]
    [tolgraven.component :as component]
    [tolgraven.component.storage :as storage]
    [tolgraven.blog.cache :as blog-cache]
    [tolgraven.main.module :as main-module]
    [react :as react]
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
    [tolgraven.macros :as m]
    [tolgraven.routes :as routes]
    [tolgraven.subs]
    [tolgraven.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.views-common :as common]
    [tolgraven.views.page :as page-view]))

(def spec main-module/spec)

(def page page-view/page)
(def swapper page-view/swapper)

;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce *init-root (atom nil))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element react/StrictMode
                      nil                ;; <-- props
                      (r/as-element [page]))
    [#'page]))

(defn <root-page> []
  [ssr/<hydrate> [page]])

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
        (js/Promise.all
         #js [(l/load! {:module :user})
              (l/load! {:module :link-preview})
              (component-data/ensure! {:source :subscription :query [:common/page-ready?] :ttl-ms 1})])
        (js/Promise.resolve nil))
      (.then (fn [_] (render) (rf/dispatch [:scroll/restore-position-dev 150])))))

(defn init "Called only on page load" []
  (restore/begin! {:back? (restore/back-navigation?)
                   :hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))})
  (rf/dispatch-sync [:init/app-db])
  (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
  (ajax/load-interceptors!)
  (letfn [(start! []
            (rf/dispatch-sync [:state [:page-init] {:status :loading}])
            (service-status/recover! :page-init)
            (-> (storage/ready!)
                (.then (fn [_] (ssr/install!) (blog-cache/restore!)))
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
