(ns tolgraven.streaming-test
  (:require [clojure.test :refer [deftest is]]
            [ring.core.protocols :as ring]
            [tolgraven.streaming :as streaming])
  (:import [java.io PipedInputStream PipedOutputStream]
           [java.util.zip GZIPInputStream]))

(deftest gzip-shell-decompresses-before-the-final-page-is-ready
  (let [release (promise)
        response (streaming/gzip-response
                   {:headers {"accept-encoding" "br, gzip;q=0.8"}}
                   {:status 200
                    :headers {"Content-Type" "text/html" "Content-Length" "99999" "Vary" "Origin"}
                    :body (streaming/html-document
                            [:html {:lang "en"} [:head [:title "Stream"]] [:body {}]]
                            [:div {:id "shell"} "First paint"]
                            (fn [] @release [:article "Completed page"]))})
        input (PipedInputStream. 65536)
        output (PipedOutputStream. input)
        writer (future (ring/write-body-to-stream (:body response) response output))
        first-chunk (future
                      (let [gzip (GZIPInputStream. input)
                            buffer (byte-array 65536)
                            size (.read gzip buffer)]
                        [gzip (String. buffer 0 size "UTF-8")]))]
    (try
      (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
      (is (= "Origin, Accept-Encoding" (get-in response [:headers "Vary"])))
      (is (nil? (get-in response [:headers "Content-Length"])))
      (let [result (deref first-chunk 5000 ::timeout)]
        (is (not= ::timeout result) "Compressed bytes must flush while final content is pending")
        (when-not (= ::timeout result)
          (let [[gzip text] result]
            (is (.contains text "First paint"))
            (is (not (.contains text "Completed page")))
            (is (not (realized? release)))
            (deliver release true)
            (let [rest (slurp gzip)]
              (is (.contains rest "Completed page"))
              (is (.endsWith rest "</body></html>"))))))
      (finally
        (deliver release true)
        (deref writer 5000 nil)
        (.close input)
        (.close output)
        (future-cancel first-chunk)))))

(deftest streaming-compression-respects-negotiation-and-existing-encoding
  (let [response {:status 200 :headers {} :body (streaming/->Body (fn [_]))}]
    (doseq [encoding [nil "br" "gzip;q=0" "*;q=1, gzip;q=0" "gzip;q=invalid" "gzip;q=2"]]
      (is (identical? response (streaming/gzip-response {:headers {"accept-encoding" encoding}} response))))
    (is (= "gzip" (get-in (streaming/gzip-response {:headers {"accept-encoding" "*;q=1"}} response)
                           [:headers "Content-Encoding"])))
    (let [encoded (assoc-in response [:headers "Content-Encoding"] "br")]
      (is (identical? encoded (streaming/gzip-response {:headers {"accept-encoding" "gzip"}} encoded))))))
