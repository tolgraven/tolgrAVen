(ns website.http
  "The browser transport for re-frame effects and managed data sources."
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [website.async :as a]
            [website.http.time :as time]))

(def ^:private media-types
  {:json "application/json" :transit "application/transit+json" :text "text/plain"})

(defn- encode [format value]
  (case format
    :json (js/JSON.stringify (clj->js value))
    :transit (transit/write (transit/writer :json time/write-options) value)
    :text value))

(defn- decode [format text]
  (if (= :text format)
    text
    (when (seq text)
      (case format
        :json (js->clj (js/JSON.parse text) :keywordize-keys true)
        :transit (transit/read (transit/reader :json time/read-options) text)))))

(defn- request-url [uri params]
  (let [base (or (when (exists? js/document) (.-baseURI js/document))
                 (when (exists? js/location) (.-href js/location)))
        url (if base (js/URL. uri base) (js/URL. uri))
        query (.-searchParams url)]
    (doseq [[k value] params
            v (if (sequential? value) value [value])
            :when (some? v)]
      (.append query (name k) (if (keyword? v) (name v) (str v))))
    url))

(defn- local? [url]
  (and (exists? js/location) (= (.-origin url) (.-origin js/location))))

(defn- headers-for [url {:keys [headers response-format format params method body]}]
  (let [result (js/Headers. (clj->js (or headers {})))]
    (when-not (.has result "Accept")
      (.set result "Accept" (media-types response-format)))
    (when (and (not= :get method) (some? params) (nil? body) (not (.has result "Content-Type")))
      (.set result "Content-Type" (media-types format)))
    (when (and (local? url) (exists? js/csrfToken) (some? js/csrfToken) (not (.has result "x-csrf-token")))
      (.set result "x-csrf-token" js/csrfToken))
    result))

(defn- failed [uri status status-text failure response]
  (ex-info (str (str/upper-case (name failure)) " " uri " " status " " status-text)
           {:uri uri :status status :status-text status-text :failure failure :response response}))

(defn error-data [error]
  (or (ex-data error) {:message (.-message error) :failure :failed}))

(defn- result [uri format status status-text text]
  (let [ok? (<= 200 status 299)
        value (try
                (decode format text)
                (catch :default _
                  (if ok? (throw (failed uri status status-text :parse nil)) text)))]
    (if ok? value (throw (failed uri status status-text :error value)))))

(defn- follow-abort! [signal abort!]
  (when signal
    (if (.-aborted signal) (abort!) (.addEventListener signal "abort" abort! #js {:once true})))
  #(when signal (.removeEventListener signal "abort" abort!)))

(defn- ^:async fetch! [url {:keys [uri response-format timeout signal]} method headers body]
  (let [controller (js/AbortController.)
        timer (js/setTimeout #(.abort controller) timeout)
        detach! (follow-abort! signal #(.abort controller))]
    (try
      (let [response (await (js/fetch (.-href url)
                                #js {:method method :headers headers :body body :signal (.-signal controller)
                                     :credentials "same-origin" :mode (if (local? url) "same-origin" "cors")}))
            text (await (.text response))]
        (result uri response-format (.-status response) (.-statusText response) text))
      (catch :default error
        (cond
          (ex-data error) (throw error)
          (some-> signal .-aborted) (throw (failed uri 0 "Request aborted" :aborted nil))
          (.-aborted (.-signal controller)) (throw (failed uri 0 "Request timed out" :timeout nil))
          :else (throw (failed uri 0 (.-message error) :failed nil))))
      (finally (js/clearTimeout timer) (detach!)))))

(defn- upload! [url {:keys [uri response-format timeout on-upload-progress signal]} method headers body]
  ;; Fetch exposes download streams, but browsers still expose actual upload
  ;; progress (including multipart boundaries) through XMLHttpRequest.upload.
  (a/create
    (fn [resolve reject]
      (let [xhr (js/XMLHttpRequest.)]
        (.open xhr method (.-href url))
        (.forEach headers (fn [v k] (.setRequestHeader xhr k v)))
        (set! (.-timeout xhr) timeout)
        (set! (.-onprogress (.-upload xhr))
              (fn [event]
                (when (.-lengthComputable event)
                  (on-upload-progress {:loaded (.-loaded event) :total (.-total event)}))))
        (set! (.-onload xhr)
              #(try (resolve (result uri response-format (.-status xhr) (.-statusText xhr) (.-responseText xhr)))
                    (catch :default error (reject error))))
        (set! (.-ontimeout xhr) #(reject (failed uri 0 "Request timed out" :timeout nil)))
        (set! (.-onerror xhr) #(reject (failed uri 0 "Network error" :failed nil)))
        (set! (.-onabort xhr) #(reject (failed uri 0 "Request aborted" :aborted nil)))
        (.send xhr body)
        (set! (.-onloadend xhr) (follow-abort! signal #(.abort xhr)))))))

(defn ^:async request!
  "Resolve with the decoded CLJS body. Rejections carry HTTP status and decoded
  response in ex-data, including JSON error responses such as edit conflicts.
  GET params go into the URL. Supply :body for FormData, or :signal to cancel."
  [request]
  (let [{:keys [uri method format params body url-params on-upload-progress] :as options}
        (merge {:method :get :timeout 15000 :format :json :response-format :json} request)
        url (request-url uri (if (= :get method) (merge params url-params) url-params))
        headers (headers-for url options)
        body (when-not (= :get method) (if (some? body) body (when (some? params) (encode format params))))
        verb (str/upper-case (name method))]
    (when (some-> (:signal options) .-aborted)
      (throw (failed uri 0 "Request aborted" :aborted nil)))
    (when (and (.has headers "Authorization") (not (local? url)))
      (throw (failed uri 0 "An authenticated request requires this site's origin" :failed nil)))
    (if on-upload-progress
      (upload! url options verb headers body)
      (fetch! url options verb headers body))))
