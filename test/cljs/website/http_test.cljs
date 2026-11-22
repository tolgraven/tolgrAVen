(ns website.http-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [website.async :as a]
            [website.http :as http]))

(defn- ^:async with-fetch! [fetch! test!]
  (let [original js/fetch]
    (set! js/fetch fetch!)
    (try (await (test!)) (finally (set! js/fetch original)))))

(defn- ^:async failure-of [opts]
  (try (await (http/request! opts)) nil
       (catch :default error (http/error-data error))))

(deftest request-codecs-query-encoding-and-response-status
  (async done
    (let [uri "https://api.example.test/path?existing=1#anchor"
          seen (atom nil)
          fetch! (fn ^:async respond [url options]
                   (reset! seen {:url (js/URL. url) :headers (.-headers options) :method (.-method options)})
                   (case (.-method options)
                     "POST" (js/Response. (.-body options) #js {:status 200})
                     "DELETE" (js/Response. "{\"error\":\"Conflict\"}" #js {:status 409})
                     (js/Response. "{\"ok\":true}" #js {:status 200})))
          test! (fn ^:async check []
                  (is (= {:ok true}
                         (await (http/request! {:uri uri :url-params {:path "a/b?x=2" :item ["a" "b"]}}))))
                  (let [query (.-searchParams (:url @seen))]
                    (is (= "a/b?x=2" (.get query "path")))
                    (is (= ["a" "b"] (vec (.getAll query "item"))))
                    (is (= "1" (.get query "existing"))))
                  (is (not (.has (:headers @seen) "x-csrf-token")))
                  (is (not (.has (:headers @seen) "Content-Type")))
                  (let [date (js/Date. "2026-10-10T23:04:05Z")
                        value {:id :message :time date :items [nil false "text"]}
                        result (await (http/request! {:method :post :uri uri :params value
                                                      :format :transit :response-format :transit}))]
                    (is (= value result))
                    (is (= "application/transit+json" (.get (:headers @seen) "Content-Type"))))
                  (let [failure (await (failure-of {:method :delete :uri uri}))]
                    (is (= 409 (:status failure)))
                    (is (= {:error "Conflict"} (:response failure))))
                  (reset! seen nil)
                  (is (= 0 (:status (await (failure-of {:uri uri :headers {"Authorization" "Bearer example"}})))))
                  (is (nil? @seen)))]
      (-> (with-fetch! fetch! test!)
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest decode-errors-deadlines-and-explicit-cancellation
  (async done
    (let [bad (fn ^:async malformed [_ _] (js/Response. "not-json" #js {:status 200}))
          pending (fn [_ opts]
                    (a/create (fn [_ reject]
                                (let [signal (.-signal opts) aborted #(reject (js/Error. "aborted"))]
                                  (if (.-aborted signal) (aborted) (.addEventListener signal "abort" aborted))))))
          uri "https://api.example.test/response"]
      (-> ((fn ^:async check []
             (let [e (await (with-fetch! bad #(failure-of {:uri uri})))]
               (is (= {:status 200 :failure :parse} (select-keys e [:status :failure]))))
             (let [e (await (with-fetch! pending #(failure-of {:uri uri :timeout 5})))]
               (is (= {:status 0 :failure :timeout} (select-keys e [:status :failure]))))
             (let [controller (js/AbortController.)
                   e (with-fetch! pending #(failure-of {:uri uri :signal (.-signal controller)}))]
               (.abort controller)
               (is (= :aborted (:failure (await e)))))))
          (.catch #(is false (str %)))
          (.finally done)))))
