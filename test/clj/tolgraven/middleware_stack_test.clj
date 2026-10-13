(ns tolgraven.middleware-stack-test
  "Compatibility evidence for the proposed stack before production integration."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [reitit.ring :as reitit]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.default-charset :refer [wrap-default-charset]]
            [ring.middleware.content-length :refer [wrap-content-length]]
            [ring.middleware.resource :as resource]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.codec :as codec]
            [reitit.ring.middleware.multipart :as reitit-multipart]
            [tolgraven.middleware :as middleware]
            [tolgraven.middleware.assets :as assets]
            [tolgraven.middleware.multipart :as multipart]
            [tolgraven.middleware.ranges :as ranges]
            [tolgraven.middleware-transport-test :refer [body-text body-bytes upload-request]]
            [tolgraven.routes.services :as services]
            [tolgraven.schema.http :as schemas]
            [tolgraven.config :as config]
            [tolgraven.media.cms :as cms]
            [tolgraven.media.pipeline :as pipeline]
            [tolgraven.media.storage :as storage])
  (:import [java.io File]
           [java.net URL]
           [java.util.jar JarEntry JarOutputStream]))

(defn candidate-stack
  ;; This is a controlled test fixture only. Production stays on the existing
  ;; stack until this actual Ring/Optimus/Reitit composition proves its contracts.
  ([handler inventory] (candidate-stack handler inventory identity))
  ([handler inventory authorize]
   (-> handler
      (defaults/wrap-defaults
        (-> defaults/secure-site-defaults
            (assoc :static {} :security (select-keys (:security defaults/secure-site-defaults) [:anti-forgery]))
            (assoc-in [:params :multipart] false)
            (assoc :responses {})))
      assets/wrap-public-resources
      (assets/serve-assets (constantly inventory) (fn [files _] files) {})
      (wrap-content-type {:mime-types {"avif" "image/avif"}})
      (wrap-default-charset "utf-8")
      wrap-content-length
      middleware/wrap-module-cache
      wrap-not-modified
      ranges/wrap-ranges
      middleware/wrap-media-cache
      middleware/wrap-gzip-content-aware
      multipart/wrap-multipart
      authorize
      (defaults/wrap-defaults
        {:proxy true
         :security (dissoc (:security defaults/secure-site-defaults) :anti-forgery)}))))

(defn request [uri & [headers method]]
  {:request-method (or method :get)
   :scheme :https
   :server-name "localhost"
   :server-port 443
   :uri uri
   :headers (or headers {})})

