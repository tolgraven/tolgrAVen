(ns tolgraven.ssr.return-policy
  "Return-document decisions over ordinary data; browser handles stay in the worker."
  (:require [clojure.string :as string]))

(def ttl-ms 1800000)
(def max-documents 8)
(def prefix "tolgraven-return-v1-")

(def request-schema
  [:map [:url :string] [:origin :string] [:method :string] [:mode :string]])
(def arm-schema [:map [:url :string] [:expires :int]])
(def navigation-plan-schema
  [:map [:source [:enum :cache :network]] [:returning? :boolean]])

(defn intercept? [{:keys [origin method mode]} worker-origin]
  (and (= mode "navigate") (= method "GET") (= origin worker-origin)))

(defn navigation-plan [url saved-at armed now]
  {:source     (if (and (number? saved-at) (< (- now saved-at) ttl-ms))
                 :cache
                 :network)
   :returning? (boolean (and (= url (:url armed))
                            (number? (:expires armed))
                            (> (:expires armed) now)))})

(defn arm-record [url now]
  {:url url, :expires (+ now ttl-ms)})

(defn excess-documents [entries]
  ;; Cache.keys supplies insertion order; retain the newest bounded suffix.
  (drop-last max-documents entries))

(defn obsolete-caches [names current]
  (filter #(and (string/starts-with? % prefix) (not= % current)) names))
