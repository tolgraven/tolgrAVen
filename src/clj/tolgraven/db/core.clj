(ns tolgraven.db.core
  (:require
    [datomic.api :as d]
    [io.rkn.conformity :as c]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as timbre]
    [clojure.pprint :refer [pprint]]
    [tolgraven.config :refer [env]]))

(defstate ^{:on-reload :noop} conn
  :start (do (-> env :database-url d/create-database)
             (-> env :database-url d/connect))
  :stop (-> conn .release))

(defn install-schema
  "This function expected to be called at system start up.

  Datomic schema migrations or db preinstalled data can be put into 'migrations/schema.edn'
  Every txes will be executed exactly once no matter how many times system restart."
  [conn]
  (let [norms-map (c/read-resource "migrations/schema.edn")]
    (c/ensure-conforms conn norms-map (keys norms-map))))

(defstate ^{:on-reload :noop} schema
  :start (install-schema conn))

(defn show-schema
  "Show currently installed schema"
  [conn]
  (let [system-ns #{"db" "db.type" "db.install" "db.part"
                    "db.lang" "fressian" "db.unique" "db.excise"
                    "db.cardinality" "db.fn" "db.sys" "db.bootstrap"
                    "db.alter"}]
    (d/q '[:find ?ident
           :in $ ?system-ns
           :where
           [?e :db/ident ?ident]
           [(namespace ?ident) ?ns]
           [((comp not contains?) ?system-ns ?ns)]]
         (d/db conn) system-ns)))


(defn show-transaction "(-> conn show-transaction count) => the number of transaction"
  [conn]
  (seq (d/tx-range (d/log conn) nil nil)))

(defn add-user
  "(add-user conn {:id \"aaa\"
                   :screen-name \"AAA\"
                   :status :user.status/active
                   :email \"aaa@example.com\"
                   :role :user.role/admin})"
  [conn {:keys [id screen-name status email role]}]
  @(d/transact conn [{:user/id         id
                      :user/name       screen-name
                      :user/status     status
                      :user/email      email
                      :user/role       role}]))

(defn find-one-by
  "Given db value and an (attr/val), return the user as EntityMap (datomic.query.EntityMap)
   If there is no result, return nil.
   e.g.
    (d/touch (find-one-by (d/db conn) :user/email \"user@example.com\"))
    => show all fields
    (:user/first-name (find-one-by (d/db conn) :user/email \"user@example.com\"))
    => show first-name field"
  [db attr val]
  (d/entity db
            ;;find Specifications using ':find ?a .' will return single scalar
            (d/q '[:find ?e .
                   :in $ ?attr ?val
                   :where [?e ?attr ?val]]
                 db attr val)))


(defn find-user [db id]
  (if-let [user (find-one-by db :user/id id)]
    (d/touch user)))

(defn get-user [id]
  (find-user (d/db conn) id))

(defn get-by-field [field v]
  (let [db (d/db conn)
        hit (find-one-by db field v)]
    (when hit
      (d/touch hit))))


(defn add-blog "(add-blog {:id 1 :user \"tolgraven\"
                           :title \"fuk em\" :text \"all the text hey\""
  [{:keys [id user title text]}]
  @(d/transact conn [{:blog-post/id       id
                      :blog-post/user     user
                      :blog-post/title    title
                      :blog-post/text     text}]))

(defn add "just pass the fucking input"
  [m]
  @(d/transact conn m))

(defn get-blog "Get all the blog posts"
  [id]
  (get-by-field :blog-post/id id))


(defstate ^{:on-reload :noop} install-dummy-data
  :start (do (add [{:blog-post/id "1" :blog-post/user "tolgraven" :blog-post/title "First blog" :blog-post/text "very short"}])
             (add [{:blog-post/id "2" :blog-post/user "tolgraven" :blog-post/title "Second blog: the seconding: ascetic buggaboo" :blog-post/text "slighrtly longer"}])
             (add-user conn {:id "tolgraven" :screen-name "tolgraven"
                :status :user.status/active
                :role :user.role/admin
                :email "joen.tolgraven@gmail.com"})))

; (install-schema conn)
; (show-schema conn)
; (get-user "tolgraven")
; (find-user (d/db conn) "tolgraven")
; (get-blogs)
; (timbre/info (-> (show-transaction conn) first ))
