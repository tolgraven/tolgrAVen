(ns tolgraven.ssr.return-worker
  "Local return documents only. No asset caching or interception of SPA reads."
  (:require [clojure.string :as string]))

(def ttl-ms 1800000)
(def max-documents 8)
(def prefix "tolgraven-return-v1-")
(def build (or (aget js/self "TOLGRAVEN_RETURN_BUILD")
               (.get (.-searchParams (js/URL. (.-href js/self.location))) "build")))
(def cache-name (str prefix build))
(def arm-url (str (.-origin js/self.location) "/__local-return-arm__"))
(defonce *writes (atom (js/Promise.resolve nil)))

(defn reply! [event value]
  (when-let [port (aget (.-ports event) 0)] (.postMessage port (clj->js value))))

(defn save! [{:keys [url html] message-build :build}]
  (if-not (= message-build build)
    (js/Promise.reject (js/Error. "Incompatible return document build"))
    (-> (.open js/caches cache-name)
      (.then (fn [cache]
               (-> (.put cache url
                         (js/Response. html #js {:headers #js {"Content-Type" "text/html; charset=utf-8"
                                                              "Cache-Control" "no-store"
                                                              "X-Local-Page" "1"
                                                              "X-Local-Saved" (str (.now js/Date))}}))
                   (.then #(.keys cache))
                   (.then (fn [keys]
                            (js/Promise.all
                             (into-array (map #(.delete cache %) (take (max 0 (- (.-length keys) max-documents)) (array-seq keys)))))))))))))

(defn arm! [url]
  (-> (.open js/caches cache-name)
      (.then #(.put % arm-url (js/Response. (js/JSON.stringify #js {:url url :expires (+ (.now js/Date) ttl-ms)}))))))

(defn network! [request returning?]
  ;; Without an exact local pair, ordinary requests still use network SSR.
  ;; Only an explicitly armed failed external return requests disk restoration.
  (let [headers (js/Headers. (.-headers request))]
    (.set headers "X-Page-Render" (if returning? "state" "ssr"))
    (js/fetch (js/Request. request #js {:headers headers}))))

(defn navigation! [request]
  (-> @*writes
      (.catch (fn [_] nil))
      (.then (fn [_] (.open js/caches cache-name)))
      (.then (fn [cache]
               (-> (js/Promise.all #js [(.match cache request) (.match cache arm-url)])
                   (.then (fn [entries]
                            (let [saved (aget entries 0)
                                  armed (aget entries 1)]
                              (-> (if armed (.json armed) (js/Promise.resolve nil))
                                  (.then (fn [armed]
                                           (let [returning? (and armed (= (.-url armed) (.-url request))
                                                                 (> (.-expires armed) (.now js/Date)))
                                                 fresh? (and saved
                                                             (< (- (.now js/Date)
                                                                   (js/Number (.get (.-headers saved) "X-Local-Saved")))
                                                                ttl-ms))]
                                             (if fresh?
                                               ;; Reload/address-bar navigation needs the same
                                               ;; paired layout as an external history return.
                                               ;; Consume once; the new page republishes current state.
                                               (-> (js/Promise.all
                                                     (into-array (cond-> [(.delete cache request)]
                                                                   returning? (conj (.delete cache arm-url)))))
                                                   (.then (fn [_] saved)))
                                               (network! request returning?))))))))))))
      (.catch (fn [_] (js/fetch request)))))

(defn init! []
  (.addEventListener js/self "install" (fn [event] (.waitUntil event (.skipWaiting js/self))))
  (.addEventListener js/self "activate"
    (fn [event]
      (.waitUntil event
        (-> (.keys js/caches)
            (.then (fn [names]
                     (js/Promise.all (into-array (for [name (array-seq names)
                                                     :when (and (string/starts-with? name prefix) (not= name cache-name))]
                                                 (.delete js/caches name))))))
            (.then #(.claim (.-clients js/self)))))))
  (.addEventListener js/self "message"
    (fn [event]
      (let [{:keys [op url arm] :as message} (js->clj (.-data event) :keywordize-keys true)
            pending (-> @*writes (.catch (fn [_] nil))
                        (.then (fn [_]
                                 (case op
                                   "save" (-> (save! message) (.then (fn [_] (when arm (arm! url)))))
                                   "arm" (arm! url)
                                   "disarm" (-> (.open js/caches cache-name) (.then #(.delete % arm-url)))
                                   "clear" (.delete js/caches cache-name)
                                   (js/Promise.resolve nil)))))]
        (reset! *writes pending)
        (.waitUntil event (-> pending (.then #(reply! event {:ok true}))
                             (.catch #(reply! event {:ok false})))))))
  (.addEventListener js/self "fetch"
    (fn [event]
      (let [request (.-request event)]
        (when (and (= "navigate" (.-mode request)) (= "GET" (.-method request))
                   (= (.-origin js/self.location) (.-origin (js/URL. (.-url request)))))
          (.respondWith event (navigation! request)))))))
