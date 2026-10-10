(ns tolgraven.ajax-test
  (:require [cljs.test :refer-macros [deftest is]]
            [cognitect.transit :as transit]
            [tolgraven.ajax :as ajax]))

(deftest transit-defaults-preserve-native-values-and-explicit-overrides
  (let [opts (ajax/as-transit {})
        date (js/Date. "2026-10-10T23:04:05Z")
        value {:id :message, :time date, :items [1 nil false "text"]}
        decoded (transit/read (:reader opts) (transit/write (:writer opts) value))]
    (is (= (:id value) (:id decoded)))
    (is (= (.getTime date) (.getTime (:time decoded))))
    (is (= (:items value) (:items decoded)))
    (is (= :json (:format (ajax/as-transit {:format :json}))))
    (is (= :custom (:reader (ajax/as-transit {:reader :custom}))))))
