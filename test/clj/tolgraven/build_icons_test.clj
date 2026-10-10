(ns tolgraven.build-icons-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tolgraven.build.icons :as icons]))

(def modules [{:id :main :entry 'fixture.main.module}
              {:id :user :entry 'fixture.user.module}])

(defn with-source! [text check!]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "icon-declarations" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file directory "views.cljc")]
    (try
      (spit file text)
      (check! (fn [] (icons/definitions modules [(str directory)])))
      (finally (.delete file) (.delete directory)))))

(deftest icon-dependencies-are-data-not-browser-code
  (with-source!
    "(ns fixture (:require [example.events :as events]))
     (def never-run (js/alert ::events/no))
     (def component {:icons [\"brands/github\" \"brands/github\"]})
     (def feature {:module :user :icons [\"brands/github\" \"brands/google\"]})
     (def opts #js {:ready true})
     #?(:cljs (def other {:module :user :icons [\"solid/copy\"]})
        :clj (throw (Exception. \"must not run\")))"
    (fn [read!]
      (let [result (read!)]
        (is (= {:main ["brands/github"] :user ["brands/google" "solid/copy"]} result))
        (is (= 1 (count (:sources (meta result)))))))))

(deftest malformed-declarations-fail-before-generating-assets
  (doseq [declaration ["{:icons (identity [])}"
                       "{:icons [\"brands/../github\"]}"
                       "{:icons [\"unknown/github\"]}"
                       "{:module :missing :icons [\"brands/github\"]}"]]
    (with-source! (str "(ns fixture) (def spec " declaration ")")
      (fn [read!] (is (thrown? clojure.lang.ExceptionInfo (read!))))))
  (with-source! "(ns fixture) #=(throw (Exception. \"must not run\")) {:icons []}"
    (fn [read!] (is (thrown? Exception (read!))))))

(deftest icon-styles-have-one-owning-bundle
  (is (nil? (icons/stylesheet-path :main)))
  (is (= "/css/tolgraven/modules/icons-user.min.css" (icons/stylesheet-path :user)))
  (is (icons/valid-icons? []))
  (is (not (icons/valid-icons? '("brands/github")))))
