(ns tolgraven.modules.github.events
  (:require
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.modules.github.message :as message]
    [clojure.string :as string]))

(def url "https://api.github.com/repos/")

(rf/reg-event-fx :github/init
 (fn [{:keys [db]} [_ user repo]]
   (let [user "tolgraven"
         repo "tolgraven"] ; TODO should be injected from options
   (when-not (get-in db [:state :github :pages-fetched])
     {:dispatch-n [[:github/fetch-commits user repo 1]
                   [:github/fetch-commit-count user repo]]}))))

(rf/reg-event-fx :github/fetch-commits
 (fn [{:keys [db]} [_ user repo page]]
   (when (and page
              (not (some #{page} (get-in db [:state :github :pages-fetched]))))
     {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits")
                               :headers {"Accept" "application/vnd.github.v3+json"}
                               :params {:page page}}
                    [:github/save-commits page]]]})))

(rf/reg-event-fx :github/save-commits
 (fn [{:keys [db]} [_ page data]]
   (let [commits (for [{:keys [commit author html_url sha] :as item} data
                       :let [ts (get-in commit [:author :date])
                             [date clock] (string/split ts "T")
                             message (message/parse-message (:message commit))]]
                   (assoc item
                          :date date :clock clock :ts ts
                          :message message
                          :sha7 (apply str (take 7 (seq sha)))))]
     {:db (-> db
              (update-in [:content :github :repo] concat data)
              (update-in [:content :github :commits] concat commits)
              (update-in [:state :github :pages-fetched] conj page))})))

(rf/reg-event-fx :github/fetch-commits-next
 (fn [{:keys [db]} [_ user repo]]
   (when-let [page (some->> (get-in db [:state :github :pages-fetched])
                            (apply max)
                            inc)]
     {:dispatch [:github/fetch-commits user repo page]})))

(rf/reg-event-fx :github/fetch-commit-count
 (fn [{:keys [db]} [_ user repo]]
   (when-not (get-in db [:content :github :repo-headers])
     {:dispatch [:http/get {:uri (str url user "/" repo "/commits")
                            :url-params {:per_page 1}
                            :response-format
                            {:read (fn [^js response]
                                     (js->clj (.getResponseHeaders response)
                                              :keywordize-keys true))
                             :description "headers"}
                            :headers {"Accept" "application/vnd.github.v3+json"}}
                 [:content [:github :repo-headers]] ]})))


(rf/reg-event-fx :github/fetch-commit
 (fn [{:keys [db]} [_ user repo sha]]
   (when (and sha
              (not (some #{sha} (get-in db [:state :github :commits-fetched]))))
     {:dispatch-n [[:http/get {:uri (str url user "/" repo "/commits/" sha)
                               :headers {"Accept" "application/vnd.github.v3+json"}}
                    [:github/save-commit sha]
                    [:content [:github :errors]]]]})))

(rf/reg-event-fx :github/save-commit
 (fn [{:keys [db]} [_ sha data]]
   {:db (-> db
            (assoc-in [:content :github :commit sha] data)
            (update-in [:state :github :commits-fetched] conj sha))}))
