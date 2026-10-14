(ns website.modules.github.sync
  "Resumable all-branch archive. One bounded page per leased checkpoint."
  (:require [clojure.tools.logging :as log]
            [mount.core :as mount]
            [clojure.string :as string]
            [website.config :as config]
            [website.content.service :as content]
            [website.modules.github.provider :as provider]
            [website.modules.github.message :as message]
            [website.modules.github.archive-schema :as schema]
            [website.validation :as validation]
            [website.platform.supabase :as platform])
  (:import [java.util UUID]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(defn target! []
  (let [{:keys [user repo]} (get-in (content/bundle! [:github-settings]) [:content :github-settings])]
    (when (and (provider/user? user) (provider/repo? repo))
      {:owner (string/lower-case user), :repo (string/lower-case repo)})))

(defn commit-row [item]
  (let [{:keys [sha commit author html_url]} item
        [_ subcategory category] (message/parse-message (:message commit))]
    (validation/check! "Archived GitHub commit" schema/row
    {:sha sha
     :message (:message commit)
     :authored_at (get-in commit [:author :date])
     :committed_at (get-in commit [:committer :date])
     :author_name (get-in commit [:author :name])
     :author_login (:login author)
     :avatar_url (:avatar_url author)
     :html_url html_url
     :category (or category subcategory)
     :subcategory (when category subcategory)})))

(defn- branch-page! [path state branch]
  (let [base (when-not (:full? state)
               (or (:base state)
                   (:sha (first (filter #(= (:name branch) (:name %)) (:heads state))))
                   (:sha (first (:heads state)))))
        parameters {:query-params {:per_page 100, :page (or (:page state) 1)}}]
    (if-not base
      {:items (provider/request! :get (str path "/commits")
                (assoc-in parameters [:query-params :sha] (:sha branch)))}
      (try
        (let [value (provider/request! :get (str path "/compare/" base "..." (:sha branch)) parameters)]
          (when-not (vector? (:commits value)) (throw (ex-info "Invalid GitHub comparison" {})))
          {:items (:commits value), :base base})
        (catch clojure.lang.ExceptionInfo error
          ;; Unrelated/unavailable ancestry, or an oversized file diff, can
          ;; safely restart a full immutable-head walk. Rate limits must wait.
          (if (or (= 404 (:upstream-status (ex-data error))) (= :body-limit (:reason (ex-data error))))
            {:fallback? true}
            (throw error)))))))

(defn- still-public! [path result heads]
  (if (and (seq (:rows result)) (not (false? (:private (provider/request! :get path {})))))
    {:state {:stage "metadata", :heads (or heads [])}
     :rows []
     :public false
     :delay 900}
    result))

(defn step!
  "Pin immutable heads; a fully archived base allows paginated BASE..HEAD.
   Never stop merely on seeing one known commit in a date-ordered page."
  [{:keys [owner repo state is_public complete]}]
  (validation/check! "GitHub archive checkpoint" schema/checkpoint state)
  (let [path (provider/repository-path owner repo)
        {:keys [stage page branches pending heads default-branch]} state
        base {:rows [], :public is_public, :complete complete, :delay 0}
        visible? (or (= stage "metadata")
                     (false? (:private (provider/request! :get path {}))))]
    (merge base
     (if-not visible?
       {:state {:stage "metadata", :heads (or heads [])}, :public false, :delay 900}
      (case stage
        ("branches" "tags")
        (let [items (provider/request! :get (str path "/" stage) {:query-params {:per_page 100, :page (or page 1)}})
              branches' (into (or branches [])
                              (map #(hash-map :name (str (when (= stage "tags") "tag:") (:name %))
                                             :sha (get-in % [:commit :sha])) items))]
          (if (= 100 (count items))
            {:state (assoc state :branches branches' :page (inc (or page 1)))}
            (if (= stage "branches")
              {:state (assoc state :stage "tags" :branches branches' :page 1)}
              {:state {:stage "commits"
                     :heads (or heads [])
                     :pending (->> branches'
                                   (remove (fn [branch] (some #{branch} heads)))
                                   (sort-by #(if (= default-branch (:name %)) 0 1))
                                   vec)
                     :page 1}})))
        "commits"
        (if-let [{:keys [name sha] :as branch} (first pending)]
          (let [{:keys [items base fallback?]} (branch-page! path state branch)
                finished? (< (count items) 100)]
            (if fallback?
              {:state (assoc state :full? true :page 1)}
              (still-public! path
              {:rows (mapv commit-row items)
               :state (if finished?
                        (-> state
                            (assoc :heads (conj (filterv #(not= name (:name %)) heads) branch)
                                   :pending (vec (rest pending)) :page 1)
                            (dissoc :base :full?))
                        (cond-> (update state :page (fnil inc 1)) base (assoc :base base)))} heads)))
          {:state {:stage "metadata", :heads (or heads [])}
           :complete true
           :delay 900})
        ;; Fail closed before indexing a new repository. Never ingest private
        ;; content even if the operator's token has access to it.
        (let [metadata (provider/request! :get path {})]
          (if (false? (:private metadata))
            {:state {:stage "branches", :heads (or heads []), :branches [], :page 1
                     :default-branch (:default_branch metadata)}
             :public true}
            {:state {:stage "metadata", :heads (or heads [])}
             :public false
             :delay 900})))))))

(defn- rpc! [function data]
  (:body (platform/request! :post (str "rpc/" function) {:content-type :json, :form-params data})))

(defn work!
  ([] (when-let [target (target!)] (work! target)))
  ([{:keys [owner repo]}]
    (provider/repository-path owner repo)
    (let [worker (str (UUID/randomUUID))
          parameters {:p_owner owner, :p_repo repo, :p_worker worker}]
      (when-let [job (first (rpc! "website_claim_github" parameters))]
        (let [result (try (step! job)
                          (catch Exception error
                            (log/warn "GitHub archive page unavailable; retaining checkpoint")
                            {:state (if (= 404 (:upstream-status (ex-data error)))
                                      (assoc (:state job) :stage "metadata") (:state job))
                             :rows []
                             :public (if (= 404 (:upstream-status (ex-data error))) false (:is_public job))
                             :complete (:complete job)
                             :delay (min 86400 (max 60 (- (or (:retry-at (ex-data error)) 0)
                                                          (quot (System/currentTimeMillis) 1000))))}))]
          (when (rpc! "website_finish_github"
                (merge parameters {:p_state (:state result)
                                   :p_rows (:rows result)
                                   :p_public (:public result)
                                   :p_complete (:complete result)
                                   :p_delay (:delay result)}))
            result))))))

(defn start!
  "Module-owned background job; never blocks HTTP startup on a history scan."
  []
  (when-not (false? (get-in config/env [:github :sync-enabled]))
    (try
      (platform/rest-base-url)
      (platform/service-key)
      (let [executor (Executors/newSingleThreadScheduledExecutor)]
        (.scheduleWithFixedDelay executor
          ^Runnable (fn [] (try (loop [remaining 8]
                                 (when (and (pos? remaining) (work!)) (recur (dec remaining))))
                               (catch Exception _ (log/warn "GitHub archive temporarily unavailable"))))
          10 60 TimeUnit/SECONDS)
        (fn [] (.shutdownNow ^ExecutorService executor)
               (.awaitTermination ^ExecutorService executor 5 TimeUnit/SECONDS)))
      (catch Exception _
        (log/info "GitHub archive requires Supabase server settings")
        nil))))


(defn backfill!
  "Explicit operator import through the same leased worker. Does not alter CMS."
  [target max-steps]
  (loop [steps 0]
    (if (>= steps max-steps)
      {:steps steps, :status :bounded}
      (if-let [result (work! target)]
        (let [summary {:steps (inc steps)
                       :stage (get-in result [:state :stage])
                       :remaining (count (get-in result [:state :pending]))
                       :complete (:complete result)}]
          (when (or (zero? (mod steps 10)) (pos? (:delay result))) (println summary))
          (if (pos? (:delay result)) summary (recur (inc steps))))
        {:steps steps, :status :waiting}))))

(defn -main [& arguments]
  (when-not (#{0 2} (count arguments))
    (throw (ex-info "Use no arguments or exactly owner repository" {})))
  (mount/start #'config/env)
  (try
    (let [[owner repo] arguments
          target (if (and owner repo)
                   {:owner (string/lower-case owner), :repo (string/lower-case repo)}
                   (target!))]
      (when-not target (throw (ex-info "Configure a GitHub repository or pass owner repo" {})))
      (provider/repository-path (:owner target) (:repo target))
      (println (backfill! target 10000)))
    (finally (mount/stop #'config/env) (shutdown-agents))))
