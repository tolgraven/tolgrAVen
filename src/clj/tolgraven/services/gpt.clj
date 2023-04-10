(ns tolgraven.services.gpt
  (:require
   [tolgraven.config :refer [env]]
   [clojure.data.json :as json]
   [clj-http.client :as client]))

(defn ask
  [prompt]
  (let [body {:messages [{:role "user"
                          :content prompt}]
              :model "gpt-3.5-turbo"
              :max_tokens 200
              :temperature 0.9}]
    (-> (client/post "https://api.openai.com/v1/chat/completions"
                     {:content-type :json
                      :accept :json
                      :headers {:Authorization (str "Bearer " (env :openai-api-key))}
                      :body (-> body
                                json/write-str)})
        :body
        json/read-str)))
