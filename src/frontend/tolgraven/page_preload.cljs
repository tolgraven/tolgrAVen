(ns tolgraven.page-preload
  "Idle, bounded acquisition of the same modules and managed dependencies used by pages."
  (:require [reagent.core :as r]
            [reitit.core :as reitit]
            [tolgraven.react :as rf]
            [tolgraven.routes :as routes]
            [tolgraven.loader :as loader]
            [tolgraven.page :as page]
            [tolgraven.modules.main.module :as main]
            [tolgraven.component.data :as data]))

(defonce *queue (atom []))
(defonce *active (atom #{}))
(defonce *completed (atom {}))
(defonce *running? (atom false))

(defn resources [match]
  (let [declared (get-in match [:data :preload-depends])]
    (concat (when-not (get-in match [:data :module-only?]) (page/dependencies (:data match)))
            (if (fn? declared) (declared match) declared))))

(defn- acquire! [match]
  (let [{:keys [module page]} (:data match)]
    (-> (if module (loader/load! {:module module :view page})
            (js/Promise.resolve nil))
        (.then #(data/ensure-all! (resources match))))))

(declare drain!)
(defn- drain! []
  (when (and @*running? (< (count @*active) 2) (seq @*queue))
    (let [[key match] (first @*queue)]
      (swap! *queue subvec 1)
      (swap! *active conj key)
      (-> (acquire! match)
          (.then (fn [_]
                   (swap! *completed assoc key (.now js/Date))))
          ;; Speculation must not prevent later navigation/retry.
          (.catch (fn [_] nil))
          (.finally (fn [] (swap! *active disj key) (drain!))))
      (drain!))))

(defn enqueue! [paths]
  (let [now (.now js/Date)]
    (swap! *completed #(into {} (filter (fn [[_ at]] (< (- now at) 300000))) %))
    (doseq [[path match] (concat
                           (map (fn [path] [path (reitit/match-by-path routes/router path)]) (distinct paths))
                           (map (fn [id] [[:module id] {:data {:module id :module-only? true}}])
                                (:preload-modules main/spec)))
            :when (and match (not= false (get-in match [:data :preload]))
                       (not (@*active path)) (not (contains? @*completed path))
                       (not (some #(= path (first %)) @*queue)))]
      (swap! *queue conj [path match]))
    (drain!)))

(defn- linked-paths []
  ;; DOM inspection belongs to this lifecycle adapter, never to page render code.
  ;; Buttons can declare a destination with data-preload-href too.
  (->> (concat (array-seq (.querySelectorAll js/document "main a[rel=prev],main a[rel=next]"))
               (array-seq (.querySelectorAll js/document "main a[href],main button[data-preload-href]"))
               (array-seq (.querySelectorAll js/document "a[href],button[data-preload-href]")))
       (keep (fn [element]
               (when-not (or (.hasAttribute element "download")
                             (= "false" (.getAttribute element "data-preload")))
                 (try
                   (let [url (js/URL. (or (.getAttribute element "data-preload-href")
                                         (.getAttribute element "href")) (.-href js/location))]
                     (when (and (= (.-origin url) (.-origin js/location))
                                (not= (.-pathname url) (.-pathname js/location)))
                       (.-pathname url)))
                   (catch :default _ nil)))))
       distinct vec))

(rf/reg-event-fx :page/preload-links
  (fn [_ _] {:page/preload-links true}))
(rf/reg-fx :page/preload-links (fn [_] (enqueue! (linked-paths))))

(defn console-node? [node]
  (some-> (if (= 1 (.-nodeType node)) node (.-parentElement node))
          (.closest "[data-dev-console]")))
(defn relevant-mutation? [record]
  (and (not (console-node? (.-target record)))
       (let [nodes (concat (array-seq (.-addedNodes record)) (array-seq (.-removedNodes record)))]
         (or (empty? nodes) (not-every? console-node? nodes)))))

(defn start!
  "Start after the first React commit. Observe future SPA links; release observers/timers on unmount."
  []
  (reset! *running? true)
  (let [*timer (atom nil)
        *idle (atom nil)
        schedule! (fn [& _]
                    (when-not (or @*timer @*idle)
                      (reset! *timer
                        (js/setTimeout
                          (fn []
                            (reset! *timer nil)
                            (if (exists? js/requestIdleCallback)
                              (reset! *idle
                                (js/requestIdleCallback
                                  (fn [_] (reset! *idle nil) (rf/dispatch [:page/preload-links]))
                                  #js {:timeout 2000}))
                              (rf/dispatch [:page/preload-links])))
                          350))))
        observer (js/MutationObserver.
                   (fn [records _]
                     (when (some relevant-mutation? (array-seq records)) (schedule!))))]
    (.observe observer (.-body js/document)
              #js {:childList true :subtree true :attributes true
                   :attributeFilter #js ["href" "data-preload-href"]})
    (schedule!)
    (fn []
      (reset! *running? false)
      (reset! *queue [])
      (.disconnect observer)
      (when @*timer (js/clearTimeout @*timer))
      (when @*idle (js/cancelIdleCallback @*idle)))))

(r/defc <background> []
  (rf/use-effect start! #js [])
  nil)
