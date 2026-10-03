(ns tolgraven.supabase-shape-test
  (:require
   [clojure.test :refer :all]
   [tolgraven.provision.supabase.import :as provision-import]
   [tolgraven.supabase.api :as supabase-api]
   [tolgraven.supabase.interop :as interop]
   [tolgraven.supabase.query :as query]
   [tolgraven.store.contract :as contract]
   [tolgraven.supabase.store :as store]
   [tolgraven.platform.supabase :as platform]
   [clj-http.client :as http]
   [tolgraven.routes.services :as services]
   [reitit.ring :as ring]))

(def sample-export
  {"auth" [{:id "roles"
            :data {:admins ["u1"]
                   :bloggers ["u1" "u2"]}}]
   "users" [{:id "u1"
             :data {:id "u1"
                    :seq-id 1
                    :name "Joen"
                    :email "joen@example.com"
                    :avatar "avatar-1"
                    :bg-color "purple"
                    :comments ["c1" "c2"]
                    :comment-count 2
                    :karma 3
                    :voted {"[:blog 1]" :up}}}
            {:id "u2"
             :data {:id "u2"
                    :seq-id 2
                    :name "Guest"
                    :email nil
                    :avatar "avatar-2"
                    :bg-color "blue"
                    :comments []
                    :comment-count 0
                    :karma 0}}]
   "blog-posts" [{:id "1"
                  :data {:id 1
                         :title "Hello"
                         :text "World"
                         :permalink "hello-1"
                         :user "u1"
                         :tags "tagged words"
                         :ts 1000
                         :comments
                         {"c1" {:id "c1"
                                :seq-id 1
                                :title ""
                                :text "Top"
                                :user "u2"
                                :score 0
                                :ts 1001
                                :comments
                                {"c2" {:id "c2"
                                       :seq-id 1
                                       :text "Reply"
                                       :user "u1"
                                       :score 2
                                       :ts 1002}}}}}}]
   "blog-comments" [{:id "c1"
                     :data {:id "c1"
                            :seq-id 1
                            :title ""
                            :text "Top"
                            :user "u2"
                            :score 0
                            :ts 1001}}
                    {:id "c2"
                     :data {:id "c2"
                            :seq-id 1
                            :text "Reply"
                            :user "u1"
                            :score 2
                            :ts 1002}}]
   "chat" [{:id "messages"
            :data {"7" {:text "Hi"
                        :time 2000
                        :user "u1"}
                   "8" {:text "Yo"
                        :time 2001
                        :user "anon"}}}]
   "gpt" [{:id "messages"
           :data {"1" {:prompt "Hey"
                       :response "Hello"
                       :time 3000
                       :user "u1"}}}]
   "gpt-threads" [{:id "42"
                   :data {:user "u1"
                          :time 3001
                          :messages [["question" "answer"]]}}]
   "imagor" [{:id "auth"
              :data {:host "https://imagor.test"
                     :api_key "imagor-key"}}]
   "instagram" [{:id "auth"
                 :data {:access_token "instagram-token"}}
                {:id "ids"
                 :data {:ids ["ig-1" "ig-2"]}}
                {:id "posts"
                 :data {"ig-1" {:id "ig-1"
                                :caption "post"}}}]
   "strapi" [{:id "auth"
              :data {:url "https://cms.test"
                     :read-api-key "strapi-key"}}]
   "typesense" [{:id "auth"
                 :data {:host "typesense.test"
                        :port 443
                        :api_key "typesense-key"}}]})

(deftest flatten-comment-tree-backfills-parent-info
  (let [post (get-in sample-export ["blog-posts" 0 :data])
        comments (contract/flatten-comment-tree (:id post) (:comments post))
        top (first comments)
        reply (second comments)]
    (is (= "c1" (:id top)))
    (is (= 1 (:parent-post top)))
    (is (nil? (:parent-comment top)))
    (is (= [1] (:path top)))
    (is (= "c2" (:id reply)))
    (is (= 1 (:parent-post reply)))
    (is (= "c1" (:parent-comment reply)))
    (is (= [1 "c1"] (:path reply)))))

