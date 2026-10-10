(ns tolgraven.component.font
  "Committed font ownership; re-frame owns readiness and each transient fade."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [tolgraven.component.motion :as motion]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]
            [tolgraven.react :as rf]))

(def options-schema
  [:map
   [:family :string]
   [:initial-family :string]
   [:fallback :string]
   [:property [:re "^--[a-z][a-z0-9-]*$"]]
   [:sample {:optional true} :string]
   [:fade-ms {:optional true} [:int {:min 1, :max 500}]]])
(def instance-schema
  [:map
   [:options options-schema]
   [:phase [:enum :initial :native :fallback :out :swapped :in :ready :failed]]
   [:reduced? {:optional true} :boolean]])
(def state-schema
  [:map
   [:instances {:optional true} [:map-of :uuid instance-schema]]
   [:faces {:optional true}
    [:map-of :string [:map [:status [:enum :loading :ready :failed]] [:token :uuid]]]]])

(def fira
  {:family "Fira Code Ready"
   :initial-family "Fira Code"
   :fallback "'Fira Code Fallback', monospace"
   :property "--monospace-font"})
(def load-timeout-ms 10000)
(defn fade-ms [options] (get options :fade-ms 100))
(defn request [options] (str "1em " (js/JSON.stringify (:family options))))
(defn faces [family]
  (when (and (exists? js/document) (.-fonts js/document))
    (filter #(= family (string/replace (.-family %) #"^['\"]|['\"]$" ""))
            (array-seq (js/Array.from (.-fonts js/document))))))
