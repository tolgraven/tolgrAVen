(ns tolgraven.modules.github.events
  (:require
    [tolgraven.react :as rf]
    [tolgraven.modules.github.message :as message]
    [clojure.string :as string]))

(def url "https://api.github.com/repos/")

(rf/reg-event-fx :github/init
 (fn [{:keys [db]} _]
   (let [{:keys [user repo]} (get-in db [:content :github-settings])
         target [user repo]]
     (when (and (seq user) (seq repo)
                (not= target (get-in db [:state :github :target])))
       {:db (-> db
                (assoc-in [:state :github] {:target target})
                (assoc-in [:content :github] {}))
        :dispatch-n [[:github/fetch-commits user repo 1]
                     [:github/fetch-commit-count user repo]]}))))

(rf/reg-event-fx :github/fetch-commits
 (fn [{:keys [db]} [_ user repo page]]
   (when (and (= [user repo] (get-in db [:state :github :target]))
              page
              (not (some #{page} (get-in db [:state :github :pages-fetched]))))
     {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits")
                               :headers {"Accept" "application/vnd.github.v3+json"}
                               :url-params {:page page}}
                    [:github/save-commits user repo page]]]})))

(rf/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ user repo page data]]
   (when (= [user repo] (get-in db [:state :github :target]))
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
                (update-in [:state :github :pages-fetched] conj page))}))))

(rf/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (when-let [page (some->> (get-in db [:state :github :pages-fetched])
                            (apply max)
                            inc)]
     {:dispatch [:github/fetch-commits user repo page]})))

(rf/reg-event-fx :github/fetch-commit-count
 (fn [{:keys [db]} [_ user repo]]
   (when (and (= [user repo] (get-in db [:state :github :target]))
              (not (get-in db [:content :github :repo-headers])))
     {:dispatch [:http/get {:uri (str url user "/" repo "/commits")
                            :url-params {:per_page 1}
                            :response-format
                            {:read (fn [^js response]
                                     (js->clj (.getResponseHeaders response)
                                              :keywordize-keys true))
                             :description "headers"}
                            :headers {"Accept" "application/vnd.github.v3+json"}}
                 [:github/save-headers user repo]]})))


(rf/reg-event-fx :github/fetch-commit
 (fn [{:keys [db]} [_ user repo sha]]
   (when (and (= [user repo] (get-in db [:state :github :target]))
              sha
              (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
     {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits/" sha)
                               :headers {"Accept" "application/vnd.github.v3+json"}}
                    [:github/save-commit user repo sha]
                    [:content [:github :errors]]]]})))

(rf/reg-event-fx :github/save-commit
 (fn [{:keys [db]} [_ user repo sha data]]
   (when (= [user repo] (get-in db [:state :github :target]))
     {:db (-> db
              (assoc-in [:content :github :commit sha] data)
              (update-in [:state :github :commits-fetched] conj sha))})))

(rf/reg-event-db :github/save-headers
  (fn [db [_ user repo headers]]
    (if (= [user repo] (get-in db [:state :github :target]))
      (assoc-in db [:content :github :repo-headers] headers)
      db)))
