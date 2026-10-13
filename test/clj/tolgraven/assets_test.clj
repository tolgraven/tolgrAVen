(ns tolgraven.assets-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [tolgraven.env :as environment]
            [tolgraven.handler :as handler]
            [tolgraven.config :as config]
            [tolgraven.layout :as layout]
            [tolgraven.middleware :as middleware]))

(deftest relocated-module-map-url-resolves-against-every-document-and-bundle
  (let [asset {:path "/js/compiled/out/main.ABC123.js"
               :contents "var app = 1;\n//# sourceMappingURL=main.ABC123.js.map\n"}
        transformed (first (middleware/module-source-maps [asset]))]
    (is (= "/js/compiled/out/main.ABC123.js" (:path transformed)))
    (is (= "var app = 1;\n//# sourceMappingURL=/js/compiled/out/main.ABC123.js.map\n"
           (:contents transformed)))
    (doseq [parent ["https://example.com/blog/post/17"
                    "https://example.com/bundles/checksum/main.js"]]
      (is (= "https://example.com/js/compiled/out/main.ABC123.js.map"
             (str (.resolve (java.net.URI. parent) "/js/compiled/out/main.ABC123.js.map")))))
    (is (= transformed (first (middleware/module-source-maps [transformed]))) "Idempotent")))

(deftest other-assets-and-existing-absolute-map-urls-stay-identical
  (doseq [asset [{:path "/vendor/supabase.js"
                 :contents "//# sourceMappingURL=supabase.js.map"}
                {:path "/js/compiled/out/main.js"
                 :contents "//# sourceMappingURL=https://cdn.example/main.js.map"}
                {:path "/js/compiled/out/main.js"
                 :contents "//# sourceMappingURL=/js/compiled/out/main.js.map"}
                {:path "/js/compiled/out/main.js"
                 :contents "var app = 1;"}
                {:path "/img/example.avif", :contents (byte-array [1 2 3])}]]
    (is (= asset (first (middleware/module-source-maps [asset]))))))

(deftest production-refuses-missing-or-watched-browser-output
  (let [released {:output-name "main.0123456789abcdef0123456789abcdef.js"
                  :entries ['tolgraven.app]}]
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
