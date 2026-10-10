(ns tolgraven.inflight-cache-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [tolgraven.cache.inflight :as inflight]))

(deftest cache-pressure-preserves-pending-and-rejects-excess-work
  (let [pending (promise)
        completed (deliver (promise) :complete)
        cache (atom {:pending {:value pending, :expires-at 0}
                     :completed {:value completed, :expires-at 100}})
        [owner? entry :as acquisition] (inflight/acquire! cache :new 10 100 2)]
    (is owner?)
    (is (m/validate inflight/acquisition-schema acquisition))
    (is (identical? pending (get-in @cache [:pending :value])))
    (is (not (contains? @cache :completed)))
    (is (= [false entry] (inflight/acquire! cache :new 20 100 2)))
    (is (= [false nil] (inflight/acquire! cache :excess 20 100 2)))
    (is (= #{:pending :new} (set (keys @cache))))))

(deftest only-completed-expired-entries-are-pruned
  (let [cache (atom {})
        [_ first-entry] (inflight/acquire! cache :first 10 10 1)]
    (is (= [false first-entry] (inflight/acquire! cache :first 100 10 1)))
    (deliver (:value first-entry) :done)
    (let [[owner? next-entry] (inflight/acquire! cache :first 100 10 1)]
      (is owner?)
      (is (not (identical? (:value first-entry) (:value next-entry)))))))

(deftest failure-expiry-cannot-lengthen-or-recreate-a-replaced-entry
  (let [cache (atom {})
        [_ entry] (inflight/acquire! cache :url 10 100 1)]
    (inflight/shorten! cache :url entry 40)
    (is (= 40 (get-in @cache [:url :expires-at])))
    (inflight/shorten! cache :url entry 90)
    (is (= 40 (get-in @cache [:url :expires-at])))
    (deliver (:value entry) :done)
    (let [[_ replacement] (inflight/acquire! cache :url 100 100 1)]
      (inflight/shorten! cache :url entry 110)
      (is (= replacement (get @cache :url))))
    (reset! cache {})
    (inflight/shorten! cache :url entry 110)
    (is (empty? @cache))))
