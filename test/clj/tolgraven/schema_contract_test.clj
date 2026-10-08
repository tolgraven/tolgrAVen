(ns tolgraven.schema-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [malli.core :as m]
            [tolgraven.macros :as macros]
            [tolgraven.validation :as validation]
            [tolgraven.schema.app-db :as db]
            [tolgraven.schema.declarations :as declarations]
            [tolgraven.schema.integrations :as integrations]
            [tolgraven.schema.state :as state]
            [tolgraven.modules.blog.schema :as blog]
            [tolgraven.dev-console.schema :as debug]
            [tolgraven.content.contract :as content]
            [tolgraven.content.schema :as cms]
            [tolgraven.supabase.schema :as store]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.reader :as reader]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.config :as config]
            [tolgraven.ssr.schema :as ssr]))

(deftest inline-arguments-preserve-bindings-and-describe-real-arity
  (doseq [[input clean schema]
          [['[title :- :string count :- :int] '[title count] [:cat :string :int]]
           ['[{:keys [title]} :- [:map [:title :string]] index]
            '[{:keys [title]} index] [:cat [:map [:title :string]] :any]]
           ['[label :- :string & values :- :int] '[label & values] [:cat :string [:* :int]]]
           ['[value & [callback] :- [:maybe fn?]] '[value & [callback]]
            '[:cat :any [:* [:maybe fn?]]]]]]
    (let [parsed (macros/component-arguments input)]
      (is (= clean (:args parsed)))
      (is (= schema (:schema parsed)))
      (is (:typed? parsed))))
  (is (false? (:typed? (macros/component-arguments '[x & xs]))))
  (doseq [invalid ['[x :-] '[&] '[x & y z] '[:- :string] '(x)]]
    (is (thrown? clojure.lang.ExceptionInfo (macros/component-arguments invalid))))
  (let [schema (:schema (macros/component-arguments '[label :- :string & values :- :int]))]
    (is (m/validate schema ["Sum"]))
    (is (m/validate schema ["Sum" 1 2]))
    (is (not (m/validate schema ["Sum" "1"]))))
  (doseq [form ['(tolgraven.macros/defc <test> [x :- :int] [:p x])
                '(tolgraven.macros/defc <test> ([x :- :int] [:p x]) ([x :- :int y :- :string] [:p y x]))
                '(tolgraven.macros/defc <test> {:args-schema [:cat [:int {:min 1}]]} [x :- :int] [:p x])]]
    (is (seq? (macroexpand-1 form)))))

(deftest actual-cms-seed-and-normalized-bundles-share-one-contract
  (let [seed (json/read-str (slurp (io/resource "content-seed.json")) :key-fn keyword)
        bundle (content/checked-bundle {:version 1 :content seed} content/sections)]
    (is (m/validate cms/bundle bundle))
    (is (= (set content/sections) (set (keys cms/sections))))
    (doseq [[section schema] cms/sections]
      (is (m/validate schema (get-in bundle [:content section])) (str section)))
    (is (m/validate (db/schema db/sections) {:content (:content bundle)}))
    (doseq [bad [(assoc-in bundle [:content :header :text] "wrong")
                 (assoc-in bundle [:content :cv :cv :timeline] [1])
                 (assoc-in bundle [:content :blog :heading :bg :src] 1)
                 (assoc-in bundle [:content :footer] {})
                 (assoc-in bundle [:content :unknown] {})]]
      (is (seq (validation/explain cms/bundle bad))))
    (is (thrown? clojure.lang.ExceptionInfo (content/checked-bundle {:version 1 :content {}} [:blog])))))

(deftest write-contracts-cover-required-fields-types-and-protected-fields
  (doseq [[schema valid invalids]
          [[store/chat-write {:text "Hello"} [{:text "  "} {:text 1} {:text "Hi" :user "other"}]]
           [store/comment-create {:post-id 1 :text "Reply" :parent-id nil}
            [{:post-id 0 :text "Reply"} {:post-id "1" :text "Reply"} {:post-id 1 :text "Reply" :parent-id "a,b"}]]
           [store/comment-edit {:comment-id "a-b_1" :text "Edited"}
            [{:comment-id "a/b" :text "Edited"} {:comment-id "x" :text "Edited" :score 100}]]
           [store/vote-write {:comment-id "x" :vote "up"} [{:comment-id "x" :vote 1} {:comment-id "x" :vote "sideways"}]]
           [store/post-write {:title "Title" :text "Body" :tags nil}
            [{:title "" :text "Body"} {:title "Title" :text "Body" :post-id -1}]]
           [store/document-write {:path ["gpt-threads" "thread"] :data {:messages ["Hello"]}}
            [{:path ["secrets" "x"] :data {}} {:path ["gpt" "x"] :data {:messages [42]}}]]
           [store/profile-write {:name "Name" :avatar nil} [{:name 1} {:email "private"}]]]]
    (is (m/validate schema valid))
    (doseq [invalid invalids] (is (not (m/validate schema invalid)) (pr-str invalid))))
  (doseq [[schema key n base] [[store/chat-write :text 4000 {}]
                              [store/comment-edit :text 20000 {:comment-id "x"}]
                              [store/post-write :title 200 {:text "Body"}]]]
    (is (m/validate schema (assoc base key (apply str (repeat n "x")))))
    (is (not (m/validate schema (assoc base key (apply str (repeat (inc n) "x"))))))))

(deftest query-and-row-contracts-match-projections
  (doseq [q [{:path-document [:blog-posts 28]}
             {:path-collection ["blog-comments"] :limit 10 :offset 0 :where [[:parent-post :== 28]]}
             {:path-collection [:users] :where [[:id :in ["a" "b"]]]}]]
    (is (m/validate store/query q)))
  (doseq [q [{} {:path-collection []} {:path-collection [:users] :limit 0}
             {:path-document [:users "a"] :path-collection [:users]}
             {:path-collection [:users] :order-by [[:id "sideways"]]}]]
    (is (not (m/validate store/query q))))
  (doseq [[table columns] query/public-columns]
    (let [fields (map keyword (string/split columns #","))]
      (is (every? (set (keys (store/row-fields table))) fields) table)
      (is (m/validate (store/projected-rows table columns) []))))
  (let [schema (store/projected-rows "blog_comments" "id,parent_comment")]
    (is (m/validate schema [{:id "root" :parent_comment nil}]))
    (is (not (m/validate schema [{:id "root"}]))))
  (let [schema (store/projected-rows "user_documents" "doc_id,data")]
    (is (m/validate schema [{:doc_id "thread" :data {:messages ["Question" "Answer"]}}]))
    (is (not (m/validate schema [{:doc_id "thread" :data {:messages [42]}}])))))

(deftest reader-rejects-malformed-responses-before-normalization
  (with-redefs [config/validation-enabled? (constantly true)
                platform/request! (fn [& _] {:body [{:id "x" :parent_comment []}]})]
    (is (thrown? clojure.lang.ExceptionInfo
          (reader/rows! {:table "blog_comments" :select "id,parent_comment"}))))
  (with-redefs [config/validation-enabled? (constantly true)
                platform/request! (fn [& _] {:body [{:id "x" :parent_comment nil :secret "private"}]})]
    (is (= [{:id "x" :parent_comment nil}]
           (reader/rows! {:table "blog_comments" :select "id,parent_comment"})))))

(deftest assembled-state-checks-nested-provider-data-and-view-state
  (let [schema (db/schema db/sections)
        good {:state {:link-preview {:active {:url "https://example.test" :trust :trusted :status :preview}}
                      :form-field {:login {:email "user@example.test" :password "secret"}}
                      :browser-nav {:nav-type :back} :blog {:comment-thread-expanded {[28 "c"] false}}}
              :common/route {:path "/blog" :query-params nil}
              :content {:github {:commits [{:sha "abc" :commit {:message "Change"}}]}
                        :strava {:activities [{:id 1 :distance 100.0}]}}
              :store {:public {"blog-posts" {28 {:id 28 :tags ["one" "two"]}}}}}]
    (is (m/validate schema good))
    (doseq [[path value] [[[:state :form-field :login :email] 1]
                          [[:content :github :commits 0 :commit :message] []]
                          [[:content :strava :activities 0 :distance] "100"]
                          [[:store :public "blog-posts" 28 :tags] {}]
                          [[:state :blog :comment-thread-expanded [28 "c"]] "false"]]]
      (let [issues (validation/explain schema (assoc-in good path value))]
        (is (seq issues))
        (is (some #(= path (:path %)) issues))))))

(deftest dependency-variants-and-extensible-specs
  (doseq [dep [{:source :strapi :keys [:blog]}
               {:source :supabase :query {:path-document [:blog-posts 1]}}
               {:source :subscription :query [:blog/posts 1]}
               {:source :url :url "/api/read"} {:source :app-db :path [:content :docs]}
               {:source :custom :extension true}]]
    (is (m/validate declarations/dependency dep)))
  (doseq [dep [{:source :strapi} {:source :supabase :query {}} {:source :subscription :query []}
               {:source :url :url ""} {:source :app-db :path []}]]
    (is (not (m/validate declarations/dependency dep))))
  (is (m/validate declarations/component {:state {:initial {:count 0} :schema [:map [:count :int]]
                                                               :persist {:scope :public :version 1}}})))

(deftest persistence-and-render-boundaries-reject-corrupt-envelopes
  (is (m/validate ssr/storage-snapshot {:version 1 :schema 2 :expires-at 100 :value {:a []}}))
  (doseq [value [nil [] {:version 1 :schema 1 :expires-at "later" :value {}}
                       {:version 1 :schema 1 :expires-at 100}]]
    (is (not (m/validate ssr/storage-snapshot value))))
  (is (m/validate ssr/snapshot {:path "/" :content {} :shell? true}))
  (is (not (m/validate ssr/snapshot {:path "/" :posts [{:title 7}]})))
  (is (not (m/validate ssr/settings {:render-workers 0}))))


(deftest inspector-and-transient-ui-metadata-have-concrete-shapes
  (is (m/validate blog/posted-by-spec {:user "author-id" :ts 123}))
  (is (m/validate blog/posted-by-spec {:user {:id "author-id" :name "Author"}}))
  (is (m/validate state/state {:init {:scope {:github {:inited? true :args nil}}}}))
  (is (m/validate state/state {:debug {:hydration-token (random-uuid)}
                               :form-field {:write-comment {[28 "c"] nil}}}))
  (is (m/validate debug/state {:active {"instance" {:instance "instance" :component ["ns" "<view>"] :parent nil}}
                               :records [{:kind :render :duration 1.2 :start 10 :phase "mount"}
                                         {:kind :epoch :event [:state [:menu] true] :effects {}}]}))
  (is (not (m/validate debug/state {:records [{:kind :layout :start "10"}]})))
  (is (m/validate ssr/snapshot {:path "/blog/post/27" :page nil :post-id 27 :content {}})))
