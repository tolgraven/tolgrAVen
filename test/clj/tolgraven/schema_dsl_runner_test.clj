(ns tolgraven.schema-dsl-runner-test
  (:require [clojure.test :refer [deftest test-vars]]
            [tolgraven.schema-dsl-test]))

(deftest shared-shorthand-contracts
  (test-vars (vals (ns-publics 'tolgraven.schema-dsl-test))))
