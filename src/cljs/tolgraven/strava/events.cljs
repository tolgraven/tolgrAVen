(ns tolgraven.strava.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [tolgraven.util :as util]
    [tolgraven.cofx :as cofx]
    [tolgraven.interceptors :as inter]
    [clojure.string :as string]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))


(rf/reg-event-fx :strava/init ;so, currently makes assumption app is authed and some stuff is in firebase...
  (fn [{:keys [db]} [_ ]]
    {:dispatch
      [:<-store [:strava] [:strava/store-client]]}))

(rf/reg-event-fx :strava/store-client   [(rf/inject-cofx :now)
                                         debug]
  (fn [{:keys [db now]} [_ data]]
    (let [data (util/normalize-firestore-general data)] ; TEMP. FIGURE OUT AND FIX THE WHOLE THING INSTEAD
      {:db (assoc-in db [:strava] data)
       :dispatch-n [[:strava/fetch] ;XXX wont work if need refresh.
                    (when (neg? (- (-> data :auth :expires_at) now)) ;can actually refresh each time also, get same access token back then
                      [:strava/refresh (-> data :auth :refresh_token)])]})))

(rf/reg-event-fx :strava/store-session  [debug]
  (fn [{:keys [db now]} [_ response]]
    {:db (update-in db [:strava :auth] merge response)
     :dispatch-n [[:store-> [:strava :auth]
                   (merge (get-in db [:strava :auth]) response)]
                  [:strava/fetch]]})) ;bit ugly making one doomed request and then yada etc but eh

(rf/reg-event-fx :strava/refresh  [debug]
  (fn [{:keys [db]} [_ refresh-token]]
    (let [info (get-in db [:strava :auth])]
      {:dispatch
       [:http/post {:uri "https://www.strava.com/api/v3/oauth/token"
                    :url-params {:client_id (:client_id info)
                                 :client_secret (:client_secret info)
                                 :grant_type "refresh_token"
                                 :refresh_token refresh-token}}
        [:strava/store-session]]})))


(rf/reg-event-fx :strava/get
  (fn [{:keys [db]} [_ path save-to]]
    {:dispatch [:strava/get-and-dispatch
                path
                [:content (into [:strava] save-to)] ]}))

(rf/reg-event-fx :strava/get-and-dispatch
  (fn [{:keys [db]} [_ path event]]
    (let [uri "https://www.strava.com/api/v3/"]
      {:dispatch [:http/get {:uri (str uri path)
                             :headers {"Authorization" (str "Bearer " (-> db :strava :auth :access_token))}}
                  event
                  [:strava/on-error]]})))

(rf/reg-event-db :strava/on-error
  (fn [db [_ error]]
    (update-in db [:content :strava :error] conj error)))

(rf/reg-event-fx :strava/fetch
  (fn [{:keys [db]} [_ ]]
    {:dispatch-n
      [[:strava/get (str  "athletes/" (-> db :strava :auth :athlete_id) "/stats") 
        [:stats]]
       [:strava/get "athlete"
        [:athlete]]             
       [:strava/get-and-dispatch "athlete/activities"
        [:strava/store-activities]]
       [:strava/get "segments/starred"
        [:starred]]]}))

(rf/reg-event-fx :strava/store-activities
  (fn [{:keys [db]} [_ data]]
    (let [gear (->> data (map :gear_id) set)]
      {:db (assoc-in db [:content :strava :activities] data)
       :dispatch-n (mapv (fn [id] [:strava/fetch-gear id]) gear)})))

(rf/reg-event-fx :strava/fetch-gear ;needs calling after activities using gear in activities, hmm...
  (fn [{:keys [db]} [_ id]]         ;bit redundant since detailed activity includes, hmm
    {:dispatch-n [(when-not (get-in db [:content :strava :gear id])
                    [:strava/get (str "gear/" id)
                     [:gear id]])]}))

(rf/reg-event-fx :strava/fetch-stream
  (fn [{:keys [db]} [_ id data-type]]
    {:dispatch-n [(when-not (get-in db [:content :strava :activity-stream id])
                    [:strava/get (str "activities/" id "/streams" "?keys=" data-type "&key_by_type=")
                     [:activity-stream id]])]}))

(rf/reg-event-fx :strava/fetch-segment-stream
  (fn [{:keys [db]} [_ id data-type]]
    {:dispatch-n [(when-not (get-in db [:content :strava :segment-stream id])
                    [:strava/get (str "segments/" id "/streams" "?keys=" data-type "&key_by_type=")
                     [:segment-stream id]])]}))

(rf/reg-event-fx :strava/fetch-activity
  (fn [{:keys [db]} [_ id ]]
    {:dispatch-n [(when-not (get-in db [:content :strava :activity id])
                    [:strava/get (str "activities/" id  "?include_all_efforts=")
                     [:activity id]])]}))

(rf/reg-event-fx :strava/fetch-kudos
  (fn [{:keys [db]} [_ id ]]
    {:dispatch-n [(when-not (get-in db [:content :strava :kudos id])
                    [:strava/get (str "activities/" id "/kudos")
                     [:kudos id]])]}))