(defn native-ready? [options]
  (boolean (some #(= "loaded" (.-status %)) (faces (:initial-family options)))))

(rf/reg-sub :font/instance
  (fn [db [_ owner]] (get-in db [:fonts :instances owner])))
(rf/reg-fx :font/probe
  (fn [[owner options]]
    (rf/dispatch [:font/probed owner
                  (native-ready? options)
                  (boolean (seq (faces (:family options))))
                  (boolean (or (motion/reduced-motion?) (restore/local-document?)))])))
(rf/reg-fx :font/load
  (fn [[options token]]
    (let [key (request options)]
      (try
        (-> (.load (.-fonts js/document) key (get options :sample "MWiw0123456789"))
            (.then (fn [loaded]
                     (rf/dispatch [:font/loaded key token (pos? (.-length loaded))])))
            (.catch (fn [_] (rf/dispatch [:font/loaded key token false]))))
        (catch :default _ (rf/dispatch [:font/loaded key token false]))))))

(rf/reg-event-fx :font/attach
  (fn [{:keys [db]} [_ owner options]]
    {:db (assoc-in db [:fonts :instances owner] {:options options, :phase :initial})
     :font/probe [owner options]}))
(rf/reg-event-db :font/detach
  (fn [db [_ owner]]
    (if (get-in db [:fonts :instances owner])
      (update-in db [:fonts :instances] dissoc owner) db)))

(defn- ready-instance [instance]
  (assoc instance :phase (if (:reduced? instance) :ready :out)))
(defn- out-deadline [owner instance]
  {:ms (+ 50 (fade-ms (:options instance))), :dispatch [:font/swap owner]})

(rf/reg-event-fx :font/probed
  (fn [{:keys [db]} [_ owner native? supported? reduced?]]
    (when-let [instance (get-in db [:fonts :instances owner])]
      (let [options (:options instance)
            key (request options)
            status (get-in db [:fonts :faces key :status])
            instance (assoc instance :reduced? reduced?)
            token (random-uuid)]
        (cond
          (or native? (not supported?))
          {:db (assoc-in db [:fonts :instances owner] (assoc instance :phase :native))}
          (= :ready status)
          {:db (assoc-in db [:fonts :instances owner] (ready-instance instance))
           :dispatch-later [(out-deadline owner instance)]}
          :else
          (cond-> {:db (assoc-in db [:fonts :instances owner] (assoc instance :phase :fallback))}
            (not= :loading status)
            (assoc :db (-> db
                           (assoc-in [:fonts :instances owner] (assoc instance :phase :fallback))
                           (assoc-in [:fonts :faces key] {:status :loading, :token token}))
                   :font/load [options token]
                   :dispatch-later [{:ms load-timeout-ms
                                     :dispatch [:font/loaded key token false]}])))))))

(rf/reg-event-fx :font/loaded
  (fn [{:keys [db]} [_ key token success?]]
    ;; A timeout, retry, unmount or replaced owner must not revive an old fade.
    (when (= {:status :loading, :token token} (get-in db [:fonts :faces key]))
      (let [affected (into {} (filter (fn [[_ instance]]
                                       (and (= key (request (:options instance)))
                                            (= :fallback (:phase instance)))))
                           (get-in db [:fonts :instances]))
            instances (into {} (map (fn [[owner instance]]
                                      [owner (if success? (ready-instance instance)
                                                 (assoc instance :phase :failed))])) affected)]
        (cond-> {:db (-> db
                        (assoc-in [:fonts :faces key :status] (if success? :ready :failed))
                        (update-in [:fonts :instances] merge instances))}
          success? (assoc :dispatch-later (mapv (fn [[owner instance]] (out-deadline owner instance)) instances)))))))
(rf/reg-event-db :font/swap
  (fn [db [_ owner]]
    (if (= :out (get-in db [:fonts :instances owner :phase]))
      (assoc-in db [:fonts :instances owner :phase] :swapped) db)))
(rf/reg-event-fx :font/reveal
  (fn [{:keys [db]} [_ owner]]
    (when-let [instance (get-in db [:fonts :instances owner])]
      (when (= :swapped (:phase instance))
        {:db (assoc-in db [:fonts :instances owner :phase] :in)
         :dispatch-later [{:ms (+ 50 (fade-ms (:options instance)))
                           :dispatch [:font/done owner]}]}))))
(rf/reg-event-db :font/done
  (fn [db [_ owner]]
    (if (= :in (get-in db [:fonts :instances owner :phase]))
      (assoc-in db [:fonts :instances owner :phase] :ready) db)))

(defn use-font
  "Merge a font feature onto a native root. Initial SSR markup/font choice stays
   unchanged; only a face pending at commit gets a controlled late upgrade."
  [form options]
  (when options
    (when (and form (not (motion/dom-root? form)))
      (throw (js/Error. "Font transitions require a native root; they add no wrapper."))))
  (let [;; Option functions may construct an equal Clojure map on every render.
        ;; Stabilize its primitive contract fields before assigning ownership.
        options (rf/use-memo (constantly options)
                             (mapv #(get options %) [:family :initial-family :fallback
                                                     :property :sample :fade-ms]))
        owner (rf/use-memo random-uuid [options])
        instance @(rf/subscribe [:font/instance owner])
        phase (get instance :phase :initial)
        enabled? (and options (boolean form))
        attrs (when (map? (second form)) (second form))
        children (if (map? (second form)) (nnext form) (next form))]
    (rf/use-effect
      (fn []
        (when (and enabled? (not context/*server?*))
          (rf/dispatch [:font/attach owner options])
          #(rf/dispatch [:font/detach owner])))
      [owner options enabled?])
    (rf/use-layout-effect
      (fn []
        (when (= :swapped phase)
          ;; Paint the new glyphs while still fully transparent before revealing.
          (motion/next-frame! #(rf/dispatch [:font/reveal owner]))))
      [owner phase])
    (if-not enabled?
      form
      (let [target? (#{:swapped :in :ready} phase)
            changing? (#{:out :swapped :in} phase)
            ended! (:on-animation-end attrs)
            family (str (js/JSON.stringify (if target? (:family options) (:initial-family options)))
                        ", " (:fallback options))
            style (cond-> (assoc (:style attrs) (:property options) family)
                    changing? (assoc "--font-fade-time" (str (fade-ms options) "ms")))
            attributes (r/merge-props attrs
                                     (cond-> {:data-font-phase (name phase), :style style}
                                       changing? (assoc :class "font-transition")))
            attributes (assoc attributes :on-animation-end
                              (fn [event]
                                (when ended! (ended! event))
                                (when (identical? (.-target event) (.-currentTarget event))
                                  (case (.-animationName event)
                                    "font-fade-out" (rf/dispatch [:font/swap owner])
                                    "font-fade-in" (rf/dispatch [:font/done owner])
                                    nil))))]
        (with-meta (into [(first form) attributes] children) (meta form))))))
