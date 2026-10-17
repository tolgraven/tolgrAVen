(ns website.modules.cv.events
  (:require [website.component.storage :as storage]
            [website.react :as r]
            [website.util :as util]))

(defn active? [db generation]
  (let [demo (get-in db [:state :cv :demo])]
    (and (:active? demo) (= generation (:generation demo)))))

(r/reg-fx :cv/restore-visit
  (fn [generation]
    (-> (storage/ready! {:scope :public})
        (.then (fn [_]
                 (let [snapshot (storage/read! [:state [:state :cv :visited]]
                                              {:scope :public, :consume? false})]
                   (r/dispatch [:cv/visit-restored generation (true? (:value snapshot))]))))
        (.catch (fn [_] (r/dispatch [:cv/visit-restored generation false]))))))

(r/reg-event-fx :cv/mount [(r/inject-cofx :ls)]
  (fn [{:keys [db ls]} [_ {:keys [owner returned?]}]]
    (let [generation (inc (get-in db [:state :cv :demo :generation] 0))]
      (cond-> {:db (assoc-in db [:state :cv :demo]
                            (cond-> {:generation generation
                                     :active? true
                                     :returned? (boolean returned?)}
                              owner (assoc :owner owner)))}
        (not returned?)
        (assoc :dispatch-later [{:ms 2000, :dispatch [:cv/fullscreen generation]}])
        (or (true? (get-in db [:state :cv :visited])) (true? (:cv-visited ls)))
        (assoc :dispatch [:cv/visit-restored generation true])
        (not (or (true? (get-in db [:state :cv :visited])) (true? (:cv-visited ls))))
        (assoc :cv/restore-visit generation)))))

(r/reg-event-db :cv/unmount
  (fn [db [_ owner]]
    (let [demo (get-in db [:state :cv :demo])]
      (if (and demo (or (nil? owner) (= owner (:owner demo))))
        (update-in db [:state :cv :demo] #(-> % (assoc :active? false) (dissoc :origin)))
        db))))

(r/reg-event-fx :cv/visit-restored [(r/inject-cofx :ls)]
  (fn [{:keys [db ls]} [_ generation restored?]]
    (when (active? db generation)
      (let [visited? (or (true? (get-in db [:state :cv :visited]))
                         (true? (:cv-visited ls)) restored?)]
        (cond-> {:db (assoc-in db [:state :cv :visited] true)
                 ;; Keep the original durable identity. Startup must not consume
                 ;; this marker into an expiring/one-use component snapshot.
                 :ls (assoc ls :cv-visited true)}
          (and (not visited?) (not (get-in db [:state :cv :demo :returned?])))
          (assoc :dispatch-later [{:ms 3000, :dispatch [:cv/demo generation :down]}
                                  {:ms 4500, :dispatch [:cv/demo generation :back]}
                                  {:ms 5500, :dispatch [:cv/demo generation :focus]}]))))))

(r/reg-event-fx :cv/fullscreen
  (fn [{:keys [db]} [_ generation]]
    (when (and (active? db generation) (not (get-in db [:state :cv :demo :returned?])))
      {:dispatch [:state [:fullscreen :cv] true]})))

(r/reg-cofx :cv/scroll-position
  (fn [cofx _] (assoc cofx :cv/scroll-position (.-scrollY js/window))))

(r/reg-fx :cv/focus
  (fn [_]
    (when-let [button (util/elem-by-id "fullscreen-btn")]
      (.focus button #js {:preventScroll true}))))

(r/reg-event-fx :cv/demo [(r/inject-cofx :cv/scroll-position)]
  (fn [{:keys [db] :cv/keys [scroll-position]} [_ generation step]]
    (when (and (active? db generation) (not (get-in db [:state :cv :demo :returned?])))
      (case step
        :down {:db (assoc-in db [:state :cv :demo :origin] scroll-position)
               :dispatch [:scroll/by 50]}
        :back (when-some [origin (get-in db [:state :cv :demo :origin])]
                {:dispatch [:scroll/px origin]})
        :focus {:cv/focus true}))))
