(ns tolgraven.strava.events
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [tolgraven.util :as util]
    [tolgraven.cofx :as cofx]
    [tolgraven.interceptors :as inter]
    [clojure.string :as string]
    [clojure.walk :as walk]
    [goog.crypt.base64 :as b64]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))


(rf/reg-event-fx :strava/init ;so, currently makes assumption app is authed and some stuff is in firebase...
  (fn [{:keys [db]} [_ ]]
    (when-not (get-in db [:strava])
      {:dispatch
       [:<-store [:strava] [:strava/store-client]]})))

(rf/reg-event-fx :strava/store-client   [(rf/inject-cofx :now)]
  (fn [{:keys [db now]} [_ data]]
    (let [expired? (neg? (- (-> data :auth :expires_at) (/ now 1000)))]
      {:db (assoc-in db [:strava] data)
       :dispatch-n [(when-not expired?
                      [:strava/fetch]) ;XXX wont work if need refresh.
                    (when expired? ;can actually refresh each time also, get same access token back then
                      [:strava/refresh (-> data :auth :refresh_token)])]})))

(rf/reg-event-fx :strava/store-session
  (fn [{:keys [db now]} [_ response]]
    (let [data (walk/keywordize-keys response)]
      {:db (update-in db [:strava :auth] merge data)
       :dispatch-n [[:store-> [:strava :auth]
                     (merge (get-in db [:strava :auth]) data)]
                    [:strava/fetch]]}))) ;bit ugly making one doomed request and then yada etc but eh

(rf/reg-event-fx :strava/refresh
  (fn [{:keys [db]} [_ refresh-token]]
    (let [info (get-in db [:strava :auth])]
      {:dispatch
       [:http/post {:uri "https://www.strava.com/api/v3/oauth/token"
                    :url-params {:client_id (:client_id info)
                                 :client_secret (:client_secret info)
                                 :grant_type "refresh_token"
                                 :refresh_token refresh-token}}
        [:strava/store-session]]})))


(rf/reg-event-fx :strava/save
  (fn [{:keys [db]} [_ path content]]
    {:db (assoc-in db (into [:content :strava] path) content)}))

(rf/reg-event-fx :strava/get
  (fn [{:keys [db]} [_ path save-to]]
    {:dispatch [:strava/get-and-dispatch
                path
                [:strava/save save-to] ]}))

(rf/reg-event-fx :strava/get-and-dispatch
  (fn [{:keys [db]} [_ path event]]
    (let [uri "https://www.strava.com/api/v3/"]
      {:dispatch [:http/get {:uri (str uri path)
                             :headers {"Authorization" (str "Bearer " (-> db :strava :auth :access_token))}}
                  event
                  [:strava/on-error]]})))

(rf/reg-event-fx :strava/on-error
  (fn [{:keys [db]} [_ error]]
    {:db (update-in db [:content :strava :error] conj error)
     :dispatch [:diag/new :error "Strava error" error]}))

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
    (let [gear (->> data (map :gear_id) set (filter identity))]
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


; OTHER STRAVA EVENTS

(rf/reg-event-fx :strava/activity-expand
  (fn [{:keys [db]} [_ id-or-action]]
    (let [curr-id (get-in db [:state :strava :activity-expanded] -1)
          num-activities (count (get-in db [:content :strava :activities]))
          id (case id-or-action
               :next (cond-> curr-id
                       (< (inc curr-id) num-activities) inc)
               :prev (cond-> curr-id
                       (>= (dec curr-id) 0) dec)
               id-or-action)]
      {:db (assoc-in db [:state :strava :activity-expanded] id)})))


; INTERVALS FETCHES
 
(rf/reg-event-fx :intervals/get
  (fn [{:keys [db]} [_ path save-to]]
    {:dispatch [:intervals/get-and-dispatch
                path
                [:content (into [:intervals] save-to)] ]}))

(rf/reg-event-fx :intervals/get-and-dispatch
  (fn [{:keys [db]} [_ path event]]
    (let [id (get-in db [:strava :auth :intervals_athlete_id])
          uri (str "https://intervals.icu/api/v1/athlete/" id "/")]
      {:dispatch [:http/get {:uri (str uri path)
                             :headers {"Authorization"
                                       (str "Basic "
                                            (b64/encodeString
                                             (str "API_KEY:"
                                                  (-> db :strava :auth :intervals_api_key))))}}
                  event
                  [:strava/on-error]]})))

