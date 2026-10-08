(ns tolgraven.state-schema-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [tolgraven.validation :as validation]
            [tolgraven.schema.app-db :as app-db]
            [tolgraven.validation.schema :as state]
            [tolgraven.modules.blog.schema :as blog]
            [tolgraven.dev-console.schema :as debug]
            [tolgraven.ssr.schema :as ssr]))

(deftest assembled-state-checks-nested-provider-data-and-view-state
  (let [schema (app-db/schema state/sections)
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

(deftest partial-app-db-sections-compose
  (let [schema (app-db/schema state/sections)
        data {:state {:menu false :blog {:page 0 :comment-limit {42 20}
                                         :comment-thread-expanded {[42 "a"] true}}}
              :unmigrated {:anything [:still :valid]}}]
    (is (m/validate schema data))
    (is (not (m/validate schema (assoc-in data [:state :menu] "true"))))
    (is (not (m/validate schema (assoc-in data [:state :blog :page] -1))))
    (is (empty? (app-db/changed-errors state/sections data data)))
    (is (= [] (:path (first (app-db/changed-errors state/sections data [])))))
    (is (= [:state :blog :comment-limit 42]
           (:path (first (app-db/changed-errors state/sections data
                          (assoc-in data [:state :blog :comment-limit 42] 0))))))))

