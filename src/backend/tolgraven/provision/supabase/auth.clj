(ns tolgraven.provision.supabase.auth
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [tolgraven.platform.supabase :as platform]))

(defn- admin-request! [method account-id opts]
  (let [key (platform/service-key)
        result (http/request
                (merge {:method method
                        :url (str (string/replace (platform/rest-base-url) #"/+$" "")
                                  "/auth/v1/admin/users/" account-id)
                        :headers {"apikey" key "Authorization" (str "Bearer " key)}
                        :as :json :coerce :always :throw-exceptions false
                        :conn-timeout 3000 :socket-timeout 5000}
                       opts))]
    (when-not (<= 200 (:status result) 299)
      (throw (ex-info "Supabase account linkage failed" {:status (:status result)})))
    (:body result)))

(defn link-user! [account-id profile-id]
  (when-not (and (string? account-id)
                 (re-matches #"[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}" account-id)
                 (string? profile-id) (re-matches #"[A-Za-z0-9_-]+" profile-id))
    (throw (ex-info "Expected a Supabase account UUID and existing profile ID" {})))
  (let [user (admin-request! :get account-id {})
        profile (first (:body (platform/request! :get "site_users"
                               {:query-params {"id" (str "eq." profile-id)
                                               "select" "id,email" "limit" 1}})))
        previous (get-in user [:app_metadata :site_user_id])
        email (some-> (:email user) string/lower-case)]
    (when-not (and profile (:email_confirmed_at user) (seq email)
                   (= email (some-> (:email profile) string/lower-case)))
      (throw (ex-info "Linkage requires a confirmed account with the same email as the imported profile" {})))
    (when (and previous (not= previous profile-id))
      (throw (ex-info "Account already links to a different profile" {})))
    (admin-request! :put account-id
                    {:content-type :json
                     :form-params {:app_metadata (assoc (:app_metadata user) :site_user_id profile-id)}})
    {:account-id account-id :profile-id profile-id :linked? true}))
