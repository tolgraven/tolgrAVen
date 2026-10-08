(ns tolgraven.supabase.storage
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [tolgraven.media.image :as image]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth])
  (:import [javax.imageio ImageIO]
           [java.io ByteArrayOutputStream]
           [java.security MessageDigest]))

(defn- fail! [status message]
  (throw (ex-info message {:auth/status status})))

(defn png-bytes! [{:keys [tempfile size]}]
  (when-not (and tempfile (integer? size) (pos? size) (<= size 5242880))
    (fail! 400 "Choose an image smaller than 5 MB"))
  (with-open [input (ImageIO/createImageInputStream tempfile)]
    (when-not input (fail! 400 "Unsupported image"))
    (let [readers (ImageIO/getImageReaders input)]
      (when-not (.hasNext readers) (fail! 400 "Unsupported image"))
      (let [reader (.next readers)]
        (try
          (.setInput reader input true true)
          (when-not (and (<= 1 (.getWidth reader 0) 4096)
                         (<= 1 (.getHeight reader 0) 4096))
            (fail! 400 "Image dimensions must be at most 4096 pixels"))
          (with-open [output (ByteArrayOutputStream.)]
            (ImageIO/write (.read reader 0) "png" output)
            (.toByteArray output))
          (catch clojure.lang.ExceptionInfo error (throw error))
          (catch Exception _ (fail! 400 "Unable to read this image"))
          (finally (.dispose reader)))))))

(defn- upload! [base path mime-type bytes]
  (let [result (try
                 (http/post (str base "/storage/v1/object/" path)
                            {:headers {"apikey" (platform/service-key)
                                       "Authorization" (str "Bearer " (platform/service-key))
                                       "x-upsert" "true"}
                             :content-type mime-type
                             :body bytes
                             :as :json
                             :throw-exceptions false
                             :conn-timeout 3000
                             :socket-timeout 10000})
                 (catch Exception _ (fail! 503 "Unable to save the avatar")))]
    (when-not (<= 200 (:status result) 299)
      (fail! 503 "Unable to save the avatar"))))

(defn save-avatar! [user file]
  (let [bytes (png-bytes! file)
        variants (image/variants! bytes)
        id (auth/ensure-profile! user)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)
        version (apply str (map #(format "%02x" (bit-and 255 %)) digest))
        base (string/replace (platform/rest-base-url) #"/+$" "")
        stem (str "avatars/" id "/" version)]
    ;; Versioned names prevent stale CDN variants and concurrent uploads mixing
    ;; formats. Publish the profile pointer only when all three objects exist.
    (doseq [[format mime-type] [[:png "image/png"]
                               [:webp "image/webp"]
                               [:avif "image/avif"]]]
      (upload! base (str stem "." (name format)) mime-type (variants format)))
    (auth/save-profile! user {:avatar (str base "/storage/v1/object/public/" stem ".png")})))
