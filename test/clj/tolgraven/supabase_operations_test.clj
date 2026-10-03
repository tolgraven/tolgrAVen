(ns tolgraven.supabase-operations-test
  (:require [clojure.test :refer :all]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]
            [muuntaja.core :as m]
            [tolgraven.routes.services :as services]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]
            [tolgraven.supabase.operations :as operations]))

(def actor {:id "account-1" :app_metadata {:site_user_id "legacy-1"}
            :user_metadata {:site_user_id "victim"}})

(deftest missing-sessions-cannot-invoke-write-rpcs
  (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected database access")))]
    (doseq [operation [operations/post-chat! operations/create-comment!
                      operations/edit-comment! operations/set-comment-vote!]]
      (is (= 401 (:status (auth/response! {} #(operation % {:text "hello"}))))))))

(deftest client-controlled-author-and-protected-fields-are-rejected
  (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected RPC")))]
    (doseq [[operation body]
            [[operations/post-chat! {:text "hello" :user "victim"}]
             [operations/post-chat! {:text "hello" :ts 1}]
             [operations/create-comment! {:post-id 1 :text "hello" :score 100}]
             [operations/create-comment! {:post-id 1 :text "hello" :path [2]}]
             [operations/edit-comment! {:comment-id "c" :text "hello" :user_id "victim"}]
             [operations/set-comment-vote! {:comment-id "c" :vote "up" :p_legacy_vote 1}]
             [operations/set-comment-vote! {:comment-id "c" :vote "up" :karma 100}]
             [operations/set-comment-vote! {:comment-id "c" :vote 1}]
             [operations/create-comment! {:post-id "1" :text "hello"}]
             [operations/create-comment! {:post-id 1 :parent-id "bad,id" :text "hello"}]
             [operations/edit-comment! {:comment-id "c" :text "  "}]
             [operations/post-chat! {:text (apply str (repeat 4001 "x"))}]]]
      (is (= 400 (:auth/status (ex-data (try (operation actor body)
                                            (catch clojure.lang.ExceptionInfo error error)))))))))

(deftest writes-use-verified-linkage-and-single-transaction-rpcs
  (let [calls (atom [])]
    (with-redefs [platform/request! (fn [method path options]
                                     (swap! calls conj [method path options])
                                     {:body {:id "created"}})
                  auth/profile! (fn [user]
                                  (is (= actor user))
                                  {:comment-votes {"c" 1}})]
      (operations/post-chat! actor {:text "hello"})
      (operations/create-comment! actor {:post-id 1 :parent-id "parent" :text "reply"})
      (operations/edit-comment! actor {:comment-id "c" :text "edit" :title "title"})
      (operations/set-comment-vote! actor {:comment-id "c" :vote "down"})
      (is (= ["rpc/tolgraven_post_chat" "rpc/tolgraven_create_comment"
              "rpc/tolgraven_edit_comment" "rpc/tolgraven_set_comment_vote"] (mapv second @calls)))
      (is (every? #(= "legacy-1" (get-in % [2 :form-params :p_actor])) @calls))
      (is (= {:p_actor "legacy-1" :p_comment_id "c" :p_vote -1 :p_legacy_vote 1}
             (get-in @calls [3 2 :form-params]))))))

(deftest database-ownership-and-parent-errors-become-safe-http-errors
  (doseq [[code expected] [["PT400" 400] ["PT403" 403] ["PT404" 404]]]
    (with-redefs [auth/current-user! (constantly actor)
                  platform/request! (fn [& _]
                                      (throw (ex-info "private details" {:body {:code code :message "private details"}})))]
      (let [response (auth/response! {} #(operations/edit-comment! % {:comment-id "c" :text "edit"}))]
        (is (= expected (:status response)))
        (is (not= "private details" (get-in response [:body :error])))))))

(deftest imported-vote-paths-remain-a-private-score-baseline
  (is (= {"108" 1 "uuid-comment" -1}
         (auth/legacy-comment-votes {"[24 105 108]" "up"
                                     (keyword "[24 \"uuid-comment\"]") "down"
                                     "not-edn" "up" "[24]" "up"})))
  (let [calls (atom [])]
    (with-redefs [platform/request!
                  (fn [_ table options]
                    (swap! calls conj [table options])
                    {:body (case table
                             "site_users" [{:id "legacy-1" :voted {(keyword "[24 105 108]") "up"}}]
                             "comment_votes" (if (zero? (get-in options [:query-params "offset"]))
                                               [{:comment_id "108" :vote 0}] []))})]
      (is (= {"108" 0} (:comment-votes (auth/profile! actor))))
      (is (every? #(= "eq.legacy-1" (get-in % [1 :query-params "user_id"]))
                  (filter #(= "comment_votes" (first %)) @calls))))))

(deftest authenticated-write-routes-decode-json-and-enforce-auth
  (let [handler (ring/ring-handler (ring/router (services/service-routes)))]
    (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected database access")))]
      (doseq [[method path body] [[:post "/api/supabase/chat" {:text "message"}]
                                 [:post "/api/supabase/comments" {:post-id 1 :text "comment"}]
                                 [:put "/api/supabase/comments" {:comment-id "c" :text "edit"}]
                                 [:post "/api/supabase/votes" {:comment-id "c" :vote "up"}]]]
        (is (= 401 (:status (handler (-> (mock/request method path) (mock/json-body body))))))))
    (let [seen (atom nil)]
      (with-redefs [auth/current-user! (fn [request]
                                        (is (= "Bearer verified" (get-in request [:headers "authorization"])))
                                        actor)
                    platform/request! (fn [_ path options]
                                        (reset! seen [path options])
                                        {:body {:id "created" :user "legacy-1"}})]
        (let [response (handler (-> (mock/request :post "/api/supabase/chat")
                                    (mock/header "authorization" "Bearer verified")
                                    (mock/json-body {:text "message"})))]
          (is (= 200 (:status response)))
          (is (= "legacy-1" (:user (m/decode-response-body response))))
          (is (= "legacy-1" (get-in @seen [1 :form-params :p_actor]))))
        (reset! seen nil)
        (is (= 400 (:status (handler (-> (mock/request :post "/api/supabase/chat")
                                         (mock/header "authorization" "Bearer verified")
                                         (mock/json-body {:text "message" :user "victim"}))))))
        (is (nil? @seen))))))
