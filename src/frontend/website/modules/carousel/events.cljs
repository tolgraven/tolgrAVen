(ns website.modules.carousel.events
  (:require [website.react :as r]))

(r/reg-event-fx :carousel/rotate
  (fn [{:keys [db]} [_ id content direction]]
    (let [curr-idx (get-in db [:state :carousel id :index] 0)]
      {:dispatch [:carousel/set-index id (case direction
                                                  :dec (if (neg? (dec curr-idx))
                                                         (dec (count content))
                                                         (dec curr-idx))
                                                  :inc (if (< (inc curr-idx) (count content))
                                                         (inc curr-idx)
                                                         0))]})))

(r/reg-event-db :carousel/set-direction
  (fn [db [_ id direction-class]]
    (assoc-in db [:state :carousel id :direction] direction-class)))

(r/reg-event-db :carousel/set-index
  (fn [db [_ id idx]]
    (assoc-in db [:state :carousel id :index] idx)))

(r/reg-event-fx :carousel/request-index
  (fn [{:keys [db]} [_ id direction]]
    {:dispatch-later {:ms 500
                      :dispatch [:carousel/set-index id direction]}}))
