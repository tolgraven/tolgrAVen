(ns tolgraven.assets-test
  (:require [clojure.test :refer [deftest is]]
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
