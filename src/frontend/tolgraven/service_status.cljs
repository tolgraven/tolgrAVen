(ns tolgraven.service-status
  (:require
    [tolgraven.react :as r]
    ))

(defonce *failures (r/atom {}))

(defn fail! [id title message retry!]
  ;; One log/notification per outage, not one per automatic retry.
  (when-not (contains? @*failures id)
    (r/dispatch [:diag/new :error title message
                  (cond-> {:custom-id [:service-status id] :sticky? true}
                    retry! (assoc :buttons [{:id :retry :text "Retry"
                                             :action [:service-status/retry id]}]))]))
  (swap! *failures assoc id {:title title :message message :retry! retry!}))

(defn recover! [id]
  (when (contains? @*failures id)
    (swap! *failures dissoc id)
    (r/dispatch [:diag/unhandled :remove [:service-status id]])))

(r/reg-fx :service-status/retry
  (fn [id] (when-let [retry! (get-in @*failures [id :retry!])] (retry!))))
(r/reg-event-fx :service-status/retry
  (fn [_ [_ id]] {:service-status/retry id}))

(defn within! [promise milliseconds]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout #(reject (js/Error. "Service request timed out")) milliseconds)]
       (-> (js/Promise.resolve promise)
           (.then (fn [value] (js/clearTimeout timer) (resolve value)))
           (.catch (fn [error] (js/clearTimeout timer) (reject error))))))))
