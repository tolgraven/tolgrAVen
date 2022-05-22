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
   (when-not (get-in db [:instagram :ids])
     {:dispatch
      [:<-store [:instagram] [:instagram/store-client]]})))

(rf/reg-event-fx :instagram/store-client [debug]
 (fn [{:keys [db]} [_ data]]
   {:db (-> db (assoc-in [:instagram] data)
               (assoc-in [:content :instagram :posts] (:posts data)))
      :dispatch
      [:http/get {:uri "https://graph.instagram.com/me/media"
                  :url-params {:access_token (get-in data [:auth :access_token])
                               :fields "id"}
                  :response-format (ajax/json-response-format {:keywords? true})}
       [:instagram/fetch]
       [:instagram/error]]}))

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


(rf/reg-event-fx :instagram/refresh-token [debug] ;needs refreshing every 60 days so fix dis sometime
 (fn [{:keys [db]} [_ token]]
   {:dispatch [:http/get {:uri "https://graph.instagram.com/refresh_access_token"
                          :url-params {:grant_type "ig_refresh_token" :access_token token} }
               [:instagram/store-token]
               [:content [:instagram :error :token]]] }))


(rf/reg-event-fx :instagram/store-token ;needs refreshing every 60 days so fix dis sometime
 (fn [{:keys [db]} [_ data]]
   {:db (assoc-in db [:instagram :auth :access_token] (get-in data [:access_token]))
    :dispatch [:store-> [:instagram :auth :access_token] (get-in data [:access_token])] }))

(rf/reg-event-fx :instagram/try-authorize [debug] ;get token from scratch...
 (fn [{:keys [db]} [_ _]]
   {:dispatch [:<-store [:secrets :instagram]
               [:instagram/new-authorize]]}))

(rf/reg-event-fx :instagram/new-authorize [debug] ;get token from scratch...
 (fn [{:keys [db]} [_ secrets]]
   {:db (assoc db :instagram secrets)
    :dispatch [:redirect-to-uri-well-actually-no-popup-a-link
               {:uri "https://www.instagram.com/oauth/authorize"
                :url-params {:client_id (:client_id secrets)
                             :redirect_uri "https://tolgraven.se"
                             :scope "user_profile,user_media"
                             :response_type "code"}}]}))

; ^ not how it works we need to redirect to that url then redirects back and capture the query
; so would change redir url to like tolgraven.se/redir/instagram and have controller there
; capturing query, stripping #_, sending to next event.

(rf/reg-event-fx :instagram/new-short-token [debug] ;get token from scratch...
 (fn [{:keys [db]} [_ code]]
   (if-let [secrets (get-in db [:instagram :secrets])]
     {:dispatch [:http/post {:uri "https://api.instagram.com/oauth/access_token"
                             :url-params {:client_id (:client_id secrets) ; obviously get these from db or firebase (only if admin) not hardcoded...
                                          :client_secret (:client_secret secrets)
                                          :grant_type "authorization_code"
                                          :redirect_uri (:redirect_uri secrets)
                                          :code code} }
                 [:instagram/new-token]
                 [:content [:instagram :error :short-token]]] }
     [:diag/new :error "Hacking attempt detected" "Go away evildoer"])))

(rf/reg-event-fx :instagram/new-token [debug]
 (fn [{:keys [db]} [_ short-token]]
   (if-let [secrets (get-in db [:instagram :secrets])]
     {:dispatch [:http/get {:uri "https://graph.instagram.com/refresh_access_token"
                            :url-params {:grant_type "ig_exchange_token"
                                         :client_secret (:client_secret secrets)
                                         :access_token short-token}}
                 [:instagram/store-token]
                 [:content [:instagram :error :token]]] })))
