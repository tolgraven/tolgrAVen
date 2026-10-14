(ns website.dev-console-runner
  (:require [cljs.test :as test :include-macros true]
            [website.dev-console-test]
            [website.dev-diagnostics-test]
            [website.events]
            [website.subs]))

(defmethod test/report [::test/default :end-run-tests] [summary]
  (set! (.-textContent (.getElementById js/document "report")) (pr-str summary))
  (.log js/console "Development console" (pr-str summary)))

(defn ^:export init []
  (test/run-tests (test/empty-env) 'website.dev-console-test 'website.dev-diagnostics-test))
