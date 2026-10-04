(ns tolgraven.ssr
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.content.service :as content]
            [tolgraven.platform.supabase :as supabase]
            [tolgraven.supabase.query :as query]
            [tolgraven.ssr.contract :as contract])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def renderer-version 2)
(def page-size 3)
(defonce *worker (atom nil))
(defonce *cache (atom {}))

(defn worker-path [] (or (System/getenv "SSR_WORKER") (System/getenv "BLOG_SSR_WORKER") "target/ssr/site.js"))
(defn enabled? []
  (= "true" (or (System/getenv "SSR_ENABLED") (System/getenv "BLOG_SSR_ENABLED"))))

(defn route [uri]
  (cond
    (contract/page-spec uri) {:kind :landing}
    (= uri "/blog") {:page 1}
    (re-matches #"/blog/page/[1-9]\d{0,5}" uri)
    {:page (Long/parseLong (last (string/split uri #"/")))}
    (re-matches #"/blog/post/[^/]+" uri)
    (when-let [[_ id] (re-find #"(?:^|[-/])(\d{1,15})$" uri)]
      {:post-id (Long/parseLong id)})
    :else nil))

(defn stop-worker! []
  (when-let [{:keys [process]} @*worker]
    (reset! *worker nil)
    (.destroyForcibly ^Process process)))

(defn- worker! []
  (or (when-let [w @*worker] (when (.isAlive ^Process (:process w)) w))
      (let [process (-> (ProcessBuilder. ^java.util.List
                                         [(or (System/getenv "NODE_BINARY") "node")
                                          "--max-old-space-size=128" (worker-path)])
                        (.redirectError ProcessBuilder$Redirect/INHERIT)
                        (.start))
            worker {:process process
                    :in (io/writer (.getOutputStream process) :encoding "UTF-8")
                    :out (io/reader (.getInputStream process) :encoding "UTF-8")}]
        (reset! *worker worker)
        worker)))

(defn render! [snapshot]
  ;; One bounded persistent renderer, never a process or global re-frame db per
  ;; request. Serial JSON-lines protocol also prevents cross-request interleaving.
  (locking *worker
    (let [{:keys [in out]} (worker!)
          work (future
                   (.write ^java.io.Writer in (str (json/write-str snapshot) "\n"))
                   (.flush ^java.io.Writer in)
                   (when-let [line (.readLine ^java.io.BufferedReader out)]
                     (json/read-str line :key-fn keyword)))]
      (try
        (let [result (deref work 10000 ::timeout)]
          (when-not (and (map? result) (string? (:html result)))
            (throw (ex-info "Page renderer unavailable" {:status 503})))
          (:html result))
        (catch Exception e
          (stop-worker!)
          (future-cancel work)
          (throw (ex-info "Page renderer unavailable" {:status 503} e)))))))

(defn- rows! [table params]
  (:body (supabase/request! :get table {:query-params params})))

(defn- post [row author]
  ;; Strict public allowlist. No service key, email, auth metadata, or raw DB row
  ;; can accidentally become hydration state.
  (let [{:keys [id permalink title text tags ts]} row
        tags (if (string? tags) (string/split tags #"\s+") tags)]
    {:id id :permalink permalink :title title :text text :ts ts
     :user (:user_id row) :author (select-keys author [:id :name])
     :tags (vec (distinct (remove string/blank? tags)))
     :date (when ts (.format DateTimeFormatter/ISO_LOCAL_DATE
                            (.atZone (Instant/ofEpochMilli (long ts)) ZoneOffset/UTC)))}))

(defn- blog-snapshot! [uri {:keys [page post-id]}]
  (let [rows (rows! "blog_posts"
                    (cond-> {"select" (query/public-columns "blog_posts") "order" "id.desc"}
                      post-id (assoc "id" (str "eq." post-id) "limit" 1)
                      page (assoc "offset" (* page-size (dec page)) "limit" (inc page-size))))
        selected (vec (take page-size rows))
        authors (into {} (for [id (distinct (keep :user_id selected))]
                           [id (first (rows! "site_users" {"id" (str "eq." id)
                                                           "select" "id,name" "limit" 1}))]))
        ;; Read the CMS too before deciding a cached render is still valid.
        bundle (content/fresh-bundle! [:document :header :common :blog :footer :post-footer])]
    {:renderer-version renderer-version :kind :blog :path uri :page page
     :more? (and page (> (count rows) page-size))
     :missing? (and post-id (empty? selected))
     :posts (mapv #(post % (get authors (:user_id %))) selected)
     :content (:content bundle)}))

(defn snapshot! [uri selection]
  (if-let [spec (contract/page-spec uri)]
    {:renderer-version renderer-version :kind (:id spec) :path uri :posts []
     :content (:content (content/fresh-bundle! (get-in spec [:depends 0 :keys])))}
    (blog-snapshot! uri selection)))

(defn page! [uri]
  (when-let [selection (route uri)]
    (let [snapshot (snapshot! uri selection)]
      ;; Compare the full fresh public snapshot, not timestamps (the schema has
      ;; no reliable post revision). Changed/deleted rows and page membership
      ;; therefore cannot hit a stale path cache. Errors never populate it.
      (locking *cache
        (let [cached (get @*cache uri)]
          (if (= snapshot (:snapshot cached))
            (assoc cached :cache :hit)
            (let [html (render! snapshot)
                  entry {:snapshot snapshot :html html :at (System/nanoTime)}]
              (let [size (+ (count html) (count (pr-str snapshot)))]
                (when (< size 2000000)
                  (swap! *cache dissoc uri)
                  (loop []
                    (when (and (seq @*cache)
                               (or (>= (count @*cache) 64)
                                   (> (+ size (reduce + 0 (map :size (vals @*cache)))) 8000000)))
                      (swap! *cache dissoc (key (apply min-key (comp :at val) @*cache)))
                      (recur)))
                  (swap! *cache assoc uri (assoc entry :size size))))
              (assoc entry :cache :miss))))))))
