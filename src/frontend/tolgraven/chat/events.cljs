(ns tolgraven.chat.events
  (:require
   [tolgraven.react :as rf]
   [clojure.string :as string]))

(rf/reg-event-fx :chat/post
  (fn [{:keys [db]} _]
    (let [text (get-in db [:state :form-field :chat])]
      (when (and (not (string/blank? text)) (not (get-in db [:state :supabase-writes :chat])))
        {:db (assoc-in db [:state :supabase-writes :chat] true)
         :supabase/request {:method :post :uri "/api/supabase/chat" :data {:text text}
                            :on-success [:chat/posted text] :on-error [:chat/post-failed]}}))))

(rf/reg-event-fx :chat/posted
  (fn [{:keys [db]} [_ submitted _]]
    {:db (cond-> (update-in db [:state :supabase-writes] dissoc :chat)
           (= submitted (get-in db [:state :form-field :chat]))
           (update-in [:state :form-field] dissoc :chat))}))

(rf/reg-event-fx :chat/post-failed
  (fn [{:keys [db]} [_ error]]
    {:db (update-in db [:state :supabase-writes] dissoc :chat)
     :dispatch [:supabase/write-error error]}))

(rf/reg-event-fx :chat/set-visible
  (fn [{:keys [db]} [_ visible?]]
    {:db (assoc-in db [:state :chat :visible] visible?)}))
