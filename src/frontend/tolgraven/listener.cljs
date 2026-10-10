(ns tolgraven.listener
  (:require
    [clojure.string :as string]
    [tolgraven.react :as rf]
    [tolgraven.validation.runtime :as validation]
    [tolgraven.util :as util]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(def binding-schema
  [:map
   [:id :any]
   [:owner :keyword]
   [:target :any]
   [:event :string]
   [:handler fn?]
   [:capture? {:optional true} :boolean]])
(defonce *bindings (atom {}))
(defonce scroll-listeners (atom {}))

(defn- native-target [value]
  (case value "document" js/document "window" js/window value))

(defn remove! [id]
  (when-let [{:keys [target event handler capture?]} (get @*bindings id)]
    (.removeEventListener target event handler (boolean capture?))
    (swap! *bindings dissoc id)))

(defn register! [{:keys [id target event handler capture?] :as binding}]
  (when (exists? js/window)
    (validation/check! "document listener" binding-schema binding)
    (remove! id)
    (let [element (native-target target)]
      (.addEventListener element event handler (boolean capture?))
      (swap! *bindings assoc id (assoc binding :target element)))))

(defn stop! [owner]
  (doseq [[id binding] @*bindings :when (= owner (:owner binding))] (remove! id))
  (when (= owner :site) (reset! scroll-listeners {})))

(rf/reg-fx :listener/register (fn [bindings] (doseq [binding bindings] (register! binding))))
(rf/reg-fx :listener/stop stop!)

(rf/reg-event-fx :listener/add!  [debug]
 (fn [{:keys [db]} [_ el event f]]
   {:listener/add-fx [el event f]}))

(rf/reg-fx :listener/add-fx 
 (fn [[el event f]]
   (register! {:id [:site el event]
               :owner :site
               :target el
               :event event
               :handler f})))


(defn- get-height
  []
  (- (.-clientHeight (util/elem-by-type "body"))
     (-> (util/elem-by-type "body")
         js/getComputedStyle
         .-paddingBottom
         js/parseFloat)))

(rf/reg-fx :scroll/register-update-fn
  (fn [[id f]]
    (swap! scroll-listeners assoc id f)))

(rf/reg-event-fx :scroll/update-css-var
 (fn [{:keys [_]} [_ _]]
   (let [f (fn update-css-var [e]
             (let [new-pos    (.-scrollY js/window)
                   new-height (get-height)
                   vh         (.-innerHeight js/window)
                   percent    (-> new-pos
                                  (/ new-height)
                                  (* (+ 1 (/ vh new-height)))
                                  (* 100)
                                  js/Math.ceil
                                  (min 100)
                                  (max 0))
                   opacity   (cond
                              (< percent 10) 0
                              (> percent 90) 0
                              :else          1)]
               (when-not (.isNaN js/Number percent)
                 (rf/dispatch [:->css-var! "page-scroll-percent" (str percent "%")])
                 (rf/dispatch [:->css-var! "page-scroll-opacity" opacity]))))]
     {:scroll/register-update-fn [:update-css-var f]})))

(rf/reg-event-fx :scroll/update-direction   [(rf/inject-cofx :css-var [:header-height])
                                             (rf/inject-cofx :css-var [:space-top])
                                             (rf/inject-cofx :css-var [:space-lg])]
 (fn [{:keys [_ css-var]} [_ _]]
   (let [scroll-pos (atom 0)
         last-direction (atom :up)
         accum-in-direction (atom 0)
         page-height (atom 0)
         triggered-at (atom (js/Date.now))
         top-size (+ (util/rem-to-px (:header-height css-var))     ; distance from top to main is header-height + space-top above/below,
                     (* 2 (util/rem-to-px (:space-top css-var)))
                     (util/rem-to-px (:space-lg css-var)))
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
                                            (.-clientHeight (util/elem-by-id "footer-end"))
                                            100)) ; "maybe at bottom"
                          at-top? (<= new-pos top-size)]
                        (when (and (> 10 (abs (- @page-height new-height)))
                                   (or (not= @scroll-pos new-pos)
                                       at-top?
                                       at-bottom?)) ; avoid running up accum from massive page size jumps...
                          (reset! accum-in-direction
                                  (+ (if (= new-direction @last-direction)
                                       @accum-in-direction
                                       0)
                                     (abs (- new-pos @scroll-pos))))
                          (when (and (or at-bottom?
                                         at-top?
                                         (> (- (js/Date.now) @triggered-at) 150)) ; ensure "scroll" isn't due to content resizing
                                     (or (<= 150 @accum-in-direction) ; bit of debounce
                                          (and at-top?
                                               #_(= new-direction :up)
                                               #_(= @last-direction :down))
                                          (and at-bottom?
                                               (= new-direction :down)
                                               #_(= @last-direction :up)))) ; always post when at bottom, regardless of accum
                            (reset! accum-in-direction 0)
                            (reset! triggered-at (js/Date.now))
                            (rf/dispatch [:scroll/direction
                                          new-direction new-pos new-height at-top? at-bottom?]))
                          (reset! scroll-pos new-pos)
                          (reset! last-direction new-direction))
                        (reset! page-height new-height)))]
     {:scroll/register-update-fn [:direction callback]})))

(rf/reg-event-fx :listener/scroll   []
 (fn [{:keys [_]} [_ _]]
   (let [ticking? (atom false)
         cnt      (atom 0)
         throttle 4
         callback (fn [e]
                    (if (or (not @ticking?)
                            (zero? @cnt)
                            (zero? (.-scrollY js/window)))
                      (do
                       (reset! ticking? true)
                       (swap! cnt inc)
                       (.requestAnimationFrame
                        js/window
                        (fn []
                          (doseq [f (vals @scroll-listeners)]
                            (f e))
                          (reset! ticking? false))))
                      (if (> @cnt throttle)
                        (reset! cnt 0)
                        (swap! cnt inc))))]
     {:listener/add-fx ["document" "scroll" callback]})))

; TODO some things. apparently beforeunload is not recommended and doesn't fire reliably.
; especially on mobile if swapping apps and then page gets killed in bg etc.
; below combo of visibilitychange and sendbeacon which is meant to POST analytics.
; Persistent application data is stored in Supabase.
; will definitely become a thing in lexcraft at least...
; document.addEventListener('visibilitychange', function logData() {
;   if (document.visibilityState === 'hidden') {
;     navigator.sendBeacon('/log', analyticsData);
;   }
; });

(rf/reg-event-fx :listener/before-unload-save-scroll ; gets called even when link to save page, silly results.
                 ;; might make sense to just store each pushstate tbh?
 (fn [{:keys [db]} [_ ]]
  (let [scroll-to-ls
        (fn []
          (when (= js/document.visibilityState "hidden")
            (rf/dispatch-sync [:scroll/save-history])
            (rf/dispatch-sync [:ls/store-path [:scroll-position]
                               [:state :scroll-position]])))]
    {:dispatch [:listener/add! "window" "visibilitychange" scroll-to-ls]})))

(rf/reg-event-fx :listener/popstate-back ; gets called on browser back. or if we nanually pop state, so keep track of that if end up doing it...
 (fn [{:keys [db]} [_ ]]
   (let [f (fn [e]
             (rf/dispatch-sync [:history/popped e]))] ; which will actually have fresh db and can do stuff ugh
    {:listener/add-fx ["window" "popstate" f]})))

(rf/reg-event-fx :listener/load
 (fn [{:keys [db]} [_ _]]
   (let [f (fn [e]
             (rf/dispatch [:booted :load]))] ; which will actually have fresh db and can do stuff ugh
    {:dispatch [:listener/add! "window" "load" f]})))

(rf/reg-event-fx :listener/global-click
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
     {:dispatch [:listener/add! "document" visibility-change
                 #(rf/dispatch [:handle-visibility-change hidden])]})))
