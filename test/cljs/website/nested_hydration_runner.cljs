(ns website.nested-hydration-runner
  (:require [cljs.test :as test :include-macros true]
            [website.authored-hydration-test]
            [website.nested-hydration-test]))

(defmethod test/report [::test/default :end-run-tests] [summary]
  (set! (.-textContent (.getElementById js/document "report")) (pr-str summary))
  (.log js/console "Nested hydration" (pr-str summary)))

(defn ^:export init []
  (test/run-tests (test/empty-env) 'website.authored-hydration-test 'website.nested-hydration-test))
