(ns website.shadow-startup-nrepl-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.client :as http]
            [mount.core :as mount]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [website.config :as config]
            [website.content.service :as content]
            [website.page-router :as pages]
            [website.dev.shadow :as startup]))

(deftest real-nrepl-starts-during-cms-outage-and-content-recovers
  (let [previous @content/*immediate
        offline? (atom true)
        recovered (promise)
        shell {:document {:title "Recovered CMS"}, :footer []}
        repl-server (server/start-server :bind "127.0.0.1" :port 0)]
    (reset! content/*immediate nil)
    (add-watch content/*immediate ::recovered
      (fn [_ _ _ snapshot]
        (when (= shell (:content snapshot)) (deliver recovered true))))
    (try
      (with-redefs [config/env {:dev true, :ssr {:shell-refresh-seconds 1}}
                    content/settings (constantly {:url "https://repl-outage.test"})
                    pages/immediate-keys (constantly [:document :footer])
                    http/get (fn [& _]
                               (if @offline?
                                 (throw (java.net.ConnectException. "refused"))
                                 {:status 200, :body {:version 1, :content shell}}))]
        (try
          (with-open [connection (nrepl/connect :host "127.0.0.1" :port (:port repl-server))]
            (let [client (nrepl/client connection 5000)
                  initialization (doall (nrepl/message client
                                         {:op "eval"
                                          :code "(mount.core/start #'website.content.service/startup-content) nil"}))]
              (is (some #(some #{"done"} (:status %)) initialization))
              (is (not-any? #(some #{"eval-error"} (:status %)) initialization))
              (is (some #(= "42" (:value %))
                        (doall (nrepl/message client {:op "eval", :code "(+ 20 22)"}))))
              (is (nil? @content/*immediate) "No seed replaces configured CMS content")
              (is (= 503 (:status (content/response [:document]))))
              (is (thrown? clojure.lang.ExceptionInfo (content/immediate-content)))
              (reset! offline? false)
              (is (true? (deref recovered 5000 :timeout)) "The owned worker retries without restarting the REPL")
              (is (= shell (content/immediate-content)))))
          (finally (mount/stop #'content/startup-content))))
      (finally
        (remove-watch content/*immediate ::recovered)
        (reset! content/*immediate previous)
        (server/stop-server repl-server)))))

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
