(ns tolgraven.chat.events
  (:require
   [re-frame.core :as rf]
   [clojure.string :as string]))

(rf/reg-event-fx :chat/post [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ id]]
    (let [text (get-in db [:state :form-field :chat])]
      (when (and (not (string/blank? text))
                 (not (get-in db [:state :supabase-writes :chat])))
        (if (= :supabase (get-in db [:options :store :provider]))
          {:db (assoc-in db [:state :supabase-writes :chat] true)
           :supabase/request {:method :post :uri "/api/supabase/chat" :data {:text text}
                              :on-success [:chat/posted text]
                              :on-error [:chat/post-failed]}}
          (let [id (inc (or id 0))]
            {:db (update-in db [:state :form-field] dissoc :chat)
             :dispatch [:store-> [:chat :messages]
                        {id {:time now :text text :user (get-in db [:state :user] "anon")}}
                        [id]]}))))))

(rf/reg-event-fx :chat/posted
  (fn [{:keys [db]} [_ submitted _]]
    {:db (cond-> (update-in db [:state :supabase-writes] dissoc :chat)
           (= submitted (get-in db [:state :form-field :chat]))
           (update-in [:state :form-field] dissoc :chat))
     :dispatch [:supabase/write-complete]}))

(rf/reg-event-fx :chat/post-failed
  (fn [{:keys [db]} [_ error]]
    {:db (update-in db [:state :supabase-writes] dissoc :chat)
     :dispatch [:supabase/write-error error]}))

(rf/reg-event-fx :chat/set-visible
  (fn [{:keys [db]} [_ visible?]]
    {:db (assoc-in db [:state :chat :visible] visible?)}))
