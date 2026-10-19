(ns website.log
  (:require [clojure.pprint :as pprint]
            [clojure.string :as string]
            [taoensso.telemere :as tel]
            [taoensso.encore :as enc]
            [taoensso.telemere.tools-logging :as tools-logging]
            [taoensso.telemere.utils :as utils]
            [clj-commons.format.exceptions :as exceptions]
            [clj-commons.pretty.repl :as pretty-repl]
            [mount.core :refer [defstate]]))

(def namespace-levels
  {"website.*"               :debug
   "org.apache.http*"        :warn
   "com.datomic.db*"         :info
   "org.xnio.nio*"           :warn
   "com.zaxxer.hikari*"      :warn
   "org.eclipse.jetty.server*" :warn
   "io.pedestal*"            :info})

(defn log-with-pprint
  "Retain pretty printed arguments from the upstream Timbre migration shim."
  [{:keys [timbre/vargs] :as signal}]
  (if (seq vargs)
    (assoc signal :msg_
           (delay (string/join " " (map #(if (string? %) % (with-out-str (pprint/pprint %)))
                                         vargs))))
    signal))

(defn error-be-red "Make errors red middleware" [])

(defn output-formatter []
  (tel/format-signal-fn
    {:content-fn (utils/signal-content-fn
                   {:format-error-fn exceptions/format-exception})}))

(defn file-handler
  ([] (file-handler "log/website.log" 10000000 5))
  ([path max-size backlog]
   (tel/handler:file {:path path
                      :interval nil
                      :max-file-size max-size
                      :max-num-parts backlog
                      :gzip-archives? false
                      :output-fn (output-formatter)})))

(defn- repeated-signal-filter []
  (let [limited? (enc/rate-limiter [[1 250] [2 1000] [4 10000]])]
    (fn [{:keys [ns coords level msg_] :as signal}]
      (when-not (limited? [ns coords level (force msg_)])
        signal))))

(defn init-logging
  "Mount owns filtering, sinks, bridges and the application uncaught handler."
  []
  (tel/set-min-level! :debug)
  (doseq [[pattern level] namespace-levels]
    (tel/set-min-level! nil pattern level))
  (tel/set-root-xfn! log-with-pprint)
  ;; Replace our existing handlers on restart; Telemere closes their file writers.
  (tel/remove-handler! :default/console)
  (tel/add-handler! ::console (tel/handler:console {:output-fn (output-formatter)})
                    {:min-level :info :async nil})
  (tel/add-handler! ::file (file-handler)
                    {:min-level :debug
                     :async nil
                     :xfn (repeated-signal-filter)})
  (tools-logging/tools-logging->telemere!)
  (pretty-repl/install-pretty-exceptions)
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (tel/log! {:level :error
                   :id :uncaught-exception
                   :error ex
                   :data {:thread (.getName thread)}
                   :msg (str "Uncaught exception on " (.getName thread))}))))
  nil)

;; Retain the Mount identity used by existing REPL/lifecycle callers. Sinks
;; survive service stops so core/user can report stopped components afterward;
;; reinitialization replaces/closes them. Synchronous writes flush immediately;
;; process exit releases remaining file descriptors.
(defstate ^{:on-reload :noop} logger-timbre
  :start (init-logging))
