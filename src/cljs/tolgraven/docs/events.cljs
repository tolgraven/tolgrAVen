(ns tolgraven.docs.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    [clojure.walk :as walk]
    [ajax.core :as ajax]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

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

(rf/reg-event-fx :docs/get
  (fn [{:keys [db]} [_ page]]
    (when-not (get-in db [:docs page])
      {:dispatch [:http/get-internal
                  {:uri (str "/api/doc?path=" page)
                   :response-format (ajax/raw-response-format)}
                  [:docs/store-page page]]})))

(rf/reg-event-fx :docs/store-page
  (fn [{:keys [db]} [_ page data]]
    {:db (assoc-in db [:docs page] data)}))


