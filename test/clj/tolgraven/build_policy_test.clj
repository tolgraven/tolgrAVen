(ns tolgraven.build-policy-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.build.policy :as policy]))

(deftest resolved-release-sources-exclude-development-inspectors
  (doseq [source [{:ns 'day8.re-frame-10x}
                  {:ns 'day8.re-frame-10x.preload}
                  {:ns 're-frisk.core}
                  {:ns 're-frisk-remote.core}
                  {:ns 'tolgraven.legacy-debug}
                  {:resource-name "day8/re_frame_10x.cljs"}
                  {:resource-name "day8/re_frame_10x/panels/app_db.cljs"}
                  {:resource-name "re_frisk/core.cljs"}
                  {:resource-name "re_frisk_remote/core.cljs"}]]
    (let [state {:mode :release
                 :build-sources [:active]
                 :sources {:active source}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot enter a production"
                           (policy/no-debug-tools state)))
      (is (= (assoc state :mode :dev)
             (policy/no-debug-tools (assoc state :mode :dev)))))))

(deftest ordinary-production-effects-and-unused-cache-entries-are-allowed
  (let [state {:mode :release
               :build-sources [:http]
               :sources {:http {:ns 'day8.re-frame.http-fx
                                :resource-name "day8/re_frame/http_fx.cljs"}
                         :old {:ns 'day8.re-frame-10x}}}]
    (is (identical? state (policy/no-debug-tools state)))))
