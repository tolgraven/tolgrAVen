(ns tolgraven.deployment-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.deployment :as deployment]))

(deftest public-deployment-identity
  (let [sha (apply str (repeat 40 "a"))]
    (is (= {:commit sha
            :deployed-at "2026-10-13T12:30:00Z"
            :commit-url (str "https://github.com/another/site/commit/" sha)}
           (deployment/public-info {:commit sha, :deployed-at "2026-10-13T12:30:00Z"} "another/site")))
    (is (= {:commit nil, :deployed-at nil, :commit-url nil}
           (deployment/public-info {:commit "master", :deployed-at "today", :token "private"} "https://bad")))
    (is (nil? (:commit-url (deployment/public-info {:commit sha} nil))))
    (is (nil? (:deployed-at (deployment/public-info {} nil))))))
