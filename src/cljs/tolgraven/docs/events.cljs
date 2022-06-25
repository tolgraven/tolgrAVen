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
   (when-not (get-in db [:docs])
    {:dispatch-n
      [[:docs/get "01-intro"]
       [:docs/set-page "01-intro"]]})))


(rf/reg-event-fx :docs/state
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :docs] path) value)}))

(rf/reg-event-fx :docs/set-page
  (fn [{:keys [db]} [_ page]]
    {:db (assoc-in db [:state :docs :current-page] page)}))

(rf/reg-event-fx :docs/get
  (fn [{:keys [db]} [_ page]]
    {:dispatch [:http/get-internal
                {:uri (str "/api/doc?path=" page)
                 :response-format (ajax/raw-response-format)}
                [:docs/store-page page]]}))

(rf/reg-event-fx :docs/store-page
  (fn [{:keys [db]} [_ page data]]
    {:db (assoc-in db [:docs page] data)}))


