(ns tolgraven.development-styles-test
  (:require [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [mount.core :as mount]
            [tolgraven.dev.shadow :as shadow]
            [tolgraven.routes.home :as home]
            [user :as user]))

(deftest current-styles-precede-http-and-deferred-shadow
  (let [*calls (atom [])
        previous-explain s/*explain-out*]
    (try
      (with-redefs [shell/sh (fn [& args]
                              (swap! *calls conj [:styles args])
                              {:exit 0, :out "CSS is current\n", :err ""})
                    home/gen-sitemap! #(swap! *calls conj :sitemap)
                    mount/start-without (fn [& _]
                                          (swap! *calls conj :http)
                                          {:started []})
                    clojure.core/add-tap (fn [_])
                    shadow/start! (fn [_] (swap! *calls conj :shadow))]
        (is (nil? (user/start)))
        (is (= [[:styles '("node" "scripts/build/styles.mjs" "--if-stale")]
                :sitemap :http :shadow]
               @*calls)))
      (finally (alter-var-root #'s/*explain-out* (constantly previous-explain))))))

(deftest style-failure-does-not-open-http-with-old-sheets
  (let [*started? (atom false)]
    (with-redefs [shell/sh (fn [& _] {:exit 1, :out "", :err "fixture Sass failure"})
                  mount/start-without (fn [& _] (reset! *started? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Development styles failed"
                           (user/start)))
      (is (false? @*started?)))))
