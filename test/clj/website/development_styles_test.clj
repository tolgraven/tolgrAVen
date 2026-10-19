(ns website.development-styles-test
  (:require [clojure.java.shell :as shell]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [mount.core :as mount]
            [website.dev.shadow :as shadow]
            [website.dev.styles :as styles]
            [website.routes.home :as home]
            [user :as user]))

(deftest current-styles-precede-http-and-deferred-shadow
  (let [*calls (atom [])
        previous-explain s/*explain-out*]
    (try
      (with-redefs [styles/start! #(swap! *calls conj :styles)
                    home/gen-sitemap! #(swap! *calls conj :sitemap)
                    mount/start-without (fn [& _]
                                          (swap! *calls conj :http)
                                          {:started []})
                    clojure.core/add-tap (fn [_])
                    shadow/prepare! #(swap! *calls conj :document-barrier)
                    shadow/start! (fn [_] (swap! *calls conj :shadow))]
        (is (nil? (user/start)))
        (is (= [:styles :document-barrier :sitemap :http :shadow]
               @*calls)))
      (finally (alter-var-root #'s/*explain-out* (constantly previous-explain))))))

(deftest style-failure-does-not-open-http-with-old-sheets
  (let [*started? (atom false)]
    (with-redefs [styles/start! #(styles/ensure-current!)
                  shell/sh (fn [& _] {:exit 1, :out "", :err "fixture Sass failure"})
                  mount/start-without (fn [& _] (reset! *started? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Development styles failed"
                           (user/start)))
      (is (false? @*started?)))))
