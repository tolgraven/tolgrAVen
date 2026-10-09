(ns tolgraven.ssr
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.content.service :as content]
            [tolgraven.config :as config]
            [tolgraven.validation :as validation]
            [tolgraven.ssr.contract-schema :as schema]
            [tolgraven.concurrent :as concurrent]
            [tolgraven.supabase.reader :as reader]
            [tolgraven.platform.supabase :as supabase]
            [tolgraven.supabase.plan :as plan]
            [tolgraven.page :as page]
            [tolgraven.page-router :as router])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent ArrayBlockingQueue TimeUnit CompletableFuture Future]))

(def renderer-version 3)
(defn settings []
  (let [settings (merge {:enabled true
                         :render-workers 2
                         :cache-ttl-ms 3600000
                         :worker "target/ssr/site.js"
                         :node-binary "node"}
                        (:ssr config/env))]
    (when (config/validation-enabled?) (validation/check! "SSR configuration" schema/settings settings))
    settings))

(defonce *pool (atom nil))
(defn- pool! []
  (or @*pool
      (locking *pool
        (or @*pool
            (let [configured (:render-workers (settings))
                  size (if (int? configured) (max 1 (min 4 configured)) 2)
                  pool {:workers (vec (repeatedly size #(atom nil)))
                        :available (doto (ArrayBlockingQueue. size true) (.addAll (vec (range size))))}]
              (reset! *pool pool))))))
(defonce *inflight (atom {}))
(defonce *cache (atom {}))

(defn worker-path [] (:worker (settings)))
(defn enabled? [] (not (false? (:enabled (settings)))))

(defn renderer-build []
  (let [file (io/file (worker-path)) revision (io/file (str (worker-path) ".revision"))]
    (hash [(str file) (.lastModified file) (.length file)
           (when (.exists revision) (slurp revision))])))

(defn route [uri]
  (when-let [match (router/match uri)]
    (when (get-in match [:data :ssr]) (page/selection match))))

(defn- stop-slot! [slot]
  (locking slot
    (when-let [{:keys [process]} @slot]
      (reset! slot nil)
      (.destroyForcibly ^Process process))))

(defn stop-worker! [] (doseq [slot (:workers @*pool)] (stop-slot! slot)))

(defn- worker! [slot]
  (locking slot
    (or (when-let [w @slot]
          (if (and (.isAlive ^Process (:process w)) (= (:build w) (renderer-build)))
            w (do (stop-slot! slot) nil)))
        (let [builder (ProcessBuilder. ^java.util.List
                                     [(:node-binary (settings))
                                      "--max-old-space-size=128" (worker-path)])
              _ (.put (.environment builder) "VALIDATION_ENABLED" (str (config/validation-enabled?)))
              process (-> builder
                          (.redirectError ProcessBuilder$Redirect/INHERIT)
                          (.start))
              worker {:process process :build (renderer-build)
                      :in (io/writer (.getOutputStream process) :encoding "UTF-8")
                      :out (io/reader (.getInputStream process) :encoding "UTF-8")}]
          (reset! slot worker)
          worker))))

(defn render-page! [snapshot]
  ;; Each lease owns one isolated Node process. Different pages can render at
  ;; once; no React/re-frame state is shared between those processes.
  (let [{:keys [workers available]} (pool!)]
   (if-some [index (.poll ^ArrayBlockingQueue available 10000 TimeUnit/MILLISECONDS)]
    (let [slot (nth workers index)]
      (try
        (let [{:keys [in out]} (worker! slot)
              work (concurrent/submit!
                     #(do (.write ^java.io.Writer in (str (json/write-str snapshot) "\n"))
                          (.flush ^java.io.Writer in)
                          (when-let [line (.readLine ^java.io.BufferedReader out)]
                            (json/read-str line :key-fn keyword))))]
          (try
            (let [result (concurrent/await! work 10000)]
              (when-not (and (string? (:html result)) (map? (:module-views result)))
                (throw (ex-info "Page renderer unavailable" {:status 503})))
              (when (config/validation-enabled?)
                (validation/check! "Renderer response" schema/render-response result))
              {:html (:html result)
               :snapshot (assoc snapshot :module-views (:module-views result))})
            (catch Exception e
              (stop-slot! slot)
              (.cancel ^Future work true)
              (throw (ex-info "Page renderer unavailable" {:status 503} e)))))
        (finally (.offer ^ArrayBlockingQueue available index))))
    (throw (ex-info "Page renderer queue is busy" {:status 503})))))

(defn render! [snapshot]
  (:html (render-page! snapshot)))

(defmulti page-data!
  "Public data adapters for registered pages. Rendering/cache logic is page-agnostic."
  (fn [spec _uri _selection] (or (:data-source spec) :content)))

(defmethod page-data! :content [spec _uri selection]
  (let [tasks [(fn [] {:content (:content (concurrent/upstream!
                                          #(content/fresh-bundle!
                                             (vec (distinct (mapcat :keys (page/dependencies spec)))))))})]
        tasks (if-let [nodes (:data-plan spec)]
                (conj tasks reader/public-context!
                      (fn []
                        (let [result (plan/evaluate nodes selection reader/read-many!)]
                          (when-not (:ready? result) (throw (ex-info "Incomplete page data plan" {:status 503})))
                          ((:snapshot spec) result (System/currentTimeMillis)))))
                tasks)]
    (apply merge (concurrent/map! (fn [task] (task)) tasks))))

