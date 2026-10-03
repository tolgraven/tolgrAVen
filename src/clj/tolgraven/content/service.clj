(ns tolgraven.content.service
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.config :as config]
            [tolgraven.content.contract :as contract]))

(defonce *cache (atom {}))
(defn settings []
  {:url (or (System/getenv "STRAPI_URL") (:strapi-url config/env))
   :token (or (System/getenv "STRAPI_READ_TOKEN") (:strapi-read-token config/env))})

(defn- load-content! [ks]
  (let [{:keys [url token]} (settings)]
    (if (seq url)
      (let [response (http/get (str (string/replace url #"/+$" "") "/api/site-content")
                              {:headers {"Authorization" (str "Bearer " token)}
                               :query-params {"keys" (string/join "," (map name ks))}
                               :as :json :coerce :always :throw-exceptions false
                               :conn-timeout 3000 :socket-timeout 10000})
            body (:body response)]
        (when-not (and (= 200 (:status response)) (= contract/version (:version body))
                       (map? (:content body)) (every? #(contains? (:content body) %) ks))
          (throw (ex-info "CMS content unavailable" {:status 503})))
        (select-keys (:content body) ks))
      ;; Development/offline seed is server-side only. A configured CMS failure
      ;; never silently replaces the editor's content with the original seed.
      (select-keys (json/read-str (slurp (io/resource "content-seed.json")) :key-fn keyword) ks))))

(defn bundle!
  ([] (bundle! nil))
  ([requested]
   (let [ks (contract/validate-keys requested)
         source (:url (settings))
         now (System/currentTimeMillis)]
     (locking *cache
       (let [cached (get @*cache source {})
             missing (filterv #(> (- now (get-in cached [% :at] 0)) 30000) ks)]
         (when (seq missing)
           (let [fresh (contract/normalize-content (load-content! missing))]
             (swap! *cache update source merge
                    (into {} (map (fn [[k v]] [k {:at now :value v}])) fresh)))))
       {:version contract/version
        :content (into {} (map (fn [k] [k (get-in @*cache [source k :value])])) ks)}))))

(defn for-route! [route] (bundle! (contract/keys-for-route route)))

(defn response [requested]
  (try {:status 200 :headers {"Cache-Control" "public, max-age=30"} :body (bundle! requested)}
       ;; No upstream exceptions or credentials in browser errors.
       (catch Exception error {:status (or (:status (ex-data error)) 503)
                               :body {:error "Content is temporarily unavailable"}})))

(defn hydration-json [bundle]
  ;; Safe inside a non-executable script element, even for editor-provided text.
  (-> (json/write-str bundle)
      (string/replace "<" "\\u003c")
      (string/replace "&" "\\u0026")
      (string/replace "\u2028" "\\u2028")
      (string/replace "\u2029" "\\u2029")))
