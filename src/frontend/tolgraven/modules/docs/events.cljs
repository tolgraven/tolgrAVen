(ns tolgraven.modules.docs.events
  (:require [tolgraven.react :as rf]
            [tolgraven.modules.docs.pages :as pages]
            [tolgraven.component.data :as data]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :docs/init
 (fn [{:keys [db]} [_ ]]
   (let [page (get-in db [:state :docs :current-page])] ;this shouldn't be needed, but early get from controller somehow doesn't ever continue its chain
     (when-not (get-in db [:docs])
       {:dispatch-n
        [[:docs/get (or page "index")]
         [:docs/set-page (or page "index")]]}))))


(rf/reg-event-fx :docs/state
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :docs] path) value)}))

(rf/reg-event-fx :docs/set-page
  (fn [{:keys [db]} [_ page]]
    (let [old-page (get-in db [:state :docs :current-page])]
      {:db (-> db
               (assoc-in [:state :docs :current-page] page)
               (assoc-in [:state :docs :previous-page] old-page))})))

(rf/reg-fx :docs/load
  (fn [page]
    (data/prefetch! [(pages/document-dependency page)])))

(rf/reg-event-fx :docs/get
  (fn [_ [_ page]]
    {:docs/load page}))