(defmethod page-data! :docs [spec uri {:keys [doc]}]
  (if-let [resource (io/resource (str "docs/codox/" doc ".html"))]
    (assoc (page-data! (assoc spec :data-source :content) uri nil)
           :app-db-edn (pr-str {:docs {doc (string/replace (slurp resource)
                                                        #"^[\s\S]*<body[^\>]*>([\s\S]*)<\/body>[\s\S]*$" "$1")}
                               :state {:docs {:current-page doc}}}))
    (throw (ex-info "Documentation page not found" {:status 404}))))

(defn snapshot! [uri selection]
  (let [spec (:data (router/match uri))]
    (merge {:renderer-version renderer-version :renderer-build (renderer-build) :kind (or (:kind spec) (:module spec))
            :path uri :posts []}
           (supabase/with-rest-connections! #(page-data! spec uri selection)))))

(def ^:private cache-entry-schema
  [:map
   [:snapshot schema/snapshot]
   [:selection [:map-of :keyword :any]]
   [:html :string]
   [:build :int]
   [:at :int]])

(defn- cache-entry! [cache-key entry]
  (when (config/validation-enabled?)
    (validation/check! "SSR cache entry" cache-entry-schema entry))
  (let [size (+ (count (:html entry)) (count (pr-str (:snapshot entry))))]
    (when (< size 2000000)
      ;; Only bookkeeping is serialized. Fetching and rendering never hold this lock.
      (locking *cache
        (swap! *cache dissoc cache-key)
        (loop []
          (when (and (seq @*cache)
                     (or (>= (count @*cache) 64)
                         (> (+ size (reduce + 0 (map :size (vals @*cache)))) 8000000)))
            (swap! *cache dissoc (key (apply min-key (comp :at val) @*cache)))
            (recur)))
        (swap! *cache assoc cache-key (assoc entry :size size))))))

(defn- fresh? [{:keys [at build]}]
  (let [ttl (:cache-ttl-ms (settings))]
    (and at
         (= build (renderer-build))
         (pos? ttl)
         (< (- (System/nanoTime) at) (* 1000000 ttl)))))

(defn- fresh-page-data [uri selection]
  ;; Queries control presentation; the page declaration selects public data from
  ;; the path. Reuse data only, never another query's HTML or hydration settings.
  (->> @*cache
       (keep (fn [[[path _] entry]]
               (when (and (= uri path) (= selection (:selection entry)) (fresh? entry))
                 entry)))
       (sort-by :at >)
       first))

(defn- build-page! [uri selection query-params]
  (let [key [uri query-params]
        cached (get @*cache key)]
    (if (and (= selection (:selection cached)) (fresh? cached))
      (assoc cached :cache :hit)
      (let [reused (fresh-page-data uri selection)
            public-data (if reused
                          (dissoc (:snapshot reused) :query-params :document-title :module-views)
                          (snapshot! uri selection))
            parameters (:parameters (router/request-match uri (or query-params {})))
            snapshot (cond-> public-data
                       parameters (assoc :route-parameters parameters)
                       (seq query-params) (assoc :query-params query-params))
            title (page/document-title (:data (router/match uri)) snapshot)
            snapshot (cond-> snapshot (some? title) (assoc :document-title title))
            unchanged? (and (= (:build cached) (renderer-build))
                            (= snapshot (dissoc (:snapshot cached) :module-views)))
            ;; Renew freshness only after a successful public snapshot read.
            ;; An unchanged snapshot also validates its paired HTML again.
            rendered (if unchanged?
                       (select-keys cached [:snapshot :html])
                       (render-page! snapshot))
            entry (assoc rendered
                         :selection selection
                         :build (renderer-build)
                         :at (or (:at reused) (System/nanoTime)))]
        (cache-entry! key entry)
        (assoc entry :cache (if unchanged? :hit :miss))))))

(defn page-async!
  "Share an in-flight snapshot/render for a path while unrelated paths progress.
   Return a Future; failures are never cached and retries get a new flight."
  [uri & [query-params]]
  (if-let [selection (route uri)]
    (let [key [uri query-params]]
      (locking *inflight
        (or (get @*inflight key)
            (let [result (CompletableFuture.)]
              (if (>= (count @*inflight) 64)
                (.completeExceptionally result (ex-info "Page queue is busy" {:status 503}))
                (do
                  (swap! *inflight assoc key result)
                  (concurrent/submit!
                   #(let [outcome (try {:value (build-page! uri selection query-params)}
                                       (catch Throwable error {:error error}))]
                      (swap! *inflight dissoc key)
                      (if-let [error (:error outcome)]
                        (.completeExceptionally result error)
                        (.complete result (:value outcome)))))))
              result))))
    (CompletableFuture/completedFuture nil)))

(defn page! [uri & [query-params]]
  ;; Layout is synchronous inside the outer Ring async/virtual-thread boundary.
  (concurrent/await! (page-async! uri query-params) 60000))

(defonce *shell-cache (atom {}))

(defn cached? [uri query-params]
  ;; Expired entries revalidate behind the streamed shell, not before headers.
  (let [entry (get @*cache [uri query-params])]
    (boolean (and (= (route uri) (:selection entry)) (fresh? entry)))))

(defn shell! [uri query-params]
  (let [spec (:data (router/match uri))
        snapshot {:renderer-version renderer-version :renderer-build (renderer-build)
                  :kind (or (:kind spec) (:module spec)) :path uri
                  :posts [] :shell? true :query-params query-params
                  :route-parameters (:parameters (router/request-match uri (or query-params {})))
                  :content (content/immediate-content)}
        key [uri query-params (:shell spec) (:content snapshot) (:renderer-build snapshot)]]
    ;; No network work and no worker lease while upstream data is pending.
    ;; Links and active navigation are route-specific, even in the loading shell.
    (or (get @*shell-cache key)
        (let [result (render-page! snapshot)]
          (swap! *shell-cache (fn [cache] (assoc (if (> (count cache) 32) {} cache) key result)))
          result))))
