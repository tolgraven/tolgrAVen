(ns tolgraven.build-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [tolgraven.build.modules :as modules]
            [clojure.java.io :as io]))

(deftest module-build-and-runtime-catalog-have-one-owner
  (let [found (modules/discover)
        by-id (into {} (map (juxt :id identity)) found)]
    (is (every? #(contains? by-id %) [:blog :cv :docs :test :user :link-preview]))
    (is (not (contains? by-id :main)))
    (is (= 'tolgraven.modules.experiments.module (get-in by-id [:test :entry])))
    (is (= #{:main :user :link-preview} (get-in by-id [:blog :depends-on])))
    (is (= #{:main} (get-in by-id [:cv :depends-on])))
    (is (= (set (keys by-id)) (set (keys (modules/bundles)))))))

(deftest declaration-is-data-and-does-not-evaluate-browser-code
  (let [file (java.io.File/createTempFile "module-declaration" ".cljs")]
    (try
      (spit file "(ns example.module {:bundle/depends-on #{:main :user}})\n(def spec {:id :example :init #(js/alert :never)})")
      (is (= {:id :example :entry 'example.module :depends-on #{:main :user}}
             (modules/declaration file)))
      (spit file "(ns example.module)\n(def spec {:id (identity :example)})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal spec" (modules/declaration file)))
      (spit file "#=(throw (Exception. \"must not run\"))")
      (is (thrown? RuntimeException (modules/declaration file)))
      (finally (.delete file)))))

(deftest stylesheet-catalog-contains-transitive-code-dependencies
  (let [file (java.io.File/createTempFile "module-styles" ".cljs")]
    (try
      (spit file "(ns example.module)\n(def spec {:id :example :styles [\"/css/example.css\"]})")
      (is (= ["/css/example.css"] (:styles (modules/declaration file))))
      (spit file "(ns example.module)\n(def spec {:id :example :styles (identity [])})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal vector" (modules/declaration file)))
      (finally (.delete file)))))
