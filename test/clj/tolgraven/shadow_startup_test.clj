(ns tolgraven.shadow-startup-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.tools.logging :as log]
            [tolgraven.dev.shadow :as startup]))

(use-fixtures :each
  (fn [test!]
    (let [previous @startup/*startup]
      (reset! startup/*startup nil)
      (try
        (test!)
        (finally
          (when-let [task (:task @startup/*startup)]
            (try @task (catch java.util.concurrent.ExecutionException _ nil)))
          (reset! startup/*startup previous))))))

(deftest initialization-returns-before-compilation-and-watches-stay-ordered
  (let [*calls (atom [])
        entered (promise)
        release (promise)
        selected (promise)
        builds [:return-worker :ssr :document :app-dev]
        resolve! (fn [symbol]
                   (case symbol
                     shadow.cljs.devtools.server/start! #(swap! *calls conj :server)
                     shadow.cljs.devtools.api/release!
                     (fn [build]
                       (swap! *calls conj [:release build])
                       (deliver entered true)
                       @release)
                     shadow.cljs.devtools.api/watch
                     (fn [build]
                       (swap! *calls conj build)
                       (when (= build :return-worker)
                         (deliver entered true)
                         @release))
                     shadow.cljs.devtools.api/nrepl-select
                     (fn [build]
                       (deliver selected {:build build
                                          :thread (Thread/currentThread)})
                       [:selected build])))]
    (with-redefs [clojure.core/requiring-resolve resolve!
                  startup/startup-delay-ms 0]
      (let [task (startup/start! builds)]
        (try
          (is (true? (deref entered 2000 :timeout)))
          (is (not (future-done? task)) "Initialization returned while compilation is blocked")
          (is (identical? task (startup/start! builds)) "Repeated start shares the same work")
          (is (= [:server [:release :document]] @*calls) "Next targets wait for the first build")
          (let [selection (future
                            (let [caller (Thread/currentThread)]
                              {:caller caller
                               :result (startup/select! builds :app-dev)}))]
            (is (not (realized? selected)) "Session selection waits for build readiness")
            (deliver release true)
            (is (nil? (deref task 2000 :timeout)))
            (let [result (deref selection 2000 :timeout)]
              (is (= [:selected :app-dev] (:result result)))
              (is (= :app-dev (:build @selected)))
              (is (identical? (:caller result) (:thread @selected))
                  "Select on the requesting nREPL thread, never the startup thread"))
            (is (= [:server [:release :document] :return-worker :ssr :app-dev
                    :return-worker :ssr :app-dev]
                   @*calls)))
          (finally
            (deliver release true)
            @task))))))

(deftest background-startup-failure-remains-visible-and-explicit-selection-retries
  (let [*attempts (atom 0)
        *selected (atom nil)
        *errors (atom [])
        failure (ex-info "test startup failure" {:build :return-worker})
        resolve! (fn [symbol]
                   (case symbol
                     shadow.cljs.devtools.server/start! (constantly nil)
                     shadow.cljs.devtools.api/release!
                     (fn [_] (when (= 1 (swap! *attempts inc)) (throw failure)))
                     shadow.cljs.devtools.api/watch (constantly nil)
                     shadow.cljs.devtools.api/nrepl-select
                     #(reset! *selected %)))]
    (with-redefs [clojure.core/requiring-resolve resolve!
                  startup/startup-delay-ms 0
                  log/log* (fn [_ level error message]
                             (swap! *errors conj {:level level
                                                 :error error
                                                 :message message}))]
      (let [task (startup/start! [:return-worker :app-dev])]
        (is (thrown? java.util.concurrent.ExecutionException @task))
        (is (nil? @*selected))
        (is (= :error (:level (first @*errors))))
        (is (identical? failure (:error (first @*errors))))
        (is (re-find #"Deferred Shadow startup failed" (:message (first @*errors))))
        (is (= :app-dev (startup/select! [:return-worker :app-dev] :app-dev)))
        (is (= 2 @*attempts))
        (is (= :app-dev @*selected))))))

(deftest document-barrier-is-prepared-before-start-and-released-before-watches
  (let [release (promise)
        watching (promise)
        finish (promise)]
    (startup/prepare!)
    (with-redefs [startup/document-wait-ms 1]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"still building"
                           (startup/await-document!))))
    (with-redefs [startup/startup-delay-ms 0
                  clojure.core/requiring-resolve
                  (fn [symbol]
                    (case symbol
                      shadow.cljs.devtools.server/start! (constantly nil)
                      shadow.cljs.devtools.api/release! (fn [_] @release)
                      shadow.cljs.devtools.api/watch (fn [_] (deliver watching true) @finish)))]
      (let [task (startup/start! [:document :app-dev])]
        (try
          (deliver release true)
          (is (true? (deref watching 2000 :timeout)))
          (is (nil? (startup/await-document!)))
          (is (not (future-done? task)) "HTTP does not wait for the app watch")
          (finally (deliver release true) (deliver finish true) @task))))))

(deftest document-release-failure-resolves-http-barrier
  (with-redefs [startup/startup-delay-ms 0
                clojure.core/requiring-resolve
                (fn [symbol]
                  (case symbol
                    shadow.cljs.devtools.server/start! (constantly nil)
                    shadow.cljs.devtools.api/release!
                    (fn [_] (throw (ex-info "release failed" {})))))]
    (let [task (startup/start! [:document :app-dev])]
      (is (thrown? java.util.concurrent.ExecutionException @task))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bootstrap failed"
                           (startup/await-document!))))))
