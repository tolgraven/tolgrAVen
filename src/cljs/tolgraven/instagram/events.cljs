(ns tolgraven.instagram.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-fx :instagram/init
  (fn [_ _]
    {:dispatch [:http/get {:uri "/api/integrations/instagram"}
                [:instagram/store-posts] [:instagram/error]]}))

(rf/reg-event-db :instagram/store-posts
  (fn [db [_ data]] (assoc-in db [:content :instagram :posts] (:posts data))))

(rf/reg-event-fx :instagram/error
  (fn [_ [_ error]] {:dispatch [:content [:instagram :error] error]}))
