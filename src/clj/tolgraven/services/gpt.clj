(ns tolgraven.services.gpt
  (:require
   [tolgraven.config :refer [env]]
   [clojure.data.json :as json]
   [taoensso.timbre :as timbre]
   [clj-http.client :as client]))

(def base-url "https://api.openai.com/v1/")
(defonce temp (atom 0.9))


(defn get-body
  [messages & [m]]
  (merge {:messages (for [[message role] (partition 2 (interleave messages
                                                                  (flatten (repeat ["user" "assistant"]))))]
                      {:role role
                       :content message})
          :model "gpt-3.5-turbo"
          :temperature @temp}
         m))

(defn get-request
  [body]
  {:content-type :json
   :accept :json
   :headers {:Authorization (str "Bearer " (env :openai-api-key))}
   :body (json/write-str body)})

(defn send-request
  [request]
  (timbre/debug request)
  (client/post (str base-url "chat/completions")
               request))

(defn get-response
  [response]
  (-> response
      :body
      json/read-str))

(defn ask
  [prompt]
  (get-response (send-request (get-request (get-body [prompt])))))

(defn chat
  [messages]
  (-> messages
      get-body
      get-request
      send-request
      get-response))
