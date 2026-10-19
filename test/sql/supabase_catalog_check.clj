(require '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as result-set]
         '[clojure.string :as string]
         '[website.config :as config]
         '[website.supabase.catalog :as catalog]
         '[website.platform.supabase :as platform]
         '[website.provision.supabase.import :as importer]
         '[website.provision.supabase.schema :as schema])
(defn ensure! [condition message] (when-not condition (throw (ex-info message {}))))
;; All fixture DDL and data live in a unique schema and roll back together.
;; Neither installed tables nor database credentials are printed.
(let [url (or (System/getenv "SUPABASE_CATALOG_TEST_DB_URL")
              (throw (ex-info "Set SUPABASE_CATALOG_TEST_DB_URL to a test PostgreSQL JDBC URL" {})))
      schema-name (str "catalog_fixture_" (string/replace (str (java.util.UUID/randomUUID)) "-" ""))]
  (jdbc/with-transaction [db url {:rollback-only true}]
    (try
    (jdbc/execute! db [(str "create schema " schema-name)])
    (jdbc/execute! db [(str "set local search_path to " schema-name)])
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
                 "create table website_search_documents(id text primary key)"
                 "create table website_search_consumers(id text primary key)"
                 "create table website_search_jobs(consumer text references website_search_consumers(id), id text references website_search_documents(id))"
                 "create table site_media(id text primary key, asset jsonb, metadata jsonb)"
                 "create table github_repositories(owner text, repo text, primary key(owner, repo))"
                 "create table github_commits(owner text, repo text, sha text, foreign key(owner, repo) references github_repositories(owner, repo))"
                 "create table user_documents(owner_id text references site_users(id), collection text, doc_id text, data jsonb, primary key(owner_id,collection,doc_id))"]]
      (jdbc/execute! db [sql]))
    (with-redefs [config/env {}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (let [tx db]
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
        (ensure! (zero? (:n (first (jdbc/execute! db [(str "select count(*) as n from " (name table))]
                                               {:builder-fn result-set/as-unqualified-lower-maps}))))
                 "Declared table remained populated after reset"))
      (println "PASS: real JDBC import, JSONB, reads and explicit catalog FK reset"))
    ;; A supplied schema connection must join, never commit this outer transaction.
    (with-redefs [platform/jdbc-available? (constantly true)
                  schema/schema-sql (constantly "create table joined_schema_probe(id integer)")]
      (schema/apply-schema! db)
      (with-open [observer (jdbc/get-connection url)]
        (ensure! (nil? (:relation (jdbc/execute-one! observer
                          ["select to_regclass(?) as relation" (str schema-name ".joined_schema_probe")])))
                 "Schema application committed its caller's transaction")))
    ;; The comments-disabled site has only its post table.
    (jdbc/execute! db ["drop table comment_votes, blog_comments"])
    (with-redefs [config/env {:supabase {:modules {:blog {:comments false}}}}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (ensure! (contains? (platform/fetch-seed) :blog_posts) "Enabled posts were omitted")
      (ensure! (not (contains? (platform/fetch-seed) :blog_comments)) "Disabled comments were fetched")
      (importer/reset-data! db)
      (println "PASS: reads and reset on real database without comment tables"))
    ;; The no-blog site really has no blog tables, exercising missing-table avoidance.
    (jdbc/execute! db ["drop table blog_posts"])
    (with-redefs [config/env {:supabase {:modules {:blog {:enabled false}}}}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (ensure! (not (contains? (platform/fetch-seed) :blog_posts)) "Disabled blog was fetched")
      (importer/reset-data! db)
      (println "PASS: reads and reset on real database without blog tables"))
    ;; A newly declared domain uses the same platform with no platform code changes.
    (jdbc/execute! db ["create table portfolio_items(slug text primary key, details jsonb)"])
    (with-redefs [config/env {}
                  catalog/definitions {:portfolio [{:table :portfolio_items :seed-key :portfolio :key [:slug]
                                                    :order [[:slug :asc]] :jsonb #{:details}}]}
                  platform/database-spec (constantly db)
                  platform/jdbc-available? (constantly true)]
      (importer/import-seed! db {:portfolio [{:slug "one" :details {:title "Item"}}]})
      (ensure! (= "one" (:slug (first (:portfolio (platform/fetch-seed))))) "Custom table was not read")
      (importer/reset-data! db)
      (println "PASS: real JDBC custom domain table, JSONB and reset"))
      (finally nil))))

;; Standalone imports also roll back every row in the table on a later error,
;; while heterogeneous maps leave omitted optional columns to PostgreSQL defaults.
(let [url (System/getenv "SUPABASE_CATALOG_TEST_DB_URL")
      table-name (str "jdbc_import_fixture_" (string/replace (str (java.util.UUID/randomUUID)) "-" ""))
      table (keyword table-name)
      declaration {:table table, :seed-key :fixture, :key [:id], :order [[:id :asc]]}]
  (jdbc/execute! url [(str "create table " table-name "(id text primary key, label text default 'default')")])
  (try
    (with-redefs [config/env {}
                  catalog/definitions {:fixture [declaration]}
                  platform/database-spec (constantly url)
                  platform/jdbc-available? (constantly true)]
      (try
        (importer/import-seed! {:fixture [{:id "one" :label "first"} {:id "one"}]})
        (throw (ex-info "Duplicate insert unexpectedly succeeded" {}))
        (catch java.sql.SQLException _))
      (ensure! (zero? (:n (jdbc/execute-one! url [(str "select count(*) as n from " table-name)])))
               "Failed standalone import left an earlier row committed")
      (importer/import-seed! {:fixture [{:id "one" :label "first"} {:id "two"}]})
      (ensure! (= ["first" "default"] (mapv :label (:fixture (platform/fetch-seed))))
               "Heterogeneous import lost optional-column defaults")
      (println "PASS: standalone table rollback and heterogeneous row defaults"))
    (finally (jdbc/execute! url [(str "drop table " table-name)]))))
