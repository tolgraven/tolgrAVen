(ns tolgraven.supabase-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [mount.core :as mount]
            [cprop.core :as cprop]
            [cprop.source :as source]
            [tolgraven.config :as config]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.provision.supabase.cli :as cli])
  (:import [java.io File]))

(deftest cprop-file-and-environment-values-reach-the-platform-adapter
  (let [config-file (File/createTempFile "supabase-config-" ".edn")
        env-file (File/createTempFile "supabase-env-" ".env")]
    (try
      (spit config-file
            (pr-str {:supabase-public-url "https://file.example"
                     :supabase-service-key "file-key"
                     :postgres-hostname "db.example"
                     :postgres-db "site"
                     :postgres-user "backend"
                     :postgres-password "private &+=?"
                     :supabase-db-sslmode "require"}))
      (spit env-file (str "SUPABASE_PUBLIC_URL=https://environment.example\n"
                         "SUPABASE_SERVICE_KEY=environment-key\n"
                         "POSTGRES_PORT=6543\n"
                         "SUPABASE_INSECURE_TLS=true\n"))
      (with-redefs [source/read-system-env (constantly {})
                    source/read-system-props (constantly {})
                    source/from-resource (constantly {})]
        (with-redefs [config/env (cprop/load-config :file (.getPath config-file)
                                                  :merge [(source/from-env-file (.getPath env-file))])]
          (is (= "https://environment.example" (platform/rest-base-url)))
          (is (= "environment-key" (platform/service-key)))
          (is (true? (platform/insecure-rest?)))
          (is (= "jdbc:postgresql://db.example:6543/site?user=backend&password=private+%26%2B%3D%3F&sslmode=require"
                 (platform/database-spec)))
          (is (= {:source :components
                  :configured? true
                  :host "db.example"
                  :port 6543
                  :database "site"
                  :user "backend"
                  :sslmode "require"}
                 (platform/database-target-summary)))))
      (finally
        (.delete config-file)
        (.delete env-file)))))

(deftest consolidated-configuration-preserves-connection-aliases
  (testing "REST origins and server keys"
    (doseq [key [:supabase-public-url :supabase-url :next-public-supabase-url]]
      (with-redefs [config/env {key "https://supabase.example/"}]
        (is (= "https://supabase.example/rest/v1/blog_posts" (platform/rest-url "blog_posts")))))
    (doseq [key [:supabase-service-key :supabase-service-role-key :service-supabaseservice-key]]
      (with-redefs [config/env {key "server-key"}]
        (is (= "server-key" (platform/service-key))))))
  (testing "direct JDBC URLs take precedence over component settings without exposing them"
    (doseq [key [:supabase-database-url :supabase-db-url]]
      (with-redefs [config/env {key "jdbc:postgresql://db.example/site?password=private"
                               :postgres-hostname "other.example"}]
        (is (= "jdbc:postgresql://db.example/site?password=private" (platform/database-spec)))
        (is (= {:source :database-url, :configured? true} (platform/database-target-summary))))))
  (testing "Postgres and PG component aliases retain defaults"
    (doseq [settings [{:postgres-host "db.example"
                      :postgres-database "site"
                      :postgres-user "backend"
                      :postgres-password "private"}
                     {:pghost "db.example"
                      :pgdatabase "site"
                      :pguser "backend"
                      :pgpassword "private"}]]
      (with-redefs [config/env settings]
        (is (= "jdbc:postgresql://db.example:5432/site?user=backend&password=private&sslmode=disable"
               (platform/database-url)))))
    (with-redefs [config/env {:pghost "db.example"
                             :pgdatabase "site"
                             :pguser "backend"
                             :pgpassword "private"
                             :pgport 5433
                             :postgres-sslmode "verify-full"}]
      (is (= "jdbc:postgresql://db.example:5433/site?user=backend&password=private&sslmode=verify-full"
             (platform/database-url))))))

