(ns website.macros-loading-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]))

(deftest macro-loading-keeps-the-schema-engine-out-of-the-compiler-boundary
  ;; A new JVM prevents another suite's Malli initialization from hiding this
  ;; dependency. Macros emit schema data; runtime adapters own interpretation.
  (let [program "(require 'website.macros)
                 (assert (= '[value] (:args (website.macros/component-arguments '[value :- :string]))))
                 (assert (not (contains? (loaded-libs) 'malli.core)))
                 (assert (not (contains? (loaded-libs) 'malli.error)))
                 (println :ok)"
        ;; Exclude development user.clj: Clojure auto-loads it before -e and
        ;; that application REPL bootstrap initializes the runtime engine.
        separator java.io.File/pathSeparator
        classpath (->> (string/split (System/getProperty "java.class.path")
                                     (re-pattern (java.util.regex.Pattern/quote separator)))
                       (remove #(.endsWith (string/replace % "\\" "/") "/env/dev/clj"))
                       (string/join separator))
        result (shell/sh "java" "-cp" classpath
                         "clojure.main" "-e" program)]
    (is (zero? (:exit result)) (:err result))
    (is (= ":ok\n" (:out result)))))
