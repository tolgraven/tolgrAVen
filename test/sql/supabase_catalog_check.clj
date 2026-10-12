(require '[clojure.java.jdbc :as jdbc]
         '[clojure.string :as string]
         '[tolgraven.config :as config]
         '[tolgraven.supabase.catalog :as catalog]
         '[tolgraven.platform.supabase :as platform]
         '[tolgraven.provision.supabase.import :as importer])
(defn ensure! [condition message] (when-not condition (throw (ex-info message {}))))
;; All fixture DDL and data live in a unique schema and roll back together.
;; Neither installed tables nor database credentials are printed.
(let [url (or (System/getenv "SUPABASE_CATALOG_TEST_DB_URL")
              (throw (ex-info "Set SUPABASE_CATALOG_TEST_DB_URL to a test PostgreSQL JDBC URL" {})))
      schema-name (str "catalog_fixture_" (string/replace (str (java.util.UUID/randomUUID)) "-" ""))]
  (jdbc/with-db-transaction [db url]
    (try
    (jdbc/db-do-commands db false [(str "create schema " schema-name)
                                  (str "set local search_path to " schema-name)])
    ;; This database is disposable: create the real current table shapes/FKs without
    ;; installed Supabase roles, and exercise actual JDBC rather than replacements.
    (doseq [sql ["create table site_users(id text primary key, comments jsonb, voted jsonb, raw jsonb)"
                 "create table auth_roles(role text, user_id text references site_users(id), primary key(role,user_id))"
                 "create table blog_posts(id bigint primary key, raw jsonb, user_id text references site_users(id))"
                 "create table blog_comments(id text primary key, ts bigint, path jsonb, raw jsonb, parent_post bigint references blog_posts(id), user_id text references site_users(id))"
                 "create table chat_messages(message_id text primary key, raw jsonb)"
                 "create table service_configs(service text primary key, config jsonb)"
                 "create table store_documents(collection text, doc_id text, data jsonb, primary key(collection,doc_id))"
                 "create table comment_votes(user_id text references site_users(id), comment_id text references blog_comments(id), primary key(user_id,comment_id))"
                 "create table user_documents(owner_id text references site_users(id), collection text, doc_id text, data jsonb, primary key(owner_id,collection,doc_id))"]]
      (jdbc/db-do-commands db false sql))
    (with-redefs [config/env {}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (jdbc/with-db-transaction [tx db]
        (importer/import-seed! tx {:users [{:id "one" :comments [] :voted {} :raw {}}]
                                  :roles [{:role "admins" :user_id "one"}]
                                  :blog_posts [{:id 1 :user_id "one" :raw {}}]
                                  :blog_comments [{:id "comment" :ts 1 :parent_post 1 :user_id "one" :path [1] :raw {}}]
                                  :chat_messages [{:message_id "chat" :raw {}}]
                                  :service_configs [{:service "service" :config {:url "example"}}]
                                  :store_documents [{:collection "custom" :doc_id "doc" :data {:text "one"}}]}))
      (ensure! (= 1 (count (:blog_comments (platform/fetch-seed)))) "JDBC seed fetch missing comment")
      (jdbc/execute! db ["insert into comment_votes values ('one','comment')"])
      (jdbc/execute! db ["insert into user_documents values ('one','gpt','thread','{}'::jsonb)"])
      (importer/reset-data! db)
      (doseq [table (platform/delete-order)]
        (ensure! (zero? (:n (first (jdbc/query db [(str "select count(*) as n from " (name table))]))))
                 "Declared table remained populated after reset"))
      (println "PASS: real JDBC import, JSONB, reads and explicit nine-table FK reset"))
    ;; The comments-disabled site has only its post table.
    (jdbc/db-do-commands db false "drop table comment_votes, blog_comments")
    (with-redefs [config/env {:supabase {:modules {:blog {:comments false}}}}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (ensure! (contains? (platform/fetch-seed) :blog_posts) "Enabled posts were omitted")
      (ensure! (not (contains? (platform/fetch-seed) :blog_comments)) "Disabled comments were fetched")
      (importer/reset-data! db)
      (println "PASS: reads and reset on real database without comment tables"))
    ;; The no-blog site really has no blog tables, exercising missing-table avoidance.
    (jdbc/db-do-commands db false "drop table blog_posts")
    (with-redefs [config/env {:supabase {:modules {:blog {:enabled false}}}}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (ensure! (not (contains? (platform/fetch-seed) :blog_posts)) "Disabled blog was fetched")
      (importer/reset-data! db)
      (println "PASS: reads and reset on real database without blog tables"))
    ;; A newly declared domain uses the same platform with no platform code changes.
    (jdbc/db-do-commands db false "create table portfolio_items(slug text primary key, details jsonb)")
    (with-redefs [config/env {}
                  catalog/definitions {:portfolio [{:table :portfolio_items :seed-key :portfolio :key [:slug]
                                                    :order [[:slug :asc]] :jsonb #{:details}}]}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (importer/import-seed! db {:portfolio [{:slug "one" :details {:title "Item"}}]})
      (ensure! (= "one" (:slug (first (:portfolio (platform/fetch-seed))))) "Custom table was not read")
      (importer/reset-data! db)
      (println "PASS: real JDBC custom domain table, JSONB and reset"))
      (finally (jdbc/db-set-rollback-only! db)))))
