(ns tolgraven.chat.events
  (:require
   [re-frame.core :as rf]))

(rf/reg-event-fx :chat/post [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ id]]
    (let [text (get-in db [:state :form-field :chat])
          id (inc id)]
      {:db (update-in db [:state :form-field] dissoc :chat)
       :dispatch [:store-> [:chat :messages]
                  {id {:time now
                       :text text
                       :user (get-in db [:state :user] "anon")} }
                  [id]]})))

(rf/reg-event-fx :chat/set-visible
  (fn [{:keys [db]} [_ visible?]]
    {:db (assoc-in db [:state :chat :visible] visible?)}))
