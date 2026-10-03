(ns tolgraven.supabase-import-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.provision.supabase.import :as importer]
            [tolgraven.provision.supabase.schema :as schema]
            [tolgraven.store.contract :as contract]))

(deftest full-import-backfills-private-documents-after-import-in-one-transaction
  (let [calls (atom []) tx {:connection :test} seed {:users []}]
    (with-redefs [platform/jdbc-available? (constantly true)
                  platform/database-spec (constantly {})
                  jdbc/db-transaction* (fn [_ f & _] (swap! calls conj :begin) (f tx) (swap! calls conj :commit))
                  importer/export->seed (constantly seed)
                  schema/apply-schema! (fn [db] (is (= tx db)) (swap! calls conj :schema))
                  importer/reset-data! (fn [db] (is (= tx db)) (swap! calls conj :reset))
                  importer/import-seed! (fn [db data] (is (= tx db)) (is (= seed data)) (swap! calls conj :import))]
      (is (= seed (importer/install-and-import! "unused")))
      (is (= [:begin :schema :reset :import :schema :commit] @calls)))))

(deftest scoped-import-only-upserts-selected-changes
  (let [calls (atom [])
        current {"strapi" {"auth" {:url "https://old.test"}}
                 "gpt-threads" {"private" {:user "owner" :messages []}}}
        incoming {"strapi" {"auth" {:url "https://new.test"}}}]
    (with-redefs [platform/jdbc-available? (constantly false)
                  importer/fetch-contract (constantly current)
                  importer/load-export! identity
                  contract/firebase-export->contract (constantly incoming)
                  importer/reset-data! (fn [& _] (throw (Exception. "Must never truncate")))
                  platform/upsert-rest! (fn [table rows] (swap! calls conj [table rows]))]
      (is (= "services" (:scope (importer/import-scope! "services" "unused"))))
      (is (= [:service_configs] (mapv first @calls)))
      (is (= "https://new.test" (get-in @calls [0 1 0 :config :url]))))))
