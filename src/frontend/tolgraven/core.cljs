(ns tolgraven.core
  (:require
    [goog.events]
    [tolgraven.react :as rf]
    [tolgraven.validation.runtime :as validation]
    [tolgraven.dev-console.views :as dev-console]
    [tolgraven.render-context :as context]
    [tolgraven.component.data :as component-data]
    [tolgraven.component :as component]
    [tolgraven.component.storage :as storage]
    [tolgraven.modules.blog.cache :as blog-cache]
    [tolgraven.modules.main.module :as main-module]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]
    [tolgraven.ajax :as ajax]
    [tolgraven.content.client :as content]
    [tolgraven.ssr.client :as ssr]
    [tolgraven.ssr.local :as local-page]
    [tolgraven.components.oembed :as oembed]
    [tolgraven.components.init :as init-view]
    [tolgraven.component.restore :as restore]
    [tolgraven.component.motion :as motion]
    [tolgraven.service-status :as service-status]
    [tolgraven.events]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m :include-macros true]
    [tolgraven.component.registry]
    [tolgraven.navigation.routes :as routes]
    [tolgraven.navigation.preload :as page-preload]
    [tolgraven.subs]
    [tolgraven.components.ui :as ui]
    [tolgraven.util :as util]
    [tolgraven.components.shell :as common]
    [tolgraven.components.page :as page-view]))

(def spec main-module/spec)

(def page page-view/<page>)

;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce *init-root (atom nil))
(defonce *shell-root (atom nil))

(m/defc <disposed-shell> {:profile false} [complete!]
  (rf/use-layout-effect (fn [] (complete!) js/undefined) #js [complete!])
  [:<>])

(defonce *shell-removal (atom nil))
(defn clear-shell!
  "Let the streamed overlay finish its exit, then let React dispose its tree.
   Bootstrap/data acquisition runs in parallel; hydration waits for disposal so
   duplicate shell IDs cannot interfere with routing or measurements."
  []
  (or @*shell-removal
      (let [pending
            (if-let [element (.getElementById js/document "ssr-shell")]
              (js/Promise.
                (fn [complete! _]
                  (motion/finish-animation! element {:timeout-ms 500}
                    (fn []
                      (reset! *shell-root (rdomc/create-root element))
                      (rdomc/render @*shell-root [<disposed-shell> complete!])))))
              (js/Promise.resolve nil))]
        (reset! *shell-removal pending)
        pending)))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element rf/strict-mode
                      nil                ;; <-- props
                      (r/as-element [page]))
    [page]))

;; Profiling this host would include console commits and create capture feedback.
(m/defc <root-page> {:profile false} []
  [:<> [ssr/<hydrate> [page]] [page-preload/<background>] [local-page/<capture>]
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
  (validation/install!)
  (validation/module! main-module/spec)
  (let [shell-cleared (clear-shell!)]
    ;; A persisted-content hint can also accompany a normal reload. Only browser
    ;; history traversal bypasses entrance motion and restores the saved scroll.
    (let [hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))]
      ;; Fresh network SSR keeps its server entrance attributes even on a
      ;; history traversal. A local pair installs its own restoration context
      ;; below; a client-only return has no server attributes to match.
      (restore/begin! {:back? (and (restore/back-navigation?) (not hydrate?))
                       :hydrate? hydrate?}))
    (rf/dispatch-sync [:init/app-db])
    (rf/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
    (ajax/load-interceptors!)
    (letfn [(start! []
              (rf/dispatch-sync [:state [:page-init] {:status :loading}])
              (service-status/recover! :page-init)
              (-> (storage/ready!)
                  (.then (fn [_]
                           (let [local-snapshot (local-page/install!)]
                             (if local-snapshot
                               (local-page/prepare! local-snapshot)
                               (do (ssr/install!) (blog-cache/restore!))))))
                  (.then (fn [_]
                           (rf/dispatch-sync [:ls/get-path [:scroll-position] [:state :scroll-position]])
                           ;; A history return using persisted content needs layout-
                           ;; aware scroll restoration. Real SSR HTML uses the browser's
                           ;; native restoration; ordinary reloads keep entrance motion.
                           (when (and (restore/back-navigation?)
                                      (not (.getElementById js/document "local-page-bootstrap"))
                                      (= "true" (.getAttribute (.getElementById js/document "app") "data-restore")))
                             (rf/dispatch [:scroll/restore-history (.-pathname js/location)]))))
                  (.then (fn [_] (content/bootstrap!)))
                  (.then (fn [_]
                           (js/Promise.all
                             #js [shell-cleared (component-data/ensure-all! (:depends spec))])))
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
      (start!))))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
