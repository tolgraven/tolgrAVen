(ns website.undertow-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [nrepl.core :as nrepl]
            [user :as user]
            [website.dev.terminal :as terminal]
            [website.dev.shadow :as shadow]
            [website.dev.styles :as styles]
            [website.routes.home :as home]
            [website.ssr :as ssr]
            [mount.core :as mount]
            [website.core :as core]
            [website.handler :as handler]
            [website.config :as config]
            [website.concurrent :as concurrent]
            [website.streaming :as streaming]
            [ring.adapter.undertow.middleware.session :as session]
            [ring.adapter.undertow.websocket :as ws])
  (:import [java.net URI ServerSocket CookieManager CookiePolicy]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket$Listener]
           [java.time Duration]
           [java.util.concurrent CompletableFuture TimeUnit]))

(defn- port [server]
  (.getPort ^java.net.InetSocketAddress (.getAddress (first (.getListenerInfo server)))))

(deftest mounted-http-server-preserves-native-transport-test
  (let [release (promise)
        received (promise)
        cancel-release (promise)
        cancel-finished (promise)
        socket-handler (fn [request]
                         (case (:uri request)
                           "/ws" {:status 101 :body nil
                                  :undertow/websocket {:on-message (fn [{:keys [data channel]}]
                                                            (ws/send-text data channel))}}
                           "/cancel" {:status 200
                                      :body (streaming/->Body
                                              (fn [output]
                                                (try
                                                  (.write output (.getBytes "shell" "UTF-8"))
                                                  (.flush output)
                                                  @cancel-release
                                                  (dotimes [_ 64]
                                                    (.write output (byte-array 65536))
                                                    (.flush output))
                                                  (catch java.io.IOException _ nil)
                                                  (finally (deliver cancel-finished true)))))}
                           "/stream" {:status 200
                                      :headers {"Content-Type" "text/html"}
                                      :body (streaming/html-document
                                              [:html {} [:head] [:body {}]]
                                              [:p "shell"]
                                              (fn [] @release [:p "finished"]))}
                           (let [visits (inc (get-in request [:session :visits] 0))]
                             {:status 200 :body (str visits) :session {:visits visits}})))
        app (-> socket-handler (session/wrap-session {}) concurrent/wrap-async)
        cookies (CookieManager. nil CookiePolicy/ACCEPT_ALL)
        client (-> (HttpClient/newBuilder) (.cookieHandler cookies)
                   (.connectTimeout (Duration/ofSeconds 5)) .build)]
    (with-redefs [config/env {:port 0 :host "127.0.0.1" :io-threads 1 :worker-threads 2}
                  handler/app (constantly app)]
      (try
        (mount/start #'core/http-server)
        (let [server core/http-server
              server-port (port server)
              base (str "http://127.0.0.1:" (port server))
              request (fn [path] (-> (HttpRequest/newBuilder (URI/create (str base path)))
                                     (.timeout (Duration/ofSeconds 10)) .GET .build))]
          (doseq [expected ["1" "2"]]
            (is (= expected (.body (.send client (request "/session") (HttpResponse$BodyHandlers/ofString))))))
          (let [response (.send client (request "/stream") (HttpResponse$BodyHandlers/ofInputStream))
                input (.body response)
                buffer (byte-array 4096)
                count (.read input buffer)]
            (try
              (is (.contains (String. buffer 0 count "UTF-8") "shell"))
              (is (not (realized? release)))
              (deliver release true)
              (is (.contains (slurp input) "finished"))
              (finally (.close input))))
          (let [input (.body (.send client (request "/cancel") (HttpResponse$BodyHandlers/ofInputStream)))]
            (is (pos? (.read input (byte-array 16))))
            (.close input)
            (deliver cancel-release true)
            (is (= true (deref cancel-finished 10000 ::timeout)))
            (is (= "3" (.body (.send client (request "/session") (HttpResponse$BodyHandlers/ofString))))))
          (let [listener (reify WebSocket$Listener
                           (onOpen [_ socket] (.request socket 1))
                           (onText [_ socket data _]
                             (deliver received (str data))
                             (.request socket 1)
                             (CompletableFuture/completedFuture nil)))
                socket (.get (.buildAsync (.newWebSocketBuilder client)
                                          (URI/create (str "ws://127.0.0.1:" (port server) "/ws")) listener)
                             10 TimeUnit/SECONDS)]
            (try
              (.get (.sendText socket "echo" true) 10 TimeUnit/SECONDS)
              (is (= "echo" (deref received 10000 ::timeout)))
              (finally (.abort socket))))
          (mount/stop #'core/http-server)
          ;; The exact listener must be closed, not merely removed from Mount.
          (with-open [rebound (ServerSocket. server-port)]
            (is (.isBound rebound))))
        (finally
          (deliver release true)
          (deliver cancel-release true)
          (mount/stop #'core/http-server)
          (.close client))))))

(deftest occupied-port-fails-mount-start-test
  (with-open [occupied (ServerSocket. 0)]
    (with-redefs [config/env {:port (.getLocalPort occupied) :host "127.0.0.1" :io-threads 1 :worker-threads 2}
                  handler/app (constantly (concurrent/wrap-async (constantly {:status 200 :body "unused"})))]
      (try
        (is (thrown? Exception (mount/start #'core/http-server)))
        (finally (mount/stop #'core/http-server))))))


(deftest occupied-development-http-port-retains-repl-and-native-watch-startup-test
  (let [previous-shadow @shadow/*startup
        previous-explain spec/*explain-out*
        original-resolve requiring-resolve
        original-file io/file
        file (java.io.File/createTempFile "development-bind-repl-" ".port")
        *events (atom [])
        *errors (atom [])
        resolve! (fn [symbol]
                   (case symbol
                     shadow.cljs.devtools.server/start! #(swap! *events conj :shadow-server)
                     shadow.cljs.devtools.api/release! (fn [build] (swap! *events conj [:release build]))
                     shadow.cljs.devtools.api/watch (fn [build] (swap! *events conj [:watch build]))
                     (original-resolve symbol)))]
    (reset! shadow/*startup nil)
    (try
      (with-open [occupied (ServerSocket. 0)]
        (with-redefs [config/env {:dev true
                                 :port (.getLocalPort occupied)
                                 :host "127.0.0.1"
                                 :io-threads 1
                                 :worker-threads 2}
                      handler/app (constantly (concurrent/wrap-async (constantly {:status 200 :body "unused"})))
                      mount/start-without (fn [& _]
                                            (swap! *events conj :http)
                                            (mount/start #'core/http-server))
                      styles/start! #(swap! *events conj :styles)
                      home/gen-sitemap! (constantly nil)
                      ssr/enabled? (constantly false)
                      clojure.core/add-tap (constantly nil)
                      clojure.core/requiring-resolve resolve!
                      shadow/startup-delay-ms 0
                      log/log* (fn [_ level error message]
                                 (when (= :error level)
                                   (swap! *errors conj {:error error :message message})))
                      io/file (fn [& args]
                                (if (= [".nrepl-port"] (vec args)) file (apply original-file args)))
                      terminal/stop! (constantly nil)
                      terminal/run-client!
                      (fn [host port]
                        (with-open [connection (nrepl/connect :host host :port port)]
                          (let [client (nrepl/client connection 30000)
                                response (doall (nrepl/message client {:op "eval" :code "(+ 20 22)"}))
                                description (nrepl/combine-responses (nrepl/message client {:op "describe"}))]
                            (is (some #(= "42" (:value %)) response))
                            (is (contains? (:ops description) :cider/info))))
                        (is (not= ::timeout (deref (:task @shadow/*startup) 5000 ::timeout)))
                        0)]
          (is (= 0 (terminal/run! (terminal/options [":port" "0"]))))
          (is (= [:styles :http :shadow-server [:release :document]
                  [:watch :return-worker] [:watch :app-dev]] @*events))
          (is (= 1 (count @*errors)))
          (is (re-find #"Application startup failed; REPL and Shadow watches remain available"
                       (:message (first @*errors))))
          (is (some #(instance? java.net.BindException %)
                    (take-while some? (iterate ex-cause (:error (first @*errors))))))
          (is (.isBound occupied))))
      (finally
        (when-let [task (:task @shadow/*startup)] (deref task 5000 nil))
        (reset! shadow/*startup previous-shadow)
        (alter-var-root #'spec/*explain-out* (constantly previous-explain))
        (mount/stop #'core/http-server)
        (io/delete-file file true)))))
