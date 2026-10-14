(ns website.middleware.assets
  "Optimus delivery adapter retaining its URL inventory and resource lengths."
  (:require [clojure.core.memoize :as memo]
            [clojure.string :as string]
            [ring.middleware.resource :as resource]
            [optimus.asset :as asset]
            [optimus.strategies :as strategies]))

(defn wrap-public-resources [handler]
  (let [resources (resource/wrap-resource handler "public")]
    (fn [request]
      ;; Dynamic API and worker registration paths always belong to their routes,
      ;; even if an accidentally named public file appears in a future artifact.
      ((if (or (= "/return-worker.js" (:uri request))
               (= "/api" (:uri request))
               (string/starts-with? (:uri request "") "/api/"))
         handler resources)
       request))))

(defn- asset-response [{:keys [contents resource headers]}]
  (if (some? contents)
    {:status 200, :headers headers, :body contents}
    (let [connection (doto (.openConnection ^java.net.URL resource)
                       (.setUseCaches false))
          length (.getContentLengthLong connection)]
      {:status 200
       :headers (cond-> headers (not (neg? length)) (assoc "Content-Length" (str length)))
       :body (.getInputStream connection)})))

(defn serve-assets
  "One realized path map per revision. A supplied TTL owns development refresh;
   no unowned watcher observes unrelated resources or upload directories."
  [app get-assets optimize {:keys [cache-live-assets] :as options}]
  (let [load! (fn []
                (let [assets (vec (optimize (strategies/guard-against-duplicate-assets
                                             (get-assets)) options))]
                  {:assets assets, :by-path (into {} (map (juxt asset/path identity) assets))}))
        snapshot! (if cache-live-assets
                    (memo/ttl load! {} :ttl/threshold cache-live-assets)
                    (let [snapshot (load!)] (constantly snapshot)))]
    (fn [request]
      (let [{:keys [assets by-path]} (snapshot!)]
        (if-let [asset (when (#{:get :head} (:request-method request)) (by-path (:uri request)))]
          (asset-response asset)
          (app (assoc request :optimus-assets assets)))))))
