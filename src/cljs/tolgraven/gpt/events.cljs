(ns tolgraven.gpt.events
  (:require
   [re-frame.core :as rf]
    [tolgraven.interceptors :as inter :refer [debug]]
   [tolgraven.util :as util]))

(rf/reg-event-fx :gpt/single [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ id]]
    (let [text (get-in db [:state :form-field :gpt])
          id (inc id)]
      {:db (update-in db [:state :form-field] dissoc :gpt)
       :dispatch-n
       [[:store-> [:gpt :messages]
         {id
          {:time now
           :prompt text
           :user (get-in db [:state :user] "anon")} }
         [id]]
        [:http/post {:uri "/api/gpt"
                     :params {:prompt text}}
         [:gpt/on-response id]]]})))

(rf/reg-event-fx :gpt/on-response-single
  (fn [{:keys [db]} [_ id response]]
    (let [text (-> response :choices first :message :content)]
      {:dispatch-n
       [[:store-> [:gpt :messages]
         {id {:response text}}
         [:response]] ]})))


;; fetch a convo to initiate
;; then can use post to continue it
;; at some point should ask gpt to summarize conversation so it can keep going at 4k window
;; probably assign a weight on how early and how much
(rf/reg-event-fx :gpt/new-thread [(rf/inject-cofx :now)
                                  debug]
  (fn [{:keys [db now]} [_ ids id]]
    {:dispatch-n
     [[:store-> [:gpt-threads (str id)]
       {:time now
        :user (get-in db [:state :user] "anon")}
       [(str id)]]]}))

(rf/reg-event-fx :gpt/post-in-thread [(rf/inject-cofx :now)
                                      debug]
  (fn [{:keys [db now]} [_ id history]]
    (let [text (get-in db [:state :form-field :gpt-thread id])]
      {:db (update-in db [:state :form-field :gpt-thread] dissoc id)
       :dispatch-n
       [[:store-> [:gpt-threads (str id)]
         {:time now
          :messages (conj history text)
          :user (get-in db [:state :user] "anon")}
         [:messages]]
        [:http/post {:uri "/api/gpt"
                     :params {:messages (conj history text)}
                     :timeout 60000}
         [:gpt/on-response-thread id (conj history text)]]]})))

(rf/reg-event-fx :gpt/on-response-thread [debug]
  (fn [{:keys [db]} [_ id history response]]
    (let [message (-> response :choices first :message :content)]
      {:dispatch-n
       [[:store-> [:gpt-threads (str id)]
         {:messages (conj (vec history) message)}
         [:messages]] ]})))
