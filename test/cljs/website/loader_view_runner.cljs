(ns website.loader-view-runner
  (:require [cljs.test :as test :include-macros true]
            [website.loader-view-test]))

(defmethod test/report [::test/default :end-run-tests] [summary]
  (set! (.-textContent (.getElementById js/document "report")) (pr-str summary))
  (.log js/console "Lightweight loader" (pr-str summary)))

(defn ^:export init []
  (test/run-tests (test/empty-env) 'website.loader-view-test))
