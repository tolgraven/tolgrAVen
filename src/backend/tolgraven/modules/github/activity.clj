(ns tolgraven.modules.github.activity
  "Read GitHub's public calendar from a fixed origin with bounded time and cache."
  (:require [clojure.string :as string]
            [tolgraven.cache.inflight :as inflight])
  (:import [org.jsoup Jsoup]
           [java.util.concurrent Semaphore]))

(def user-schema [:re #"^[A-Za-z0-9][A-Za-z0-9-]{0,38}$"])
(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))

(defn parse-calendar [html]
  (let [document (Jsoup/parse html)
        labels (into {} (for [tip (.select document "tool-tip[for]")]
                          [(.attr tip "for") (.text tip)]))
        days (->> (.select document "td[data-date][data-level]")
                  (mapv (fn [cell]
                          (let [label (get labels (.id cell))
                                count (cond
                                        (string/starts-with? (or label "") "No contributions") 0
                                        :else (some-> (re-find #"^([0-9,]+) contribution" (or label ""))
                                                      second (string/replace "," "") Long/parseLong))]
                            (when (nil? count) (throw (ex-info "Calendar count unavailable" {})))
                            {:date (.attr cell "data-date")
                             :count count
                             :level (Long/parseLong (.attr cell "data-level"))})))
                  (sort-by :date)
                  vec)]
    (when-not (and (<= 1 (count days) 371)
                   (every? #(and (re-matches #"\d{4}-\d{2}-\d{2}" (:date %))
                                 (<= 0 (:level %) 4)) days))
      (throw (ex-info "Invalid public calendar" {})))
    {:days days}))

(defn fetch-calendar! [user]
  (-> (Jsoup/connect (str "https://github.com/users/" user "/contributions"))
      (.timeout 7000)
      (.maxBodySize 1048576)
      (.followRedirects false)
      (.get)
      (.outerHtml)
      parse-calendar))

(defn- unavailable []
  {:status 503
   :headers {"Cache-Control" "no-store"}
   :body {:error "GitHub activity is temporarily unavailable"}})

(defn response! [user]
  (if-not (and (string? user) (re-matches #"[A-Za-z0-9][A-Za-z0-9-]{0,38}" user))
    {:status 400, :body {:error "Invalid GitHub user"}}
    (let [now (System/currentTimeMillis)
          [owner? entry] (inflight/acquire! *cache user now 900000 128)]
      (when owner?
        (let [response (if (.tryAcquire slots)
                         (try {:status 200
                               :headers {"Cache-Control" "public, max-age=300"}
                               :body (fetch-calendar! user)}
                              (catch Exception _ (unavailable))
                              (finally (.release slots)))
                         (unavailable))]
          (when-not (= 200 (:status response))
            (inflight/shorten! *cache user entry (+ now 60000)))
          (deliver (:value entry) response)))
      (if entry (deref (:value entry) 8000 (unavailable)) (unavailable)))))
