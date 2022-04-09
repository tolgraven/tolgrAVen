(ns tolgraven.instagram.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [tolgraven.util :as util]
    [tolgraven.cofx :as cofx]
    [tolgraven.interceptors :as inter]
    [clojure.string :as string]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :instagram/init
 (fn [{:keys [db]} [_ _]]
   {:dispatch
    [:<-store [:instagram] [:instagram/store-client]]}))

(rf/reg-event-fx :instagram/store-client [debug]
 (fn [{:keys [db]} [_ data]]
   (let [data (util/normalize-firestore-general data)]
     {:db (-> db (assoc-in [:instagram] data)
                 (assoc-in [:content :instagram :posts] (:posts data)))
      :dispatch
      [:http/get {:uri "https://graph.instagram.com/me/media"
                  :url-params {:access_token (get-in data [:auth :access_token])
                               :fields "id"}
                  :response-format (ajax/json-response-format {:keywords? true})}
       [:instagram/fetch]
       [:instagram/error]]})))

(rf/reg-event-fx :instagram/error
 (fn [{:keys [db]} [_ error]]
   (let [event (case error
                 "expired-token-thingy" [:instagram/try-authorize]
                 [:content [:instagram :error] error])]
     {:dispatch event})))

(rf/reg-event-fx :instagram/get
 (fn [{:keys [db]} [_ id]]
   (let [uri "https://graph.instagram.com/"
         fields "caption,id,media_type,media_url,username,timestamp" ; want likes n comments tho...
         token (get-in db [:instagram :auth :access_token])]
     {:dispatch
      [:http/get {:uri (str uri id)
                  :url-params {:fields fields :access_token token} }
       [:instagram/store-post id]
       [:content [:instagram :error id]]] })))

(rf/reg-event-fx :instagram/store-post
 (fn [{:keys [db]} [_ id post]]
   {:db (assoc-in db [:content :instagram :posts (keyword id)] post)
    :dispatch [:store-> [:instagram :posts] {id post} [:posts]]}))

(rf/reg-event-fx :instagram/fetch [debug]
  (fn [{:keys [db]} [_ data]]
    (let [new-ids (into (sorted-set) (map :id (:data data)))
          old-ids (into (sorted-set) (get-in db [:instagram :ids :ids]))
          match? (= new-ids old-ids)]
      (when-not match?
       {:db (-> db (assoc-in [:instagram :ids :ids] new-ids)
                   (assoc-in [:instagram :data] (get-in data [:paging])))
        :dispatch-n [[:store-> [:instagram :ids] {:ids new-ids}]
                     [:instagram/fetch-from-insta new-ids]] })))) ;fetch all since url signatures expire after a while...


(rf/reg-event-fx :instagram/fetch-from-insta [debug]
  (fn [{:keys [db]} [_ ids]]
    {:dispatch-n
      (mapv (fn [id] [:instagram/get id]) ids)}))


(rf/reg-event-fx :instagram/refresh-token ;needs refreshing every 60 days so fix dis sometime
 (fn [{:keys [db]} [_ token]]
   {:dispatch [:http/get {:uri "https://graph.instagram.com/refresh_access_token"
                          :url-params {:grant_type "ig_refresh_token" :access_token token} }
               [:instagram/store-token]
               [:content [:instagram :error :token]]] }))
