(ns tolgraven.supabase.operations
  (:require [tolgraven.validation :as validation]
            [tolgraven.supabase.schema :as schema]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]))

(defn- fail! [status message]
  (throw (ex-info message {:auth/status status})))

(defn- request! [schema data]
  ;; Also protect direct callers; authorization/RLS is independent of shape.
  (when-let [issues (validation/explain schema data)]
    (throw (ex-info (validation/message issues)
                    {:auth/status 400 :issues issues})))
  data)

(defn- rpc! [function parameters]
  (try
    (:body (platform/request! :post (str "rpc/" function)
                              {:content-type :json :form-params parameters}))
    (catch clojure.lang.ExceptionInfo error
      (if-let [[status message] ({"PT400" [400 "Invalid comment, reply, or vote"]
                                  "PT403" [403 "You cannot edit or vote on this comment"]
                                  "PT404" [404 "Post or comment not found"]}
                                (get-in (ex-data error) [:body :code]))]
        (fail! status message)
        (throw error)))))

(defn post-chat! [user data]
  (request! schema/chat-write data)
  (rpc! "tolgraven_post_chat" {:p_actor (auth/profile-id user)
                               :p_text (:text data)}))

(defn create-comment! [user data]
  (request! schema/comment-create data)
  (rpc! "tolgraven_create_comment"
        {:p_actor (auth/profile-id user) :p_post_id (:post-id data)
         :p_parent_id (:parent-id data)
         :p_text (:text data) :p_title (:title data)}))

(defn edit-comment! [user data]
  (request! schema/comment-edit data)
  (rpc! "tolgraven_edit_comment"
        {:p_actor (auth/profile-id user) :p_comment_id (:comment-id data)
         :p_text (:text data) :p_title (:title data)}))

(def vote-values {"up" 1 "down" -1 "none" 0})

(defn set-comment-vote! [user data]
  (request! schema/vote-write data)
  (let [comment-id (:comment-id data)
        vote (vote-values (:vote data))]
    (rpc! "tolgraven_set_comment_vote"
          {:p_actor (auth/profile-id user) :p_comment_id comment-id :p_vote vote
           :p_legacy_vote (get (:comment-votes (auth/profile! user)) comment-id 0)})))


(defn save-post! [user data]
  (request! schema/post-write data)
  (rpc! "tolgraven_save_post"
        {:p_actor (auth/ensure-profile! user) :p_post_id (:post-id data)
         :p_title (:title data) :p_text (:text data)
         :p_tags (:tags data)}))

(defn save-document! [user {:keys [path data merge-fields] :as request}]
  (request! schema/document-write request)
  (rpc! "tolgraven_save_document"
        {:p_actor (auth/ensure-profile! user) :p_collection (name (first path))
         :p_doc_id (str (second path)) :p_data (dissoc data :user)
         :p_merge (some? merge-fields)}))
