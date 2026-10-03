(ns tolgraven.supabase.operations
  (:require [clojure.string :as string]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]))

(defn- fail! [status message]
  (throw (ex-info message {:auth/status status})))

(defn- fields! [data required optional]
  (when-not (and (map? data)
                 (every? #(contains? data %) required)
                 (every? (into (set required) optional) (keys data)))
    (fail! 400 "Invalid fields")))

(defn- text! [value max-length]
  (when-not (and (string? value) (not (string/blank? value))
                 (<= (count value) max-length))
    (fail! 400 "Text is empty or too long"))
  value)

(defn- id! [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9_-]+" value))
    (fail! 400 "Invalid comment ID"))
  value)

(defn- title! [value]
  (when-not (or (nil? value) (and (string? value) (<= (count value) 200)))
    (fail! 400 "Title is too long"))
  value)

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
  (fields! data [:text] [])
  (rpc! "tolgraven_post_chat" {:p_actor (auth/profile-id user)
                               :p_text (text! (:text data) 4000)}))

(defn create-comment! [user data]
  (fields! data [:post-id :text] [:parent-id :title])
  (when-not (pos-int? (:post-id data))
    (fail! 400 "Invalid post ID"))
  (rpc! "tolgraven_create_comment"
        {:p_actor (auth/profile-id user) :p_post_id (:post-id data)
         :p_parent_id (some-> (:parent-id data) id!)
         :p_text (text! (:text data) 20000) :p_title (title! (:title data))}))

(defn edit-comment! [user data]
  (fields! data [:comment-id :text] [:title])
  (rpc! "tolgraven_edit_comment"
        {:p_actor (auth/profile-id user) :p_comment_id (id! (:comment-id data))
         :p_text (text! (:text data) 20000) :p_title (title! (:title data))}))

(def vote-values {"up" 1 "down" -1 "none" 0})

(defn set-comment-vote! [user data]
  (fields! data [:comment-id :vote] [])
  (let [comment-id (id! (:comment-id data))
        vote (vote-values (:vote data))]
    (when (nil? vote) (fail! 400 "Invalid vote"))
    (rpc! "tolgraven_set_comment_vote"
          {:p_actor (auth/profile-id user) :p_comment_id comment-id :p_vote vote
           :p_legacy_vote (get (:comment-votes (auth/profile! user)) comment-id 0)})))
