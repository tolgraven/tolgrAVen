(ns tolgraven.chat.events
  (:require
   [re-frame.core :as rf]
   [tolgraven.interceptors :as inter]))

(rf/reg-event-fx :chat/post [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ id]]
    (let [text (get-in db [:state :form-field :chat])
          id (inc id)]
      {:db (-> db
               (update-in [:content :chat] conj text)
               (update-in [:state :form-field] dissoc :chat))
       :dispatch [:store-> [:chat :messages]
                  {id {:time now
                       :text text
                       :user (get-in db [:state :user] "anon")} }
                  [id]]})))
