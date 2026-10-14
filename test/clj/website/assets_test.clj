(ns website.assets-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [website.env :as environment]
            [website.handler :as handler]
            [website.config :as config]
            [website.layout :as layout]
            [website.middleware :as middleware]))

(deftest shadow-owns-the-main-entry-without-an-optimus-copy
  (doseq [output [nil "main.js" "main.0123456789abcdef0123456789abcdef.js"]]
    (with-redefs [layout/browser-modules (constantly (when output {:main {:output-name output}}))]
      (let [path (layout/main-script-path)
            inventory (middleware/get-bundled-assets)]
        (is (= (str "/js/compiled/out/" (or output "main.js")) path))
        (is (not-any? #(= "main.js" (:bundle %)) inventory))
        (is (not-any? #(= path (:path %)) inventory))
        (is (= (str "https://example.com" path ".map")
               (str (.resolve (java.net.URI. (str "https://example.com" path))
                              (str (or output "main.js") ".map")))))))))

(deftest production-refuses-missing-or-watched-browser-output
  (let [released {:output-name "main.0123456789abcdef0123456789abcdef.js"
                  :entries ['website.app]}]
    (with-redefs [config/env {:prod true}]
      (doseq [manifest [nil {:main {:output-name "main.js"}}
                        {:main (assoc released :entries ['shadow.cljs.devtools.client.browser])}]]
        (with-redefs [layout/browser-modules (constantly manifest)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bb prod:build"
                               (middleware/ensure-production-assets!)))))
      (with-redefs [layout/browser-modules (constantly {:main released})
                    io/resource (constantly ::resource)]
        (is (nil? (middleware/ensure-production-assets!)))))
    (with-redefs [config/env {:dev true}
                  layout/browser-modules (constantly {:main {:output-name "main.js"}})]
      (is (nil? (middleware/ensure-production-assets!))))))

(deftest production-requires-both-document-bootstrap-resources
  (let [released {:main {:output-name "main.0123456789abcdef0123456789abcdef.js"}}]
    (with-redefs [config/env {:prod true}
                  layout/browser-modules (constantly released)]
      (doseq [[available missing]
              [[#{} #{"/js/document/head.js" "/js/document/entrance.js"}]
               [#{"public/js/document/head.js"} #{"/js/document/entrance.js"}]
               [#{"public/js/document/entrance.js"} #{"/js/document/head.js"}]]]
        (with-redefs [io/resource #(when (available %) ::resource)]
          (try
            (middleware/ensure-production-assets!)
            (is false "Missing document scripts must fail preflight")
            (catch clojure.lang.ExceptionInfo error
              (is (= :document (:build (ex-data error))))
              (is (= missing (set (:missing-assets (ex-data error)))))
              (is (re-find #"bb prod:build" (.getMessage error)))))))
      (with-redefs [io/resource (constantly ::resource)]
        (is (nil? (middleware/ensure-production-assets!)))))))

(deftest missing-document-bootstrap-prevents-initialization-success
  (let [initialized (atom false)]
    (with-redefs [config/env {:prod true}
                  layout/browser-modules (constantly {:main {:output-name "main.0123456789abcdef0123456789abcdef.js"}})
                  io/resource (constantly nil)
                  environment/defaults (assoc environment/defaults :init #(reset! initialized true))]
      (try
        (is (thrown-with-msg? RuntimeException #"could not start"
                             (mount/start #'handler/init-app)))
        (is (false? @initialized))
        (finally (mount/stop #'handler/init-app))))))

(deftest production-requires-the-prepared-inventory-before-initialization
  (with-redefs [config/env {:prod true}
                layout/browser-modules (constantly {:main {:output-name "main.0123456789abcdef0123456789abcdef.js"}})
                io/resource #(when (re-find #"^public/js/document/" %) ::resource)]
    (try
      (middleware/ensure-production-assets!)
      (is false "A missing prepared inventory must fail preflight")
      (catch clojure.lang.ExceptionInfo error
        (is (= :assets (:build (ex-data error))))
        (is (= ["optimus/assets.edn"] (:missing-assets (ex-data error))))
        (is (re-find #"bb prod:build" (.getMessage error)))))))
