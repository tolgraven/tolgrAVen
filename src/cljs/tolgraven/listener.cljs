(ns tolgraven.listener
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [reitit.frontend.easy :as rfe]
    [reitit.frontend.controllers :as rfc]
    [clojure.string :as string]
    [tolgraven.util :as util]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :listener/add!  [debug]
 (fn [{:keys [db]} [_ el event f]]
   {:listener/add-fx [el event f]}))

(rf/reg-fx :listener/add-fx 
 (fn [[el event f]]
   (let [el (case el
              "document" js/document
              "window" js/window
              el)]
     (util/on-event el event f))))


 ; this causes event spam obviously but since need subs & db it's necessary.
(rf/reg-event-fx :listener/scroll-direction   [(rf/inject-cofx :css-var [:header-height])
                                               (rf/inject-cofx :css-var [:space-top])]
 (fn [{:keys [db css-var]} [_ _]]
   (let [scroll-pos (atom 0)
         last-direction (atom :up)
         accum-in-direction (atom 0)
         get-height (fn []
                      (- (.-clientHeight (util/elem-by-id "main"))
                         (-> (util/elem-by-id "main")
                             js/getComputedStyle
                             .-paddingBottom
                             js/parseFloat)))
         page-height (atom 0)
         triggered-at (atom (ct/now))
         top-size (+ (util/rem-to-px (:header-height css-var))     ; distance from top to main is header-height + space-top above/below,
                     (* 2 (util/rem-to-px (:space-top css-var)))
                     50)
         callback (fn [e]
                    (let [new-pos (.-scrollY js/window)
                          new-height (get-height) ; jumps between actual and ~double val causing jitter and badness...
                          new-direction (cond
                                         (> new-pos @scroll-pos) :down
                                         (< new-pos @scroll-pos) :up
                                         :else @last-direction)
                          at-bottom? (>= new-pos ; XXX use calc from :scroll/direction instead...
                                         (- new-height
                                            (.-innerHeight js/window)
                                            150)) ; "maybe at bottom"
                          at-top? (<= @scroll-pos top-size)]
                        (when (and (not= @scroll-pos new-pos)
                                   (= @page-height new-height)) ; avoid running up accum from massive page size jumps...
                          (reset! accum-in-direction (if (= new-direction @last-direction)
                                                       (+ @accum-in-direction (abs (- new-pos @scroll-pos)))
                                                       0))
                          (when (and (or at-bottom?
                                         at-top?
                                         (ct/after? (ct/minus (ct/now) (ct/millis 150)) @triggered-at)) ; ensure "scroll" isn't due to content resizing
                                     (or (<= 250 @accum-in-direction) ; bit of debounce
                                          (and at-top?
                                               (= new-direction :up)
                                               (= @last-direction :down))
                                          (and at-bottom?
                                               (= new-direction :down)
                                               (= @last-direction :up)))) ; always post when at bottom, regardless of accum
                            (reset! accum-in-direction 0)
                            (reset! triggered-at (ct/now))
                            (rf/dispatch [:scroll/direction
                                          new-direction new-pos new-height at-bottom?]))
                          (reset! scroll-pos new-pos)
                          (reset! last-direction new-direction))
                        (reset! page-height new-height)))]
     {:dispatch [:listener/add! "document" "scroll" callback]})))

; TODO some things. apparently beforeunload is not recommended and doesn't fire reliably.
; especially on mobile if swapping apps and then page gets killed in bg etc.
; below combo of visibilitychange and sendbeacon which is meant to POST analytics.
; could use a firebase function endpoint to POST-save stuff there and not merely ls.
; will definitely become a thing in lexcraft at least...
; document.addEventListener('visibilitychange', function logData() {
;   if (document.visibilityState === 'hidden') {
;     navigator.sendBeacon('/log', analyticsData);
;   }
; });

(rf/reg-event-fx :listener/before-unload-save-scroll ; gets called even when link to save page, silly results.
 (fn [{:keys [db]} [_ ]]
  (let [scroll-to-ls
        (fn []
          (when (= js/document.visibilityState "hidden")
            (rf/dispatch-sync [:state [:scroll-position ; doesnt it make more sense to save straight to ls tho instead of going by db... guess doesn't matter with -sync on both tho?
                                       (-> db :common/route :data :name)]
                               (.-scrollY js/window)])
            (rf/dispatch-sync [:ls/store-path [:scroll-position]
                               [:state :scroll-position]])))]
    {:dispatch-n [[:listener/add! "window" "visibilitychange" scroll-to-ls]]})))

(rf/reg-event-fx :listener/popstate-back ; gets called on browser back. or if we nanually pop state, so keep track of that if end up doing it...
 (fn [{:keys [db]} [_ ]]
   (let [f (fn [e]
             (rf/dispatch-sync [:history/popped e]))] ; which will actually have fresh db and can do stuff ugh
    {:dispatch [:listener/add! "window" "popstate" f]})))

(rf/reg-event-fx :listener/load
 (fn [{:keys [db]} [_ _]]
   (let [f (fn [e]
             (rf/dispatch [:booted :load]))] ; which will actually have fresh db and can do stuff ugh
    {:dispatch [:listener/add! "window" "load" f]})))

(rf/reg-event-fx :listener/global-click [debug]
 (fn [{:keys [db]} [_ _]]
   {:dispatch [:listener/add! "document" "click"
               #(rf/dispatch [:global-clicked %])]}))

(defn visibility-props "Get the name of the hidden property and the change event for visibility"
  []
  (cond
    (some? js/document.hidden) {:hidden "hidden"
                                :visibility-change "visibilitychange"}
    (some? js/document.msHidden) {:hidden "msHidden"
                                  :visibility-change "msvisibilitychange"}
    (some? js/document.webkitHidden) {:hidden "webkitHidden"
                                      :visibility-change "webkitvisibilitychange"}
    :else (js/console.error "visibility prop not found in visibility-props fn")))

(rf/reg-event-fx :listener/visibility-change
 (fn [{db :db} _]
   (when-let [{:keys [hidden visibility-change]} (visibility-props)]
     {:db (assoc db :chrome-tab-visibility true)
      :dispatch [:listener/add! "document" visibility-change
                 #(rf/dispatch [:handle-visibility-change hidden])]})))


