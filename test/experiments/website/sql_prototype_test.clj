(ns website.sql-prototype-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.jdbc :as jdbc]
            [honey.sql :as honey]
            [website.db.sql :as prototype]))

(deftest generated-sql-retains-parameters-and-clauses
  (let [queries (atom [])]
    (with-redefs [prototype/query (fn [query] (swap! queries conj (honey/format query)) [])]
      (is (= [] (prototype/get-all-2)))
      (prototype/get-blog 42)
      (prototype/get-from [:blog] [:id :title])
      (prototype/get-from [[:blog :b]] [[:b.title :title]])
      (prototype/get-from :blog :* :where [:= :user_name "name' OR 1=1"] :order-by [[:id :desc]])
      (is (= [["SELECT * FROM blog"]
              ["SELECT * FROM blog WHERE id = ?" 42]
              ["SELECT id, title FROM blog"]
              ["SELECT b.title AS title FROM blog AS b"]
              ["SELECT * FROM blog WHERE user_name = ? ORDER BY id DESC" "name' OR 1=1"]]
             @queries)))))

(deftest actual-postgresql-prototype-round-trip
  (when-let [url (System/getenv "SUPABASE_CATALOG_TEST_DB_URL")]
    (jdbc/with-db-transaction [db url]
      (try
        ;; PostgreSQL temporary tables and the transaction isolate this retained
        ;; prototype from every installed table and from all other test sessions.
        (jdbc/db-do-commands db false
          "create temporary table blog(id serial primary key, user_name text, title text, text text) on commit drop")
        (with-redefs [prototype/uri db]
          (prototype/create-post ["fixture" "First" "Body"])
          (prototype/insert :blog {:user_name "other" :title "Second" :text "Body 2"})
          (is (= ["First" "Second"] (mapv :title (prototype/get-all-2))))
          (is (= [{:id 1 :title "First"} {:id 2 :title "Second"}]
                 (prototype/get-from [:blog] [:id :title])))
          (is (= ["First" "Second"]
                 (mapv :title (prototype/get-from [[:blog :b]] [[:b.title :title]]))))
          (is (= "First" (:title (first (prototype/get-blog 1)))))
          (is (= ["Second"] (mapv :title (prototype/get-from :blog :* :where [:= :user_name "other"]))))
          (is (= ["Second" "First"] (mapv :title (prototype/get-from :blog :* :order-by [[:id :desc]])))))
        (finally (jdbc/db-set-rollback-only! db))))))
