(ns tolgraven.navigation.preload
  "Idle, bounded acquisition of the same modules and managed dependencies used by pages."
  (:require [reagent.core :as r]
            [reitit.core :as reitit]
            [tolgraven.react :as rf]
            [tolgraven.navigation.routes :as routes]
            [tolgraven.loader :as loader]
            [tolgraven.page :as page]
            [tolgraven.browser-resources :as browser-resources]
            [tolgraven.component.data :as data]))

(defonce *queue (atom []))
(defonce *active (atom #{}))
(defonce *completed (atom {}))
(defonce *running? (atom false))

(defn connection-allowed? [{:keys [save-data? effective-type]}]
  (and (not save-data?) (not (#{"slow-2g" "2g"} effective-type))))

(defn speculation-allowed? []
  (let [connection (.-connection js/navigator)]
    (and (not (.-hidden js/document))
         (connection-allowed? {:save-data? (some-> connection .-saveData)
                              :effective-type (some-> connection .-effectiveType)}))))

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
  (when (and @*running? (speculation-allowed?) (< (count @*active) 2) (seq @*queue))
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
  (when (speculation-allowed?)
    (let [now (.now js/Date)]
      (swap! *completed #(into {} (filter (fn [[_ at]] (< (- now at) 300000))) %))
      (doseq [[path match] (map (fn [path] [path (reitit/match-by-path routes/router path)])
                              (distinct paths))
              :when (and match (not= false (get-in match [:data :preload]))
                         (not (@*active path)) (not (contains? @*completed path))
                         (not (some #(= path (first %)) @*queue)))]
        (swap! *queue conj [path match]))
      (drain!))))

(defn path-for-element [element]
  (when-not (or (.hasAttribute element "download")
                (= "false" (.getAttribute element "data-preload"))
                (.closest element "[data-dev-console]"))
    (try
      (let [url (js/URL. (or (.getAttribute element "data-preload-href")
                            (.getAttribute element "href")) (.-href js/location))]
        (when (and (= (.-origin url) (.-origin js/location))
                   (not= (.-pathname url) (.-pathname js/location)))
          (.-pathname url)))
      (catch :default _ nil))))

(def hinted-links "main a[rel=prev],main a[rel=next],a[data-preload=true],button[data-preload=true]")

(defn visible? [element]
  (let [rect (.getBoundingClientRect element)]
    (and (pos? (.-length (.getClientRects element)))
         (pos? (.-bottom rect)) (pos? (.-right rect))
         (< (.-top rect) (.-innerHeight js/window))
         (< (.-left rect) (.-innerWidth js/window)))))

(defn- linked-paths []
  ;; Idle work is limited to adjacent pages and explicit caller hints. Header
  ;; links acquire on intent, so a navbar does not download the entire site.
  (->> (array-seq (.querySelectorAll js/document hinted-links))
       (filter visible?)
       (keep path-for-element)
       distinct vec))

(defn intent! [event]
  (when-let [element (some-> (.-target event) (.closest "a[href],button[data-preload-href]"))]
    (when-let [path (path-for-element element)] (enqueue! [path]))))

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
        intersections (when (exists? js/IntersectionObserver)
                        (js/IntersectionObserver.
                          (fn [entries _]
                            (when (some #(.-isIntersecting %) (array-seq entries)) (schedule!)))))
        *observed (atom #{})
        observe-links! (fn []
                         (when intersections
                           (let [links (set (array-seq (.querySelectorAll js/document hinted-links)))]
                             (doseq [element @*observed :when (not (contains? links element))]
                               (.unobserve intersections element))
                             (doseq [element links :when (not (contains? @*observed element))]
                               (.observe intersections element))
                             (reset! *observed links))))
        observer (js/MutationObserver.
                   (fn [records _]
                     (when (some relevant-mutation? (array-seq records))
                       (observe-links!) (schedule!))))]
    (.observe observer (.-body js/document)
              #js {:childList true :subtree true :attributes true
                   :attributeFilter #js ["href" "data-preload-href" "data-preload" "rel"]})
    (.addEventListener js/document "pointerover" intent!)
    (.addEventListener js/document "focusin" intent!)
    (.addEventListener js/document "visibilitychange" schedule!)
    (when-not intersections (.addEventListener js/window "scroll" schedule! #js {:passive true}))
    (observe-links!)
    (schedule!)
    (fn []
      (reset! *running? false)
      (reset! *queue [])
      (.disconnect observer)
      (when intersections (.disconnect intersections))
      (.removeEventListener js/document "pointerover" intent!)
      (.removeEventListener js/document "focusin" intent!)
      (.removeEventListener js/document "visibilitychange" schedule!)
      (when-not intersections (.removeEventListener js/window "scroll" schedule!))
      (when @*timer (js/clearTimeout @*timer))
      (when @*idle (js/cancelIdleCallback @*idle)))))

(r/defc <background> []
  (rf/use-effect
    (fn []
      (let [*stop (atom nil)
            cancel! (browser-resources/after-page! #(reset! *stop (start!)))]
        (fn [] (cancel!) (when-let [stop! @*stop] (stop!)))))
    #js [])
  nil)
