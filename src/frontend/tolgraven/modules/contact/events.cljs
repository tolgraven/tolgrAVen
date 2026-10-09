(ns tolgraven.modules.contact.events
  (:require [tolgraven.react :as rf]
            [ajax.core :as ajax]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :contact/send-request
 (fn [{:keys [db]} [_ _]]
   (let [{:keys [name email title message]} (get-in db [:state :form-field :contact])]
     {:dispatch-n [[:http/post {:uri "/api/send-contact-email"
                                :params {:name name :email email :title title :message message}
                                :response-format (ajax/json-response-format {:keywords? true})}
                    [:contact/request-sent]]]})))

(rf/reg-event-fx :contact/request-sent [debug]
  (fn [{:keys [db]} [_ response]]
    {:db (-> db
             (assoc-in [:state :contact-form :sent?] true)
             (assoc-in [:state :contact-form :response] response))
     :dispatch-n [[:form-field [:contact-form :message] nil]
                  [:form-field [:contact-form :name] nil]
                  [:form-field [:contact-form :title] nil]
                  [:form-field [:contact-form :email] nil]]}))

(rf/reg-event-fx :contact/open
  (fn [{:keys [db]} [_]]
    {:db (assoc-in db [:state :contact-form :show?] true)}))

(rf/reg-event-fx :contact/close
  (fn [{:keys [db]} [_ force?]]
    (if force?
      {:db (update-in db [:state :contact-form] merge
                      {:show? false :sent? false :closing? false})}
      {:db (assoc-in db [:state :contact-form :closing?] true)
       :dispatch-later {:ms 1000
                        :dispatch [:contact/close :force]}})))
