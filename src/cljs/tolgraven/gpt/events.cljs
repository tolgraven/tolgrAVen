(ns tolgraven.gpt.events
  (:require
   [re-frame.core :as rf]
   [tolgraven.util :as util]))

(rf/reg-event-fx :gpt/post [(rf/inject-cofx :now)]
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

(rf/reg-event-fx :gpt/on-response
  (fn [{:keys [db]} [_ id response]]
    (let [text (-> response :choices first :message :content)]
      {:dispatch-n
       [[:store-> [:gpt :messages]
         {id {:response text}}
         [:response]] ]})))

