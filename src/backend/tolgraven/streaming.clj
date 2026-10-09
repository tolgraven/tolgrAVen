(ns tolgraven.streaming
  "Flush-aware response body for Ring and the configured Undertow adapter."
  (:require [ring.core.protocols :as ring]
            [hiccup.core :as hiccup]
            [hiccup.compiler :as html]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.adapter.undertow.response :as undertow]
            [tolgraven.concurrent :as concurrent])
  (:import [io.undertow.server HttpServerExchange]
           [java.util.zip GZIPOutputStream]))

(defrecord Body [write!]
  ring/StreamableResponseBody
  (write-body-to-stream [_ _ output] (write! output))
  undertow/RespondBody
  (respond [this exchange]
    (let [^HttpServerExchange exchange exchange]
      (if (.isInIoThread exchange)
        (.dispatch exchange concurrent/executor
                   ^Runnable (fn [] (undertow/respond this exchange)))
        (try
          (.startBlocking exchange)
          (write! (.getOutputStream exchange))
          (catch java.io.IOException _ nil) ; client disconnected
          (finally (.endExchange exchange)))))))

(defn- accepts-gzip? [header]
  (let [qualities (into {}
                    (for [entry (string/split (or header "") #",")
                          :let [[encoding & parameters] (string/split entry #";")
                                q (some #(second (re-matches #"(?i)\s*q\s*=\s*(.*?)\s*" %)) parameters)]]
                      [(string/lower-case (string/trim encoding))
                       (try (if q (Double/parseDouble q) 1.0)
                            (catch NumberFormatException _ 0.0))]))
        q (get qualities "gzip" (get qualities "*" 0.0))]
    (and (pos? q) (<= q 1.0))))

(defn gzip-response
  "Compress this adapter's body directly, preserving every explicit shell flush.
   The generic middleware's piped InputStream is buffered by Undertow."
  [request {:keys [body headers status] :as response}]
  (if (and (= 200 status) (instance? Body body)
           (not (or (get headers "Content-Encoding") (get headers "content-encoding")))
           (accepts-gzip? (get-in request [:headers "accept-encoding"])))
    (let [vary (or (get headers "Vary") (get headers "vary"))
          varied? (some #{"*" "accept-encoding"}
                        (map string/trim (string/split (string/lower-case (or vary "")) #",")))]
      (-> response
          (assoc :body (->Body (fn [output]
                                (with-open [gzip (GZIPOutputStream. output true)]
                                  ((:write! body) gzip)))))
          (update :headers #(-> %
                                (dissoc "Content-Length" "content-length" "vary")
                                (assoc "Content-Encoding" "gzip"
                                       "Vary" (if varied? vary
                                                  (str (when (seq vary) (str vary ", ")) "Accept-Encoding")))))))
    response))

(defn html-document
  "Stream a Hiccup document, flushing its shell before evaluating body-content!.
   Only the enclosing tags stay open across flushes. Hiccup renders all content
   and attributes; layout code never splits or splices serialized markup."
  [[tag attributes head [_ body-attributes]] shell body-content!]
  (assert (= :html tag) "Expected a Hiccup HTML document")
  (->Body
    (fn [output]
      (with-open [writer (io/writer output :encoding "UTF-8")]
        (.write writer (str "<!DOCTYPE html>\n<html" (html/render-attr-map attributes) ">"
                            (hiccup/html head) "<body" (html/render-attr-map body-attributes) ">"
                            (hiccup/html shell)))
        (.flush writer)
        (.write writer (hiccup/html (body-content!)))
        (.write writer "</body></html>")
        (.flush writer)))))
