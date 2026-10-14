(ns website.supabase-catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [website.config :as config]
            [website.build.modules :as modules]
            [website.supabase.catalog :as catalog]
            [website.platform.supabase :as platform]
            [website.provision.supabase.import :as importer]
            [website.validation :as validation]))

(defn options [settings] {:supabase {:modules settings}})

(deftest declared-tables-match-existing-contracts
  (with-redefs [config/env {}]
    (is (= #{:site_users :auth_roles :blog_posts :blog_comments :chat_messages
             :service_configs :store_documents :site_media} (set (platform/table-order))))
    (is (= #{:comment_votes :user_documents}
           (set (remove (set (platform/table-order)) (platform/delete-order)))))
    (doseq [{:keys [table references]} (platform/table-definitions)
            dependency references]
      (let [order (mapv :table (platform/table-definitions))]
        (is (< (.indexOf order dependency) (.indexOf order table)))))
    (is (= "role,user_id" (:on-conflict (platform/table-config :auth_roles))))
    (is (= "collection.asc,doc_id.asc" (:order (platform/table-config :store_documents))))
    (is (= "ts.asc,id.asc" (:order (platform/table-config :blog_comments))))
    (is (= "collection=not.is.null" (:delete-filter (platform/table-config :store_documents))))
    (doseq [table (mapcat val catalog/definitions)]
      (is (= table (validation/check! "table" catalog/table table))))))

