(ns tolgraven.authored-hydration-runner
  "A focused cljs.test runner avoids the development-only display library in release."
  (:require [cljs.test :as test :include-macros true]
            [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.test-support :as support]
            [tolgraven.authored-hydration-test]))

(defonce *summary (r/atom nil))
(defonce *root (atom nil))

(defc <report> []
  [:main
   [:h1 "Release widget hydration"]
   (if-let [summary @*summary]
     [:section {:data-test-result (if (test/successful? summary) "passed" "failed")}
      [:h2 (if (test/successful? summary) "All tests passed" "Tests failed")]
      [:p (str (:test summary) " tests, " (+ (:pass summary) (:fail summary) (:error summary)) " assertions")]
      [:pre (pr-str summary)]]
     [:p {:role "status"} "Checking delayed component and validation owners…"])])

(defmethod test/report [::test/default :end-run-tests] [summary]
  (reset! *summary summary)
  (.log js/console (str "Release widget hydration: " (pr-str summary))))

(defn start []
  (reset! *summary nil)
  (test/run-tests (test/empty-env) 'tolgraven.authored-hydration-test))

(defn stop [done]
  (when @*root (support/unmount! @*root) (reset! *root nil))
  (done))

(defn ^:export init []
  (-> (support/create-root! (.getElementById js/document "release-report"))
      (.then (fn [root]
               (reset! *root root)
               (support/render! root [<report>])))
      (.then (fn [_] (start)))))
