(ns tolgraven.modules.github.events
  (:require
    [tolgraven.react :as rf]
    [tolgraven.modules.github.message :as message]
    [clojure.string :as string]))

(def url "https://api.github.com/repos/")

(defn current-request? [db user repo generation]
  (let [state (get-in db [:state :github])]
    (and (= [user repo] (:target state))
         (= generation (:generation state)))))

(rf/reg-event-fx :github/init
 (fn [{:keys [db]} _]
   (let [{:keys [user repo]} (get-in db [:content :github-settings])
         target [user repo]
         state (get-in db [:state :github])
         changed? (not= target (:target state))
         generation (if changed? (inc (or (:generation state) 0)) (:generation state))]
     (when (and (seq user) (seq repo))
       (cond-> {:dispatch-n [[:github/fetch-commits user repo 1 generation]
                            [:github/fetch-commit-count user repo generation]]}
         changed? (assoc :db (-> db
                                (assoc-in [:state :github] {:target target, :generation generation})
                                (assoc-in [:content :github] {}))))))))

(rf/reg-event-fx :github/fetch-commits
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

(rf/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ user repo generation page data]]
   (when (and (current-request? db user repo generation)
              (contains? (get-in db [:state :github :pages-pending]) page))
     (let [commits (for [{:keys [commit sha] :as item} data
                         :let [ts (get-in commit [:author :date])
                               [date clock] (string/split ts "T")
                               parsed (message/parse-message (:message commit))]]
                     (assoc item
                            :date date
                            :clock clock
                            :ts ts
                            :message parsed
                            :sha7 (apply str (take 7 (seq sha)))))]
       {:db (-> db
                (update-in [:content :github :repo] concat data)
                (update-in [:content :github :commits] concat commits)
                (update-in [:state :github :pages-fetched] conj page)
                (update-in [:state :github :pages-pending] disj page))}))))

(rf/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (when-let [page (some->> (get-in db [:state :github :pages-fetched])
                            (apply max)
                            inc)]
     {:dispatch [:github/fetch-commits user repo page]})))

(rf/reg-event-fx :github/fetch-commit-count
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


(rf/reg-event-fx :github/fetch-commit
 (fn [{:keys [db]} [_ user repo sha]]
   (when (and (= [user repo] (get-in db [:state :github :target]))
              sha
              (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
     {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits/" sha)
                               :headers {"Accept" "application/vnd.github.v3+json"}}
                    [:github/save-commit user repo (get-in db [:state :github :generation]) sha]
                    [:github/request-failed user repo (get-in db [:state :github :generation]) :commit sha]]]})))

(rf/reg-event-fx :github/save-commit
 (fn [{:keys [db]} [_ user repo generation sha data]]
   (when (current-request? db user repo generation)
     {:db (-> db
              (assoc-in [:content :github :commit sha] data)
              (update-in [:state :github :commits-fetched] conj sha))})))

(rf/reg-event-db :github/save-headers
  (fn [db [_ user repo generation headers]]
    (if (current-request? db user repo generation)
      (-> db
          (assoc-in [:content :github :repo-headers] headers)
          (assoc-in [:state :github :headers-pending?] false))
      db)))

(rf/reg-event-fx :github/request-failed
  (fn [{:keys [db]} [_ user repo generation kind page error]]
    (when (current-request? db user repo generation)
      {:db (cond-> (assoc-in db [:content :github :errors] error)
             (= kind :page) (update-in [:state :github :pages-pending] disj page)
             (= kind :headers) (assoc-in [:state :github :headers-pending?] false))
       :dispatch [:default-http-error error]})))
