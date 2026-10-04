(ns tolgraven.module-macro-test
  (:require [clojure.test :refer [deftest is]]
            [cljs.env :as env]
            [tolgraven.macros]))

(deftest browser-module-references-require-shadow-module-context
  (let [form '(shadow.lazy/loadable tolgraven.blog.module/spec)
        expand #(binding [env/*compiler* (atom %)]
                  (macroexpand-1 (list 'tolgraven.macros/browser-only form)))
        browser {:shadow.build/ns->mod {'tolgraven.blog.module :blog}}]
    (is (nil? (expand {})) "Standalone Codox analysis has no lazy-module graph")
    (is (= form (expand browser)) "Browser compilation retains lazy loadables")
    (is (nil? (expand (assoc browser :options
                            {:external-config {:tolgraven/ssr true}})))
        "SSR still excludes browser module references")))
