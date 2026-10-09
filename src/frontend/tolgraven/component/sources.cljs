(ns tolgraven.component.sources
  "Adapters use existing application clients; credentials stay in those clients."
  (:require [ajax.core :as ajax]
            [goog.object :as gobj]
            [reagent.ratom :as ratom]
            [tolgraven.component.data :as data]
            [tolgraven.component.storage :as storage]
            [tolgraven.react :as rf]
            [tolgraven.content.client :as content]
            [tolgraven.content.contract :as contract]
            [tolgraven.supabase.client :as supabase]))

(rf/reg-sub :component-data/path
  (fn [db query]
    (let [[_ path] (or (:re-frame/query-v query) query)
          value (get-in db path ::missing)]
      {:ready? (not= ::missing value) :value value})))
(rf/reg-sub :component-data/content
  (fn [db query]
    (let [[_ keys] (or (:re-frame/query-v query) query)]
      {:ready? (every? #(contains? (:content db) %) keys)
       :value (select-keys (:content db) keys)})))
(rf/reg-sub :component-data/supabase-cache
  (fn [_ q] (supabase/cached-query (second (or (:re-frame/query-v q) q)))))

(defn- db-value [path]
  @(rf/sub [:component-data/path path]))

(data/register-source!
 :app-db
 {:restore! (fn [{:keys [path]} value] (rf/dispatch-sync [:component-data/install path value]))
  :read (fn [{:keys [path]}] (db-value path))
  :load! (fn [{:keys [path load timeout-ms]}]
           (-> (if load (data/ensure! load) (js/Promise.resolve nil))
               (.then (fn [_]
                        (let [*value (ratom/make-reaction #(db-value path) :auto-run true)]
                          (-> (data/wait-for! *value #(deref *value) (or timeout-ms 15000))
                              (.finally #(ratom/dispose! *value))))))))})

(data/register-source!
 :strapi
 {:restore! (fn [{:keys [keys]} value]
              (let [bundle {:version contract/version :content value}]
                (when-not (content/valid-bundle? bundle keys) (throw (js/Error. "Invalid cached content")))
                (rf/dispatch-sync [:content/install bundle])))
  :read (fn [{:keys [keys]}]
          @(rf/sub [:component-data/content keys]))
  :load! (fn [{:keys [keys]}]
           (-> (content/ensure! keys)
               (.then (fn [_] (:value @(rf/sub [:component-data/content keys]))))))})

(data/register-source!
 :url
 {:load! (fn [{:keys [url format timeout-ms]}]
           (js/Promise.
            (fn [resolve reject]
              (ajax/GET url {:timeout (or timeout-ms 15000)
                             :response-format (if (= :text format) (ajax/text-response-format)
                                                 (ajax/json-response-format {:keywords? true}))
                             :handler resolve
                             :error-handler (fn [_] (reject (js/Error. "URL dependency unavailable")))}))))})

(defonce *auth-generation (atom 0))
(defn- invalidate-auth-data! [& _]
  (swap! *auth-generation inc)
  ;; URL endpoints can be cookie-authenticated too. Never reuse their cached
  ;; response across an account/client change or install a late old response.
  (data/invalidate! #(#{:supabase :url} (:source %))))
(add-watch supabase/*client ::client
           (fn [_ _ old new] (when (and old (not (identical? old new))) (invalidate-auth-data!))))
(defn- session-id [session]
  [(gobj/getValueByKeys session "user" "id")
   (gobj/getValueByKeys session "user" "app_metadata" "site_user_id")])
(add-watch supabase/*session ::session
           (fn [_ _ old new]
             (when (not= (session-id old) (session-id new)) (invalidate-auth-data!))
             (storage/identity! (when new (session-id new)))))

(data/register-source!
 :supabase
 {:scope #(deref *auth-generation)
  :read (fn [{:keys [query]}] @(rf/sub [:component-data/supabase-cache query]))
  :load! (fn [{:keys [query timeout-ms]}]
           (let [generation @*auth-generation]
             (-> (data/wait-for! supabase/*client
                                #(hash-map :ready? (some? @supabase/*client) :value @supabase/*client)
                                (or timeout-ms 15000))
                 (.then (fn [_]
                          (when-not (= generation @*auth-generation)
                            (throw (js/Error. "Supabase session changed")))
                          (supabase/preload-query! query))))))})

;; Promise interop ends at this adapter. Page declarations describe subscriptions,
;; not transport requests. Dispose only our reaction, never a shared subscription.
(data/register-source!
 :subscription
 {:load! (fn [{:keys [query timeout-ms]}]
           (let [*value (ratom/make-reaction #(deref (rf/subscribe query)) :auto-run true)]
             (-> (data/wait-for! *value
                                #(let [value @*value] {:ready? (true? value) :value value})
                                (or timeout-ms 15000))
                 (.finally #(ratom/dispose! *value)))))})
