(ns website.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.validation :as validation]
            [website.validation.malli :as validation-engine]
            [website.schema.declarations :as declarations]
            [website.schema.app-db :as app-db]
            [website.schema.http :as http]
            [website.page-router :as pages]
            [website.modules.home.pages :as main]
            [website.modules.blog.pages :as blog]
            [website.modules.docs.pages :as docs]
            [website.modules.cv.pages :as cv]))

(deftest shared-declarations-cover-real-route-trees
  (doseq [spec [main/spec blog/spec docs/spec cv/spec]]
    (is (nil? (validation/explain declarations/routes spec))))
  (is (seq (validation/explain declarations/routes [["/bad" {:ssr "yes"}]])))
  (is (nil? (validation/explain declarations/component
                              {:features [:error-boundary [:appear "opacity"]]
                               :depends [{:source :strapi :keys [:blog]}]})))
  (is (seq (validation/explain declarations/component {:features "appear"})))
  (is (seq (validation/explain declarations/dependency {:source :url :ttl-ms -1})))
  (is (nil? (validation/explain declarations/dependency
                               {:source :supabase :query {:path-collection [:blog-posts]}}))))

(deftest workspace-shell-options-are-typed-with-existing-fallback-fields
  (is (nil? (validation/explain declarations/routes
              [["/workspace" {:shell {:chrome? false
                                      :heading [:blog :heading]
                                      :loading-prefab :article
                                      :loading-view {:module :blog, :view :post-content}
                                      :loading-args {:post {:title "Loading"}}}}]])))
  (is (seq (validation/explain declarations/routes
             [["/workspace" {:shell {:chrome? "false"}}]]))))

(deftest route-parameters-coerce-at-shared-boundary
  (let [match (pages/request-match "/blog/page/2" {"userBox" "false" "debug" "keep"})]
    (is (= {:nr 2} (get-in match [:parameters :path])))
    (is (= {:userBox false :debug "keep"} (get-in match [:parameters :query]))))
  (doseq [url ["/blog/page/0" "/blog/page/nope" "/blog/post/not-an-id" "/docs/codox/.."]]
    (is (thrown? clojure.lang.ExceptionInfo (pages/request-match url {})) url))
  (is (thrown? clojure.lang.ExceptionInfo (pages/request-match "/" {"userBox" "maybe"}))))

(deftest reports-do-not-retain-values
  (let [issues (validation/explain http/operands {:x 1 :y "secret-password"})]
    (is (= [:y] (:path (first issues))))
    (is (not (.contains (pr-str issues) "secret-password")))
    (is (every? #(= #{:path :message} (set (keys %))) issues))))

(deftest runtime-policy-has-explicit-overrides
  (is (validation/enabled? {} true))
  (is (not (validation/enabled? {} false)))
  (is (not (validation/enabled? {:validation {:enabled false}} true)))
  (is (validation/enabled? {:validation {:enabled true}} false))
  (is (not (validation/enabled? {:validation-enabled "false" :validation {:enabled true}} true))))

(deftest namespaced-module-build-fields-are-optional-and-typed
  (let [schema (dsl/->malli declarations/module)]
    (is (m/validate schema {:id :blog}))
    (is (m/validate schema {:id :blog, :bundle/depends-on #{:main :user}, :site/owner :site}))
    (is (not (m/validate schema {:id :blog, :bundle/depends-on [:main]})))
    (is (not (m/validate schema {:id :blog, :site/owner "site"})))))

(deftest declarations-compose-base-and-owner-contracts
  (let [extended (declarations/extend-module [:map [:cache-limit [:int {:min 1}]]])]
    (is (m/validate (dsl/->malli extended) {:id :catalog :cache-limit 20}))
    (is (not (m/validate (dsl/->malli extended) {:cache-limit 20})))
    (is (not (m/validate (dsl/->malli extended) {:id :catalog :cache-limit 0}))))
  (let [extended (declarations/extend-spec [:map [:label :string]])]
    (is (m/validate (dsl/->malli extended) {:label "Title" :props {:class "title"}}))
    (is (not (m/validate (dsl/->malli extended) {:label "Title" :props "wrong"}))))
  (let [extended (declarations/extend-page [:map [:permission :keyword]])]
    (is (m/validate (dsl/->malli extended) {:name :account :permission :signed-in :ssr true}))
    (is (not (m/validate (dsl/->malli extended) {:name :account :permission :signed-in :ssr "yes"})))))

(deftest schema-caches-release-historical-generations
  (let [first-schema [:map [:first-generation :int]]
        first-compiled (validation/compiled first-schema)]
    (is (identical? first-compiled (validation/compiled first-schema)))
    (dotimes [index validation-engine/compiled-cache-limit]
      (validation/compiled [:map [:cache-generation [:= index]]]))
    (let [recompiled (validation/compiled first-schema)]
      (is (not (identical? first-compiled recompiled)))
      (is ((:valid? recompiled) {:first-generation 1}))))
  (let [sections {[:first-generation] [:map [:count :int]]}
        assembled (app-db/schema sections)]
    (is (identical? assembled (app-db/schema sections)))
    (app-db/schema {[:second-generation] :string})
    (let [reassembled (app-db/schema sections)]
      (is (not (identical? assembled reassembled)))
      (is (m/validate (dsl/->malli reassembled) {:first-generation {:count 1}})))))

(deftest dynamic-contracts-follow-explicit-state-deletions
  (let [path [:component "example" "<counter>" "one"]
        before (assoc-in {} path {:count 1})]
    (is (empty? (app-db/removed-sections [path] before before)))
    (is (empty? (app-db/removed-sections [path] before (assoc-in before path nil))))
    (is (empty? (app-db/removed-sections [path] {} {})))
    (is (= [path] (app-db/removed-sections [path] before {:component {}})))
    (is (= [path] (app-db/removed-sections [path] before (dissoc before :component))))))

(deftest explicit-coercion-is-shared-and-does-not-hide-invalid-input
  (let [schema [:tuple [:int {:min 1}] :boolean]]
    (is (= [3 false] (validation/decode schema ["3" "false"] :string)))
    (is (= ["3" "false"] (validation/decode schema ["3" "false"] nil)))
    (is (seq (validation/explain schema
                                (validation/decode schema ["invalid" "false"] :string)))))
  (is (= [:ready] (validation/decode [:tuple :keyword] ["ready"] :json)))
  (is (= [] (validation/decode [:tuple] [] :string)))
  (is (thrown? clojure.lang.ExceptionInfo (validation/decode :int 1 :unknown))))
