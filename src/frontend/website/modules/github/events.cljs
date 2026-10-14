(ns website.modules.github.events
  (:require [website.react :as r]
            [website.modules.github.message :as message]
            [clojure.string :as string]))

(defn current-request? [db user repo generation]
  (let [state (get-in db [:state :github])]
    (and (= [user repo] (:target state)) (= generation (:generation state)))))

(defn- begin-query [db target query]
  ;; Feed queries own their search/page cache, while immutable revision viewers
  ;; can already be opening when the module's configuration finishes loading.
  (-> db
      (assoc-in [:state :github]
                (merge (select-keys (get-in db [:state :github]) [:diff-view :diff-attempt])
                       {:target target
                        :generation (inc (get-in db [:state :github :generation] 0))
                        :query query}))
      (assoc-in [:content :github] (select-keys (get-in db [:content :github]) [:diff-view]))))

(r/reg-event-fx :github/init
  (fn [{:keys [db]} _]
    (let [{:keys [user repo]} (get-in db [:module :github :configuration :settings])
          target [user repo]
          db' (if (= target (get-in db [:state :github :target])) db
                  (begin-query db target (or (get-in db [:state :form-field :github :search]) "")))]
      (when (and (seq user) (seq repo))
        {:db db'
         :dispatch [:github/fetch-commits user repo 1 (get-in db' [:state :github :generation])]}))))

(r/reg-event-fx :github/search
  (fn [{:keys [db]} [_ query]]
    (let [[user repo :as target] (get-in db [:state :github :target])]
      (when (and user repo (not= query (get-in db [:state :github :query])))
        (let [db' (-> (begin-query db target query) (assoc-in [:state :form-field :github :search] query))]
          {:db db'
           :dispatch-later [{:ms 180
                             :dispatch [:github/fetch-commits user repo 1 (get-in db' [:state :github :generation])]}]})))))

(r/reg-event-fx :github/refresh
  (fn [{:keys [db]} _]
    (let [[user repo :as target] (get-in db [:state :github :target])
          db' (begin-query db target (get-in db [:state :github :query] ""))]
      (when (and user repo)
        {:db db'
         :dispatch [:github/fetch-commits user repo 1 (get-in db' [:state :github :generation])]}))))

(r/reg-event-fx :github/fetch-commits
  (fn [{:keys [db]} [_ user repo page generation]]
    (let [state (get-in db [:state :github])
          generation' (or generation (:generation state))]
      (when (and (current-request? db user repo generation') page
                 (not (some #{page} (:pages-fetched state)))
                 (not (contains? (:pages-pending state) page)))
        {:db (update-in db [:state :github :pages-pending] (fnil conj #{}) page)
         :dispatch [:http/get {:uri "/api/github/commits"
                              :url-params (cond-> {:user user, :repo repo, :q (or (:query state) "")}
                                            (> page 1) (assoc :cursor (get-in db [:content :github :next-cursor])))}
                    [:github/save-commits user repo generation' page]
                    [:github/request-failed user repo generation' :page page]]}))))

(r/reg-event-fx :github/save-commits
  (fn [{:keys [db]} [_ user repo generation page {:keys [commits total complete next-cursor]}]]
    (when (and (current-request? db user repo generation)
               (contains? (get-in db [:state :github :pages-pending]) page))
      (let [commits' (mapv (fn [{:keys [commit sha] :as item}]
                             (let [ts (get-in commit [:author :date])
                                   [date clock] (string/split (or ts "") "T")]
                               (assoc item :date date :clock clock :ts ts
                                           :message (message/parse-message (:message commit))
                                           :sha7 (subs sha 0 (min 7 (count sha)))))) commits)]
        {:db (-> db
                 (update-in [:content :github :commits]
                   (fn [existing]
                     (let [known (set (map :sha existing))]
                       (into (vec existing) (remove #(contains? known (:sha %))) commits'))))
                 (assoc-in [:content :github :total] total)
                 (assoc-in [:content :github :next-cursor] next-cursor)
                 (assoc-in [:content :github :complete?] complete)
                 (update-in [:state :github :pages-fetched] (fnil conj []) page)
                 (update-in [:state :github :pages-pending] disj page))}))))

(r/reg-event-fx :github/fetch-commits-next
  (fn [{:keys [db]} [_ user repo]]
    (let [state (get-in db [:state :github])
          cursor (get-in db [:content :github :next-cursor])]
      (when (and (seq (:pages-fetched state)) cursor)
        {:dispatch [:github/fetch-commits user repo (inc (apply max (:pages-fetched state)))]}))))

(r/reg-event-fx :github/fetch-commit
  (fn [{:keys [db]} [_ user repo sha]]
    (when (and (= [user repo] (get-in db [:state :github :target])) sha
               (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
      {:dispatch [:http/get {:uri (str "https://api.github.com/repos/" user "/" repo "/commits/" sha)
                            :headers {"Accept" "application/vnd.github.v3+json"}}
                  [:github/save-commit user repo (get-in db [:state :github :generation]) sha]
                  [:github/request-failed user repo (get-in db [:state :github :generation]) :commit sha]]})))

(r/reg-event-db :github/save-commit
  (fn [db [_ user repo generation sha data]]
    (if (current-request? db user repo generation)
      (-> db (assoc-in [:content :github :commit sha] data)
             (update-in [:state :github :commits-fetched] (fnil conj []) sha)) db)))

(r/reg-event-fx :github/request-failed
  (fn [{:keys [db]} [_ user repo generation kind page error]]
    (when (current-request? db user repo generation)
      {:db (cond-> (assoc-in db [:content :github :errors] error)
             (= kind :page) (update-in [:state :github :pages-pending] disj page))
       :dispatch [:default-http-error error]})))
