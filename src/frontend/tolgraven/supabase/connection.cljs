(ns tolgraven.supabase.connection
  "Quiet initial negotiation; bounded failure reporting after startup or a live loss."
  (:require [tolgraven.service-status :as status]))

(defn watch!
  [{:keys [id title current? retry! failed! timeout-ms]
    :or {timeout-ms 10000 current? (constantly true) failed! (fn [])}}]
  (let [*live? (atom false) *closed? (atom false) *timer (atom nil)
        active? #(and (not @*closed?) (current?))
        clear! #(when @*timer (js/clearTimeout @*timer) (reset! *timer nil))
        fail! (fn []
                (when (active?)
                  (clear!)
                  (status/fail! id title
                                (if @*live?
                                  "The live connection was lost. Current content is retained while reconnecting."
                                  "Live updates could not connect. Current content is still available; retry to refresh it.")
                                retry!)
                  (failed!)))]
    (reset! *timer (js/setTimeout fail! timeout-ms))
    {:status! (fn [state]
                (when (active?)
                  (case state
                    "SUBSCRIBED" (do (clear!) (reset! *live? true) (status/recover! id))
                    "TIMED_OUT" (fail!)
                    ;; A socket can be replaced during initial Auth negotiation.
                    ;; Before the first successful join, let the startup deadline
                    ;; distinguish that transition from a connection failure.
                    ("CHANNEL_ERROR" "CLOSED") (when @*live? (fail!))
                    nil)))
     :close! (fn [] (reset! *closed? true) (clear!) (status/recover! id))}))
