;; Legacy narrow runner. The full browser suite is Shadow :app-test; see doc/testing.md.
(ns tolgraven.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [tolgraven.core-test]))

(doo-tests 'tolgraven.core-test)

