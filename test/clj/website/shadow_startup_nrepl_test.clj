(ns website.shadow-startup-nrepl-test
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [website.dev.shadow :as startup]))

(deftest real-nrepl-acknowledges-init-and-evaluates-while-first-build-is-blocked
  (let [previous @startup/*startup
        entered (promise)
        release (promise)
        repl-server (server/start-server :bind "127.0.0.1" :port 0)
        resolve! (fn [symbol]
                   (case symbol
                     shadow.cljs.devtools.server/start! (constantly nil)
                     shadow.cljs.devtools.api/release!
                     (fn [build]
                       (when (= :document build)
                         (deliver entered true)
                         @release))
                     shadow.cljs.devtools.api/watch (constantly nil)))]
    (reset! startup/*startup nil)
    (try
      (with-redefs [clojure.core/requiring-resolve resolve!
                    startup/startup-delay-ms 0]
        (with-open [connection (nrepl/connect :host "127.0.0.1" :port (:port repl-server))]
          (let [client (nrepl/client connection 5000)
                initialization (doall (nrepl/message client
                                       {:op "eval"
                                        :code "(website.dev.shadow/start! [:return-worker :ssr :document :app-dev]) nil"}))]
            (is (some #(some #{"done"} (:status %)) initialization)
                "Initialization is acknowledged before the blocked compilation finishes")
            (is (true? (deref entered 5000 :timeout)))
            (is (not (future-done? (:task @startup/*startup))))
            (let [response (doall (nrepl/message client {:op "eval", :code "(+ 20 22)"}))]
              (is (some #(= "42" (:value %)) response)
                  "The real REPL accepts another evaluation during compilation"))
            (deliver release true)
            (is (nil? (deref (:task @startup/*startup) 5000 :timeout))))))
      (finally
        (deliver release true)
        (when-let [task (:task @startup/*startup)] @task)
        (reset! startup/*startup previous)
        (server/stop-server repl-server)))))
