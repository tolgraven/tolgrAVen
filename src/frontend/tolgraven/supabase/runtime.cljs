(ns tolgraven.supabase.runtime
  "Acquire the ordinary Supabase module only when a binding or action needs it."
  (:require-macros [tolgraven.build.site :refer [when-enabled]])
  (:require [tolgraven.loader.code :as code]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.state :as state]
            [tolgraven.component.data :as data]
            [tolgraven.react :as rf]
            [tolgraven.service-status :as status]
            [reagent.ratom :as ratom]
            [shadow.lazy :as lazy]))

(def *client state/*client)
(def *session state/*session)
(defonce *api (ratom/atom nil))
(defonce *loading (atom nil))

(defn ensure-code! []
  (or @*loading
      (if-let [module (get code/modules :supabase)]
        (let [ready? (lazy/ready? module)
              _ (when ready? (reset! *api (:client @module)))
              promise (-> (js/Promise.resolve (if ready? @module (lazy/load module)))
                          (.then (fn [spec]
                                   (status/recover! [:module :supabase])
                                   (reset! *api (:client spec))))
                          (.catch (fn [error]
                                    (reset! *loading nil)
                                    (status/fail! [:module :supabase] "Database code unavailable"
                                                  "The database module could not load. Retry to load it again."
                                                  #(ensure-code!))
                                    (throw error))))]
          (reset! *loading promise)
          promise)
        (js/Promise.reject (js/Error. "Supabase module is disabled")))))

(defn ensure! []
  (-> (ensure-code!)
      (.then (fn [_]
               (if @*client
                 @*client
                 (do (rf/dispatch [:store/init])
                     (data/wait-for! *client #(hash-map :ready? (some? @*client) :value @*client) 15000)))))))

(defn cached-query [query]
  (if-let [api @*api] ((:cached-query api) query) {:ready? false}))

(defn- restored-value [opts]
  (let [store @(rf/sub [:get :store])]
    (if (query/scoped-query? opts)
      (get-in store [:scoped (pr-str (query/normalize-query opts))])
      (query/query-contract (:public store) opts))))

(defn ensure-query! [opts]
  (let [code (ensure-code!)]
    ;; Settings/init failures use the existing retry/status events.
    (-> (ensure!) (.catch (fn [_] nil)))
    (if-let [api @*api]
      ((:ensure-query! api) opts)
      (let [*reader (ratom/atom nil)
            owner (gensym "supabase-reader")
            install! (fn [_ _ _ api]
                       (when (and api (nil? @*reader))
                         (let [reader ((:ensure-query! api) opts)]
                           ;; Own the pooled reader before the outer reaction's
                           ;; next render, including disposal during that gap.
                           (add-watch reader owner (fn [& _]))
                           (reset! *reader reader))))]
        ;; A failed chunk may later load through the module's Retry action.
        ;; Observe API installation so the original mounted binding recovers.
        (add-watch *api owner install!)
        (-> code (.catch (fn [_] nil)))
        (ratom/make-reaction
          #(if-let [reader @*reader] @reader (restored-value opts))
          :on-dispose (fn []
                        (remove-watch *api owner)
                        (when-let [reader @*reader] (remove-watch reader owner))))))))

(defn- invoke! [method args]
  (-> (ensure!) (.then (fn [_] (apply (get @*api method) args)))))

(defn preload-query! [opts] (invoke! :preload-query! [opts]))
(defn init! [& args]
  (-> (ensure-code!) (.then (fn [_] (apply (:init! @*api) args)))))
(defn authenticated-request! [& args] (invoke! :authenticated-request! args))
(when-enabled :gpt nil
  (defn write! [& args] (invoke! :write! args)))
(when-enabled :user nil
  (defn- auth-action! [method args]
    (-> (invoke! method args)
        (.catch (fn [_] ((last args) {:message "Supabase could not initialize"})))))
  (defn sign-in! [& args] (auth-action! :sign-in! args))
  (defn sign-up! [& args] (auth-action! :sign-up! args))
  (defn sign-out! [& args] (auth-action! :sign-out! args))
  (defn change-password! [& args] (auth-action! :change-password! args)))
