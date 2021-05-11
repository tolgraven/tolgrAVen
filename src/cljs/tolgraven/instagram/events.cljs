(ns tolgraven.instagram.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [tolgraven.util :as util]
    [tolgraven.cofx :as cofx]
    [tolgraven.interceptors :as inter]
    [clojure.string :as string]))


(rf/reg-event-fx :instagram/init
 (fn [{:keys [db]} [_ _]]
   {:dispatch
    [:<-store [:instagram] [:instagram/store-client]]}))

(rf/reg-event-fx :instagram/store-client
 (fn [{:keys [db]} [_ data]]
   (let [data (util/normalize-firestore-general data)]
     {:db (assoc-in db [:instagram] data)
      :dispatch
      [:http/get {:uri (str "https://graph.instagram.com/me/media"
                            "?fields=id"
                            "&access_token=" (get-in data [:auth :access_token]))
                  :response-format (ajax/json-response-format {:keywords? true})}
       [:instagram/fetch]] })))

(rf/reg-event-fx :instagram/get
 (fn [{:keys [db]} [_ id]]
   (let [uri "https://graph.instagram.com/"
         fields "id,media_type,media_url,username,timestamp"
         token (get-in db [:instagram :auth :access_token])]
     {:dispatch
      [:http/get {:uri (str uri id "?fields=" fields "&access_token=" token)
                  :response-format (ajax/json-response-format {:keywords? true})}
       [:content [:instagram :posts id]]] })))

(rf/reg-event-fx :instagram/fetch
  (fn [{:keys [db]} [_ id-data]]
    {:dispatch-n
      (mapv (fn [id] [:instagram/get id])
            (map :id (:data id-data)))}))


(rf/reg-event-fx :instagram/refresh-token ;needs refreshing every 60 days so fix dis sometime
 (fn [{:keys [db]} [_ ]]
   {:dispatch "https://graph.instagram.com/refresh_access_token
  ?grant_type=ig_refresh_token
  &access_token={long-lived-access-token}"
    }))
