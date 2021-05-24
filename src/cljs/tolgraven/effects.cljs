(ns tolgraven.effects
  (:require
   [re-frame.core :as rf]
   [re-frame.registrar :as rfr]
   [re-frame.router :as router]
   [re-frame.interop :refer [set-timeout!]]))

(defn clear-timeout! [id]
   (js/clearTimeout id))

(def kind :fx)
(assert (re-frame.registrar/kinds kind))
(def register  (partial rfr/register-handler kind))
;; from https://github.com/day8/re-frame/pull/258/commits/642f9b6aeb423a7fd4725e14b197b6d4d402cf7f#diff-126dcfb8f966c442fd6aab13d47ad3777888e90f5a3483fef2b908374064125b
;; :dispatch-throttle
 ;;
 ;; This will only dispatch the event at most once per every 'window-duration' milliseconds.
 ;; The throttled function can be configured to dispatch on the leading, trailing or both edges of this window.

 ;; Note: When both leading and trailing flags are enabled the event will be dispatched on trailing edge only if there are more than one event.
 ;;
 ;; usage
 ;;
 ;; Expects either a single map, or a sequence of maps.
 ;;
 ;; {:dispatch-throttle {:id       ::re-render-markdown
 ;;                      :window-duration  250
 ;;                      :leading? true
 ;;                      :trailing? false
 ;;                      :dispatch [:re-render :main-section]}}
 ;;
 ;; Cancel throttled events
 ;;
 ;; {:dispatch-throttle {:id       ::re-render-markdown
 ;;                      :action :cancel}}
 ;;
 ;; Flush latest throttled event
 ;;
 ;; {:dispatch-throttle {:id ::re-render-markdown
 ;;                      :action :flush}}
 (def throttled-events (atom {}))

 (defn- on-leading-edge
   [id]
   (if-let [{:keys [leading? deferred-dispatch]} (id @throttled-events)]
     (if leading?
       (do
         (router/dispatch deferred-dispatch)
         (swap! throttled-events update-in [id] dissoc :deferred-dispatch)))))

 (defn- on-trailing-edge
   [id]
   (if-let [{:keys [trailing? deferred-dispatch]} (id @throttled-events)]
     (do
       (if (and trailing? deferred-dispatch)
         (router/dispatch deferred-dispatch))
       (swap! throttled-events dissoc id))))

 (register
  :dispatch-throttle
  (fn [dispatches]
    (let [dispatches (if (sequential? dispatches) dispatches [dispatches])]
      (doseq [{:keys [id action dispatch window-duration leading? trailing?] :as effect
               :or   {action :dispatch
                      leading? true}}
              dispatches]
        (case action

          :dispatch (if (or (empty? dispatch) (not (number? window-duration)))
                      (js/console.error "re-frame: ignoring bad :dispatch-throttle value:" effect)
                      (let [new-event? (nil? (id @throttled-events))]
                        (swap! throttled-events #(-> %
                                                     (assoc-in [id :deferred-dispatch] dispatch)
                                                     (assoc-in [id :leading?] leading?)
                                                     (assoc-in [id :trailing?] trailing?)))
                        (if new-event?
                          (do
                            (swap! throttled-events assoc-in [id :timeout] (set-timeout! #(on-trailing-edge id) window-duration))
                            (on-leading-edge id)))))

          :cancel (do
                    (clear-timeout! (get-in [id :timeout] @throttled-events))
                    (swap! throttled-events dissoc id))

          :flush (on-trailing-edge id)

          (js/console.error "re-frame: bad dispatch-throttle action:" action "id: " id))))))
