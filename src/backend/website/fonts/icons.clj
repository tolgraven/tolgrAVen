(ns website.fonts.icons
  "CMS-sync-owned icon subsets; HTTP only serves completely prepared immutable bytes."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [website.middleware.asset-manifest :as manifest]
            [website.content.icons :as content-icons]
            [website.validation :as validation])
  (:import [java.io ByteArrayInputStream]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def selection-schema
  [:map {:closed true}
   [:solid {:optional true} [:set {:max 1024} [:int {:min 0 :max 65535}]]]
   [:brands {:optional true} [:set {:max 1024} [:int {:min 0 :max 65535}]]]])

(def families
  {:solid {:weight 900, :family "Font Awesome 5 Free"}
   :brands {:weight 400, :family "Font Awesome 5 Brands"}})

(defonce ^:private *prepared (atom {:assets {}}))

(defn declared-selection []
  (into {} (map (fn [[kind points]] [kind (set points)]))
        (json/read-str (slurp (io/resource "icon-subsets.json")) :key-fn keyword)))

(def ^:private glyph-codes
  (delay
    (into {} (map (fn [[_ glyph code]] [glyph (Integer/parseInt code 16)]))
          (re-seq #"\.fa-([a-z0-9-]+):before\s*\{\s*content:\s*[\"']\\([0-9a-f]+)"
                  (slurp (io/resource "public/css/fontawesome.css"))))))

(defn content-selection
  "Resolve only declared icon-bearing content paths, never arbitrary prose or markup."
  [sections]
  (let [references (content-icons/references sections)]
    (when (validation/explain content-icons/references-schema references)
      (throw (ex-info "Invalid content icon references" {})))
    (reduce (fn [selected reference]
              (let [[family glyph] (string/split reference #"/" 2)]
                (if-let [code (@glyph-codes glyph)]
                  (update selected (keyword family) conj code)
                  selected)))
            {:solid #{}, :brands #{}} references)))

(defn selection
  "Union source/seed glyphs with validated references from synchronized content."
  [additional]
  (when (validation/explain selection-schema additional)
    (throw (ex-info "Invalid icon glyph selection" {})))
  (let [declared (declared-selection)
        value (merge-with into declared additional)]
    (when (validation/explain selection-schema value)
      (throw (ex-info "Invalid declared icon glyph selection" {})))
    value))

(defn- parent-name [kind]
  (str "fa-" (name kind) "-" (:weight (families kind))))

(defn- font-resource [kind]
  (io/resource (str "public/webfonts/" (parent-name kind) ".woff2")))

(defn- run-subsetter! [directory]
  (let [builder (ProcessBuilder. ^java.util.List
                                ["python3" (str (io/file directory "subset.py")) (str directory)])]
    (.redirectOutput builder ProcessBuilder$Redirect/DISCARD)
    (.redirectError builder ProcessBuilder$Redirect/DISCARD)
    (let [process (.start builder)]
      (try
        (when-not (and (.waitFor process 30 TimeUnit/SECONDS) (zero? (.exitValue process)))
          (throw (ex-info "Icon subset preparation failed" {})))
        (finally
          (when (.isAlive process)
            (.destroyForcibly process)
            (.waitFor process 2 TimeUnit/SECONDS)))))))

(defn compile-subsets!
  "Run the bounded codec once per changed selection; private temporary files never publish."
  [selected]
  (let [directory (.toFile (Files/createTempDirectory "tolgraven-icons-" (make-array FileAttribute 0)))]
    (try
      (doseq [[target resource] (concat [["subset.py" (io/resource "fonts/subset.py")]]
                                       (for [kind [:solid :brands]]
                                         [(str (parent-name kind) ".woff2")
                                          (font-resource kind)]))]
        (with-open [input (io/input-stream resource)
                    output (io/output-stream (io/file directory target))]
          (io/copy input output)))
      (spit (io/file directory "selection.json")
            (json/write-str (into {} (map (fn [[kind points]] [kind (vec (sort points))])) selected)))
      (run-subsetter! directory)
      (into {} (for [kind [:solid :brands]
                     :let [file (io/file directory (str (name kind) ".woff2"))
                           data (Files/readAllBytes (.toPath file))]]
                 (do
                   (when-not (and (<= 4 (alength data) 131072)
                                  (= "wOF2" (String. data 0 4 "US-ASCII")))
                     (throw (ex-info "Invalid prepared icon font" {})))
                   [kind data])))
      (finally
        (doseq [file (reverse (file-seq directory))]
          (Files/deleteIfExists (.toPath file)))))))

(defn- font-face [kind points path]
  (let [{:keys [weight family]} (families kind)]
    (str "@font-face{font-family:\"" family "\";font-style:normal;font-weight:" weight
         ";font-display:auto;src:url(\"" path "\") format(\"woff2\");unicode-range:"
         ;; Space retains the original font's inline line-box metrics.
         (string/join "," (map #(format "U+%X" %) (sort (conj points 32)))) ";}")))

(defn prepare!
  "Prepare before publishing CMS content. Existing seeded icon fields and future
   editable fields extend the declared selection through content-selection.
   Failed updates leave every previously published font URL available."
  ([] (prepare! {}))
  ([additional]
   (let [selected (selection additional)
         identity [selected (manifest/digest (io/resource "fonts/subset.py"))
                   (mapv #(manifest/digest (font-resource %)) [:solid :brands])]]
     (locking *prepared
       (if (= identity (:identity @*prepared))
         (:snapshot @*prepared)
         (let [fonts (compile-subsets! selected)
               paths (into {} (for [[kind data] fonts]
                                [kind (str "/webfonts/icons/" (parent-name kind) "-"
                                           (manifest/digest (ByteArrayInputStream. data)) ".woff2")]))
               assets (into {} (for [[kind data] fonts] [(paths kind) data]))
               snapshot {:css (apply str (for [kind [:solid :brands]]
                                           (font-face kind (selected kind) (paths kind))))}]
           (swap! *prepared #(-> %
                                 (assoc :identity identity :snapshot snapshot)
                                 (update :assets merge assets)))
           snapshot))))))

(defn wrap-assets [handler]
  (fn [{:keys [uri request-method] :as request}]
    (if (string/starts-with? (or uri "") "/webfonts/icons/")
      (if (#{:get :head} request-method)
        (if-let [data (get-in @*prepared [:assets uri])]
          {:status 200
           :headers {"Content-Type" "font/woff2"
                     "Content-Length" (str (alength ^bytes data))
                     "Cache-Control" "public, max-age=31536000, immutable"
                     "ETag" (str "\"" (re-find #"[a-f0-9]{64}(?=\.woff2$)" uri) "\"")}
           :body (when (= :get request-method) (ByteArrayInputStream. data))}
          {:status 404, :headers {"Cache-Control" "no-store"}, :body "Font unavailable"})
        {:status 405
         :headers {"Allow" "GET, HEAD", "Cache-Control" "no-store"}
         :body "Method not allowed"})
      (handler request))))