(deftest candidate-static-source-and-jar-contracts
  (with-redefs [config/env {}]
    (let [file (File/createTempFile "asset-source-" ".avif")
          jar (File/createTempFile "asset-packaged-" ".jar")]
      (spit file "0123456789")
      (with-open [out (JarOutputStream. (io/output-stream jar))]
        (.putNextEntry out (JarEntry. "public/img/fixture.avif"))
        (.write out (.getBytes "0123456789" "UTF-8"))
        (.closeEntry out))
      (try
        (doseq [resource [(.toURL (.toURI file))
                          (URL. (str "jar:" (.toURL (.toURI jar)) "!/public/img/fixture.avif"))]]
          (let [inventory [{:path "/img/fixture.avif"
                            :resource resource
                            :headers {"Last-Modified" "Wed, 21 Oct 2015 07:28:00 GMT"}}
                           {:path "/bundles/styles.hash.css"
                            :contents "body{color:red}"
                            :headers {"Cache-Control" "max-age=315360000"}}]
                handler (candidate-stack
                          (reitit/ring-handler
                            (reitit/router [["/api/page-return-template"
                                             {:get (fn [_] {:status 200
                                                            :headers {"Content-Type" "application/json"
                                                                      "Cache-Control" "no-store"}
                                                            :body "{\"html\":\"ready\"}"})}]])
                            (reitit/create-default-handler))
                          inventory)]
            (let [response (handler (request "/img/fixture.avif" {"accept-encoding" "gzip"}))]
              (is (= 200 (:status response)))
              (is (= "image/avif" (get-in response [:headers "Content-Type"])))
              (is (= "SAMEORIGIN" (get-in response [:headers "X-Frame-Options"])))
              (is (= "nosniff" (get-in response [:headers "X-Content-Type-Options"])))
              (is (seq (get-in response [:headers "Strict-Transport-Security"])))
              (is (= "10" (get-in response [:headers "Content-Length"])))
              (is (= "public, max-age=31536000, immutable" (get-in response [:headers "Cache-Control"])))
              (is (nil? (get-in response [:headers "Content-Encoding"])))
              (is (nil? (get-in response [:headers "Set-Cookie"])))
              (is (= "0123456789" (body-text response))))
            (let [response (handler (request "/img/fixture.avif" {"range" "bytes=-3"}))]
              (is (= 206 (:status response)))
              (is (= "789" (body-text response)))
              (is (= "public, max-age=31536000, immutable" (get-in response [:headers "Cache-Control"]))))
            (is (= 416 (:status (handler (request "/img/fixture.avif" {"range" "bytes=20-30"})))))
            (let [response (handler (request "/img/fixture.avif" {} :head))]
              (is (= 200 (:status response)))
              (is (nil? (:body response))))
            (is (= 403 (:status (handler (request "/img/fixture.avif" {} :post)))))
            (is (= 404 (:status (handler (request "/img/fixture.avif" {"x-ring-anti-forgery" "test"} :post)))))
            (is (#{301 302 307 308} (:status (handler (assoc (request "/img/fixture.avif") :scheme :http)))))
            (let [response (handler (request "/img/fixture.avif" {"if-modified-since" "Wed, 21 Oct 2015 07:28:00 GMT"}))]
              (is (= 304 (:status response)))
              (is (nil? (:body response))))
            (let [response (handler (request "/bundles/styles.hash.css"))]
              (is (= "max-age=315360000" (get-in response [:headers "Cache-Control"])))
              (body-bytes response))
            (let [response (handler (request "/robots.txt"))]
              (is (= 200 (:status response)))
              (is (nil? (get-in response [:headers "Set-Cookie"])))
              (body-bytes response))
            (let [response (handler (request "/api/page-return-template"))]
              (is (= "no-store" (get-in response [:headers "Cache-Control"])))
              (is (= {"html" "ready"} (json/read-str (body-text response)))))))
        (finally (io/delete-file file true) (io/delete-file jar true))))))

(deftest plain-static-owner-cannot-shadow-reserved-routes
  (let [*lookups (atom 0)
        handler (assets/wrap-public-resources (constantly {:status 200, :body "route"}))]
    (with-redefs [resource/resource-request (fn [& _]
                                             (swap! *lookups inc)
                                             {:status 200, :body "static"})]
      (doseq [path ["/api" "/api/page-return-template" "/api/media/strapi/webhook" "/return-worker.js"]]
        (is (= "route" (:body (handler (request path))))))
      (is (zero? @*lookups))
      (is (= "static" (:body (handler (request "/robots.txt"))))))
    (is (= 1 @*lookups))))

(deftest candidate-api-format-coercion-and-browser-csrf-contracts
  (let [route-data (second (#'services/ordinary-service-routes))
        route-data' (update route-data :middleware
                            #(mapv (fn [entry] (if (= entry reitit-multipart/multipart-middleware)
                                                multipart/coercion-middleware entry)) %))
        handler (candidate-stack
                  (reitit/ring-handler
                    (reitit/router
                      [["/api" route-data'
                        ["/typed" {:post {:parameters {:body schemas/operands}
                                          :handler (fn [request]
                                                     {:status 200
                                                      :body {:total (apply + (vals (get-in request [:parameters :body])))}})}}]
                        ["/upload" {:post {:parameters {:multipart [:map [:file schemas/upload]]}
                                           :handler (fn [request]
                                                      {:status 200
                                                       :body {:size (get-in request [:parameters :multipart :file :size])}})}}]]]))
                  [])
        post (assoc (request "/api/typed" {"content-type" "application/json"
                                             "accept" "application/json"
                                             "x-ring-anti-forgery" "test"} :post)
                    :body (io/input-stream (.getBytes "{\"x\":2,\"y\":3}" "UTF-8")))]
    (is (= 403 (:status (handler (update post :headers dissoc "x-ring-anti-forgery")))))
    (let [response (handler post)]
      (is (= 200 (:status response)))
      (is (= {"total" 5} (json/read-str (body-text response)))))
    (doseq [body ["{" "{\"x\":\"wrong\",\"y\":3}"]]
      (is (= 400 (:status (handler (assoc post :body (io/input-stream (.getBytes body "UTF-8"))))))))
    (let [upload (-> (upload-request [["file" "test.txt" "abc"]])
                     (assoc :uri "/api/upload" :scheme :https)
                     (assoc-in [:headers "x-ring-anti-forgery"] "test"))
          response (handler upload)]
      (is (= 200 (:status response)))
      (is (= {"size" 3} (json/read-str (body-text response)))))))

