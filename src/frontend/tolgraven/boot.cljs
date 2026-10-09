(ns tolgraven.boot
  "Ordered document setup, committed UI setup and deferred background hosts."
  (:require [breaking-point.core :as bp]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.listener :as listener]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.legacy-storage :as legacy]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]
            [tolgraven.browser-resources :as resources]
            [tolgraven.navigation.preload :as preload]
            [tolgraven.ssr.local :as local-page]))

(defn save! []
  ;; Capture history before flushing either disk queue. Native history ownership
  ;; is released afterwards by pagehide, so BFCache keeps the final offset.
  (storage/save-navigation!)
  (rf/dispatch-sync [:ls/store-path [:scroll-position] [:state :scroll-position]])
  (legacy/drain!))

(defn page-hidden! [_]
  (save!)
  (restore/release-history!))

(defn visibility-changed! [_]
  (rf/dispatch [:handle-visibility-change "hidden"])
  (when (.-hidden js/document) (save!)))

(defn document-bindings []
  [{:id :boot/pagehide
    :owner :document
    :target "window"
    :event "pagehide"
    :handler page-hidden!}
   {:id :boot/pageshow
    :owner :document
    :target "window"
    :event "pageshow"
    :handler restore/resumed!}
   {:id :boot/storage
    :owner :document
    :target "window"
    :event "storage"
    :handler legacy/storage-event!}
   {:id :boot/visibility
    :owner :document
    :target "document"
    :event "visibilitychange"
    :handler visibility-changed!}])

(rf/reg-fx :boot/load
  (fn [_]
    (if (= "complete" (.-readyState js/document))
      (rf/dispatch [:booted :load])
      (listener/register! {:id :boot/load
                           :owner :document
                           :target "window"
                           :event "load"
                           :handler (fn [_]
                                      (listener/remove! :boot/load)
                                      (rf/dispatch [:booted :load]))}))))

(rf/reg-event-fx :boot/document
  (fn [_ _]
    {:fx [[:listener/register (document-bindings)]
          [:boot/load true]]}))

(def breakpoints [:mobile 560 :tablet 992 :small-monitor 1200 :large-monitor])
(rf/reg-fx :boot/breakpoints
  (fn [_]
    ;; The library's set-breakpoints installs an anonymous resize listener with
    ;; no teardown. Reuse its subscriptions/events with our owned listener.
    (bp/register-subs breakpoints)
    (rf/dispatch [::bp/set-screen-dimensions])
    (listener/register! {:id :boot/resize
                         :owner :site
                         :target "window"
                         :event "resize"
                         :handler #(rf/dispatch [::bp/set-screen-dimensions-debounced 250])})))

(def interactive-events
  [[:ls/get-path [:scroll-position] [:state :scroll-position]]
   [:scroll/update-direction]
   [:scroll/update-css-var]
   [:listener/scroll]
   [:listener/popstate-back]
   [:ls/get-path [:form-field] [:state :form-field]]
   [:ls/get-path [:cv-visited] [:state :cv :visited]]
   [:cookie/show-notice]
   [:booted :site]])

(rf/reg-event-fx :boot/interactive
  (fn [_ _]
    {:fx [[:listener/register (document-bindings)]
          [:boot/breakpoints true]
          [:dispatch-n interactive-events]]}))

(rf/reg-event-fx :boot/stop
  (fn [_ _]
    {:fx [[:listener/stop :site]
          [:listener/stop :document]]}))

;; Compatibility for explicitly invoked old callers; startup uses the lifecycle.
(rf/reg-event-fx :init/init (fn [_ _] {:dispatch [:boot/interactive]}))

(defc <lifecycle> {:profile false} []
  (rf/use-effect
    (fn []
      (rf/dispatch-sync [:boot/interactive])
      #(rf/dispatch-sync [:boot/stop]))
    #js [])
  [:<> [preload/<background>] [local-page/<capture>]
   (when @context/*interactive? [resources/<deferred>])])
