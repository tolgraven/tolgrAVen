(ns tolgraven.log
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [taoensso.timbre :as timbre]
            [taoensso.encore :as enc]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre.tools.logging]
            [io.aviso.logging]
            [io.aviso.repl]
            [io.aviso.ansi :as ansi :refer :all]
            [tolgraven.config :refer [env]]
            [mount.core :refer [defstate]]))

;; log uncaught exceptions in threads how combine this with aviso??
(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (timbre/error ex))))



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
        loglevel (get ns-patterns namesp (get config :level))]
    (when (and (timbre/may-log? loglevel namesp)
               (timbre/level>= level loglevel))
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
   {:enabled true
    :level :debug
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
  
  (io.aviso.repl/install-pretty-exceptions)
  (io.aviso.logging/uncaught-exception-handler)
  (io.aviso.logging/install-pretty-logging) ;ya
  (init-timbre))

(defstate ^{:on-reload :noop} logger-timbre ;problem: afterglow timbre (im guessing) overwrites our config...
 :start (init-logging))
; (init-logging) ;want this to happen on load yo
