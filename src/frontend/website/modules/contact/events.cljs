(ns website.modules.contact.events
  (:require [website.react :as r]
            [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG r/debug))

(r/reg-event-fx :contact/send-request
 (fn [{:keys [db]} [_ _]]
   (let [{:keys [name email title message]} (get-in db [:state :form-field :contact])]
     {:dispatch-n [[:http/post {:uri "/api/send-contact-email"
                                :params {:name name :email email :title title :message message}
                                :response-format (ajax/json-response-format {:keywords? true})}
                    [:contact/request-sent]]]})))

(r/reg-event-fx :contact/request-sent [debug]
  (fn [{:keys [db]} [_ response]]
    {:db (-> db
             (assoc-in [:state :contact-form :sent?] true)
             (assoc-in [:state :contact-form :response] response))
     :dispatch-n [[:form-field [:contact-form :message] nil]
                  [:form-field [:contact-form :name] nil]
                  [:form-field [:contact-form :title] nil]
                  [:form-field [:contact-form :email] nil]]}))

(r/reg-event-db :contact/open
  (fn [db _]
    (update-in db [:state :contact-form] merge {:show? true, :sent? false})))

(r/reg-event-db :contact/close
  (fn [db _]
    (assoc-in db [:state :contact-form :show?] false)))
