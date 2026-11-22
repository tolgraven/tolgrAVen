(ns website.schema-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.macros :as macros]
            [website.validation :as validation]
            [website.schema.app-db :as db]
            [website.schema.common :as c]
            [website.schema.declarations :as declarations]
            [website.content.contract :as content]
            [website.content.schema :as cms]
            [website.supabase.schema :as store]
            [website.supabase.catalog :as catalog]
            [website.supabase.query :as query]
            [website.supabase.realtime :as realtime]
            [website.supabase.reader :as reader]
            [website.platform.supabase :as platform]
            [website.config :as config]
            [website.ssr.contract-schema :as ssr]))

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
    (is (m/validate (dsl/->malli schema) ["Sum"]))
    (is (m/validate (dsl/->malli schema) ["Sum" 1 2]))
    (is (not (m/validate (dsl/->malli schema) ["Sum" "1"]))))
  (doseq [form ['(website.macros/defc <test> [x :- :int] [:p x])
                '(website.macros/defc <test> ([x :- :int] [:p x]) ([x :- :int y :- :string] [:p y x]))
                '(website.macros/defc <test> {:args-schema [:cat [:int {:min 1}]]} [x :- :int] [:p x])]]
    (is (seq? (macroexpand-1 form)))))

(deftest actual-cms-seed-and-normalized-bundles-share-one-contract
  (let [seed (json/read-str (slurp (io/resource "content-seed.json")) :key-fn keyword)
        bundle (content/checked-bundle {:version 1 :content seed} content/sections)]
    (is (m/validate (dsl/->malli cms/bundle) bundle))
    (is (= (set (keys seed)) (set (keys cms/sections))))
    (let [unpublished #{:github-settings :codex-profile}]
      (is (= (set content/sections) (set/difference (set (keys cms/sections)) unpublished)))
      (is (= content/sections (content/validate-keys nil)))
      (doseq [section unpublished]
        (is (thrown? clojure.lang.ExceptionInfo (content/validate-keys [section])))))
    (doseq [[section schema] cms/sections]
      (is (m/validate (dsl/->malli schema) (get-in bundle [:content section])) (str section)))
    (is (m/validate (dsl/->malli (db/schema {[:content] (c/optional-map cms/sections)})) {:content (:content bundle)}))
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
    (is (m/validate (dsl/->malli schema) valid))
    (doseq [invalid invalids] (is (not (m/validate (dsl/->malli schema) invalid)) (pr-str invalid))))
  (doseq [[schema key n base] [[store/chat-write :text 4000 {}]
                              [store/comment-edit :text 20000 {:comment-id "x"}]
                              [store/post-write :title 200 {:text "Body"}]]]
    (is (m/validate (dsl/->malli schema) (assoc base key (apply str (repeat n "x")))))
    (is (not (m/validate (dsl/->malli schema) (assoc base key (apply str (repeat (inc n) "x"))))))))

(deftest query-and-row-contracts-match-projections
  (doseq [q [{:path-document [:blog-posts 28]}
             {:path-collection ["blog-comments"] :limit 10 :offset 0 :where [[:parent-post :== 28]]}
             {:path-collection [:users] :where [[:id :in ["a" "b"]]]}]]
    (is (m/validate (dsl/->malli store/query) q)))
  (doseq [q [{} {:path-collection []} {:path-collection [:users] :limit 0}
             {:path-document [:users "a"] :path-collection [:users]}
             {:path-collection [:users] :order-by [[:id "sideways"]]}]]
    (is (not (m/validate (dsl/->malli store/query) q))))
  (doseq [{:keys [table public-columns collections user-collections]}
          (mapcat val catalog/definitions)
          :when (and public-columns (or (seq collections) (seq user-collections)))]
    (let [table-name (name table)
          fields (map keyword (string/split public-columns #","))]
      (is (= public-columns (get query/public-columns table-name)))
      (is (every? (set (keys (store/row-fields table-name))) fields) table-name)
      (is (m/validate (dsl/->malli (store/projected-rows table-name public-columns)) []))))
  (let [schema (store/projected-rows "blog_comments" "id,parent_comment")]
    (is (m/validate (dsl/->malli schema) [{:id "root" :parent_comment nil}]))
    (is (not (m/validate (dsl/->malli schema) [{:id "root"}]))))
  (let [schema (store/projected-rows "user_documents" "doc_id,data")]
    (is (m/validate (dsl/->malli schema) [{:doc_id "thread" :data {:messages ["Question" "Answer"]}}]))
    (is (not (m/validate (dsl/->malli schema) [{:doc_id "thread" :data {:messages [42]}}])))))

(defn- granted-columns [sql]
  (let [select-grant #"(?i)grant\s+select\s*\(([^)]+)\)\s+on\s+public\.(\w+)\s+to\s+anon,\s*authenticated"
        [_ columns table] (re-find select-grant (slurp (io/resource sql)))]
    (when table
      {:table table :fields (set (map (comp keyword string/trim) (string/split columns #",")))})))

(deftest github-archive-projections-match-the-storage-grants
  (let [tables (:github catalog/definitions)]
    (is (= #{:github_repositories :github_commits} (set (map :table tables))))
    (doseq [{:keys [table sql public-columns]} tables]
      (let [table-name (name table)
            fields (set (map keyword (string/split public-columns #",")))
            row (zipmap fields (range))
            internal (assoc row :state {} :lease_owner "private" :search_vector "private")]
        (is (= {:table table-name :fields fields} (granted-columns sql)))
        (is (= public-columns (get query/public-columns table-name)))
        (is (= row (realtime/public-row table-name internal)))
        (is (false? (query/direct-read-query? {:path-collection [table-name]})))))))

(deftest reader-rejects-malformed-responses-before-normalization
  (with-redefs [config/validation-enabled? (constantly true)
                platform/request! (fn [& _] {:body [{:id "x" :parent_comment []}]})]
    (is (thrown? clojure.lang.ExceptionInfo
          (reader/rows! {:table "blog_comments" :select "id,parent_comment"}))))
  (with-redefs [config/validation-enabled? (constantly true)
                platform/request! (fn [& _] {:body [{:id "x" :parent_comment nil :secret "private"}]})]
    (is (= [{:id "x" :parent_comment nil}]
           (reader/rows! {:table "blog_comments" :select "id,parent_comment"})))))

(deftest dependency-variants-and-extensible-specs
  (doseq [dep [{:source :strapi :keys [:blog]}
               {:source :supabase :query {:path-document [:blog-posts 1]}}
               {:source :subscription :query [:blog/posts 1]}
               {:source :url :url "/api/read"} {:source :app-db :path [:content :docs]}
               {:source :custom :extension true}]]
    (is (m/validate (dsl/->malli declarations/dependency) dep)))
  (doseq [dep [{:source :strapi} {:source :supabase :query {}} {:source :subscription :query []}
               {:source :url :url ""} {:source :app-db :path []}]]
    (is (not (m/validate (dsl/->malli declarations/dependency) dep))))
  (is (m/validate (dsl/->malli declarations/component) {:state {:initial {:count 0} :schema [:map [:count :int]]
                                                               :persist {:scope :public :version 1}}})))

(deftest persistence-and-render-boundaries-reject-corrupt-envelopes
  (is (m/validate (dsl/->malli ssr/snapshot) {:path "/" :content {} :shell? true}))
  (is (not (m/validate (dsl/->malli ssr/snapshot) {:path "/" :posts [{:title 7}]})))
  (is (not (m/validate (dsl/->malli ssr/settings) {:render-workers 0}))))

(deftest post-summary-counts-accept-zero-and-reject-invalid-values
  (let [schema (dsl/->malli store/post)]
    (doseq [n [0 1 42]]
      (is (m/validate schema {:id 27, :title "Post", :comment-count n})))
    (doseq [n [-1 "1" nil]]
      (is (not (m/validate schema {:id 27, :title "Post", :comment-count n}))))))
