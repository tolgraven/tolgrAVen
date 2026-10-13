(ns website.modules.contact.api
  (:require [website.schema.http :as schemas]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as timbre]))

(defn routes []
  (vec (remove nil?
   [["/send-contact-email"
    {:post {:summary "Send email to self and contact"
            :parameters {:body schemas/contact}
            ; :responses {200 {:body {:reply string?}}}
            :handler
            (fn [{{{:keys [name email title message]} :body} :parameters :as params}]
              (let [uri (str "https://script.google.com/macros/s/"
                             (System/getenv "GOOGLE_SENDMAIL_SCRIPT")
                             "/exec")
                    reply (http/post uri
                                     {:body (json/write-str {:name name
                                                             :email email
                                                             :title title
                                                             :message message})
                                      :headers {"Content-Type" "text/plain;charset=utf-8"}
                                      :redirect "follow"})]
                (timbre/debug "her emailz... " email)
                (timbre/debug "reply is " reply)
                {:status 200
                 :body reply}))}}]])))
