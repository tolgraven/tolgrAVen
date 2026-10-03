(ns tolgraven.supabase.auth
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.store.contract :as contract]))

(defn- fail! [status message]
  (throw (ex-info message {:auth/status status})))

(defn current-user! [request]
  (let [authorization (get-in request [:headers "authorization"])]
    (when-not (and (string? authorization)
                   (re-matches #"(?i)Bearer [^\s]+" authorization))
      (fail! 401 "Sign in to continue"))
    ;; Validate with Auth, not a locally decoded JWT or client-provided user.
    (let [response (http/get (str (string/replace (platform/rest-base-url) #"/+$" "")
                                  "/auth/v1/user")
                             {:headers {"apikey" (platform/service-key)
                                        "Authorization" authorization}
                              :as :json :coerce :always :throw-exceptions false
                              :conn-timeout 3000 :socket-timeout 5000})
          user (:body response)]
      (when-not (= 200 (:status response))
        (fail! (if (#{401 403} (:status response)) 401 503)
               "Unable to verify your session"))
      (when-not (and (seq (:id user)) (not (:is_anonymous user)))
        (fail! 401 "A registered account is required"))
      user)))

(defn profile-id [user]
  ;; app_metadata can only be assigned by the administrator. Never use
  ;; user_metadata, an email supplied by the caller, or a request path here.
  (let [id (or (get-in user [:app_metadata :site_user_id]) (:id user))]
    (when-not (and (string? id) (re-matches #"[A-Za-z0-9_-]+" id))
      (fail! 403 "Invalid profile linkage"))
    id))

(defn legacy-comment-votes [voted]
  (reduce-kv
   (fn [votes path direction]
     (try
       (let [path (edn/read-string (if (keyword? path) (name path) path))
             direction (case direction (:up "up") 1 (:down "down") -1 0)]
         (if (and (vector? path) (< 1 (count path))
                  (or (string? (last path)) (integer? (last path))))
           (assoc votes (str (last path)) direction)
           votes))
       (catch Exception _ votes)))
   {} (or voted {})))

(defn- comment-votes! [id legacy-votes]
  ;; Private history is returned only to its verified owner. Page it independently
  ;; of public profile queries, which cannot select the ledger or imported votes.
  (loop [offset 0 votes (legacy-comment-votes legacy-votes)]
    (let [rows (:body (platform/request! :get "comment_votes"
                       {:query-params {"user_id" (str "eq." id)
                                       "select" "comment_id,vote" "order" "comment_id.asc"
                                       "offset" offset "limit" 500}}))]
      (if (seq rows)
        (recur (+ offset (count rows))
               (reduce (fn [result row] (assoc result (:comment_id row) (:vote row))) votes rows))
        votes))))

(defn profile! [user]
  (let [id (profile-id user)
        row (first (:body (platform/request! :get "site_users"
                           {:query-params {"id" (str "eq." id) "select" "*" "limit" 1}})))]
    (assoc (if row
             (get-in (contract/seed->contract {:users [row]}) ["users" id])
             {:id id :name "" :avatar nil :bg-color nil :comment-count 0 :karma 0})
           :comment-votes (comment-votes! id (:voted row)))))

(def profile-fields {:name :name :avatar :avatar :bg-color :bg_color})

(defn save-profile! [user data]
  (when-not (and (map? data)
                 (every? profile-fields (keys data))
                 (every? #(or (nil? %) (and (string? %) (<= (count %) 2048))) (vals data)))
    (fail! 400 "Only name, avatar, and bg-color can be updated"))
  (let [id (profile-id user)
        fields (into {} (map (fn [[k v]] [(profile-fields k) v])) data)]
    ;; Only provided columns participate in the upsert, preserving scores,
    ;; imported data and simultaneous changes to other profile fields.
    (platform/request! :post "site_users"
                       {:headers {"Prefer" "resolution=merge-duplicates,return=minimal"}
                        :content-type :json
                        :form-params (assoc fields :id id)
                        :query-params {"on_conflict" "id"}})
    (profile! user)))

(defn response! [request action]
  (try
    {:status 200 :body (action (current-user! request))}
    (catch clojure.lang.ExceptionInfo error
      (if-let [status (:auth/status (ex-data error))]
        {:status status :body {:error (.getMessage error)}}
        (throw error)))))
