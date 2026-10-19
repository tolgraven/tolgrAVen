(ns website.modules.github.events
  (:require [website.react :as r]
            [website.modules.github.model :as model]
            [website.modules.github.routes :as routes]
            [website.modules.github.diff :as diff]
            [clojure.string :as string]))

(defn current-request? [db user repo generation]
  (let [state (get-in db [:state :github])]
    (and (= [user repo] (:target state)) (= generation (:generation state)))))

(defn- begin-query [db target query]
  ;; Feed queries reset their page state; immutable detail and revision viewers
  ;; retain their independent caches while the managed configuration initializes.
  (let [same-target? (= target (get-in db [:state :github :target]))
        view (or (when same-target? (get-in db [:state :github :commit-view]))
                 (:sha (model/route-target (:common/route db)))
                 :commits)
        cached (merge (select-keys (get-in db [:content :github]) [:diff-view])
                      (when same-target? (select-keys (get-in db [:content :github]) [:commit :commit-errors])))]
    (-> db
        (assoc-in [:state :github]
                  (merge (select-keys (get-in db [:state :github]) [:diff-view :diff-attempt])
                         (when same-target? (select-keys (get-in db [:state :github]) [:list-position]))
                         {:target target
                          :generation (inc (get-in db [:state :github :generation] 0))
                          :query query
                          :commit-view view
                          :commits-fetched (if same-target? (get-in db [:state :github :commits-fetched] []) [])}))
        (assoc-in [:content :github] cached))))

(r/reg-event-fx :github/navigate-commit
  {:args [:tuple routes/commit-sha]}
  (fn [{:keys [db]} [_ sha]]
    (when (= (string/lower-case sha) (:sha (model/route-target (:common/route db))))
      {:dispatch-n [[:github/init]
                    [:github/select-commit sha]
                    [:scroll/component-target "github-commits" (get-in db [:common/route :path]) :instant]]})))

(r/reg-cofx :github/list-position
  (fn [coeffects]
    (assoc coeffects :github/list-position
           (when (exists? js/document)
             (some-> (.getElementById js/document "github-commits-box") .-scrollTop)))))

(r/reg-event-fx :github/select-commit
  {:args [:tuple routes/commit-sha]}
  [(r/inject-cofx :github/list-position)]
  (fn [{:keys [db] :as coeffects} [_ sha]]
    (let [[user repo] (get-in db [:state :github :target])
          position (:github/list-position coeffects)
          db' (cond-> (assoc-in db [:state :github :commit-view] sha)
                (and (number? position) (= :commits (get-in db [:state :github :commit-view] :commits)))
                (assoc-in [:state :github :list-position] position))]
      (cond-> {:db db'}
        (and user repo) (assoc :dispatch [:github/fetch-commit user repo sha])))))

(r/reg-event-db :github/close-commit
  (fn [db _] (assoc-in db [:state :github :commit-view] :commits)))

(r/reg-event-fx :github/open-diff
  {:args [:tuple diff/revision-schema]}
  (fn [_ [_ revision]]
    (when-let [href (diff/page-url revision)] {:github/open-document href})))

(r/reg-fx :github/open-document
  ;; The shared-memory viewer needs document isolation headers. A row intent
  ;; uses this native boundary; its individual links retain ordinary hrefs.
  (fn [href] (.assign js/location href)))

