(ns website.modules.github.events
  (:require [website.react :as r]
            [website.modules.github.model :as model]
            [website.modules.github.routes :as routes]
            [clojure.string :as string]))

(def url "https://api.github.com/repos/")

(r/reg-event-fx :github/navigate-commit
  {:args [:tuple routes/commit-sha]}
  (fn [{:keys [db]} [_ sha]]
    (when (= (string/lower-case sha) (:sha (model/route-target (:common/route db))))
      {:dispatch [:github/init]})))

(r/reg-event-fx :github/search
  {:args [:tuple [:string {:max 200}]]}
  (fn [{:keys [db]} [_ query]]
    (let [[user repo] (get-in db [:state :github :target])
          query' (string/lower-case (string/trim query))]
      (cond-> {:db (assoc-in db [:state :form-field :github :search] query)}
        (and user repo (re-matches #"[a-f0-9]{7,40}" query'))
        (assoc :dispatch [:github/fetch-commit user repo query'])))))

(defn current-request? [db user repo generation]
  (let [state (get-in db [:state :github])]
    (and (= [user repo] (:target state))
         (= generation (:generation state)))))

(r/reg-event-fx :github/init
 (fn [{:keys [db]} _]
   (let [{:keys [user repo]} (get-in db [:content :github-settings])
         target [user repo]
         state (get-in db [:state :github])
         focus (model/route-target (:common/route db))
         changed? (not= target (:target state))
         generation (if changed? (inc (or (:generation state) 0)) (or (:generation state) 0))]
     (when (and (seq user) (seq repo))
       {:db (cond-> (assoc-in db [:state :github :generation] generation)
                     changed? (assoc-in [:state :github] {:target target, :generation generation})
                     changed? (assoc-in [:content :github] {})
                     focus (assoc-in [:state :form-field :github :search] (:sha focus)))
                :dispatch-n [(if focus
                               [:github/fetch-commit user repo (:sha focus)]
                               [:github/fetch-commits user repo 1 generation])
                            [:github/fetch-commit-count user repo generation]]}))))

(r/reg-event-fx :github/fetch-commits
 (fn [{:keys [db]} [_ user repo page generation]]
   (let [state (get-in db [:state :github])
         generation' (or generation (:generation state))]
     (when (and (current-request? db user repo generation')
                page
                (not (some #{page} (:pages-fetched state)))
                (not (contains? (:pages-pending state) page)))
       {:db (update-in db [:state :github :pages-pending] (fnil conj #{}) page)
        :dispatch-n [[:http/get {:uri (str url user "/" repo "/commits")
                               :headers {"Accept" "application/vnd.github.v3+json"}
                               :url-params {:page page}}
                      [:github/save-commits user repo generation' page]
                      [:github/request-failed user repo generation' :page page]]]}))))

(r/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ user repo generation page data]]
   (when (and (current-request? db user repo generation)
              (contains? (get-in db [:state :github :pages-pending]) page))
     (let [commits (mapv model/normalize-commit data)]
       {:db (-> db
                (update-in [:content :github :repo] model/merge-commits data)
                (update-in [:content :github :commits] model/merge-commits commits)
                (update-in [:state :github :pages-fetched] conj page)
                (update-in [:state :github :pages-pending] disj page))}))))

(r/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (let [page (inc (reduce max 0 (get-in db [:state :github :pages-fetched])))]
     {:dispatch [:github/fetch-commits user repo page]})))

(r/reg-event-fx :github/fetch-commit-count
 (fn [{:keys [db]} [_ user repo generation]]
   (let [state (get-in db [:state :github])
         generation' (or generation (:generation state))]
     (when (and (current-request? db user repo generation')
                (not (:headers-pending? state))
                (not (get-in db [:content :github :repo-headers])))
       {:db (assoc-in db [:state :github :headers-pending?] true)
        :dispatch [:http/get {:uri (str url user "/" repo "/commits")
                            :url-params {:per_page 1}
                            :response-format
                            {:read (fn [^js response]
                                     (js->clj (.getResponseHeaders response)
                                              :keywordize-keys true))
                             :description "headers"}
                            :headers {"Accept" "application/vnd.github.v3+json"}}
                   [:github/save-headers user repo generation']
                   [:github/request-failed user repo generation' :headers nil]]}))))

(r/reg-event-fx :github/fetch-commit
 (fn [{:keys [db]} [_ user repo sha]]
   (when (and (= [user repo] (get-in db [:state :github :target]))
              sha
              (not (contains? (get-in db [:state :github :commits-pending]) sha))
              (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
     {:db (-> db
              (update-in [:state :github :commits-pending] (fnil conj #{}) sha)
              (update-in [:content :github :commit-errors] (fnil dissoc {}) sha))
      :dispatch-n [[:http/get {:uri (str url user "/" repo "/commits/" sha)
                               :headers {"Accept" "application/vnd.github.v3+json"}}
                    [:github/save-commit user repo (get-in db [:state :github :generation]) sha]
                    [:github/request-failed user repo (get-in db [:state :github :generation]) :commit sha]]]})))

(r/reg-event-fx :github/save-commit
 (fn [{:keys [db]} [_ user repo generation sha data]]
   (when (and (current-request? db user repo generation)
              (contains? (get-in db [:state :github :commits-pending]) sha))
     (if (and (map? data) (string? (:sha data))
              (string/starts-with? (string/lower-case (:sha data)) (string/lower-case sha)))
       {:db (-> db
              (assoc-in [:content :github :commit sha] data)
              (assoc-in [:content :github :commit (:sha data)] data)
              (update-in [:content :github :commits] model/merge-commits [(model/normalize-commit data)])
              (update-in [:state :github :commits-pending] disj sha)
              (update-in [:state :github :commits-fetched]
                         #(vec (distinct (conj % sha (:sha data))))))}
       {:dispatch [:github/request-failed user repo generation :commit sha
                   {:status 502, :status-text "GitHub returned an invalid commit"}]}))))

(r/reg-event-db :github/save-headers
  (fn [db [_ user repo generation headers]]
    (if (current-request? db user repo generation)
      (-> db
          (assoc-in [:content :github :repo-headers] headers)
          (assoc-in [:state :github :headers-pending?] false))
      db)))

(r/reg-event-fx :github/request-failed
  (fn [{:keys [db]} [_ user repo generation kind page error]]
    (when (current-request? db user repo generation)
      {:db (cond-> (assoc-in db [:content :github :errors] error)
             (= kind :page) (update-in [:state :github :pages-pending] disj page)
             (= kind :headers) (assoc-in [:state :github :headers-pending?] false)
             (= kind :commit) (update-in [:state :github :commits-pending] disj page)
             (= kind :commit) (assoc-in [:content :github :commit-errors page] error))
       :dispatch [:default-http-error error]})))