(deftest candidate-webhook-exception-requires-the-real-secret-before-pipeline-work
  (let [*pipeline-calls (atom 0)
        *intake-calls (atom 0)]
    (with-redefs [config/env {:media {:strapi-webhook-token "disposable-webhook-secret"}}
                  pipeline/configured! #(swap! *pipeline-calls inc)
                  cms/intake! (fn [_] (swap! *intake-calls inc) {:accepted true})
                  storage/public-url #(str "https://storage.invalid/" %)]
      (let [handler (candidate-stack
                        (reitit/ring-handler
                          (reitit/router
                            [["/api" (second (#'services/ordinary-service-routes))
                              [cms/webhook-path {:post (fn [request] {:status 200, :body (cms/webhook! request)})}]
                              ["/token" {:get (fn [_] {:status 200
                                                       :headers {"Content-Type" "text/plain"}
                                                       :body (force csrf/*anti-forgery-token*)})}]
                              ["/ordinary" {:post (fn [_] {:status 200, :body {:accepted true}})}]]]))
                        [] cms/wrap-webhook-authorization)
            post! (fn [uri authorization & [safe-header]]
                    (handler (assoc (request uri (cond-> {"content-type" "application/json"
                                                          "accept" "application/json"}
                                                   authorization (assoc "authorization" authorization)
                                                   safe-header (assoc "x-ring-anti-forgery" safe-header)) :post)
                                    :body (io/input-stream
                                            (.getBytes "{\"event\":\"media.create\",\"media\":{\"id\":1,\"url\":\"/uploads/a.png\"}}"
                                                       "UTF-8")))))]
        (doseq [authorization [nil "Bearer wrong" "disposable-webhook-secret"]
                safe-header [nil "client-cannot-authorize-webhook"]]
          (let [response (post! "/api/media/strapi/webhook" authorization safe-header)]
            (is (= 401 (:status response)))
            (is (= "nosniff" (get-in response [:headers "X-Content-Type-Options"])))
            (is (= "SAMEORIGIN" (get-in response [:headers "X-Frame-Options"])))
            (is (seq (get-in response [:headers "Strict-Transport-Security"])))))
        (is (zero? @*pipeline-calls))
        (is (zero? @*intake-calls))
        (let [response (post! "/api/media/strapi/webhook" "Bearer disposable-webhook-secret")]
          (is (= 200 (:status response)))
          (is (= {"accepted" true} (json/read-str (body-text response)))))
        (is (= 1 @*pipeline-calls))
        (is (= 1 @*intake-calls))
        (let [token-response (handler (request "/api/token"))
              token (body-text token-response)
              set-cookie (get-in token-response [:headers "Set-Cookie"])
              cookie (first (string/split (if (string? set-cookie) set-cookie (first set-cookie)) #";"))]
          (doseq [[value status] [[token 200] ["wrong-token" 403]]]
            (is (= status
                   (:status (handler (assoc (request "/api/ordinary"
                                                      {"content-type" "application/x-www-form-urlencoded"
                                                       "cookie" cookie} :post)
                                            :body (io/input-stream
                                                    (.getBytes (codec/form-encode {"__anti-forgery-token" value}) "UTF-8")))))))))
        (doseq [uri ["/api/ordinary" "/api/media/strapi/webhook/" "/api/media/strapi/reconcile"]]
          (is (= 403 (:status (post! uri "Bearer disposable-webhook-secret")))))
        (is (= 403 (:status (handler (request "/api/media/strapi/webhook"
                                              {"authorization" "Bearer disposable-webhook-secret"} :put)))))
        (with-redefs [config/env {}]
          (is (= 503 (:status (post! "/api/media/strapi/webhook" "Bearer disposable-webhook-secret")))))
        (is (= 1 @*pipeline-calls))))))
