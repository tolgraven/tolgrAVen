(ns tolgraven.service-status
  (:require
    [reagent.core :as r]
    [tolgraven.react :as rf]))

(defonce *failures (r/atom {}))

(defn fail! [id title message retry!]
  ;; One log/notification per outage, not one per automatic retry.
  (when-not (contains? @*failures id)
    (rf/dispatch [:diag/new :error title message
                  (cond-> {:custom-id [:service-status id] :sticky? true}
                    retry! (assoc :buttons [{:id :retry :text "Retry"
                                             :action [:service-status/retry id]}]))]))
  (swap! *failures assoc id {:title title :message message :retry! retry!}))

(defn recover! [id]
  (when (contains? @*failures id)
    (swap! *failures dissoc id)
    (rf/dispatch [:diag/unhandled :remove [:service-status id]])))

(rf/reg-fx :service-status/retry
  (fn [id] (when-let [retry! (get-in @*failures [id :retry!])] (retry!))))
(rf/reg-event-fx :service-status/retry
  (fn [_ [_ id]] {:service-status/retry id}))

(defn within! [promise milliseconds]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout #(reject (js/Error. "Service request timed out")) milliseconds)]
       (-> (js/Promise.resolve promise)
           (.then (fn [value] (js/clearTimeout timer) (resolve value)))
           (.catch (fn [error] (js/clearTimeout timer) (reject error))))))))
