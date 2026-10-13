(ns website.dev.shadow
  "Deferred development watches; explicit REPL selection stays in its calling session."
  (:require [clojure.tools.logging :as log]))

(def startup-delay-ms 1000)
(defonce *startup (atom nil))

(defn- watch-builds! [build-ids]
  ;; Resolve the compiler/server on the background thread too. These remain
  ;; Shadow-owned watches, with its normal output and JVM shutdown lifecycle.
  ((requiring-resolve 'shadow.cljs.devtools.server/start!))
  (let [watch! (requiring-resolve 'shadow.cljs.devtools.api/watch)]
    (doseq [build-id build-ids]
      ;; Default synchronous watch completes each initial build before the next.
      (watch! build-id))))

(defn start!
  "Return immediately; start the ordered watches shortly after application init.
   Repeated starts share pending work. Existing Shadow workers are reused."
  [build-ids]
  (locking *startup
    (if (and @*startup (not (future-done? @*startup)))
      @*startup
      (reset! *startup
              (future
                (try
                  (Thread/sleep startup-delay-ms)
                  (watch-builds! build-ids)
                  (catch Throwable error
                    (log/error error "Deferred Shadow startup failed; call (cljs-repl) to retry")
                    (throw error))))))))

(defn select!
  "Wait for queued startup, then select from the caller's nREPL session.
   Retry failed startup through the same native Shadow watch API."
  [build-ids selected-build]
  (when-let [startup @*startup]
    (try
      @startup
      (catch java.util.concurrent.ExecutionException _ nil)))
  (watch-builds! build-ids)
  ((requiring-resolve 'shadow.cljs.devtools.api/nrepl-select) selected-build))