(deftest firebase-contract-roundtrips-through-supabase-seed
  (let [firebase-contract (contract/firebase-export->contract sample-export)
        seed (contract/firebase-export->seed sample-export)
        rebuilt-contract (contract/seed->contract seed)]
    (is (= (get-in firebase-contract ["auth" "roles"])
           (get-in rebuilt-contract ["auth" "roles"])))
    (is (= (get-in firebase-contract ["users" "u1"])
           (get-in rebuilt-contract ["users" "u1"])))
    (is (= (get-in firebase-contract ["blog-posts" "1"])
           (get-in rebuilt-contract ["blog-posts" "1"])))
    (is (= (get-in firebase-contract ["blog-comments" "c1"])
           (get-in rebuilt-contract ["blog-comments" "c1"])))
    (is (= (get-in firebase-contract ["blog-comments" "c2"])
           (get-in rebuilt-contract ["blog-comments" "c2"])))
    (is (= (get-in firebase-contract ["chat" "messages"])
           (get-in rebuilt-contract ["chat" "messages"])))
    (is (= (get-in firebase-contract ["gpt" "messages"])
           (get-in rebuilt-contract ["gpt" "messages"])))
    (is (= (get-in firebase-contract ["gpt-threads" "42"])
           (get-in rebuilt-contract ["gpt-threads" "42"])))
    (is (= (get-in firebase-contract ["imagor" "auth"])
           (get-in rebuilt-contract ["imagor" "auth"])))
    (is (= (get-in firebase-contract ["instagram" "ids"])
           (get-in rebuilt-contract ["instagram" "ids"])))
    (is (= (get-in firebase-contract ["instagram" "posts"])
           (get-in rebuilt-contract ["instagram" "posts"])))
    (is (= (get-in firebase-contract ["strapi" "auth"])
           (get-in rebuilt-contract ["strapi" "auth"])))
    (is (= (get-in firebase-contract ["typesense" "auth"])
           (get-in rebuilt-contract ["typesense" "auth"])))))

(deftest query-contract-supports-firebase-style-lookups
  (let [contract (contract/seed->contract (contract/firebase-export->seed sample-export))]
    (is (= {:id "roles"
            :data {:admins ["u1"]
                   :bloggers ["u1" "u2"]}}
           (query/query-contract contract {:path-document ["auth" "roles"]})))
    (is (= {:docs [{:id "1"
                    :data (get-in contract ["blog-posts" "1"])}]}
           (query/query-contract contract {:path-collection ["blog-posts"]
                                           :where [[:id :== 1]]})))
    (is (= {:docs [{:id "c1"
                    :data (get-in contract ["blog-comments" "c1"])}]}
           (query/query-contract contract {:path-collection ["blog-comments"]
                                           :where [[:parent-post :== 1]
                                                   [:parent-comment :== nil]]
                                           :order-by [[:ts :asc]]
                                           :limit 1})))))

