(ns website.middleware.exception
  (:require [clojure.tools.logging :as log]
            [website.validation :as validation]
            [reitit.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]))

(defn coercion-error-handler [status]
  (fn [error _request]
    {:status status
     :body {:error (if (= status 400) "Invalid request parameters" "Invalid server response")
            :issues (if (= status 400) (validation/problems (ex-data error))
                        [{:path [] :message "The response did not match its declared schema."}])}}))

(def exception-middleware
  (exception/create-exception-middleware
   (merge exception/default-handlers
     {::exception/wrap
      (fn [handler error request]
        (if (#{::coercion/request-coercion ::coercion/response-coercion :muuntaja/decode} (:type (ex-data error)))
          ;; Coercion and decode ex-data contain complete requests. Never log
          ;; bodies, cookies or bearer credentials on public input failures.
          (log/warn (:type (ex-data error)) (:uri request))
          (log/error error (.getMessage error)))
        (handler error request))
      ::coercion/request-coercion (coercion-error-handler 400)
      ::coercion/response-coercion (coercion-error-handler 500)})))
