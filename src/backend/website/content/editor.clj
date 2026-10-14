(ns website.content.editor
  "Administrator-only field writes. CMS credentials never enter the browser."
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [website.config :as config]
            [website.content.service :as content]
            [website.content.contract :as contract]
            [website.content.editor-contract :as editor]
            [website.supabase.auth :as auth]
            [website.media.pipeline :as media]
            [website.build.site :refer [when-enabled]]))

(defn settings []
  {:url (:url (content/settings))
   :token (:strapi-write-token config/env)
   :admin-url (or (:strapi-admin-url config/env)
                  (when-let [url (:url (content/settings))]
                    (str (string/replace url #"/+$" "") "/admin")))})

(defn- write! [request! endpoint section data conflict-message]
  (let [{:keys [url token]} (settings)]
    (when-not (and (seq url) (seq token))
      (throw (ex-info "CMS writes are not configured" {:status 503})))
    (let [response (request! (str (string/replace url #"/+$" "") endpoint)
                     {:headers {"Authorization" (str "Bearer " token)}
                      :content-type :json
                      :form-params data
                      :as :json
                      :coerce :always
                      :throw-exceptions false
                      :redirect-strategy :none
                      :conn-timeout 3000
                      :socket-timeout 15000})]
      (when-not (= 200 (:status response))
        (let [current (when (= 409 (:status response))
                        (-> (contract/checked-bundle (:body response) [section])
                            (update :content content/enrich-media!)))]
          (when current (content/install-edited! (:content current)))
          (throw (ex-info (case (:status response)
                         409 conflict-message
                         400 "This field cannot be edited inline. Use Strapi for configuration."
                         "Unable to save content. Your draft is retained.")
                       (cond-> {:status (if (#{400 409} (:status response)) (:status response) 503)}
                         current (assoc :content-bundle current))))))
      (let [bundle (contract/checked-bundle (:body response) [section])
            content' (content/enrich-media! (:content bundle))]
        (content/install-edited! content')
        (assoc bundle :content content')))))

(defn save! [{:keys [path] :as data}]
  (let [section (first (contract/validate-keys [(first (editor/data-path path))]))]
    (write! http/put "/api/site-content/field" section data
            "This field changed in Strapi. Use the latest value before editing again.")))

(when-enabled :cv nil
  (defn create-cv! [data]
    (write! http/post "/api/site-content/cv-entry" :cv data
            "The timeline changed. Review its current entries, then reopen Add entry.")))

(defn response! [request f]
  (try
    (media/admin! (auth/current-user! request))
    {:status 200 :headers {"Cache-Control" "private, no-store"} :body (f)}
    (catch Exception error
      {:status (or (:auth/status (ex-data error)) (:status (ex-data error)) 503)
       :headers {"Cache-Control" "private, no-store"}
       :body (cond-> {:error (if (or (:auth/status (ex-data error)) (:status (ex-data error)))
                              (.getMessage error) "Content editing is temporarily unavailable")}
               (:content-bundle (ex-data error)) (assoc :content-bundle (:content-bundle (ex-data error))))})))

(defn routes []
  (vec (remove nil?
      [["/content/editor"
       {:get {:responses {200 {:body editor/settings}}
              :handler (fn [request]
                         (response! request
                           #(let [{:keys [url token admin-url]} (settings)]
                              {:admin-url admin-url, :writable? (boolean (and (seq url) (seq token)))})))}}]
      ["/content/field"
       {:put {:parameters {:body editor/write-request}
              :handler (fn [request]
                         (response! request #(save! (get-in request [:parameters :body]))))}}]
      (when-enabled :cv nil
        ["/content/cv-entry"
         {:post {:parameters {:body editor/create-cv-entry}
                 :handler (fn [request]
                            (response! request #(create-cv! (get-in request [:parameters :body]))))}}])])))
