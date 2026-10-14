(ns website.middleware-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ring.core.protocols :as ring]
            [ring.middleware.multipart-params :as ring-multipart]
            [reitit.ring :as reitit]
            [reitit.ring.coercion :as coercion]
            [website.schema.http :as schemas]
            [website.config :as config]
            [website.middleware :as middleware]
            [website.middleware.multipart :as multipart]
            [website.middleware.ranges :as ranges])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]
           [java.util.zip GZIPInputStream]))

(defn body-bytes [response]
  (let [output (ByteArrayOutputStream.)]
    (ring/write-body-to-stream (:body response) response output)
    (.toByteArray output)))

(defn body-text [response]
  (String. ^bytes (body-bytes response) "UTF-8"))

(deftest outer-errors-render-without-ended-csrf-bindings-or-private-details
  (with-redefs [config/env {:dev true}]
    (let [response ((middleware/wrap-internal-error
                      (fn [_] (throw (ex-info "private-upstream-detail" {:status 503}))))
                    {:uri "/"})
          html (body-text response)]
      (is (= 503 (:status response)))
      (is (.contains html "Please try again."))
      (is (not (.contains html "private-upstream-detail"))))))

(deftest compression-negotiates-response-types-and-cache-variants
  (doseq [mime ["text/html" "text/css" "application/json" "application/transit+json" "image/svg+xml"]]
    (let [handler (middleware/wrap-gzip-content-aware
                    (constantly {:status 200
                                 :headers {"Content-Type" mime, "Vary" "Origin", "Content-Length" "900"}
                                 :body (apply str (repeat 300 "abc"))}))]
      (doseq [encoding [nil "br" "gzip;q=0" "*;q=1, gzip;q=0" "gzip;q=bogus"]]
        (let [response (handler {:request-method :get, :headers {"accept-encoding" encoding}})]
          (is (nil? (get-in response [:headers "Content-Encoding"])))
          (is (= "Origin, Accept-Encoding" (get-in response [:headers "Vary"])))
          (is (= 900 (alength (body-bytes response))))))
      (let [response (handler {:request-method :get, :headers {"accept-encoding" "gzip"}})]
        (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
        (is (nil? (get-in response [:headers "Content-Length"])))
        (with-open [input (GZIPInputStream. (io/input-stream (body-bytes response)))]
          (is (= 900 (count (slurp input))))))))
  (doseq [mime ["image/avif" "image/webp" "image/png" "image/jpeg" "video/webm" "font/woff2"]]
    (let [response {:status 200, :headers {"Content-Type" mime}, :body "encoded-media"}]
      (is (identical? response ((middleware/wrap-gzip-content-aware (constantly response))
                                {:headers {"accept-encoding" "gzip"}})))))
  (let [handler (middleware/wrap-gzip-content-aware
                  (constantly {:status 304, :headers {"Content-Type" "text/css"}, :body nil}))]
    (is (= 304 (:status (handler {:headers {"accept-encoding" "gzip"}}))))))

(deftest byte-range-semantics-and-stream-ownership
  (doseq [kind [:string :bytes :stream :file]]
    (let [file (File/createTempFile "range-test-" ".webm")
          *closed? (atom false)
          handler (ranges/wrap-ranges
                    (fn [_]
                      {:status 200
                       :headers {"Content-Type" "video/webm"
                                 "Content-Length" "10"
                                 "ETag" "\"v1\""
                                 "Last-Modified" "Wed, 21 Oct 2015 07:28:00 GMT"}
                       :body (case kind
                               :string "0123456789"
                               :bytes (.getBytes "0123456789" "UTF-8")
                               :stream (proxy [ByteArrayInputStream] [(.getBytes "0123456789" "UTF-8")]
                                         (close [] (reset! *closed? true) (proxy-super close)))
                               :file file)}))]
      (spit file "0123456789")
      (try
        (doseq [[range expected content-range] [["bytes=2-5" "2345" "bytes 2-5/10"]
                                               ["bytes=-3" "789" "bytes 7-9/10"]
                                               ["bytes=7-" "789" "bytes 7-9/10"]
                                               ["bytes=-100" "0123456789" "bytes 0-9/10"]
                                               ["bytes=9-999999999999999999999999" "9" "bytes 9-9/10"]]]
          (reset! *closed? false)
          (let [response (handler {:request-method :get, :headers {"range" range}})]
            (is (= 206 (:status response)))
            (is (= content-range (get-in response [:headers "Content-Range"])))
            (is (= expected (body-text response)))
            (when (= kind :stream) (is @*closed?))))
        (doseq [range ["bytes=20-30" "bytes=-0" "bytes=5-2"]]
          (let [response (handler {:request-method :get, :headers {"range" range}})]
            (is (= 416 (:status response)))
            (is (= "bytes */10" (get-in response [:headers "Content-Range"])))
            (is (= "0" (get-in response [:headers "Content-Length"])))))
        (doseq [validator ["\"v2\"" "W/\"v1\"" "Tue, 20 Oct 2015 07:28:00 GMT"
                          "Thu, 22 Oct 2015 07:28:00 GMT" "invalid"]]
          (let [response (handler {:request-method :get
                                   :headers {"range" "bytes=2-5", "if-range" validator}})]
            (is (= 200 (:status response)))
            (is (= "0123456789" (body-text response)))))
        (doseq [validator ["\"v1\"" "Wed, 21 Oct 2015 07:28:00 GMT"]]
          (let [response (handler {:request-method :get
                                   :headers {"range" "bytes=2-5", "if-range" validator}})]
            (is (= 206 (:status response)))
            (is (= "2345" (body-text response)))))
        (doseq [range ["bytes=0-2,5-6" "items=0-2" "bytes=-" "nonsense"]]
          (let [response (handler {:request-method :get, :headers {"range" range}})]
            (is (= 200 (:status response)))
            (body-bytes response)))
        (reset! *closed? false)
        (let [response (handler {:request-method :head, :headers {"range" "bytes=2-5"}})]
          (is (= 200 (:status response)))
          (is (= "10" (get-in response [:headers "Content-Length"])))
          (is (nil? (:body response)))
          (when (= kind :stream) (is @*closed?)))
        (finally (io/delete-file file true))))))

(defn upload-request [parts]
  (let [body (str (apply str (for [[name filename contents] parts]
                                (str "--test-boundary\r\nContent-Disposition: form-data; name=\"" name "\""
                                     (when filename (str "; filename=\"" filename "\""))
                                     "\r\nContent-Type: text/plain\r\n\r\n" contents "\r\n")))
                  "--test-boundary--\r\n")]
    {:request-method :post
     :uri "/upload"
     :headers {"content-type" "multipart/form-data; boundary=test-boundary"}
     :body (io/input-stream (.getBytes body "UTF-8"))}))

(deftest multipart-is-bounded-coerced-once-and-cleaned-up
  (let [*file (atom nil)
        *calls (atom 0)
        parse! ring-multipart/parse-multipart-params
        handler (multipart/wrap-multipart
                  (reitit/ring-handler
                    (reitit/router
                      [["/upload" {:coercion schemas/coercion
                                   :middleware [coercion/coerce-request-middleware multipart/coercion-middleware]
                                   :post {:parameters {:multipart [:map [:file schemas/upload]]}
                                          :handler (fn [request]
                                                     (let [file (get-in request [:parameters :multipart :file])]
                                                       (reset! *file (:tempfile file))
                                                       (is (= "abc" (slurp (:tempfile file))))
                                                       (is (= "sample.txt" (:filename file)))
                                                       {:status 200, :body "accepted"}))}}]])))]
    (with-redefs [ring-multipart/parse-multipart-params (fn [request options]
                                                       (swap! *calls inc)
                                                       (parse! request options))]
      (is (= 200 (:status (handler (upload-request [["file" "sample.txt" "abc"]]))))))
    (is (= 1 @*calls))
    (is (not (.exists ^File @*file)))
    (doseq [parts [[["file" "large.txt" (String. (char-array (inc multipart/max-file-bytes) \x))]]
                   [["file" "one.txt" "a"] ["file" "two.txt" "b"]]
                   (mapv #(vector (str "field" %) nil "x") (range 17))]]
      (is (= 413 (:status (handler (upload-request parts))))))))