(deftest tls-policy-accepts-cprop-values-and-keeps-verification-by-default
  (doseq [value [true 1 "1" "true" "TRUE" "yes" "YES"]]
    (with-redefs [config/env {:supabase-insecure-tls value}]
      (is (true? (platform/insecure-rest?)))))
  (doseq [value [nil false 0 "0" "false" "no" ""]]
    (with-redefs [config/env {:supabase-insecure-tls value}]
      (is (false? (platform/insecure-rest?))))))

(deftest missing-configuration-remains-actionable-without-credentials
  (with-redefs [config/env {:postgres-hostname "db.example"
                           :postgres-password "private"}]
    (is (nil? (platform/database-url)))
    (is (false? (platform/jdbc-available?)))
    (is (= :missing (:source (platform/database-target-summary))))
    (doseq [resolve! [platform/database-spec platform/rest-base-url platform/service-key]]
      (let [error (try (resolve!) (catch clojure.lang.ExceptionInfo error error))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (seq (:config-keys (ex-data error))))
        (is (not (.contains (pr-str (ex-data error)) "private")))))))

(deftest provisioning-doctor-starts-only-config-and-reports-consolidated-values
  (let [*started (atom nil)]
    (doseq [[settings expected] [[{:supabase-db-url "jdbc:postgresql://db.example/site?password=private"
                                  :supabase-url "https://supabase.example"
                                  :service-supabaseservice-key "private"}
                                 {:database {:source :database-url, :configured? true}
                                  :rest-url-configured? true
                                  :service-key-configured? true}]
                                [{} {:database {:source :missing
                                                :configured? false
                                                :expected-config {:database-url [:supabase-database-url :supabase-db-url]
                                                                  :host [:postgres-hostname :postgres-host :pghost]
                                                                  :database [:postgres-db :postgres-database :pgdatabase]
                                                                  :user [:postgres-user :pguser]
                                                                  :password [:postgres-password :pgpassword]
                                                                  :port [:postgres-port :pgport]
                                                                  :sslmode [:supabase-db-sslmode :postgres-sslmode]}}
                                     :rest-url-configured? false
                                     :service-key-configured? false}]]]
      (with-redefs [config/env settings
                    mount/start (fn [& states] (reset! *started states))]
        (let [output (with-out-str (cli/-main "doctor"))]
          (is (= [#'config/env] @*started))
          (is (= (with-out-str (println expected)) output))
          (is (not (.contains output "private"))))))))

(deftest declarative-aliases-use-the-first-configured-value
  (with-redefs [config/env {:supabase-public-url "https://preferred.example"
                           :supabase-url "https://other.example"
                           :supabase-service-key "preferred-key"
                           :supabase-service-role-key "other-key"
                           :supabase-database-url "jdbc:postgresql://preferred.example/site"
                           :supabase-db-url "jdbc:postgresql://other.example/site"}]
    (is (= "https://preferred.example" (platform/rest-base-url)))
    (is (= "preferred-key" (platform/service-key)))
    (is (= "jdbc:postgresql://preferred.example/site" (platform/database-url))))
  (with-redefs [config/env {:supabase-public-url nil
                           :supabase-url "https://fallback.example"
                           :supabase-service-key nil
                           :supabase-service-role-key "fallback-key"}]
    (is (= "https://fallback.example" (platform/rest-base-url)))
    (is (= "fallback-key" (platform/service-key)))))

(deftest missing-settings-report-their-declared-config-aliases
  (with-redefs [config/env {}]
    (doseq [[resolve! expected] [[platform/rest-base-url
                                 [:supabase-public-url :supabase-url :next-public-supabase-url]]
                                [platform/service-key
                                 [:supabase-service-key :supabase-service-role-key :service-supabaseservice-key]]]]
      (let [error (try (resolve!) (catch clojure.lang.ExceptionInfo error error))]
        (is (= {:config-keys expected} (ex-data error)))))
    (let [expected (:expected-config (platform/database-target-summary))
          error (try (platform/database-spec) (catch clojure.lang.ExceptionInfo error error))]
      (is (= expected (:config-keys (ex-data error))))
      (is (= [:postgres-password :pgpassword] (:password expected))))))
