(ns website.core
  (:require
    [website.async :as a]
    [website.react :as r]
    [website.validation.runtime :as validation]
    [website.validation :as engine]
    [website.loader :as loader]
    [website.render-context :as context]
    [website.component.data :as component-data]
    [website.component.instrumentation :as instrumentation]
    [website.component.storage :as storage]
    [website.modules.main.module :as main-module]


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
              (a/create
                (fn [complete! _]
                  (motion/finish-animation! element {:timeout-ms 500}
                    (fn []
                      (reset! *shell-root (r/create-root element))
                      (r/render @*shell-root [<disposed-shell> complete!])))))
              (a/resolved nil))]
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
                           (instrumentation/without-profiling
                             [browser-resources/<deferred>
                              (m/<> {:module :dev-console
                                     :view :console
                                     :load-on :immediate
                                     :<loading> [:<>]})]))))

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
          (a/resolved nil))
        (a/then (fn [_]
                 (render)
                 (when hot-reload? (r/dispatch [:scroll/restore-position-dev 150])))))))

(defn ^:async prepare-bootstrap! []
  (let [snapshot (when-not (.getElementById js/document "local-page-bootstrap")
                   (or @ssr/*snapshot (ssr/install!)))]
    (if (and (:hydrate? @restore/*context) (:route-parameters snapshot)
             (not (validation/configured?)))
      ;; This pair was validated on the server. A retry must not reinstall old disk state.
      snapshot
      (do
        (await (loader/acquire-code! :coercion))
        (validation/install!)
        (validation/module! main-module/spec)
        (await (storage/ready!))
        (await (if-let [local-snapshot (local-page/install!)]
                 (local-page/prepare! local-snapshot)
                 (storage/restore-public-cache!)))
        nil))))

(defn- restore-history! []
  (r/dispatch-sync [:ls/get-path [:scroll-position] [:state :scroll-position]])
  ;; Real network SSR keeps native scroll restoration. A disk-backed history
  ;; return waits for restored layout before moving to the saved position.
  (when (and (restore/back-navigation?)
             (not (.getElementById js/document "local-page-bootstrap"))
             (= "true" (.getAttribute (.getElementById js/document "app") "data-restore")))
    (r/dispatch [:scroll/restore-history (.-pathname js/location)])))

(defn- ^:async initialize-page! [shell-cleared]
  ;; Let synchronous document setup finish before installing bootstrap data.
  (await nil)
  (let [snapshot (await (prepare-bootstrap!))]
    (restore-history!)
    (await (if snapshot (content/bootstrap-server! snapshot) (content/bootstrap!)))
    (await (a/all [shell-cleared (component-data/ensure-all! (:depends spec))]))
    (await (mount-components))
    (r/dispatch [:state [:page-init] {:status :ready}])))

(defn- page-starter [shell-cleared]
  (letfn [(failed! [_]
            (service-status/fail! :page-init "Page initialization failed"
                                  "The page could not initialize. Existing server content is retained." start!)
            (r/dispatch [:state [:page-init] {:status :failed}]))
          (start! []
            (r/dispatch-sync [:state [:page-init] {:status :loading}])
            (service-status/recover! :page-init)
            (a/catch-error (initialize-page! shell-cleared) failed!))]
    start!))

(defn init "Called only on page load" []
  (when (engine/ready?)
    (validation/install!)
    (validation/module! main-module/spec))
  (let [shell-cleared (clear-shell!)]
    ;; A persisted-content hint can also accompany a normal reload. Only browser
    ;; history traversal bypasses entrance motion and restores the saved scroll.
    (let [hydrate? (= "true" (.getAttribute (.getElementById js/document "app") "data-hydrate"))]
      ;; Network SSR retains its exact server tree, including native title
      ;; spans. The head return policy settles its clocks before first paint;
      ;; local pairs install their own matching restoration context below.
      (restore/begin! {:back? (and (restore/back-navigation?) (not hydrate?))
                       :hydrate? hydrate?})
      (when (= "true" (.getAttribute (.-documentElement js/document) "data-image-direct"))
        (restore/direct-images!)))
    (r/dispatch-sync [:init/app-db])
    (r/dispatch-sync [:boot/document])
    (r/dispatch-sync [:history/set-referrer js/document.referrer js/window.performance.navigation.type])
    (let [start! (page-starter shell-cleared)]
      (when-let [element (.getElementById js/document "page-init-status")]
        (when-not @*init-root (reset! *init-root (r/create-root element)))
        (r/render @*init-root [init-view/<fallback> start!]))
      (start!))))

(defn ^:export init!  []
  (defonce _init_ (init))) ;; why still need for thisi don't get it init! is now being called each reload?
