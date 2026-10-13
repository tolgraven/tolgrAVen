(ns tolgraven.middleware.asset-manifest
  "Read a build-prepared Optimus inventory without discovery or transformation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.validation :as validation])
  (:import [java.io OutputStream PushbackReader]
           [java.security DigestInputStream MessageDigest]))

(def resource-path "optimus/assets.edn")
(def digest-schema [:re #"[a-f0-9]{64}"])
(def resource-schema
  [:and :string [:re #"^(?:public/.+|optimus/[a-f0-9]{64}\.bin)$"]
   [:fn #(not (some #{".." "."} (string/split % #"/")))]])
(def asset-schema
  [:and
   [:map
    [:path :string]
    [:contents {:optional true} :string]
    [:resource-path {:optional true} resource-schema]
    [:headers {:optional true} [:map-of :string :string]]]
   [:fn #(not= (contains? % :contents) (contains? % :resource-path))]])
(def build-schema
  [:map
   [:browser-modules [:map-of :keyword :map]]
   [:site :map]
   [:styles [:map-of :string [:vector :string]]]])
(def manifest-schema
  [:map
   [:version [:= 1]]
   [:build build-schema]
   [:sources [:vector [:map [:path resource-schema] [:sha256 digest-schema]]]]
   [:assets [:vector asset-schema]]])

(defn digest [resource]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (DigestInputStream. (io/input-stream resource) digest)]
      (.transferTo input (OutputStream/nullOutputStream)))
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest)))))

(defn- unavailable! [message data]
  (throw (ex-info (str message "; rebuild with tolgraven.build.assets")
                  (assoc data :status 503))))

(defn read-prepared!
  "Validate the artifact and its source digests once at handler construction.
   The resolver also supports packaged-jar verification; requests use only the
   resulting inventory. Missing/stale output never starts a runtime rebuild."
  ([expected] (read-prepared! (io/resource resource-path) expected io/resource))
  ([resource expected resolve-resource]
   (when-not resource (unavailable! "Prepared asset manifest is missing" {}))
   (let [manifest (try
                    (with-open [reader (PushbackReader. (io/reader resource))]
                      (edn/read reader))
                    (catch Exception _ (unavailable! "Prepared asset manifest is unreadable" {})))]
     (when (validation/explain manifest-schema manifest)
       (unavailable! "Prepared asset manifest is invalid" {}))
     (when-not (= expected (:build manifest))
       (unavailable! "Prepared asset manifest belongs to a different build" {}))
     (doseq [{:keys [path sha256]} (:sources manifest)]
       (let [source (resolve-resource path)]
         (when-not (and source (= sha256 (digest source)))
           (unavailable! "Prepared asset source is missing or stale" {:asset path}))))
     (let [resources (into {}
                           (for [path (distinct (keep :resource-path (:assets manifest)))]
                             (let [resource (resolve-resource path)]
                               (when-not resource
                                 (unavailable! "Prepared asset body is missing" {:asset path}))
                               (when (and (string/starts-with? path "optimus/")
                                          (not= (subs path 8 72) (digest resource)))
                                 (unavailable! "Prepared asset body is corrupt" {:asset path}))
                               [path resource])))]
       (mapv (fn [{:keys [resource-path] :as asset}]
               (if resource-path
                 (-> asset (dissoc :resource-path) (assoc :resource (get resources resource-path)))
                 asset))
             (:assets manifest))))))
