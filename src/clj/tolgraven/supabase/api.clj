(ns tolgraven.supabase.api
  (:require
   [tolgraven.config :refer [env]]
   [tolgraven.supabase.interop :as interop]
   [tolgraven.supabase.query :as query]))

(defn public-settings []
  {:url (or (env :supabase-public-url)
            (env :supabase-url)
            (System/getenv "SUPABASE_PUBLIC_URL")
            (System/getenv "SUPABASE_URL")
            (System/getenv "NEXT_PUBLIC_SUPABASE_URL"))
   :anon-key (or (env :supabase-anon-key)
                 (System/getenv "SUPABASE_ANON_KEY")
                 (System/getenv "NEXT_PUBLIC_SUPABASE_ANON_KEY"))})

(defn query-store! [query-map]
  (-> (interop/fetch-contract)
      (query/query-contract query-map)))

(defn write-store! [{:keys [path data merge-fields]}]
  (interop/write-document! path data merge-fields))
