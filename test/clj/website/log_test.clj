(ns website.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as tools-log]
            [clojure.tools.logging.impl :as logging-impl]
            [taoensso.telemere :as tel]
            [taoensso.telemere.timbre :as timbre]
            [website.log :as log]
            [mount.core :as mount])
  (:import [java.nio.file Files]
           [org.jboss.logging Logger]
           [org.slf4j LoggerFactory]))

(deftest startup-provider-test
  (is (= "slf4j" (System/getProperty "org.jboss.logging.provider")))
  (is (= "org.jboss.logging.Slf4jLogger"
         (.getName (class (Logger/getLogger "startup.probe")))))
  (is (= "com.taoensso.telemere.slf4j.TelemereLoggerFactory"
         (.getName (class (LoggerFactory/getILoggerFactory)))))
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (is (= 1 (count (enumeration-seq (.getResources loader "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"))))
        "Exactly one SLF4J backend may own Java output")
    (is (= 1 (count (enumeration-seq (.getResources loader "io/aviso/repl.clj")))))
  (is (thrown? ClassNotFoundException (Class/forName "org.apache.log4j.Logger")))))

(deftest namespace-loading-preserves-uncaught-handler-test
  (let [original-handler (Thread/getDefaultUncaughtExceptionHandler)
        handler (reify Thread$UncaughtExceptionHandler (uncaughtException [_ _ _]))]
    (try
      (Thread/setDefaultUncaughtExceptionHandler handler)
      (require 'website.core :reload)
      (require 'website.log :reload)
      (is (identical? handler (Thread/getDefaultUncaughtExceptionHandler)))
      (finally (Thread/setDefaultUncaughtExceptionHandler original-handler)))))

(deftest logging-paths-test
  (let [original-factory tools-log/*logger-factory*
        original-handler (Thread/getDefaultUncaughtExceptionHandler)
        *entries (atom [])
        exception (ex-info "logging probe" {:probe true})
        file (.toFile (Files/createTempFile "tolgraven-logging-" ".log"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        write! (log/file-handler (.getPath file) 1000000 1)]
    (try
      (log/init-logging)
      (log/init-logging)
      (tel/with-handler ::capture
        (fn [signal] (swap! *entries conj signal) (write! signal))
        {:async nil :min-level :info}
        (do
          (timbre/info "direct probe")
          (tools-log/error exception "tools probe")
          (.warn (LoggerFactory/getLogger "probe.slf4j") "slf4j probe" ^Throwable exception)
          (.error (Logger/getLogger "probe.jboss") "jboss probe" exception)
          (.info (LoggerFactory/getLogger "org.apache.http") "suppressed http probe")
          (.warn (LoggerFactory/getLogger "org.apache.http") "http warning probe")
          (.uncaughtException (Thread/getDefaultUncaughtExceptionHandler)
                              (Thread. "uncaught-probe-thread") exception)))
      (write!)
      (testing "facades reach Telemere once and retain original Throwables"
        (is (= 6 (count @*entries)))
        (is (= [:info :error :warn :error :warn :error] (mapv :level @*entries)))
        (is (= 4 (count (filter :error @*entries)))
            (pr-str (mapv #(select-keys % [:ns :kind :error :msg_]) @*entries)))
        (is (every? #(identical? exception (:error %)) (filter :error @*entries)))
        (is (= "taoensso.telemere" (logging-impl/name tools-log/*logger-factory*))))
      (testing "rotating file output includes library calls and pretty errors"
        (let [output (slurp file)]
          (doseq [message ["direct probe" "tools probe" "slf4j probe" "jboss probe"
                          "http warning probe" "logging probe" "Uncaught exception on uncaught-probe-thread"]]
            (is (.contains output message)))
          (is (not (.contains output "suppressed http probe")))
          (is (.contains output ":uncaught-exception"))))
      (finally
        (tel/remove-handler! :website.log/console)
        (tel/remove-handler! :website.log/file)
        (alter-var-root #'tools-log/*logger-factory* (constantly original-factory))
        (Thread/setDefaultUncaughtExceptionHandler original-handler)
        (write!)
        (.delete file)))))

(deftest file-rate-limits-only-repeated-signals-test
  (let [filter! (#'log/repeated-signal-filter)
        first-message {:ns "probe" :coords [1 1] :level :info :msg_ (delay "first")}
        second-message (assoc first-message :msg_ (delay "second"))]
    (is (= first-message (filter! first-message)))
    (is (nil? (filter! first-message)))
    (is (= second-message (filter! second-message)))))

(deftest rotating-file-handler-bounds-archives-test
  (let [directory (.toFile (Files/createTempDirectory "tolgraven-log-rotation-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file directory "probe.log")
        write! (log/file-handler (.getPath file) 64 1)]
    (try
      (doseq [message ["first" "second" "third"]]
        (write! {:level :info :msg_ (delay (str message (apply str (repeat 128 "x"))))})
        ;; The released handler checks size at bounded maintenance intervals.
        (Thread/sleep 300))
      (write!)
      (let [files (vec (.listFiles directory))]
        (is (= 2 (count files)))
        (is (.contains (slurp file) "third"))
        (is (some #(.contains (slurp %) "second") (remove #{file} files))))
      (finally
        (write!)
        (doseq [owned-file (.listFiles directory)] (.delete owned-file))
        (.delete directory)))))


(deftest stopped-component-diagnostics-retain-owned-output-test
  (let [original-factory tools-log/*logger-factory*
        original-handler (Thread/getDefaultUncaughtExceptionHandler)
        file (.toFile (Files/createTempFile "tolgraven-stop-logging-" ".log"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        write! (log/file-handler (.getPath file) 1000000 1)]
    (try
      (with-redefs [log/file-handler (constantly write!)]
        (mount/start #'log/logger-timbre)
        (mount/stop #'log/logger-timbre)
        (timbre/info "post-stop component diagnostics")
        (is (.contains (slurp file) "post-stop component diagnostics"))
        (is (contains? (tel/get-handlers) :website.log/console))
        (is (contains? (tel/get-handlers) :website.log/file)))
      (finally
        (tel/remove-handler! :website.log/console)
        (tel/remove-handler! :website.log/file)
        (alter-var-root #'tools-log/*logger-factory* (constantly original-factory))
        (Thread/setDefaultUncaughtExceptionHandler original-handler)
        (write!)
        (.delete file)))))
