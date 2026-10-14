(ns website.media.storage
  "Private originals and immutable public renditions through server-only Storage."
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [website.platform.supabase :as platform])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.security MessageDigest]))

(def max-source-bytes 5242880)

(defn digest [bytes]
  (apply str (map #(format "%02x" (bit-and 255 %))
                 (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes))))

(defn fail! [status message]
  (throw (ex-info message {:auth/status status})))

(defn base-url [] (string/replace (platform/rest-base-url) #"/+$" ""))
(defn public-url [path] (str (base-url) "/storage/v1/object/public/" path))

(defn limited-bytes! [^InputStream input maximum]
  (let [deadline (+ (System/nanoTime) 15000000000)
        buffer (byte-array 8192)]
    (with-open [output (ByteArrayOutputStream.)]
      (loop [total 0]
        (when (> (System/nanoTime) deadline) (fail! 503 "Media download timed out"))
        (let [count (.read input buffer)]
          (if (neg? count)
            (.toByteArray output)
            (let [total' (+ total count)]
              (when (> total' maximum) (fail! 400 "Choose an image smaller than 5 MB"))
              (.write output buffer 0 count)
              (recur total'))))))))

(defn extension! [bytes]
  (let [size (alength ^bytes bytes)
        prefix (String. ^bytes bytes 0 (min 1024 size) "ISO-8859-1")]
    (cond
      (and (<= 8 size) (= [137 80 78 71 13 10 26 10]
                          (mapv #(bit-and 255 %) (take 8 bytes)))) "png"
      (and (<= 3 size) (= [255 216 255] (mapv #(bit-and 255 %) (take 3 bytes)))) "jpg"
      (and (<= 12 size) (string/starts-with? prefix "RIFF") (= "WEBP" (subs prefix 8 12))) "webp"
      (and (<= 16 size) (= "ftyp" (subs prefix 4 8)) (string/includes? prefix "avif")) "avif"
      (re-find #"(?is)^\s*(?:ï»¿)?\s*(?:<\?xml\s[^?]*\?>\s*)?(?:<!--.*?-->\s*)*<(?:[A-Za-z_][\w.-]*:)?svg(?:\s|>)" prefix) "svg"
      :else (fail! 400 "Choose a PNG, JPEG, WebP, AVIF or SVG image"))))

(def mime-types {"png" "image/png" "jpg" "image/jpeg" "webp" "image/webp" "avif" "image/avif" "svg" "image/svg+xml"})

(defn put! [path mime bytes]
  (let [response (try
                   (http/post (str (base-url) "/storage/v1/object/" path)
                     (merge (platform/base-http-opts)
                            {:headers (assoc (:headers (platform/base-http-opts))
                                             "x-upsert" "false" "Cache-Control" "max-age=31536000, immutable")
                             :body bytes
                             :content-type mime
                             :socket-timeout 15000}))
                   (catch Exception _ (fail! 503 "Unable to publish media")))]
    ;; Every object path includes its actual content digest. Duplicate uploads
    ;; reuse the immutable object; they never overwrite an earlier version.
    (when-not (or (<= 200 (:status response) 299)
                  (and (#{400 409} (:status response))
                       (#{"Duplicate" "ResourceAlreadyExists" "409"}
                        (str (or (get-in response [:body :error])
                                 (get-in response [:body :statusCode]))))))
      (fail! 503 "Unable to publish media"))))

(defn original! [path]
  (let [response (try
                   (http/get (str (base-url) "/storage/v1/object/authenticated/" path)
                             (merge (platform/base-http-opts) {:as :stream :socket-timeout 5000}))
                   (catch Exception _ (fail! 503 "Unable to read the original media")))]
    (with-open [input ^InputStream (:body response)]
      (when-not (= 200 (:status response)) (fail! 503 "Unable to read the original media"))
      (limited-bytes! input max-source-bytes))))
