(ns tolgraven.gpt.events
  (:require [tolgraven.react :as rf]
            [clojure.string :as string]))

(rf/reg-event-fx :gpt/new-thread [(rf/inject-cofx :now)]
  (fn [{:keys [now]} _]
    {:supabase/request {:method :post :uri "/api/supabase/documents"
                        :data {:path ["gpt-threads" (str (random-uuid))]
                               :data {:time now :messages []}}
                        :on-success [:gpt/thread-created]
                        :on-error [:supabase/auth-error]}}))

(rf/reg-event-fx :gpt/thread-created (fn [_ _] {}))

(rf/reg-event-fx :gpt/post-in-thread
  (fn [{:keys [db]} [_ id history]]
    (let [text (get-in db [:state :form-field :gpt-thread id])]
      (when (and (not (string/blank? text))
                 (not (get-in db [:state :supabase-writes :gpt id])))
        {:db (assoc-in db [:state :supabase-writes :gpt id] true)
         :supabase/request {:method :post :uri "/api/gpt"
                            :data {:messages (conj (vec history) text)}
                            :on-success [:gpt/on-response-thread id history text]
                            :on-error [:gpt/write-error id]}}))))

(rf/reg-event-fx :gpt/on-response-thread [(rf/inject-cofx :now)]
  (fn [{:keys [now]} [_ id history text response]]
    (if-let [message (get-in response [:choices 0 :message :content])]
      {:supabase/request {:method :post :uri "/api/supabase/documents"
                          :data {:path ["gpt-threads" (str id)]
                                 :data {:time now :messages (conj (vec history) text message)}
                                 :merge-fields [:messages :time]}
                          :on-success [:gpt/thread-saved id text]
                          :on-error [:gpt/write-error id]}}
      {:dispatch [:gpt/write-error id {:message "No response returned"}]})))

(rf/reg-event-db :gpt/thread-saved
  (fn [db [_ id text _]]
    (cond-> (assoc-in db [:state :supabase-writes :gpt id] false)
      (= text (get-in db [:state :form-field :gpt-thread id]))
      (update-in [:state :form-field :gpt-thread] dissoc id))))

(rf/reg-event-fx :gpt/write-error
  (fn [{:keys [db]} [_ id error]]
    {:db (assoc-in db [:state :supabase-writes :gpt id] false)
     :dispatch [:supabase/write-error error]}))
