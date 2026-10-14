(ns website.middleware.ranges
  "Single byte ranges over known-length representations, including jar streams."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.io :as ring-io]
            [ring.util.response :as response]
            [ring.util.time :as time]
            [website.streaming :as streaming])
  (:import [java.io File InputStream]))

(defn- body-length [body]
  (cond
    (string? body) (alength (.getBytes ^String body "UTF-8"))
    (bytes? body) (alength ^bytes body)
    (instance? File body) (.length ^File body)))

(defn- content-length [response]
  (or (body-length (:body response))
      (some-> (response/get-header response "Content-Length") parse-long)))

(defn- sliceable? [body]
  (or (string? body) (bytes? body) (instance? File body) (instance? InputStream body)))

(defn- requested-range [header length]
  ;; Ignore malformed/unsupported multi-range requests; the complete 200 response
  ;; is permitted. Valid single ranges outside the representation return 416.
  (when-let [[_ first-byte last-byte] (re-matches #"bytes=(\d*)-(\d*)" (or header ""))]
    (when (or (seq first-byte) (seq last-byte))
      (let [start (if (seq first-byte) (bigint first-byte)
                     (max 0 (- length (bigint last-byte))))
            end (if (and (seq first-byte) (seq last-byte))
                  (min (dec length) (bigint last-byte))
                  (dec length))]
        (if (or (zero? length) (>= start length) (> start end))
          :unsatisfiable
          [(long start) (long end)])))))

(defn- if-range-matches? [request response]
  (if-let [validator (get-in request [:headers "if-range"])]
    (if (or (string/starts-with? validator "\"") (string/starts-with? validator "W/"))
      (and (not (string/starts-with? validator "W/"))
           (= validator (response/get-header response "ETag")))
      (let [requested (time/parse-date validator)
            modified (some-> (response/get-header response "Last-Modified") time/parse-date)]
        ;; If-Range uses an exact date match, unlike If-Unmodified-Since.
        (and requested modified (= requested modified))))
    true))

(defn- range-body [body start length]
  (streaming/->Body
    (fn [output]
      (with-open [input (io/input-stream (if (string? body) (.getBytes ^String body "UTF-8") body))]
        (.skipNBytes ^InputStream input start)
        (let [buffer (byte-array 8192)]
          (loop [remaining length]
            (when (pos? remaining)
              (let [read (.read ^InputStream input buffer 0 (int (min remaining 8192)))]
                (when (neg? read)
                  (throw (java.io.EOFException. "Representation ended before its declared range")))
                (.write ^java.io.OutputStream output buffer 0 read)
                (recur (- remaining read))))))))))

(defn wrap-ranges [handler]
  (fn [request]
    (let [{:keys [body status] :as response} (handler request)
          length (when (and (= 200 status) (sliceable? body)
                            (not (response/get-header response "Content-Encoding")))
                   (content-length response))
          response' (cond-> response
                      (and length (not (neg? length)))
                      (update :headers assoc "Content-Length" (str length) "Accept-Ranges" "bytes"))
          selected (when (and length (pos? length) (= :get (:request-method request))
                              (if-range-matches? request response))
                     (requested-range (get-in request [:headers "range"]) length))]
      (cond
        (= :head (:request-method request))
        (do (ring-io/close! body) (assoc response' :body nil))

        (= :unsatisfiable selected)
        (do (ring-io/close! body)
            (-> response'
                (assoc :status 416 :body "")
                (update :headers assoc "Content-Range" (str "bytes */" length) "Content-Length" "0")))

        (vector? selected)
        (let [[start end] selected
              size (inc (- end start))]
          (-> response'
              (assoc :status 206 :body (range-body body start size))
              (update :headers assoc "Content-Range" (str "bytes " start "-" end "/" length)
                      "Content-Length" (str size))))

        :else response'))))
