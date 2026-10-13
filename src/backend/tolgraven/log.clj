(ns tolgraven.log
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [taoensso.timbre :as timbre]
            [taoensso.encore :as enc]
            [taoensso.timbre.appenders.community.rotor :as rotor]
            [taoensso.timbre.tools.logging]
            [clj-commons.pretty.repl :as pretty-repl]
            [tolgraven.config :refer [env]]
            [mount.core :refer [defstate]]))

(defn log-by-ns-pattern
  [ns-patterns & [{:keys [?ns-str config level] :as opts}]]
  (let [ns-filter #(-> %
                       enc/compile-ns-filter
                       enc/memoize_)
        namesp   (or (some->> ns-patterns
                              keys
                              (filter #(and (string? %)
                                            ((ns-filter %) ?ns-str)))
                              not-empty
                              (apply max-key count))
                     :all)
        loglevel (get ns-patterns namesp (get config :min-level))]
    ;; Timbre has already applied its global threshold to this entry. Checking
    ;; whether the namespace's threshold itself may log drops even errors when
    ;; the global threshold is higher (for example :info versus :debug).
    (when (timbre/level>= level loglevel)
      opts)))

(defn log-with-pprint [data] 
  (update data :vargs
          (partial mapv #(if (string? %) 
                           % 
                           (with-out-str (pprint/pprint %))))))

(defn error-be-red "Make errors red middleware" [])

(defn init-timbre [] ; should grab below from env tho...
 (let [ns-levels {"tolgraven.*"                         :debug
                  "org.apache.http"                     :warn
                  "com.datomic.db"                      :info
                  "org.xnio.nio"                        :warn
                  "com.zaxxer.hikari"                   :warn
                  "org.eclipse.jetty.server*"           :warn
                  "io.pedestal*"                        :info
                  :all                                  :debug}]
  (timbre/merge-config!
   {:min-level :debug
    :timestamp-opts {:pattern "yyMMdd HH:mm:ss.SS"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}
    :appenders {:println {:min-level :info}
                :rotor (merge (rotor/rotor-appender
                               {:path "log/tolgraven.log"
                                :max-size 10000000 ;was set to just 100kb... thats like 10 stacktraces lol
                                :backlog 5})
                              {:rate-limit [[1 250] [2 1000] [4 10000]]})}
    :middleware [(partial log-by-ns-pattern ns-levels)
                 log-with-pprint]})))

(defn init-logging "Hijack others, make exceptions nice, init timbre"
  []
  (taoensso.timbre.tools.logging/use-timbre) ;route logging through timbre
  
  ;; Timbre already formats logged Throwables with clj-commons/pretty. Keep the
  ;; Throwable intact through tools.logging and Java bridges for every appender.
  (pretty-repl/install-pretty-exceptions)
  ;; Pretty installs a default handler too; the mounted logger owns the final
  ;; handler, rather than namespace load order deciding how failures are logged.
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (timbre/error ex {:what :uncaught-exception
                         :where (str "Uncaught exception on " (.getName thread))}))))
  (init-timbre))

(defstate ^{:on-reload :noop} logger-timbre ;problem: afterglow timbre (im guessing) overwrites our config...
 :start (init-logging))
; (init-logging) ;want this to happen on load yo
