(ns test.prepared-assets
  "Compare real build-prepared inventories from source and a packaged server jar."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [optimus.link :as link]
            [tolgraven.middleware.asset-manifest :as manifest]
            [tolgraven.layout :as layout]
            [tolgraven.loader.style-catalog :as styles]
            [tolgraven.site :as site]))

(when-not (and (.exists (io/file "/.dockerenv"))
               (= "/workspace" (.getCanonicalPath (io/file "."))))
  (throw (ex-info "Run this packaging fixture only inside the isolated CPU build" {})))

(defn response-bytes [response]
  (with-open [output (java.io.ByteArrayOutputStream.)]
    ((requiring-resolve 'ring.core.protocols/write-body-to-stream)
     (:body response) response output)
    (.toByteArray output)))

(defn verify-stack! [inventory]
  (let [wrap-base (requiring-resolve 'tolgraven.middleware/wrap-base)
        selected (filter #(or (= "styles.css" (:bundle %))
                               (re-find #"\.(woff2|avif)$" (:path %))) inventory)
        samples (take 3 (vals (group-by #(cond
                                          (:contents %) :style
                                          (re-find #"\.woff2$" (:path %)) :font
                                          :else :image) selected)))]
    (with-redefs-fn {(requiring-resolve 'tolgraven.config/env) {}
                    (requiring-resolve 'tolgraven.env/defaults) {:middleware identity}}
      (fn []
        (let [handler (wrap-base (constantly {:status 404, :headers {}, :body "Not found"}))]
          (assert (= 3 (count samples)) "Styles, fonts and modern images must be represented")
          (doseq [[{:keys [path contents resource]}] samples]
            (let [request {:request-method :get
                           :scheme :https
                           :server-name "localhost"
                           :server-port 443
                           :uri path
                           :headers {"accept-encoding" "identity"}}
                  response (handler request)
                  bytes (response-bytes response)
                  expected (manifest/digest (or resource (.getBytes ^String contents "UTF-8")))
                  partial (handler (assoc-in request [:headers "range"] "bytes=-3"))
                  partial-bytes (response-bytes partial)]
              (assert (= 200 (:status response)))
              (assert (= expected (manifest/digest bytes)) "Actual middleware must preserve prepared bytes")
              (assert (= "nosniff" (get-in response [:headers "X-Content-Type-Options"])))
              (assert (= (str (alength bytes)) (get-in response [:headers "Content-Length"])))
              (assert (= 206 (:status partial)))
              (assert (= (vec (take-last 3 bytes)) (vec partial-bytes)))))))))
  (assert (nil? (find-ns 'optimus.optimizations.minify)) "Production stack must not load minifiers")
  (assert (nil? (find-ns 'optimus-img-transform.core)) "Production stack must not load image transforms"))

(let [[mode] *command-line-args*
      expected {:browser-modules (layout/browser-modules)
                :site site/config
                :styles styles/stylesheet-bundles}
      inventory (manifest/read-prepared! expected)
      signatures (mapv (fn [{:keys [path contents resource] :as asset}]
                         {:asset (dissoc asset :resource :contents)
                          :sha256 (manifest/digest (or resource
                                                       (.getBytes ^String contents "UTF-8")))}) inventory)
      bundles (into (sorted-map)
                    (for [bundle (concat ["main.js" "styles.css" "icons.css"
                                          "document-head.js" "document-entrance.js"]
                                         (keys styles/stylesheet-bundles))]
                      [bundle (vec (link/bundle-paths {:optimus-assets inventory} [bundle]))]))
      record {:build expected, :bundles bundles, :assets signatures}
      report "target/prepared-assets-source.edn"]
  (assert (every? (comp seq val) bundles) "Every declared independent bundle must resolve")
  (assert (nil? (find-ns 'optimus.optimizations.minify)) "Prepared import must not load GraalJS/minifiers")
  (assert (nil? (find-ns 'optimus-img-transform.core)) "Prepared import must not load image transformations")
  (case mode
    "source" (do (io/make-parents report) (spit report (pr-str record)))
    "package" (assert (= "jar" (.getProtocol (io/resource manifest/resource-path)))
                      "Package verification must resolve the actual packaged manifest")
    "jar" (do
            (assert (= "jar" (.getProtocol (io/resource manifest/resource-path)))
                    "Jar verification must resolve the actual packaged manifest")
            (assert (= record (edn/read-string (slurp report)))
                    "Packaged URLs, headers, styles, fonts and fallback bytes must equal source output"))
    (throw (ex-info "Expected source, jar or package" {})))
  (verify-stack! inventory)
  (println "Verified" mode "prepared inventory:" (count inventory) "assets," (count bundles) "independent bundles"))
(shutdown-agents)
