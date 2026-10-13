(ns tolgraven.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as tools-log]
            [clojure.tools.logging.impl :as logging-impl]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.community.rotor :as rotor]
            [tolgraven.log :as log])
  (:import [java.nio.file Files]
           [org.jboss.logging Logger]
           [org.slf4j LoggerFactory]))

(deftest startup-provider-test
  ;; Provider selection happens before the mounted application logger starts.
  (is (= "slf4j" (System/getProperty "org.jboss.logging.provider")))
  (is (= "org.jboss.logging.Slf4jLocationAwareLogger"
         (.getName (class (Logger/getLogger "startup.probe")))))
  (is (= "com.github.fzakaria.slf4j.timbre.TimbreLoggerFactory"
         (.getName (class (LoggerFactory/getILoggerFactory)))))
  (is (thrown? ClassNotFoundException
               (Class/forName "org.apache.log4j.Logger" false
                              (.getContextClassLoader (Thread/currentThread))))
      "Log4j must not be an application backend")
  (is (= 1 (count (enumeration-seq
                   (.getResources (.getContextClassLoader (Thread/currentThread))
                                  "io/aviso/repl.clj"))))
      "Pretty's compatibility namespace must have only one classpath owner"))

(deftest namespace-loading-preserves-uncaught-handler-test
  (let [original-handler (Thread/getDefaultUncaughtExceptionHandler)
        handler (reify Thread$UncaughtExceptionHandler
                  (uncaughtException [_ _ _]))]
    (try
      (Thread/setDefaultUncaughtExceptionHandler handler)
      (require 'tolgraven.core :reload)
      (require 'tolgraven.log :reload)
      (is (identical? handler (Thread/getDefaultUncaughtExceptionHandler))
          "Only explicit logging initialization may replace the default handler")
      (finally
        (Thread/setDefaultUncaughtExceptionHandler original-handler)))))

(deftest logging-paths-test
  (let [original-config timbre/*config*
        original-factory tools-log/*logger-factory*
        original-handler (Thread/getDefaultUncaughtExceptionHandler)
        *entries (atom [])
        exception (ex-info "logging probe" {:probe true})
        thread (Thread. "uncaught-probe-thread")
        file (.toFile (Files/createTempFile "tolgraven-logging-" ".log"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      ;; Repeated application starts must not wrap tools.logging's log* or
      ;; convert Throwables into strings before the appender receives them.
      (log/init-logging)
      (log/init-logging)
      (timbre/with-config
        (assoc timbre/*config* :min-level :info :appenders
               {:capture {:enabled? true
                          :fn #(swap! *entries conj %)}
                :file (rotor/rotor-appender {:path (.getPath file)
                                             :max-size 1000000
                                             :backlog 1})})
        (timbre/info "direct probe")
        (tools-log/error exception "tools probe")
        (.warn (LoggerFactory/getLogger "probe.slf4j") "slf4j probe" exception)
        (.error (Logger/getLogger "probe.jboss") "jboss probe" exception)
        (.info (LoggerFactory/getLogger "org.apache.http") "suppressed http probe")
        (.warn (LoggerFactory/getLogger "org.apache.http") "http warning probe")
        (.uncaughtException (Thread/getDefaultUncaughtExceptionHandler)
                            thread exception))
      (testing "every facade reaches Timbre once and retains its Throwable"
        (is (= 6 (count @*entries)))
        (is (= [:info :error :warn :error :warn :error] (mapv :level @*entries)))
        (is (= 4 (count (filter :?err @*entries))))
        (is (every? #(identical? exception (:?err %)) (filter :?err @*entries)))
        (is (= "taoensso.timbre" (logging-impl/name tools-log/*logger-factory*))))
      (testing "the configured file appender receives library output"
        (let [output (slurp file)]
          (doseq [message ["direct probe" "tools probe" "slf4j probe" "jboss probe"
                          "http warning probe"]]
            (is (.contains output message)))
          (is (not (.contains output "suppressed http probe")))
          (is (.contains output "logging probe"))
          (is (.contains output "Uncaught exception on uncaught-probe-thread"))
          (is (.contains output ":uncaught-exception"))))
      (finally
        (timbre/set-config! original-config)
        (alter-var-root #'tools-log/*logger-factory* (constantly original-factory))
        (Thread/setDefaultUncaughtExceptionHandler original-handler)
        (.delete file)))))
