(ns tolgraven.ssr.local
  "Paired local HTML/state return snapshots. The service worker delivers an
   ordinary document; React owns its markup from hydration onward."
  (:require [ajax.core :as ajax]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [re-frame.db :as rfdb]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.loader :as loader]
            [tolgraven.db :as db]
            [tolgraven.render-context :as context]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.storage :as storage]
            [tolgraven.ssr.contract :as ssr-contract]
            [tolgraven.ssr.return-contract :as contract]
            [tolgraven.ssr.render :as render]
            [tolgraven.views.page :as page]))

(defonce *connection (atom nil))
(defonce *pending (atom nil))
(declare clear!)

(defn active! [registration]
  (let [worker (or (.-installing registration) (.-waiting registration) (.-active registration))]
    (js/Promise.
      (fn [resolve reject]
        (let [*timeout (atom nil)]
          (letfn [(finish! [error]
                    (js/clearTimeout @*timeout)
                    (.removeEventListener worker "statechange" changed!)
                    (if error (reject error) (resolve worker)))
                  (changed! []
                  (case (.-state worker)
                    "activated" (finish! nil)
                    "redundant" (finish! (js/Error. "Local worker was replaced"))
                    nil))]
          (reset! *timeout (js/setTimeout #(finish! (js/Error. "Local worker activation timed out")) 10000))
          (.addEventListener worker "statechange" changed!)
          (changed!)))))))

(defn message! [worker payload]
  (js/Promise.
    (fn [resolve reject]
      (let [channel (js/MessageChannel.)
            finish! (fn [value]
                      (.close (.-port1 channel))
                      (.close (.-port2 channel))
                      (if value (resolve nil) (reject (js/Error. "Local page cache unavailable"))))
            timeout (js/setTimeout #(finish! false) 3000)]
        (set! (.-onmessage (.-port1 channel))
              (fn [event] (js/clearTimeout timeout) (finish! (.-ok (.-data event)))))
        (.postMessage worker (clj->js payload) #js [(.-port2 channel)])))))

(defn connect! []
  (or @*connection
      (let [pending
            (js/Promise.
              (fn [resolve reject]
                (if-not (and (exists? js/window) js/window.isSecureContext
                             (.-serviceWorker js/navigator))
                  (resolve nil)
                  (ajax/GET "/api/page-return-template"
                    {:response-format (ajax/json-response-format {:keywords? true}) :timeout 10000
                     :handler (fn [{:keys [build] :as config}]
                                (if (and (not ^boolean goog.DEBUG)
                                           (not= build (some-> (.querySelector js/document "meta[name=app-build]")
                                                              (.getAttribute "content"))))
                                  (reject (js/Error. "Page build changed; reload before saving a local document"))
                                (-> (.register (.-serviceWorker js/navigator)
                                               (str "/return-worker.js?build=" (js/encodeURIComponent build))
                                               #js {:scope "/" :updateViaCache "none"})
                                    (.then active!)
                                    (.then (fn [worker] (resolve (assoc config :worker worker))))
                                    (.catch reject))))
                     :error-handler reject}))))]
        (let [retryable (-> pending (.catch (fn [error] (reset! *connection nil) (throw error))))]
          (reset! *connection retryable)
          retryable))))

(defn modules []
  (into {} (keep (fn [[id loadable]]
                  (when (loader/ready? id) [id @loadable]))) loader/modules))

(defn pair! [db {:keys [template build]}]
  (let [url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
        state (contract/state-for db)
        encoded (pr-str state)
        ;; Reject non-EDN values rather than hydrating with silently missing data.
        _ (when (or (> (contract/byte-count encoded) contract/max-bytes) (not= state (reader/read-string encoded)))
            (throw (js/Error. "Page state cannot be cached")))
        snapshot {:version contract/version :url url :build build :saved-at (.now js/Date)
                  :owner @storage/*identity
                  :state-edn encoded :modules (vec (keys (modules)))
                  :document-title (.-title js/document)}
        render-db (-> (ssr-contract/merge-state db/data state)
                      (assoc :common/route (:common/route db) :loader (:loader db)
                             :routes (:routes db) :page/commit nil))
        html (render/html! render-db [page/<page>]
                          {:modules (modules) :restored? true :interactive? true
                           :snapshot @context/*snapshot})
        document (contract/document template html (js/JSON.stringify (clj->js snapshot)) (.-title js/document))]
    (when (> (contract/byte-count document) contract/max-bytes) (throw (js/Error. "Page document exceeds cache budget")))
    {:op "save" :url url :build build :html document :state state}))

(defn save! [db arm?]
  ;; Rendering and transport belong to this effect, never to a subscription.
  (-> (connect!)
      (.then (fn [{:keys [worker] :as config}]
               (when worker
                 (let [pair (pair! db config)]
                   (-> (message! worker (assoc (dissoc pair :state) :arm arm?))
                       (.then (fn [_]
                                (rf/dispatch [:page-return/status {:status :ready :url (:url pair)}]))))))))
      ;; Persistence is an optional enhancement. Existing state restoration and
      ;; normal network navigation remain available when storage is denied.
      (.catch (fn [error]
                (when arm? (clear!))
                (rf/dispatch [:page-return/status {:status :unavailable :message (str error)}])))))

(rf/reg-event-fx :page-return/save
  (fn [{:keys [db]} [_ arm?]] {:page-return/save {:db db :arm? arm?}}))
(rf/reg-fx :page-return/save (fn [{:keys [db arm?]}] (save! db arm?)))
(rf/reg-event-fx :page-return/install
  (fn [{:keys [db]} [_ state]]
    {:db (ssr-contract/merge-state db state) :page-return/consume state}))
(rf/reg-fx :page-return/consume storage/consume-restored!)
(rf/reg-event-db :page-return/status (fn [db [_ status]] (assoc db :page-return status)))
(rf/reg-sub :page-return/status (fn [db _] (:page-return db)))
(rf/reg-event-fx :page-return/clear (fn [_ _] {:page-return/clear true}))
(rf/reg-fx :page-return/clear (fn [_] (clear!)))

(defn install! []
  (when-let [element (.getElementById js/document "local-page-bootstrap")]
    (let [snapshot (-> (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)
                       (update :modules #(mapv keyword %)))
          url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
          state (reader/read-string (:state-edn snapshot))]
      (when-not (and (map? state) (contract/valid? snapshot
                                   {:url url :build (.getAttribute (.getElementById js/document "app") "data-page-build")
                                    :now (.now js/Date)}))
        (throw (js/Error. "Invalid local page snapshot")))
      ;; The cached document was rendered in restoration mode. This same mode
      ;; is required for its first render, even if it was reached via a reload.
      (restore/begin! {:hydrate? true :back? true})
      (swap! restore/*context assoc :local? true)
      (rf/dispatch-sync [:page-return/install state])
      (reset! context/*interactive? false)
      snapshot)))

(defn prepare! [snapshot]
  (js/Promise.all
    (into-array (map loader/acquire-code! (:modules snapshot)))))

(defn schedule! []
  (when @*pending (js/clearTimeout @*pending))
  (reset! *pending
    (js/setTimeout (fn [] (reset! *pending nil) (rf/dispatch [:page-return/save false])) 500)))

(defn clear! []
  (-> (connect!) (.then (fn [{:keys [worker]}] (when worker (message! worker {:op "clear"}))))
      (.catch (fn [_] nil))))

(defn resumed! [event]
  (when (.-persisted event)
    (-> (connect!)
        (.then (fn [{:keys [worker]}] (when worker (message! worker {:op "disarm"}))))
        (.catch (fn [_] nil)))))

(defn external-click! [event]
  (when (and (= 0 (.-button event)) (not (.-defaultPrevented event))
             (not (or (.-metaKey event) (.-ctrlKey event) (.-shiftKey event) (.-altKey event))))
    (when-let [anchor (some-> (.-target event) (.closest "a[href]"))]
      (let [url (js/URL. (.-href anchor))]
        (when (and (not (.hasAttribute anchor "download"))
                   (#{"http:" "https:"} (.-protocol url))
                   (not= (.-origin url) (.-origin js/location))
                   (or (= "" (.-target anchor)) (= "_self" (.-target anchor))))
          (storage/save-navigation!)
          (rf/dispatch-sync [:page-return/save true]))))))

(defc <capture> {:profile false} []
  (rf/use-effect
    (fn []
      (-> (connect!) (.catch (fn [_] nil)))
      (schedule!)
      (add-watch rfdb/app-db ::capture
                 (fn [_ _ before after]
                   ;; Compare persistent source references before traversing or
                   ;; serializing anything. Trace/debug events do no cache work.
                   (when (not= (contract/source-for before) (contract/source-for after)) (schedule!))))
      (add-watch storage/*identity ::capture
                 (fn [_ _ before after] (when (not= before after) (clear!))))
      (.addEventListener js/document "click" external-click! true)
      (.addEventListener js/window "pageshow" resumed!)
      (fn []
        (remove-watch rfdb/app-db ::capture)
        (remove-watch storage/*identity ::capture)
        (.removeEventListener js/document "click" external-click! true)
        (.removeEventListener js/window "pageshow" resumed!)
        (when @*pending (js/clearTimeout @*pending) (reset! *pending nil)))) #js [])
  nil)
