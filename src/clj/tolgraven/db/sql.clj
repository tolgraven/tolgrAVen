(ns tolgraven.db.sql
  (:require
   [clojure.java.jdbc :as sql]
   [honeysql.core :as hsql :refer [build]]
   [honeysql.helpers :as helpers :refer [select from where insert-into columns values]]
   [mount.core :refer [defstate]]
   [taoensso.timbre :as timbre]
   [clojure.tools.logging :as log]
   [tolgraven.config :refer [env]]))

(def app-name "tolgraven")
(def uri (or (str "postgresql://localhost:5432/" app-name)
             (-> env :database-url)))

(defn command [cmd]
  (sql/db-do-commands uri cmd))

(defn query [q]
  (sql/query uri (hsql/format q)))


(def schema {:user_site
             [[:user_name :varchar "NOT NULL"]
              [:email :varchar "NOT NULL"]
              [:password :varchar "NOT NULL"] ;then figure out hashing and that... tho can still use char who cares.
              [:avatar :varchar "NULL"] ; like, url I guess
              ; :score :int "NULL"  ;then q whether store karma in user, seems more reasonable than select score for each comment each lookup heh.
              ; :roles :somecoll "NULL" or
              [:admin :int "NULL"]
              [:blogger :int "NULL"]]
             :blog
             [ ; [:seq_id :int "NOT NULL"] ; guessing might not need in any case if always sorting blogs n comments n replies by id anyways - will always be in right order
              [:user_name :varchar "NOT NULL"]
              [:title :varchar "NOT NULL"]
              [:text :varchar "NOT NULL"]]
             :comment
             [[:user_name :varchar "NOT NULL"]
              [:blog_id :int "NOT NULL"]
              [:parent_id :int "NULL"] ;at least blog parent. but then if switch to comment parent, collisions...
              [:title :varchar "NULL"]
              [:text :varchar "NOT NULL"]
              [:score :int "NOT NULL"]]
             })

(defn migrated? []
  (-> (sql/query uri
       [(str "select count(*) from information_schema.tables "
             "where table_name='shouts'")])
      first :count pos?))

(defn migrate []
  (timbre/info "Ensuring database structure...")
  (when (migrated?)
    (doseq [[table definition] schema
            :let [id [[:id :serial "PRIMARY KEY"]]
                  ; uuid [[:uuid :varchar "NOT NULL"]]
                  ts [[:created_at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]]]]
      (do
       ; (timbre/info (vec (concat id #_uuid definition ts)))
       (sql/db-do-commands uri
                           (sql/create-table-ddl
                            table
                            (vec (concat id #_uuid definition ts))))
       (log/info (str "Migrated " (name table)))))
    (log/info "Migrated db structure")))

(defstate ^{:on-reload :noop} migration
  :start (do
          (migrate))
  :stop (try (do
          (command "drop table blog")
          (command "drop table comment")
          (command "drop table user_site"))
             (catch Exception _)))


(defn create-post [[user title text]]
  (sql/insert! uri :blog {:user_name user
                          :title title
                          :text text}))

(defn insert [table row-map]
  (sql/insert! uri table row-map))


(def dummy-data
  {:user_site [{:user_name "tolgraven"
                :email "joen.tolgraven@gmail.com"
                :password "testing"
                ; :uuid "arfarf124"
                :admin 1
                :blogger 1}
               {:user_name "seqable"
                :email "ertyytre@gmail.com"
                :password "testing2"
                ; :uuid "kladsfhj39"
                :blogger 1}]
   :blog [{:user_name "tolgraven"
           ; :uuid "affad88"
           :title "Title 1"
           :text "A bunch of md yo. What about imgs for that btw? Should def make a drag n drop kinda thing yo"}
          {:user_name "seqable"
           ; :uuid "alksjd3819"
           :title "Guest blog by alter ego"
           :text "He's a very quiet fellow so might not get much out it..."}]})


; (defstate ^{:on-reload :noop} install-dummy-data
;   :start (doseq [[table content] dummy-data
;                piece content]
;            (insert table piece)))


(for [[table content] dummy-data]
  (println table))
(defn get-all []
  (into [] (sql/query uri ["select * from blog order by id desc"])))
(defn get-all-2 []
  (->> (build :select :*
              :from [:blog]
              ; :order-by [[:id :desc]]
              )
       query
       (into [])))

(defn get-from [from select & args]
  (->> (apply build
              :from from
              :select select
              args)
       query
       (into [])))

(defn get-blog [id]
  (get-from :blog :* :where [:= :id id]))

; (get-blog 1)
; (get-from :* :blog 
;           :where [:= :user_name "seqable"]
;           :order-by [[:id :desc]])
; (log/error "WTF")
; (+ 1 1)
; (dummy-data)
; (get-all-2)
