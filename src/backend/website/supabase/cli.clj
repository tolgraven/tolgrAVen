(ns website.supabase.cli
  (:require
   [website.provision.supabase.cli :as provision-cli]))

(defn -main [& args]
  (apply provision-cli/-main args))