(deftest interop-namespaces-load
  (is (fn? @#'provision-import/export->seed))
  (is (fn? @#'supabase-api/query-store!)))

(deftest store-set-document-replaces-and-merges
  (let [contract (contract/seed->contract (contract/firebase-export->seed sample-export))
        replaced (store/set-document contract ["imagor" "auth"] {:host "https://new"} nil)
        merged (store/set-document contract ["users" "u1"] {:karma 10 :comments ["x"]} [:karma])]
    (is (= {:host "https://new"}
           (get-in replaced ["imagor" "auth"])))
    (is (= 10
           (get-in merged ["users" "u1" :karma])))
    (is (= ["x"]
           (get-in merged ["users" "u1" :comments])))
    (is (= "Joen"
           (get-in merged ["users" "u1" :name])))))


(deftest keyword-and-json-query-paths-agree
  (let [data (contract/seed->contract (contract/firebase-export->seed sample-export))]
    (is (= (query/query-contract data {:path-document ["users" "u1"]})
           (query/query-contract data {:path-document [:users :u1]})))
    (is (= (query/seed-load-plan {:path-document ["blog-posts" "1"]})
           (query/seed-load-plan {:path-document [:blog-posts 1]})))
    (is (= (query/query-contract data {:path-collection [:users]
                                      :where [[:karma :>= 0]] :order-by [[:name :desc]]})
           (query/query-contract data {:path-collection ["users"]
                                      :where [["karma" ">=" 0]] :order-by [["name" "desc"]]})))))

(deftest private-and-unknown-queries-never-read-the-database
  (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected DB access")))]
    (doseq [path [[:secrets :strava] ["strapi" "auth"] [:auth :roles]
                  [:arbitrary :document] [:users :u1 :nested]]]
      (is (= 403 (:status (supabase-api/query-response {:path-document path})))))))

(deftest public-query-is-scoped-and-paginated
  (let [calls (atom [])]
    (with-redefs [platform/request!
                  (fn [method table opts]
                    (swap! calls conj [method table opts])
                    {:body (if (zero? (get-in opts [:query-params "offset"]))
                             [{:id "u1" :name "Joen"}] [])})]
      (is (= "Joen" (get-in (supabase-api/query-store! {:path-document [:users :u1]})
                             [:data :name])))
      (is (= ["site_users" "site_users"] (mapv second @calls)))
      (is (= "eq.u1" (get-in @calls [0 2 :query-params "id"])))
      (is (= [0 1] (mapv #(get-in % [2 :query-params "offset"]) @calls)))
      (is (not (re-find #"email|raw|voted" (get-in @calls [0 2 :query-params "select"])))))))

(deftest runtime-write-only-upserts-the-changed-row
  (let [calls (atom [])
        data (contract/seed->contract (contract/firebase-export->seed sample-export))]
    (with-redefs [interop/fetch-contract (constantly data)
                  platform/reset-table-rest! (fn [& _] (throw (Exception. "Table reset attempted")))
                  platform/upsert-rest! (fn [table rows] (swap! calls conj [table rows]))]
      (is (= 10 (:karma (interop/write-document! [:users :u1] {:karma 10} [:karma]))))
      (is (= [:site_users] (mapv first @calls)))
      (is (= ["u1"] (mapv :id (second (first @calls)))))
      (is (= "Guest" (get-in data ["users" "u2" :name])))
      (reset! calls [])
      (is (thrown? clojure.lang.ExceptionInfo
                   (interop/write-document! [:auth :roles] {:admins []} nil)))
      (is (empty? @calls)))))

(deftest rest-options-preserve-authentication-headers
  (let [opts (atom nil)]
    (with-redefs [platform/base-http-opts (constantly {:headers {"apikey" "test-key"
                                                               "Authorization" "Bearer test-key"}})
                  platform/rest-url (constantly "https://example.invalid/rest/v1/site_users")
                  http/request (fn [request] (reset! opts request) {:status 201})]
      (platform/upsert-rest! :site_users [{:id "u1"}])
      (is (= "test-key" (get-in @opts [:headers "apikey"])))
      (is (= "Bearer test-key" (get-in @opts [:headers "Authorization"])))
      (is (= "resolution=merge-duplicates,return=minimal" (get-in @opts [:headers "Prefer"]))))))


(deftest public-http-write-is-denied-before-database-access
  (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected database access")))]
    (let [handler (ring/ring-handler (ring/router (services/service-routes)))
          response (handler {:request-method :post
                             :uri "/api/supabase/store/write"
                             :headers {"content-type" "application/json"}
                             :body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))})]
      (is (nil? response)))))

(deftest profile-updates-preserve-imported-custom-fields
  (let [seed (contract/firebase-export->seed sample-export)
        seed (assoc-in seed [:users 0 :raw :custom-field] "keep")
        data (contract/seed->contract seed)
        updated (store/set-document data [:users :u1] {:karma 12} [:karma])]
    (is (= "keep" (get-in (contract/contract->seed updated) [:users 0 :raw :custom-field])))))
