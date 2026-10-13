(ns website.core
  (:require
    [website.react :as r]
    [website.validation.runtime :as validation]
    [website.validation :as engine]
    [website.loader :as loader]
    [website.render-context :as context]
    [website.component.data :as component-data]
    [website.component.storage :as storage]
    [website.modules.main.module :as main-module]


    [website.ajax :as ajax]
    [website.boot :as boot]
    [website.browser-resources :as browser-resources]
    [website.content.client :as content]
    [website.ssr.client :as ssr]
    [website.ssr.local :as local-page]
    [website.components.init :as init-view]
    [website.component.restore :as restore]
    [website.component.motion :as motion]
    [website.service-status :as service-status]
    [website.events]
    [website.macros :as m :include-macros true]
    [website.component.registry]
    [website.navigation.routes :as routes]
    [website.subs]
    [website.util :as util]
    [website.components.page :as page-view]))

(def spec main-module/spec)

;; -------------------------
;; Initialize app

(defonce root (atom nil))
(defonce *init-root (atom nil))
(defonce *shell-root (atom nil))

(m/defc <disposed-shell> {:profile false} [complete!]
  (r/use-layout-effect (fn [] (complete!) nil) [complete!])
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
                      (reset! *shell-root (r/create-root element))
                      (r/render @*shell-root [<disposed-shell> complete!])))))
              (js/Promise.resolve nil))]
        (reset! *shell-removal pending)
        pending)))
(defonce <page>
  (if false #_:biggus-debuggus
    (r/create-element r/strict-mode
                      nil                ;; <-- props
                      (r/as-element [page-view/<page>]))
    [page-view/<page>]))

;; Profiling this host would include console commits and create capture feedback.
(m/defc <root-page> {:profile false} []
  (restore/document-form [ssr/<hydrate> [page-view/<page>]] [boot/<lifecycle>]
                         (when (and ^boolean goog.DEBUG
                                    @context/*interactive?
                                    (contains? loader/modules :dev-console))
                           [browser-resources/<deferred>
                            (m/<> {:module :dev-console
                                   :view :console
                                   :load-on :immediate
                                   :<loading> [:<>]})])))

(defn render []
  (if @root
    (r/render @root [<root-page>])
    (let [element (.getElementById js/document "app")]
      (if (:hydrate? @restore/*context)
        (reset! root (r/hydrate-root element [<root-page>]
                       {:on-recoverable-error
                        (fn [error _]
                          (js/console.error "Hydration recovery" error)
                          (service-status/fail! :hydration "Page restoration failed"
                                               "The page was rebuilt in your browser. Reload if anything is missing."
                                               #(.reload js/location)))}))
        (do (reset! root (r/create-root element))
            (r/render @root [<root-page>]))))))

(defn mount-components "Called each update when developing" []
  (let [hot-reload? (some? @root)]
    ;; A first load keeps the browser's position through hydration. This save/
    ;; restore pair is only for replacing an already mounted development root.
    (when hot-reload? (r/dispatch-sync [:scroll/save-position-dev]))
    (r/clear-subscription-cache!)
    (routes/start!) ; restart router on reload?
    (r/dispatch [:reloaded])
    (util/log "Mounting root component")
    (-> (if (restore/skip-enter?)
          ;; The route owns initial readiness. Optional SSR exports preserve their
          ;; native DOM and acquire code through their mounted activation boundary.
          (component-data/ensure! {:source :subscription :query [:common/page-ready?] :ttl-ms 1})
          (js/Promise.resolve nil))
        (.then (fn [_]
                 (render)
                 (when hot-reload? (r/dispatch [:scroll/restore-position-dev 150])))))))

(defn prepare-bootstrap! []
  (let [snapshot (when-not (.getElementById js/document "local-page-bootstrap")
                   (or @ssr/*snapshot (ssr/install!)))
        server-ready? (and (:hydrate? @restore/*context)
                           (:route-parameters snapshot)
                           (not (validation/configured?)))]
    (if server-ready?
      ;; The server validated this exact pair. Disk records remain pending until
      ;; their validator arrives; retries must not reinstall older paired state.
      (js/Promise.resolve snapshot)
      (-> (loader/acquire-code! :coercion)
          (.then (fn [_]
                   (validation/install!)
                   (validation/module! main-module/spec)
                   (storage/ready!)))
          (.then (fn [_]
                   (let [local-snapshot (local-page/install!)]
                     (if local-snapshot
                       (local-page/prepare! local-snapshot)
                       (storage/restore-public-cache!)))))
          (.then (fn [_] nil))))))

(defn init "Called only on page load" []
  (when (engine/ready?)
    (validation/install!)
    (validation/module! main-module/spec))
  (let [shell-cleared (clear-shell!)]
    ;; A persisted-content hint can also accompany a normal reload. Only browser
    ;; history traversal bypasses entrance motion and restores the saved scroll.
    (let [hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))]
      ;; Fresh network SSR keeps its server entrance attributes even on a
      ;; history traversal. A local pair installs its own restoration context
      ;; below; a client-only return has no server attributes to match.
      (restore/begin! {:back? (and (restore/back-navigation?) (not hydrate?))
                       :hydrate? hydrate?})
      (when (= "true" (.getAttribute (.-documentElement js/document) "data-image-direct"))
        (restore/direct-images!)))
    (r/dispatch-sync [:init/app-db])
    (r/dispatch-sync [:boot/document])
    (r/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
    (ajax/load-interceptors!)
    (letfn [(start! []
              (r/dispatch-sync [:state [:page-init] {:status :loading}])
              (service-status/recover! :page-init)
              (-> (js/Promise.resolve nil)
                  (.then (fn [_] (prepare-bootstrap!)))
                  (.then (fn [snapshot]
                           (r/dispatch-sync [:ls/get-path [:scroll-position] [:state :scroll-position]])
                           ;; A history return using persisted content needs layout-
                           ;; aware scroll restoration. Real SSR HTML uses the browser's
                           ;; native restoration; ordinary reloads keep entrance motion.
                           (when (and (restore/back-navigation?)
                                      (not (.getElementById js/document "local-page-bootstrap"))
                                      (= "true" (.getAttribute (.getElementById js/document "app") "data-restore")))
                             (r/dispatch [:scroll/restore-history (.-pathname js/location)]))
                           (if snapshot
                             (content/bootstrap-server! snapshot)
                             (content/bootstrap!))))
                  (.then (fn [_]
                           (js/Promise.all
                             #js [shell-cleared (component-data/ensure-all! (:depends spec))])))
                  (.then (fn []
                           (-> (mount-components)
                               (.then (fn [_]
                                        (r/dispatch [:state [:page-init] {:status :ready}]))))))
                  (.catch (fn [_]
                            (service-status/fail! :page-init "Page initialization failed"
                                                  "The page could not initialize. Existing server content is retained."
                                                  start!)
                            (r/dispatch [:state [:page-init] {:status :failed}])))))]
      (when-let [element (.getElementById js/document "page-init-status")]
        (when-not @*init-root (reset! *init-root (r/create-root element)))
        (r/render @*init-root [init-view/<fallback> start!]))
      (start!))))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
