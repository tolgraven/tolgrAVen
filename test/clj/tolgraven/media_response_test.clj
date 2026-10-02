(ns tolgraven.media-response-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [optimus.assets :as assets]
            [tolgraven.config :as config]
            [tolgraven.env :as env]
            [tolgraven.middleware :as middleware]))

(deftest optimized-image-responses
  (testing "production asset middleware preserves image bytes and MIME types"
    ;; Stage permits local HTTP while retaining frozen, optimized assets.
    (with-redefs [config/env {:stage true}
                  env/defaults {:middleware identity}
                  middleware/get-assets
                  #(assets/load-assets "public" ["/img/tolgrav.avif"
                                                "/img/tolgrav.webp"])]
      (let [handler (middleware/wrap-base
                     (constantly {:status 404
                                  :headers {"Content-Type" "text/plain"}
                                  :body "Not found"}))]
        (doseq [[path mime-type] [["/img/tolgrav.avif" "image/avif"]
                                 ["/img/tolgrav.webp" "image/webp"]]]
          (let [response (handler {:request-method :get
                                   :uri path
                                   :headers {"sec-fetch-dest" "image"}})]
            (is (= 200 (:status response)))
            (is (= mime-type (get-in response [:headers "Content-Type"])))
            (with-open [actual (io/input-stream (:body response))
                        expected (io/input-stream (io/resource (str "public" path)))]
              (is (= (vec (.readAllBytes expected))
                     (vec (.readAllBytes actual)))))))))))
