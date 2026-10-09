(ns tolgraven.oembed
  (:require [clj-http.client :as http]
            [tolgraven.components.oembed.contract :as contract])
  (:import [org.jsoup Jsoup]
           [java.util.concurrent Semaphore]))

(defn normalize-result
  "Discard provider-supplied player metadata and derive a safe native frame URL.
   Other markup remains available only to the browser's opaque sandbox."
  [response]
  (let [html (if (string? (:html response))
               (subs (:html response) 0 (min 65536 (count (:html response)))) "")
        document (Jsoup/parse html)
        source (some-> (.selectFirst document "iframe[src]") (.attr "src") contract/player-url)]
    (cond-> (assoc (select-keys response [:html :title :height])
                   :html html
                   :title (if (string? (:title response)) (:title response) "Embedded player")
                   :height (if (and (number? (:height response)) (pos? (:height response)))
                             (:height response) 166))
      source (assoc :player-src source))))

(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))
(def cache-limit 128)
(def cache-ttl-ms 900000)
(def failure-ttl-ms 60000)

(defn- unavailable []
  {:status 503
   :headers {"Cache-Control" "no-store"}
   :body {:error "The player could not be loaded; try again shortly"}})

(defn- acquire! [url]
  (if-not (.tryAcquire slots)
    (unavailable)
    (try
      (let [response (:body (http/get (str "https://noembed.com/embed?url="
                                          (java.net.URLEncoder/encode url "utf-8"))
                                     {:as :json
                                      :conn-timeout 3000
                                      :socket-timeout 5000}))]
        (if (:error response)
          (unavailable)
          {:status 200
           :headers {"Cache-Control" "private, max-age=60"}
           :body (normalize-result response)}))
      (catch Exception _ (unavailable))
      (finally (.release slots)))))

(defn response!
  "Bounded, shared in-flight oEmbed reads. Cache successful metadata for fifteen
   minutes and failures for one minute; never retain unconsumed provider fields."
  [url]
  (let [now (System/currentTimeMillis)
        [owner? entry]
        (locking *cache
          (if-let [entry (let [entry (get @*cache url)]
                          (when (> (:expires-at entry 0) now) entry))]
            [false entry]
            (let [entry {:value (promise)
                         :expires-at (+ now cache-ttl-ms)}]
              (swap! *cache #(->> %
                                 (filter (fn [[_ value]] (> (:expires-at value) now)))
                                 (sort-by (comp :expires-at val))
                                 (take-last (dec cache-limit))
                                 (into {})
                                 ((fn [entries] (assoc entries url entry)))))
              [true entry])))]
    (when owner?
      (let [response (acquire! url)]
        (when-not (= 200 (:status response))
          (swap! *cache (fn [cache]
                         (if (identical? (:value entry) (get-in cache [url :value]))
                           (assoc-in cache [url :expires-at]
                                     (+ (System/currentTimeMillis) failure-ttl-ms))
                           cache))))
        (deliver (:value entry) response)))
    (deref (:value entry) 8500 (unavailable))))
