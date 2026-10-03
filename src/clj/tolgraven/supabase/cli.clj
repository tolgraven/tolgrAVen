(ns tolgraven.supabase.cli
  (:require
   [tolgraven.provision.supabase.cli :as provision-cli]))

(defn -main [& args]
  (apply provision-cli/-main args))
