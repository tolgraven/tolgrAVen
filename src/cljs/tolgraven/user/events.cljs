(ns tolgraven.user.events
  (:require
   [re-frame.core :as rf]
   [re-frame.std-interceptors :refer [path]]))


(rf/reg-event-db :user/login-ui-open
 (fn [db [_ open?]]
   (assoc-in db [:state :user :log-in-view] open?)))

(rf/reg-event-db :user/request-login
                 [path [:state :register]]
 (fn [fields [_ info]]
   ; take fields find user check pw...
 ; (fn [{:keys []} [_ info]]

   ; {:dispatch [:http-post ]}))
   {:dispatch [:http-post ]}))

