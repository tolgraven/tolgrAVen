(ns tolgraven.provision.supabase.cli
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [taoensso.timbre :as timbre]
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.provision.supabase.import :as import]
   [tolgraven.provision.supabase.schema :as schema]
   [tolgraven.provision.supabase.auth :as auth]
   [tolgraven.provision.supabase.auth-import :as auth-import]))

(defn- usage []
  (str "Usage:\n"
       "  lein run -m tolgraven.provision.supabase.cli doctor\n"
       "  lein run -m tolgraven.provision.supabase.cli dump-auth-import <auth-export.json> <auth-config.json> <private.sql>\n"
       "  lein run -m tolgraven.provision.supabase.cli schema\n"
       "  lein run -m tolgraven.provision.supabase.cli link-user <account-uuid> <profile-id>\n"
       "  lein run -m tolgraven.provision.supabase.cli reset\n"
       "  lein run -m tolgraven.provision.supabase.cli import <firebase-export.json>\n"
       "  lein run -m tolgraven.provision.supabase.cli import-scope <scope> <firebase-export.json>\n"
       "  lein run -m tolgraven.provision.supabase.cli dump-seed <firebase-export.json> [output.json]\n"))

(defn- write-json! [path value]
  (spit path (json/write-str value :escape-slash false)))

(defn -main [& args]
  (let [[command arg1 arg2 arg3] args]
    (case command
      "dump-auth-import"
      (if-not (and arg1 arg2 arg3)
        (throw (ex-info "dump-auth-import requires export, hash config and a new output path" {}))
        (println (auth-import/write-import-sql! arg1 arg2 arg3)))

      "doctor"
      (println {:database (supabase/database-target-summary)
                :rest-url-configured? (boolean (or (System/getenv "SUPABASE_PUBLIC_URL")
                                                   (System/getenv "SUPABASE_URL")
                                                   (System/getenv "NEXT_PUBLIC_SUPABASE_URL")))
                :service-key-configured? (boolean (or (System/getenv "SUPABASE_SERVICE_KEY")
                                                      (System/getenv "SUPABASE_SERVICE_ROLE_KEY")))} )

      "schema"
      (do
        (timbre/info "Applying Supabase schema")
        (println (schema/apply-schema!)))

      "link-user"
      (if-not (and arg1 arg2)
        (throw (ex-info "link-user requires an account UUID and profile ID" {}))
        (println (auth/link-user! arg1 arg2)))

      "reset"
      (do
        (timbre/info "Resetting Supabase tables")
        (import/reset-data!)
        (println "ok"))

      "import"
      (if-not arg1
        (binding [*out* *err*] (println (usage)))
        (do
          (timbre/info "Importing Firebase export into Supabase" {:path arg1})
          (import/install-and-import! arg1)
          (println "ok")))

      "import-scope"
      (if-not (and arg1 arg2)
        (binding [*out* *err*] (println (usage)))
        (let [{:keys [scope collections]} (import/import-scope! arg1 arg2)]
          (println scope ":" (string/join ", " collections))))

      "dump-seed"
      (if-not arg1
        (binding [*out* *err*] (println (usage)))
        (let [target (or arg2 "tmp/supabase-seed.json")]
          (io/make-parents target)
          (->> arg1
               import/export->seed
               (write-json! target))
          (println target)))

      (binding [*out* *err*]
        (println (usage))))))