(deftest configuration-selects-owners-and-subfeatures
  (with-redefs [config/env (options {:blog {:enabled false}})]
    (is (not-any? #{:blog_posts :blog_comments :comment_votes} (platform/delete-order)))
    (is (some #{:chat_messages} (platform/table-order))))
  (with-redefs [config/env (options {:blog {:comments false}})]
    (is (some #{:blog_posts} (platform/table-order)))
    (is (not-any? #{:blog_comments :comment_votes} (platform/delete-order)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disabled or undeclared"
                         (platform/table-config :blog_comments))))
  (doseq [settings [{:blog {:comment false}} {:missing {:enabled false}}
                    {:blog {:enabled "false"}} {:user {:enabled false}}]]
    (with-redefs [config/env (options settings)]
      (is (thrown? clojure.lang.ExceptionInfo (platform/table-order))))))

(deftest custom-owner-needs-no-platform-table-list
  (let [declaration {:table :portfolio_items
                     :seed-key :portfolio
                     :key [:slug]
                     :order [[:slug :asc]]
                     :jsonb #{:details}}
        calls (atom [])]
    (with-redefs [config/env {}
                  catalog/definitions {:portfolio [declaration]}
                  platform/jdbc-available? (constantly false)
                  platform/request! (fn [method table opts]
                                      (swap! calls conj [method table opts])
                                      {:body (if (and (= :get method)
                                                      (zero? (get-in opts [:query-params "offset"])))
                                               [{:slug "one" :details {}}] [])})]
      (is (= {:portfolio [{:slug "one" :details {}}]} (platform/fetch-seed)))
      (importer/import-seed! {:portfolio [{:slug "one" :details {}}]})
      (importer/reset-data!)
      (is (= [:get :get :post :delete] (mapv first @calls)))
      (is (every? #(= "portfolio_items" (second %)) @calls))
      (is (= "slug" (get-in @calls [2 2 :query-params "on_conflict"])))
      (is (= "not.is.null" (get-in @calls [3 2 :query-params "slug"])))
      (is (= "jsonb" (.getType (:details (platform/prepare-row :portfolio_items {:details {}})))))
      (is (= "plain" (:data (platform/prepare-row :portfolio_items {:data "plain"})))))))

(deftest disabled-tables-are-not-read-or-imported-through-either-transport
  (doseq [jdbc? [false true]]
    (let [reads (atom []) writes (atom [])]
      (with-redefs [config/env (options {:blog {:enabled false} :gpt {:enabled false}})
                    platform/jdbc-available? (constantly jdbc?)
                    platform/database-spec (constantly {})
                    jdbc/query (fn [_ [sql]] (swap! reads conj sql) [])
                    jdbc/insert-multi! (fn [_ table rows] (swap! writes conj table))
                    platform/request! (fn [method table _] (swap! reads conj table) {:body []})
                    platform/upsert-rest! (fn [table rows] (when (seq rows) (swap! writes conj table)))]
        (is (not (contains? (platform/fetch-seed) :blog_posts)))
        (is (not-any? #(string/includes? % "blog_") @reads))
        (importer/import-seed! {:users [{:id "one"}]
                               :blog_posts [{:id 1}]
                               :blog_comments [{:id "comment"}]})
        (is (= [:site_users] @writes))))))

(deftest jdbc-and-rest-share-projections-and-sorting
  (let [sql (atom []) rest-opts (atom [])]
    (with-redefs [config/env {}
                  platform/database-spec (constantly {})
                  platform/jdbc-available? (constantly true)
                  jdbc/query (fn [_ [query]] (swap! sql conj query) [])]
      (platform/fetch-seed)
      (is (some #{"select role,user_id from auth_roles order by role asc,user_id asc"} @sql))
      (is (some #{"select * from blog_comments order by ts asc,id asc"} @sql)))
    (with-redefs [config/env {}
                  platform/jdbc-available? (constantly false)
                  platform/request! (fn [_ table opts] (swap! rest-opts conj [table (:query-params opts)]) {:body []})]
      (platform/fetch-seed)
      (is (some #{["auth_roles" {"select" "role,user_id", "order" "role.asc,user_id.asc",
                                "offset" 0, "limit" 500}]} @rest-opts)))))

(deftest reset-is-explicit-and-empty-catalog-is-inert
  (let [sql (atom [])]
    (with-redefs [config/env {}
                  platform/jdbc-available? (constantly true)
                  platform/database-spec (constantly {})
                  jdbc/db-do-commands (fn [_ statement] (swap! sql conj statement))]
      (importer/reset-data!)
      (is (string/includes? (first @sql) "comment_votes"))
      (is (string/includes? (first @sql) "user_documents"))
      (is (not (string/includes? (first @sql) "cascade"))))
    (with-redefs [catalog/definitions {}
                  config/env {}
                  platform/jdbc-available? (constantly true)
                  jdbc/db-do-commands (fn [& _] (throw (Exception. "Empty reset must be inert")))]
      (is (nil? (importer/reset-data!))))))

(deftest invalid-dependency-graphs-are-rejected
  (let [base {:key [:id], :order [[:id :asc]]}]
    (doseq [definitions [{:a [(assoc base :table :a :references #{:b})]}
                         {:a [(assoc base :table :a) (assoc base :table :a)]}
                         {:a [(assoc base :table :a :references #{:b})
                              (assoc base :table :b :references #{:a})]}]]
      (is (thrown? clojure.lang.ExceptionInfo (catalog/selected-tables definitions {}))))))

(deftest discovery-resolves-portable-owner-and-tracks-both-sources
  (let [file (java.io.File/createTempFile "storage-module" ".cljs")
        reads (atom [])]
    (try
      (spit file "(ns example.module (:require [website.modules.blog.storage :as storage]))\n(def spec {:id :example :storage storage/tables :init #(js/alert :never)})")
      (is (= 'website.modules.blog.storage/tables (:storage (modules/declaration file))))
      (spit file "(ns example.module)\n(def spec {:id :example :storage (identity [])})")
      (is (thrown? clojure.lang.ExceptionInfo (modules/declaration file)))
      (finally (.delete file)))
    (let [original requiring-resolve]
      (with-redefs [clojure.core/requiring-resolve
                    (fn [sym]
                      (if (= sym 'shadow.resource/slurp-resource)
                        (fn [_ path] (swap! reads conj path) "")
                        (original sym)))]
        ((deref #'modules/storage-definitions) nil {:ns {:name 'example.catalog}})
        (is (some #{"website/modules/blog/module.cljs"} @reads))
        (is (some #{"website/modules/blog/storage.cljc"} @reads))))))
