(ns tolgraven.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [tolgraven.core-test]))

(doo-tests 'tolgraven.core-test)

