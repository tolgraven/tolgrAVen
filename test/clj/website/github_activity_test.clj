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

(defn public-day [total headings]
  ;; Structure captured from GitHub's public single-day profile response.
  (str "<table><td id='day' data-date='2026-10-13' data-level='4'></td></table>"
       "<tool-tip for='day'>" total " contributions on October 13th.</tool-tip>"
       "<div class='contribution-activity-listing'>" headings "</div>"
       "<form class='js-show-more-timeline-form' data-from='2026-10-13' data-to='2026-10-13'></form>"))

(def captured-headings
  (str "<div class='TimelineItem-body'><details><summary>Created 21 commits in 1 repository</summary>"
       "<summary>21 commits</summary></details></div>"
       "<div class='TimelineItem-body'><h4>Created a pull request in owner/repo that received 1 comment</h4>"
       "<h3>A title mentioning 999 commits</h3></div>"
       "<div class='TimelineItem-body'><summary>Opened 18 other pull requests in 1 repository</summary></div>"
       "<div class='TimelineItem-body'><summary>Reviewed 3 pull requests in 1 repository</summary></div>"
       "<div class='TimelineItem-body'><span class='f4'>2 contributions in private repositories</span></div>"))

(deftest public-day-breakdown-counts-featured-items-and-rollups-once
  (is (= {:date "2026-10-13"
          :types {:commits 21
                  :issues 0
                  :pull-requests 19
                  :reviews 3
                  :repositories 0
                  :restricted 2}}
         (activity/parse-day (public-day 45 captured-headings) "2026-10-13")))
  (is (= {:commits 1234, :issues 3, :pull-requests 0, :reviews 0, :repositories 1, :restricted 1}
         (:types (activity/parse-day
                   (public-day 1239
                     (str "<div class='TimelineItem-body'><summary>Created 1,234 commits in 2 repositories</summary></div>"
                          "<div class='TimelineItem-body'><h4>Opened an issue in owner/repo</h4></div>"
                          "<div class='TimelineItem-body'><summary>Opened 2 other issues in 1 repository</summary></div>"
                          "<div class='TimelineItem-body'><h4>Created a repository</h4></div>"
                          "<div class='TimelineItem-body'><span class='f4'>1 contribution in private repositories</span></div>"))
                   "2026-10-13")))))

(deftest public-day-breakdown-never-invents-missing-counts
  (doseq [html [(public-day 46 captured-headings)
                (public-day 1 "<div class='TimelineItem-body'><summary>An unknown activity</summary></div>")
                (clojure.string/replace (public-day 45 captured-headings) "data-to='2026-10-13'" "data-to='2026-10-14'")
                "<p>Sign in to GitHub</p>"]]
    (is (thrown? Exception (activity/parse-day html "2026-10-13"))))
  (is (every? zero? (vals (:types (activity/parse-day (public-day 0 "") "2026-10-13"))))))
