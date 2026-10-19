(require '[next.jdbc :as jdbc]
         '[website.config :as config]
         '[website.platform.supabase :as platform]
         '[website.provision.supabase.import :as importer]
         '[website.provision.supabase.schema :as schema])
;; Run only against a disposable Supabase-compatible database with fixture roles,
;; auth.uid/auth.jwt functions and the supabase_realtime publication installed.
(let [url (or (System/getenv "SUPABASE_SCHEMA_TEST_DB_URL")
              (throw (ex-info "Set SUPABASE_SCHEMA_TEST_DB_URL to a disposable database" {})))
      fail! #(throw (ex-info "Expected fixture failure" {}))]
  (with-redefs [config/env {}
                platform/database-spec (constantly url)
                platform/jdbc-available? (constantly true)]
    (assert (= {:applied? true} (schema/apply-schema!)))
    (assert (= {:applied? true} (schema/apply-schema!)))
    (println "PASS: actual selected schema resources install and reapply")
    (jdbc/execute! url ["insert into site_users(id) values ('original-fixture')"])
    (let [apply! schema/apply-schema!
          calls (atom 0)]
      (try
        (with-redefs [importer/export->seed (constantly {:users [{:id "replacement-fixture"}]})
                      schema/apply-schema! (fn [db]
                                             (if (= 2 (swap! calls inc)) (fail!) (apply! db)))]
          (importer/install-and-import! "unused-fixture-export"))
        (throw (ex-info "Failed import unexpectedly committed" {}))
        (catch clojure.lang.ExceptionInfo error
          (assert (= "Expected fixture failure" (.getMessage error)))))
      (assert (= [{:id "original-fixture"}]
                 (mapv #(select-keys % [:id]) (:users (platform/fetch-seed)))))
      (println "PASS: actual schema, reset and import roll back together after backfill failure"))))
