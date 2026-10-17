(ns website.terminal-repl-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [nrepl.ack :as ack]
            [nrepl.core :as nrepl]
            [website.dev.terminal :as terminal]))

(deftest legacy-options-and-connect-address
  (is (= ":headless" (:mode (terminal/options [":headless" ":port" "0"]))))
  (is (= "0" (get-in (terminal/options [":port" "0"]) [:options :port])))
  (is (= "localhost" (get-in (terminal/options [":connect" "localhost:7888"]) [:options :bind])))
  (is (= "7888" (get-in (terminal/options [":connect" "7888"]) [:options :port])))
  (is (thrown? clojure.lang.ExceptionInfo (terminal/options [":port"])))
  (is (thrown? clojure.lang.ExceptionInfo (terminal/options [":unknown" "value"]))))

(deftest server-retains-cider-and-releases-owned-resources
  (let [file (java.io.File/createTempFile "terminal-repl-" ".port")
        original-file io/file
        calls (atom [])
        port (atom nil)
        options (assoc (:options (terminal/options [":port" "0"])) :ack "1")]
    (spit file "previous-discovery")
    (try
      (with-redefs [io/file (fn [& args]
                             (if (= [".nrepl-port"] (vec args)) file (apply original-file args)))
                    ack/send-ack (fn [_ ack-port]
                                   (is (= 1 ack-port))
                                   (is (= [:start] @calls))
                                   (swap! calls conj :ack))
                    terminal/start! #(swap! calls conj :start)
                    terminal/stop! #(swap! calls conj :stop)
                    terminal/run-client!
                    (fn [host connected-port]
                      (reset! port connected-port)
                      (is (= (str connected-port) (slurp file)))
                      (with-open [connection (nrepl/connect :host host :port connected-port)]
                        (let [client (nrepl/client connection 5000)
                              responses (doall (nrepl/message client {:op "eval", :code "(+ 20 22)"}))
                              description (nrepl/combine-responses (nrepl/message client {:op "describe"}))]
                          (is (some #(= "42" (:value %)) responses))
                          (is (contains? (:ops description) :cider/info))))
                      7)]
        (is (= 7 (terminal/run! {:mode ":start", :options options})))
        (is (= [:start :ack :stop] @calls))
        (is (= "previous-discovery" (slurp file)))
        (is (thrown? java.net.ConnectException
                     (with-open [connection (nrepl/connect :host "127.0.0.1" :port @port)]))))
      (finally (io/delete-file file)))))

(deftest initialization-failure-stops-server-without-changing-discovery
  (let [file (java.io.File/createTempFile "terminal-repl-failure-" ".port")
        original-file io/file
        stopped? (atom false)]
    (spit file "previous-discovery")
    (try
      (with-redefs [io/file (fn [& args]
                             (if (= [".nrepl-port"] (vec args)) file (apply original-file args)))
                    terminal/start! #(throw (ex-info "styles failed" {}))
                    terminal/stop! #(reset! stopped? true)
                    terminal/run-client! (fn [& _] (throw (AssertionError. "client must not start")))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"styles failed"
                             (terminal/run! {:mode ":start"
                                             :options (assoc (:options (terminal/options [":port" "0"])) :ack "1")})))
        (is @stopped?)
        (is (= "previous-discovery" (slurp file))))
      (finally (io/delete-file file)))))
