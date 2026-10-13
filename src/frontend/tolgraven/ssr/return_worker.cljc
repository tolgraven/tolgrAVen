(ns tolgraven.ssr.return-worker
  "Cache/Fetch adapters for local return documents. No asset or SPA interception."
  #?(:cljs (:require [tolgraven.ssr.return-policy :as policy]
            #?(:module/neovim [tolgraven.modules.neovim.policy :as neovim]))))

#?(:cljs
   (do
(def ttl-ms policy/ttl-ms)
(def max-documents policy/max-documents)
(def prefix policy/prefix)
(def build (or (aget js/self "TOLGRAVEN_RETURN_BUILD")
               (.get (.-searchParams (js/URL. (.-href js/self.location))) "build")))
(def cache-name (str prefix build))
(def origin (.-origin js/self.location))
(def arm-url (str origin "/__local-return-arm__"))
(defonce *writes (atom (js/Promise.resolve nil)))

(defn reply! [event value]
  (when-let [port (first (array-seq (.-ports event)))]
    (.postMessage port (clj->js value))))

(defn- document-response [url html saved-at]
  (js/Response. html
    (clj->js {:headers (merge #?(:module/neovim (neovim/headers-for (.-pathname (js/URL. url)))
                                :default nil)
                      {"Content-Type"  "text/html; charset=utf-8"
                       "Cache-Control" "no-store"
                       "X-Local-Page"  "1"
                       "X-Local-Saved" (str saved-at)})})))

(defn- trim! [cache]
  (-> (.keys cache)
      (.then (fn [entries]
               (->> (array-seq entries)
                    policy/excess-documents
                    (map #(.delete cache %))
                    into-array
                    js/Promise.all)))))

(defn save! [{:keys [url html] message-build :build}]
  (if-not (= message-build build)
    (js/Promise.reject (js/Error. "Incompatible return document build"))
    (-> (.open js/caches cache-name)
        (.then (fn [cache]
                 (-> (.put cache url (document-response url html (.now js/Date)))
                     (.then (fn [_] (trim! cache)))))))))

(defn arm! [url]
  (let [record (policy/arm-record url (.now js/Date))
        json (js/JSON.stringify (clj->js record))]
    (-> (.open js/caches cache-name)
        (.then #(.put % arm-url (js/Response. json))))))

(defn network! [request returning?]
  ;; Only an explicitly armed failed external return requests disk restoration.
  (let [headers (js/Headers. (.-headers request))]
    (.set headers "X-Page-Render" (if returning? "state" "ssr"))
    (js/fetch (js/Request. request (clj->js {:headers headers})))))

(defn- read-arm! [response]
  (if response
    (-> (.json response) (.then #(js->clj % :keywordize-keys true)))
    (js/Promise.resolve nil)))

(defn- saved-at [response]
  (when-let [text (when response (.get (.-headers response) "X-Local-Saved"))]
    (parse-long text)))

(defn- choose-response! [cache request saved armed]
  (let [{:keys [source returning?]}
        (policy/navigation-plan (.-url request) (saved-at saved) armed (.now js/Date))]
    (case source
      :cache (-> (js/Promise.all
                   (into-array (cond-> [(.delete cache request)]
                                 returning? (conj (.delete cache arm-url)))))
                 ;; Consume once; the new page republishes current state.
                 (.then (fn [_] saved)))
      :network (network! request returning?))))

(defn- cached-navigation! [cache request]
  (-> (js/Promise.all (into-array [(.match cache request) (.match cache arm-url)]))
      (.then (fn [entries]
               (let [[saved arm] (array-seq entries)]
                 (-> (read-arm! arm)
                     (.then #(choose-response! cache request saved %))))))))

(defn navigation! [request]
  (-> @*writes
      (.catch (fn [_] nil))
      (.then (fn [_] (.open js/caches cache-name)))
      (.then #(cached-navigation! % request))
      (.catch (fn [_] (js/fetch request)))))

(defn- activate! []
  (-> (.keys js/caches)
      (.then (fn [names]
               (->> (policy/obsolete-caches (array-seq names) cache-name)
                    (map #(.delete js/caches %))
                    into-array
                    js/Promise.all)))
      (.then (fn [_] (.claim (.-clients js/self))))))

(defn- write! [{:keys [op url arm] :as message}]
  (case op
    "save" (-> (save! message) (.then (fn [_] (when arm (arm! url)))))
    "arm" (arm! url)
    "disarm" (-> (.open js/caches cache-name) (.then #(.delete % arm-url)))
    "clear" (.delete js/caches cache-name)
    (js/Promise.resolve nil)))

(defn- message! [event]
  (let [message (js->clj (.-data event) :keywordize-keys true)
        pending (-> @*writes (.catch (fn [_] nil))
                    (.then (fn [_] (write! message))))]
    (reset! *writes pending)
    (.waitUntil event (-> pending
                         (.then (fn [_] (reply! event {:ok true})))
                         (.catch (fn [_] (reply! event {:ok false})))))))

(defn- fetch! [event]
  (let [request (.-request event)
        descriptor {:url    (.-url request)
                    :origin (.-origin (js/URL. (.-url request)))
                    :method (.-method request)
                    :mode   (.-mode request)}]
    (when (policy/intercept? descriptor origin)
      (.respondWith event (navigation! request)))))

(defn init! []
  (.addEventListener js/self "install"
    (fn [event] (.waitUntil event (.skipWaiting js/self))))
  (.addEventListener js/self "activate" (fn [event] (.waitUntil event (activate!))))
  (.addEventListener js/self "message" message!)
  (.addEventListener js/self "fetch" fetch!))

))
