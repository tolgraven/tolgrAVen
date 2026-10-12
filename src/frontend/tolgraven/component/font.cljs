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
   [:phase [:enum :initial :native :fallback :prepared :crossfade :ready :failed]]
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
  (assoc instance :phase (if (:reduced? instance) :ready :prepared)))

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
          {:db (assoc-in db [:fonts :instances owner] (ready-instance instance))}
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
        {:db (-> db
                 (assoc-in [:fonts :faces key :status] (if success? :ready :failed))
                 (update-in [:fonts :instances] merge instances))}))))
(rf/reg-event-fx :font/reveal
  (fn [{:keys [db]} [_ owner]]
    (when-let [instance (get-in db [:fonts :instances owner])]
      (when (= :prepared (:phase instance))
        {:db (assoc-in db [:fonts :instances owner :phase] :crossfade)
         :dispatch-later [{:ms (+ 50 (fade-ms (:options instance)))
                           :dispatch [:font/done owner]}]}))))
(rf/reg-event-db :font/done
  (fn [db [_ owner]]
    (if (= :crossfade (get-in db [:fonts :instances owner :phase]))
      (assoc-in db [:fonts :instances owner :phase] :ready) db)))

(defn use-font
  "Overlap two declarative text layers inside a native root. Only apply this
   feature to presentational text: inputs, controls and component lifecycles must
   stay outside its children. SSR and hydration start with one original layer."
  [form options]
  (when options
    (when (and form (not (motion/dom-root? form)))
      (throw (js/Error. "Font transitions require a native text root."))))
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
        (when (= :prepared phase)
          ;; Commit the loaded-font layer behind the visible original, then start
          ;; both opacity animations on the same painted frame.
          (motion/next-frame! #(rf/dispatch [:font/reveal owner]))))
      [owner phase])
    (if-not enabled?
      form
      (let [ready? (= :ready phase)
            changing? (#{:prepared :crossfade} phase)
            family (fn [name] (str (js/JSON.stringify name) ", " (:fallback options)))
            style (assoc (:style attrs)
                         (:property options) (family (if ready? (:family options) (:initial-family options)))
                         "--font-fade-time" (str (fade-ms options) "ms"))
            attributes (r/merge-props attrs {:data-font-phase (name phase), :style style})
            incoming (with-meta
                       (into [:span {:class (if ready? "font-text-current" "font-text-next")
                                     :aria-hidden (when-not ready? true)
                                     :inert (when-not ready? true)
                                     :style {(:property options) (family (:family options))
                                             :font-family (str "var(" (:property options) ")")}
                                     :on-animation-end
                                     (fn [event]
                                       (when (and (identical? (.-target event) (.-currentTarget event))
                                                  (= "font-fade-in" (.-animationName event)))
                                         (rf/dispatch [:font/done owner])))}] children)
                       {:key :ready})
            outgoing (with-meta (into [:span.font-text-current] children) {:key :initial})
            text [:span.font-text-stack
                  (when-not ready? outgoing)
                  (when (or ready? changing?) incoming)]]
        ;; After the fade the incoming key survives, and React removes the old
        ;; text layer. The native root, its handlers and neighbouring controls
        ;; never unmount. No cloneNode or inspection of React elements is needed.
        (with-meta [(first form) attributes text] (meta form))))))
