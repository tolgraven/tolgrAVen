(ns tolgraven.modules.instagram.events
  (:require [tolgraven.react :as r]))

(r/reg-event-fx :instagram/init
  (fn [_ _]
    {:dispatch [:http/get {:uri "/api/integrations/instagram"}
                [:instagram/store-posts] [:instagram/error]]}))

(r/reg-event-db :instagram/store-posts
  (fn [db [_ data]] (assoc-in db [:content :instagram :posts] (:posts data))))

(r/reg-event-fx :instagram/error
  (fn [_ [_ error]] {:dispatch [:content [:instagram :error] error]}))
