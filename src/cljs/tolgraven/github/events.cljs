(ns tolgraven.github.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))


(rf/reg-event-fx :github/init
 (fn [{:keys [db]} [_ user repo]]
   (when-not (get-in db [:state :github :pages-fetched])
     {:dispatch-n [[:github/fetch-commits user repo 1]
                   [:github/fetch-commit-count user repo]]})))

(rf/reg-event-fx :github/fetch-commits
 (fn [{:keys [db]} [_ user repo page]]
   (let [url "https://api.github.com/repos/"]
     (when (and page
                (not (some #{page} (get-in db [:state :github :pages-fetched]))))
       {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits")
                                 :headers {"Accept" "application/vnd.github.v3+json"}
                                 :params {:page page}}
                      [:github/save-commits page]]]}))))

(rf/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ page data]]
   {:db (-> db
            (update-in [:content :github :repo] concat data)
            (update-in [:state :github :pages-fetched] conj page))}))

(rf/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (when-let [page (some->> (get-in db [:state :github :pages-fetched])
                            (apply max)
                            inc)]
     {:dispatch [:github/fetch-commits user repo page]})))

(rf/reg-event-fx :github/fetch-commit-count
 (fn [{:keys [db]} [_ user repo]]
   (let [url "https://api.github.com/repos/"]
     (when-not (get-in db [:content :github :repo-headers])
       {:dispatch [:http/get {:uri (str url user "/" repo "/commits")
                              :url-params {:per_page 1}
                              :response-format {:read #(js->clj (.getResponseHeaders %)
                                                                :keywordize-keys true)
                                                :description "headers"}
                              :headers {"Accept" "application/vnd.github.v3+json"}}
                   [:content [:github :repo-headers]] ]}))))


(rf/reg-event-fx :github/fetch-commit
 (fn [{:keys [db]} [_ user repo sha]]
   (let [url "https://api.github.com/repos/"]
     (when (and sha
                (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
       {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits/" sha)
                                 :headers {"Accept" "application/vnd.github.v3+json"}}
                      [:github/save-commit sha]
                      [:content [:github :errors]]]]}))))

(rf/reg-event-fx :github/save-commit
 (fn [{:keys [db]} [_ sha data]]
   {:db (-> db
            (assoc-in [:content :github :commit sha] data)
            (update-in [:state :github :commits-fetched] conj sha))}))
