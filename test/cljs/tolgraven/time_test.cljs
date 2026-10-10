(ns tolgraven.time-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tolgraven.util :as util]))

(deftest utc-time-formatting-preserves-log-and-chat-output
  (let [ms (js/Date.parse "2026-10-10T23:04:05.678Z")]
    (is (= "23:04:05" (util/unix->ts ms)))
    (is (= "2026-10-10" (util/unix->ts ms :date)))
    (is (= "00:00:00" (util/unix->ts 0)))
    (is (= "" (util/unix->ts nil)))
    (is (= "" (util/unix->ts js/NaN)))
    (is (thrown? js/Error (util/unix->ts ms :unknown)))))

(deftest relative-time-boundaries-and-invalid-data
  (let [now (js/Date.parse "2026-10-10T23:04:05Z")]
    (doseq [[age expected] [[0 "now"]
                           [59999 "now"]
                           [60000 "1 minute ago"]
                           [3599999 "59 minutes ago"]
                           [3600000 "1 hour ago"]
                           [7200000 "2 hours ago"]
                           [86399999 "23 hours ago"]
                           [86400000 "2026-10-09"]
                           [-60000 "now"]]]
      (is (= expected (util/timestamp (- now age) now))))
    (is (= "" (util/timestamp nil now)))
    (is (= "" (util/timestamp js/NaN now)))
    (is (= "" (util/timestamp "wrong" now)))))

(deftest calendar-month-subtraction-clamps-leap-and-short-months
  (doseq [[clock expected] [["2024-03-31T12:00:00Z" "2024-02-29"]
                           ["2025-03-31T12:00:00Z" "2025-02-28"]
                           ["2026-01-31T12:00:00Z" "2025-12-31"]
                           ["2026-05-31T12:00:00Z" "2026-04-30"]]]
    (is (= expected (util/previous-month-date (js/Date.parse clock))))))

(deftest provider-clock-uses-native-local-time-and-offset
  (let [clock "2026-10-10T23:04:05Z"
        date (js/Date. clock)
        pad #(str (when (< % 10) "0") %)
        offset (.getTimezoneOffset date)]
    (is (= (str (pad (.getHours date)) ":" (pad (.getMinutes date)) ":" (pad (.getSeconds date))
                (if (pos? offset) "-" "+") (pad (int (/ (abs offset) 60))) ":" (pad (mod (abs offset) 60)))
           (util/local-time clock)))
    (is (= "" (util/local-time "invalid")))))
