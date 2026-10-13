(ns website.dev.shadow
  "Deferred standalone bootstrap release and native development watches."
  (:require [clojure.tools.logging :as log]))

(def startup-delay-ms 1000)
(def document-wait-ms 120000)
(defonce *startup (atom nil))

(defn prepare!
  "Install the document barrier before HTTP opens; compilation starts separately."
  []
  (locking *startup
    (when (or (nil? @*startup)
              (some-> @*startup :task future-done?))
      (reset! *startup {:document-ready (promise)}))))

(defn document-ready?
  "Read the existing release barrier without waiting or acquiring compiler work."
  []
  (let [ready (:document-ready @*startup)]
    (and ready (realized? ready) (true? @ready))))

(defn await-document!
  "Wait only for the standalone release, surfacing timeout or release failure."
  []
  (when-let [ready (:document-ready @*startup)]
    (let [result (deref ready document-wait-ms ::timeout)]
      (cond
        (= ::timeout result)
        (throw (ex-info "Development document bootstrap is still building. Retry after the release completes."
                        {:status 503, :build :document}))
        (instance? Throwable result)
        (throw (ex-info "Development document bootstrap failed to build."
                        {:status 503, :build :document} result)))))
  nil)

(defn- watch-builds! [build-ids ready]
  ;; The standalone release has its own optimized namespace prefix. A browser
  ;; watch would inject a second unoptimized cljs.core into the app's globals.
  ((requiring-resolve 'shadow.cljs.devtools.server/start!))
  ;; release! binds Shadow's runtime and preserves compiler failures.
  ((requiring-resolve 'shadow.cljs.devtools.api/release!) :document)
  (deliver ready true)
  (let [watch! (requiring-resolve 'shadow.cljs.devtools.api/watch)]
    (doseq [build-id (remove #{:document} build-ids)]
      (watch! build-id))))

(defn start!
  "Return a Future immediately; release document, then start ordered watches.
   Repeated starts share pending work. Existing Shadow workers are reused."
  [build-ids]
  (locking *startup
    (if-let [task (when (and (:task @*startup)
                            (not (future-done? (:task @*startup))))
                   (:task @*startup))]
      task
      (do
        (prepare!)
        (let [ready (:document-ready @*startup)
              task (future
                     (try
                       (Thread/sleep startup-delay-ms)
                       (watch-builds! build-ids ready)
                       (catch Throwable error
                         (deliver ready error)
                         (log/error error "Deferred Shadow startup failed; call (cljs-repl) to retry")
                         (throw error))))]
          (swap! *startup assoc :task task)
          task)))))

(defn select!
  "Wait for startup, then select from the calling nREPL session.
   Retry failed startup through the same native Shadow APIs."
  [build-ids selected-build]
  (when-let [task (:task @*startup)]
    (try @task (catch java.util.concurrent.ExecutionException _ nil)))
  (when (= :document selected-build)
    (throw (ex-info "The standalone document release has no browser REPL; select :app-dev instead."
                    {:build :document})))
  (if (document-ready?)
    (let [watch! (requiring-resolve 'shadow.cljs.devtools.api/watch)]
      (doseq [build-id (remove #{:document} build-ids)] (watch! build-id)))
    (let [ready (promise)]
      (swap! *startup assoc :document-ready ready)
      (try
        (watch-builds! build-ids ready)
        (catch Throwable error
          (deliver ready error)
          (throw error)))))
  ((requiring-resolve 'shadow.cljs.devtools.api/nrepl-select) selected-build))
