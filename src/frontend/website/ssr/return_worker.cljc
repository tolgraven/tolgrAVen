(ns website.ssr.return-worker
  "Cache/Fetch adapters for local return documents. No asset or SPA interception."
  #?(:cljs (:require [website.clock :as clock]
                     [website.async :as a]
                     [website.ssr.return-policy :as policy]
                     #?(:module/neovim [website.modules.neovim.policy :as neovim]))))

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
(defonce *writes (atom (a/resolved nil)))

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

(defn- ^:async trim! [cache]
  (let [entries (await (.keys cache))]
    (a/all (map #(.delete cache %) (policy/excess-documents (array-seq entries))))))

(defn ^:async save! [{:keys [url html] message-build :build}]
  (when-not (= message-build build)
    (throw (js/Error. "Incompatible return document build")))
  (let [cache (await (.open js/caches cache-name))]
    (await (.put cache url (document-response url html (clock/epoch-ms))))
    (trim! cache)))

(defn arm! [url]
  (let [record (policy/arm-record url (clock/epoch-ms))
        json (js/JSON.stringify (clj->js record))]
    (-> (.open js/caches cache-name)
        (a/then #(.put % arm-url (js/Response. json))))))

(defn network! [request returning?]
  ;; Only an explicitly armed failed external return requests disk restoration.
  (let [headers (js/Headers. (.-headers request))]
    (.set headers "X-Page-Render" (if returning? "state" "ssr"))
    (js/fetch (js/Request. request (clj->js {:headers headers})))))

(defn- read-arm! [response]
  (if response
    (-> (.json response) (a/then #(js->clj % :keywordize-keys true)))
    (a/resolved nil)))

(defn- saved-at [response]
  (when-let [text (when response (.get (.-headers response) "X-Local-Saved"))]
    (parse-long text)))

(defn- ^:async choose-response! [cache request saved armed]
  (let [{:keys [source returning?]}
        (policy/navigation-plan (.-url request) (saved-at saved) armed (clock/epoch-ms))]
    (case source
      :cache (do
               ;; Consume once. The new page republishes current state.
               (await (a/all (cond-> [(.delete cache request)]
                               returning? (conj (.delete cache arm-url)))))
               saved)
      :network (network! request returning?))))

(defn- ^:async cached-navigation! [cache request]
  (let [[saved arm] (await (a/all [(.match cache request) (.match cache arm-url)]))]
    (choose-response! cache request saved (await (read-arm! arm)))))

(defn navigation! [request]
  (-> @*writes
      (a/catch-error (fn [_] nil))
      (a/then (fn [_] (.open js/caches cache-name)))
      (a/then #(cached-navigation! % request))
      (a/catch-error (fn [_] (js/fetch request)))))

(defn- ^:async activate! []
  (let [names (array-seq (await (.keys js/caches)))
        obsolete (policy/obsolete-caches names cache-name)]
    (await (a/all (map #(.delete js/caches %) obsolete)))
    (.claim (.-clients js/self))))

(defn- write! [{:keys [op url arm] :as message}]
  (case op
    "save" (-> (save! message) (a/then (fn [_] (when arm (arm! url)))))
    "arm" (arm! url)
    "disarm" (-> (.open js/caches cache-name) (a/then #(.delete % arm-url)))
    "clear" (.delete js/caches cache-name)
    (a/resolved nil)))

(defn- message! [event]
  (let [message (js->clj (.-data event) :keywordize-keys true)
        pending (-> @*writes (a/catch-error (fn [_] nil))
                    (a/then (fn [_] (write! message))))]
    (reset! *writes pending)
    (.waitUntil event (-> pending
                         (a/then (fn [_] (reply! event {:ok true})))
                         (a/catch-error (fn [_] (reply! event {:ok false})))))))

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
