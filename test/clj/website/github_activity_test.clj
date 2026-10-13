(ns website.github-activity-test
  (:require [clojure.test :refer [deftest is]]
            [website.modules.github.activity :as activity]))

(deftest public-calendar-counts-and-order
  (is (= {:days [{:date "2026-10-11", :count 0, :level 0}
                {:date "2026-10-12", :count 1234, :level 4}]}
         (activity/parse-calendar
           "<table><td id='b' data-date='2026-10-12' data-level='4'></td><td id='a' data-date='2026-10-11' data-level='0'></td></table><tool-tip for='b'>1,234 contributions on October 12th.</tool-tip><tool-tip for='a'>No contributions on October 11th.</tool-tip>")))
  (is (thrown? Exception (activity/parse-calendar "<p>Log in</p>")))
  (is (thrown? Exception (activity/parse-calendar "<td data-date='2026-10-11' data-level='0'></td>")))
  (is (= 400 (:status (activity/response! "../bad"))))
  (is (= 400 (:status (activity/response! "https://evil.test")))))
