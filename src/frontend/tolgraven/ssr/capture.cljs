(ns tolgraven.ssr.capture
  "Lazy transport adapter. Rendering runs against the same cached browser chunks
   in a worker; application state and publication remain in ssr.local events."
  (:require [tolgraven.loader :as loader]
            [tolgraven.loader.styles :as styles]
            [tolgraven.browser-resources :as browser-resources]
            [tolgraven.component.storage :as storage]
            [tolgraven.render-context :as context]
            [tolgraven.ssr.return-contract :as contract]
            [tolgraven.ssr.local :as local]))

(defonce *worker (atom nil))
(defonce *pending (atom {}))
(defonce *request (atom 0))

(defn stop! []
  (when-let [worker @*worker] (.terminate worker))
  (reset! *worker nil)
  (doseq [[_ {:keys [reject timeout]}] @*pending]
    (js/clearTimeout timeout)
    (reject (js/Error. "Local page rendering stopped")))
  (reset! *pending {}))

(defn- worker! [build]
  (or @*worker
      (let [worker (js/Worker. (str "/js/page-render-worker.js?build=" (js/encodeURIComponent build)))]
        (set! (.-onmessage worker)
          (fn [event]
            (let [^js data (.-data event) id (.-id data)]
              (when-let [{:keys [resolve reject timeout]} (get @*pending id)]
                (swap! *pending dissoc id)
                (js/clearTimeout timeout)
                (if (.-error data)
                  (reject (js/Error. "Local page rendering unavailable"))
                  (resolve (js->clj (.-pair data) :keywordize-keys true)))))))
        (set! (.-onerror worker) (fn [_] (stop!)))
        (reset! *worker worker)
        worker)))

(defn asset-plan [scripts ids]
  ;; Dependencies execute first, using the actual compiler manifest.
  (let [*seen (atom #{})]
    (letfn [(ordered [id]
              (when-not (contains? @*seen id)
                (let [asset (get scripts id)]
                  (when-not asset (throw (ex-info "Missing render module" {:module id})))
                  (swap! *seen conj id)
                  (concat (mapcat ordered (:depends asset))
                          [{:id (name id) :url (:url asset)}]))))]
      (vec (mapcat ordered (sort ids))))))

(defn- browser-pair! [db config code?]
  ;; Leaflet's browser entry needs a real DOM. Development's entry additionally
  ;; starts debugging tools. Preserve capture for these cases in painted idle.
  (-> (js/Promise. (fn [resolve _] (browser-resources/after-page! resolve)))
      (.then (fn [_]
               (js/Promise.all
                 (into-array (cond-> [(loader/acquire-code! :page-render)]
                               code? (conj (loader/acquire-code! :highlight)))))))
      (.then (fn [specs] ((:pair! (aget specs 0)) db config)))))

(defn pair! [db {:keys [scripts template build] :as config}]
  (let [modules (vec (remove #{:page-capture :page-render :coercion} (keys (local/modules))))
        code? (boolean (.querySelector js/document "#app .code-block, #app .code-highlight"))]
    (if (or ^boolean goog.DEBUG (not (exists? js/Worker)) (some #{:maps} modules))
      (browser-pair! db config code?)
      (let [scripts (into {} (map (fn [[id asset]] [id (update asset :depends #(mapv keyword %))])) scripts)
            request {:state (contract/state-for db)
                     :route (select-keys (:common/route db) [:path :query-params :parameters])
                     :modules modules
                     :snapshot @context/*snapshot
                     :styles (styles/manifest)
                     :template template
                     :build build
                     :owner @storage/*identity
                     :url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
                     :title (.-title js/document)}
            encoded (pr-str request)
            assets (asset-plan scripts (cond-> (conj (set modules) :page-render) code? (conj :highlight)))]
        (when (> (contract/byte-count encoded) (* 2 contract/max-bytes))
          (throw (js/Error. "Local page request exceeds cache budget")))
        (js/Promise.
          (fn [resolve reject]
            (let [id (swap! *request inc)
                  timeout (js/setTimeout
                            (fn []
                              (swap! *pending dissoc id)
                              (reject (js/Error. "Local page rendering timed out"))) 10000)]
              (swap! *pending assoc id {:resolve resolve :reject reject :timeout timeout})
              (try
                (.postMessage (worker! build) (clj->js {:id id :assets assets :request encoded}))
                (catch :default error
                  (swap! *pending dissoc id)
                  (js/clearTimeout timeout)
                  (reject error))))))))))
