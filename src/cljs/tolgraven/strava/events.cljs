(ns tolgraven.strava.events
  (:require
    [clojure.walk :as walk]
    [re-frame.core :as rf]
    [cljs-time.core :as ct]
    [cljs-time.format :as ctf]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :strava/init
  (fn [_ _]
    {:dispatch [:http/get {:uri "/api/integrations/settings"}
                [:strava/store-client] [:strava/on-error]]}))

(rf/reg-event-fx :strava/store-client
  (fn [{:keys [db]} [_ data]]
    {:db (assoc db :strava {:auth (:strava data)}) :dispatch [:strava/fetch]}))

(rf/reg-event-fx :strava/state
  (fn [{:keys [db]} [_ path value]]
    {:db (assoc-in db (into [:state :strava] path) value)}))

(rf/reg-event-fx :strava/save
  (fn [{:keys [db]} [_ path content]]
    {:db (assoc-in db (into [:content :strava] path) content)}))

(rf/reg-event-fx :strava/get
  (fn [{:keys [db]} [_ path save-to]]
    {:dispatch [:strava/get-and-dispatch
                path
                [:strava/save save-to] ]}))

(rf/reg-event-fx :strava/get-and-dispatch
  (fn [_ [_ path event]]
    {:dispatch [:http/get {:uri "/api/integrations/strava" :url-params {:path path}}
                event [:strava/on-error]]}))

(rf/reg-event-fx :strava/on-error ;TODO parse error and refresh token if that's the issue
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
        [:starred]]
       [:intervals/fetch-summary]]}))

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
  (fn [_ [_ path event]]
    {:dispatch [:http/get {:uri "/api/integrations/intervals" :url-params {:path path}}
                event [:strava/on-error]]}))

(rf/reg-event-fx :intervals/fetch-summary
  (fn [{:keys [db]} [_ ]]
    (let [start (ctf/unparse {:format-str "yyyy-MM-dd"}
                             (ct/minus (ct/today) (ct/months 1)))
          end (ctf/unparse {:format-str  "yyyy-MM-dd"}
                             (ct/today))]
      {:dispatch-n
       [[:intervals/get (str "athlete-summary{ext}?start=" start "&end=" end)
         [:summary]]]})))

