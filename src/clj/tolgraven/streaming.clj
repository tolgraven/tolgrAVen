(ns tolgraven.streaming
  "Flush-aware response body for Ring and the configured Undertow adapter."
  (:require [ring.core.protocols :as ring]
            [hiccup.core :as hiccup]
            [hiccup.compiler :as html]
            [clojure.java.io :as io]
            [ring.adapter.undertow.response :as undertow]
            [tolgraven.concurrent :as concurrent])
  (:import [io.undertow.server HttpServerExchange]))

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
