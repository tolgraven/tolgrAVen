(ns tolgraven.ssr
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.content.service :as content]
            [tolgraven.platform.supabase :as supabase]
            [tolgraven.supabase.query :as query]
            [tolgraven.ssr.contract :as contract]
            [tolgraven.page :as page]
            [tolgraven.page-router :as router])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def renderer-version 3)
(def page-size 3)
(defonce *worker (atom nil))
(defonce *cache (atom {}))

(defn worker-path [] (or (System/getenv "SSR_WORKER") (System/getenv "BLOG_SSR_WORKER") "target/ssr/site.js"))
(defn enabled? []
  (= "true" (or (System/getenv "SSR_ENABLED") (System/getenv "BLOG_SSR_ENABLED"))))

(defn route [uri]
  (when-let [match (router/match uri)]
    (when (get-in match [:data :ssr]) (page/selection match))))

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
  ;; One bounded persistent renderer. Its synchronous request scope seeds and
  ;; clears re-frame state; the serial protocol prevents request interleaving.
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

(defn- display-date [ts]
  (when ts
    (let [minutes (quot (- (System/currentTimeMillis) (long ts)) 60000)
          relative (fn [n unit] (str n " " unit (when (not= 1 n) "s") " ago"))]
      (cond
        (zero? minutes) "now"
        (< minutes 60) (relative minutes "minute")
        (< minutes 1440) (relative (quot minutes 60) "hour")
        :else (.format DateTimeFormatter/ISO_LOCAL_DATE
                       (.atZone (Instant/ofEpochMilli (long ts)) ZoneOffset/UTC))))))

(defn- post [row author]
  ;; Strict public allowlist. No service key, email, auth metadata, or raw DB row
  ;; can accidentally become hydration state.
  (let [{:keys [id permalink title text tags ts]} row
        tags (if (string? tags) (string/split tags #"\s+") tags)]
    {:id id :permalink permalink :title title :text text :ts ts
     :user (:user_id row) :author (cond-> (select-keys author [:id :name :avatar])
                                  (:bg_color author) (assoc :bg-color (:bg_color author)))
     :tags (vec (distinct (remove string/blank? tags)))
     :date (display-date ts)}))

(defn- public-comment [row author]
  (assoc (select-keys row [:id :title :text :score :path :ts])
         :parent-post (:parent_post row) :parent-comment (:parent_comment row)
         :user (:user_id row) :author (cond-> (select-keys author [:id :name :avatar])
                                      (:bg_color author) (assoc :bg-color (:bg_color author)))
         :date (display-date (:ts row))))

(defn- blog-snapshot! [uri {:keys [page post-id]}]
  (let [rows (rows! "blog_posts"
                    (cond-> {"select" (query/public-columns "blog_posts") "order" "id.desc"}
                      post-id (assoc "id" (str "eq." post-id) "limit" 1)
                      page (assoc "offset" (* page-size (dec page)) "limit" (inc page-size))))
        selected (vec (take page-size rows))
        summaries (rows! "blog_posts" {"select" "id,permalink,title,tags,ts,user_id" "order" "id.desc"})
        ;; Only the selected posts' threads, never the entire blog. Include their
        ;; replies so already-expanded root threads have identical first markup.
        comments (vec (mapcat #(rows! "blog_comments"
                                     {"select" (query/public-columns "blog_comments")
                                      "parent_post" (str "eq." (:id %)) "order" "ts.desc"}) selected))
        authors (into {} (for [id (distinct (keep :user_id (concat selected comments)))]
                           [id (first (rows! "site_users" {"id" (str "eq." id)
                                                           "select" "id,name,avatar,bg_color" "limit" 1}))]))
        ;; Read the CMS too before deciding a cached render is still valid.
        bundle (content/fresh-bundle! [:document :header :common :blog :footer :post-footer])]
    {:renderer-version renderer-version :kind :blog :path uri :page page :post-id post-id
     :more? (and page (> (count rows) page-size))
     :missing? (and post-id (empty? selected))
     :posts (mapv #(post % (get authors (:user_id %))) selected)
     :comments (mapv #(public-comment % (get authors (:user_id %))) comments)
     :summaries (mapv #(-> (select-keys % [:id :permalink :title :tags :ts])
                          (assoc :user (:user_id %))) summaries)
     :trusted-author-ids (mapv :user_id (rows! "auth_roles" {"role" "eq.admins" "select" "user_id"}))
     :content (:content bundle)}))

(defmulti page-data!
  "Public data adapters for registered pages. Rendering/cache logic is page-agnostic."
  (fn [spec _uri _selection] (or (:data-source spec) :content)))

(defmethod page-data! :content [spec _uri _selection]
  {:content (:content (content/fresh-bundle!
                       (vec (distinct (mapcat :keys (page/dependencies spec))))))})

(defmethod page-data! :blog [_spec uri selection]
  (blog-snapshot! uri selection))

(defmethod page-data! :docs [spec uri {:keys [doc]}]
  (if-let [resource (io/resource (str "docs/codox/" doc ".html"))]
    (assoc (page-data! (assoc spec :data-source :content) uri nil)
           :app-db-edn (pr-str {:docs {doc (string/replace (slurp resource)
                                                        #"^[\s\S]*<body[^\>]*>([\s\S]*)<\/body>[\s\S]*$" "$1")}
                               :state {:docs {:current-page doc}}}))
    (throw (ex-info "Documentation page not found" {:status 404}))))

(defn snapshot! [uri selection]
  (let [spec (:data (router/match uri))]
    (merge {:renderer-version renderer-version :kind (or (:kind spec) (:module spec))
            :path uri :posts []}
           (page-data! spec uri selection))))

(defn page! [uri & [query-params]]
  (when-let [selection (route uri)]
    (let [snapshot (cond-> (snapshot! uri selection)
                     (seq query-params) (assoc :query-params query-params))
          cache-key [uri query-params]]
      ;; Compare the full fresh public snapshot, not timestamps (the schema has
      ;; no reliable post revision). Changed/deleted rows and page membership
      ;; therefore cannot hit a stale path cache. Errors never populate it.
      (locking *cache
        (let [cached (get @*cache cache-key)]
          (if (= snapshot (:snapshot cached))
            (assoc cached :cache :hit)
            (let [html (render! snapshot)
                  entry {:snapshot snapshot :html html :at (System/nanoTime)}]
              (let [size (+ (count html) (count (pr-str snapshot)))]
                (when (< size 2000000)
                  (swap! *cache dissoc cache-key)
                  (loop []
                    (when (and (seq @*cache)
                               (or (>= (count @*cache) 64)
                                   (> (+ size (reduce + 0 (map :size (vals @*cache)))) 8000000)))
                      (swap! *cache dissoc (key (apply min-key (comp :at val) @*cache)))
                      (recur)))
                  (swap! *cache assoc cache-key (assoc entry :size size))))
              (assoc entry :cache :miss))))))))