(r/reg-event-fx :github/init
  (fn [{:keys [db]} _]
    (let [{:keys [user repo]} (get-in db [:module :github :configuration :settings])
          target [user repo]
          focus (model/route-target (:common/route db))
          query (or (get-in db [:state :form-field :github :search]) "")
          state (get-in db [:state :github])
          db' (if (and (= target (:target state)) (= query (:query state)))
                db
                (begin-query db target query))]
      (when (and (seq user) (seq repo))
        {:db db'
         :dispatch-n [[:github/fetch-commits user repo 1 (get-in db' [:state :github :generation])]
                      (when focus [:github/fetch-commit user repo (:sha focus)])]}))))

(r/reg-event-fx :github/search
  {:args [:tuple [:string {:max 300}]]}
  (fn [{:keys [db]} [_ query]]
    (let [[user repo :as target] (get-in db [:state :github :target])
          query' (string/lower-case (string/trim query))]
      (when (and user repo)
        (let [changed? (not= query (get-in db [:state :github :query]))
              db' (-> (if changed? (begin-query db target query) db)
                      (assoc-in [:state :form-field :github :search] query))]
          (cond-> {:db db'}
            changed?
            (assoc :dispatch-later [{:ms 180
                                     :dispatch [:github/fetch-commits user repo 1 (get-in db' [:state :github :generation])]}])
            (re-matches #"[a-f0-9]{7,40}" query')
            (assoc :dispatch [:github/fetch-commit user repo query'])))))))

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
          generation' (or generation (:generation state))
          cursor (get-in db [:content :github :next-cursor])]
      (when (and (current-request? db user repo generation') page
                 (or (= page 1) cursor)
                 (not (some #{page} (:pages-fetched state)))
                 (not (contains? (:pages-pending state) page)))
        {:db (update-in db [:state :github :pages-pending] (fnil conj #{}) page)
         :dispatch [:http/get {:uri "/api/github/commits"
                              :url-params (cond-> {:user user, :repo repo, :q (or (:query state) "")}
                                            (> page 1) (assoc :cursor cursor))}
                    [:github/save-commits user repo generation' page]
                    [:github/request-failed user repo generation' :page page]]}))))

(r/reg-event-fx :github/save-commits
  (fn [{:keys [db]} [_ user repo generation page {:keys [commits total complete next-cursor]}]]
    (when (and (current-request? db user repo generation)
               (contains? (get-in db [:state :github :pages-pending]) page))
      (let [commits' (mapv model/normalize-commit commits)]
        {:db (-> db
                 (update-in [:content :github :commits] model/merge-commits commits')
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
               (not (contains? (get-in db [:state :github :commits-pending]) sha))
               (not (and (some #{sha} (get-in db [:state :github :commits-fetched]))
                         (get-in db [:content :github :commit sha :files-complete?]))))
      {:db (-> db
               (update-in [:state :github :commits-pending] (fnil conj #{}) sha)
               (assoc-in [:state :github :commit-pages-pending sha] 1)
               (update-in [:content :github :commit-errors] (fnil dissoc {}) sha))
       :dispatch [:http/get {:uri (str "/api/github/commits/" sha)
                            :url-params {:user user, :repo repo, :page 1}}
                  [:github/save-commit-page user repo (get-in db [:state :github :generation]) sha 1]
                  [:github/request-failed user repo (get-in db [:state :github :generation]) :commit sha]]})))

(defn save-commit-effects [db user repo generation sha page data]
  (when (and (current-request? db user repo generation)
             (contains? (get-in db [:state :github :commits-pending]) sha)
             (= page (get-in db [:state :github :commit-pages-pending sha] 1)))
    (if (and (map? data) (string? (:sha data))
             (string/starts-with? (string/lower-case (:sha data)) (string/lower-case sha)))
      (let [more? (and (= 100 (count (:files data))) (< page 30))
            commit (if (= page 1)
                     data
                     (update data :files #(vec (concat (get-in db [:content :github :commit sha :files]) %))))
            commit' (assoc commit :files-complete? (not more?))
            db' (-> db
                    (assoc-in [:content :github :commit sha] commit')
                    (assoc-in [:content :github :commit (:sha data)] commit')
                    (update-in [:content :github :commits]
                               (fn [commits]
                                 (if (model/matches? data (get-in db [:state :github :query]))
                                   (model/merge-commits commits [(model/normalize-commit data)])
                                   commits))))]
        (if more?
          {:db (assoc-in db' [:state :github :commit-pages-pending sha] (inc page))
           :dispatch [:http/get {:uri (str "/api/github/commits/" sha)
                                :url-params {:user user, :repo repo, :page (inc page)}}
                      [:github/save-commit-page user repo generation sha (inc page)]
                      [:github/request-failed user repo generation :commit sha]]}
          {:db (-> db'
                   (update-in [:state :github :commits-pending] disj sha)
                   (update-in [:state :github :commit-pages-pending] dissoc sha)
                   (update-in [:state :github :commits-fetched]
                              #(vec (distinct (conj % sha (:sha data))))))}))
      {:dispatch [:github/request-failed user repo generation :commit sha
                  {:status 502, :status-text "GitHub returned an invalid commit"}]})))

(r/reg-event-fx :github/save-commit
  (fn [{:keys [db]} [_ user repo generation sha data]]
    (save-commit-effects db user repo generation sha 1 data)))

(r/reg-event-fx :github/save-commit-page
  (fn [{:keys [db]} [_ user repo generation sha page data]]
    (save-commit-effects db user repo generation sha page data)))

(r/reg-event-fx :github/request-failed
  (fn [{:keys [db]} [_ user repo generation kind id error]]
    (when (current-request? db user repo generation)
      {:db (cond-> (assoc-in db [:content :github :errors] error)
             (= kind :page) (update-in [:state :github :pages-pending] disj id)
             (= kind :commit) (update-in [:state :github :commits-pending] disj id)
             (= kind :commit) (update-in [:state :github :commit-pages-pending] dissoc id)
             (= kind :commit) (assoc-in [:content :github :commit-errors id] error))
       :dispatch [:default-http-error error]})))
